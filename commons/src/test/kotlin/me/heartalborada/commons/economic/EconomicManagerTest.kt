package me.heartalborada.commons.economic

import me.heartalborada.commons.economic.tables.GPRecordsTable
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EconomicManagerTest {
    private fun database(): Database = Database.connect(
        url = "jdbc:h2:mem:economic-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )

    private fun manager(
        instant: Instant = Instant.parse("2026-07-27T08:00:00Z"),
        award: Int = 50,
        database: Database = database(),
    ): EconomicManager {
        return EconomicManager(
            database,
            Clock.fixed(instant, ZoneOffset.UTC),
            awardGenerator = { _, _ -> award },
        )
    }

    @Test
    fun `deposit and withdrawal update balance and ordered audit records`() {
        val manager = manager()
        val userId = "qq:10001"

        assertEquals(0, manager.getBalance(userId))
        assertFalse(manager.depositGP(userId, 0))
        assertTrue(manager.depositGP(userId, 100))
        assertTrue(manager.withdrawGP(userId, 40))
        assertFalse(manager.withdrawGP(userId, 61))
        assertEquals(60, manager.getBalance(userId))
        assertEquals(60, manager.getUser(userId).balance)

        val records = manager.queryRecord(userId)
        assertEquals(2, records.size)
        assertEquals(GPRecordsTable.RecordType.WITHDRAW, records[0].operation)
        assertEquals(40, records[0].amount)
        assertEquals(GPRecordsTable.RecordType.DEPOSIT, records[1].operation)
        assertEquals(100, records[1].amount)
    }

    @Test
    fun `deposit rejects balance overflow without creating a record`() {
        val manager = manager()
        val userId = "qq:10004"

        assertTrue(manager.depositGP(userId, Long.MAX_VALUE))
        assertFalse(manager.depositGP(userId, 1))
        assertEquals(Long.MAX_VALUE, manager.getBalance(userId))
        assertEquals(1, manager.queryRecord(userId).size)
    }

    @Test
    fun `schema migration is idempotent and preserves existing balances`() {
        val database = database()
        val firstManager = manager(database = database)
        val userId = "qq:10005"
        firstManager.depositGP(userId, 125)

        val restartedManager = manager(database = database)

        assertEquals(125, restartedManager.getBalance(userId))
        assertEquals(1, restartedManager.queryRecord(userId).size)
    }

    @Test
    fun `concurrent withdrawals cannot overdraw an account`() {
        val manager = manager()
        val userId = "qq:10002"
        manager.depositGP(userId, 100)

        val executor = Executors.newFixedThreadPool(8)
        val results = try {
            executor.invokeAll(List(20) { Callable { manager.withdrawGP(userId, 10) } })
                .map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(10, results.count { it })
        assertEquals(0, manager.getBalance(userId))
        assertEquals(11, manager.queryRecord(userId, 100).size)
    }

    @Test
    fun `concurrent check-ins award only once per UTC day`() {
        val manager = manager(award = 75)
        val userId = "qq:10003"

        val executor = Executors.newFixedThreadPool(8)
        val results = try {
            executor.invokeAll(List(20) { Callable { manager.userCheckIn(userId) } })
                .map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it.second })
        assertEquals(75, results.single { it.second }.first)
        assertEquals(75, manager.getBalance(userId))
        assertEquals(1, manager.queryRecord(userId).size)
        assertEquals(Pair(0L, false), manager.userCheckIn(userId))
    }

    @Test
    fun `default check-in reward includes both 150 and 250`() {
        var requestedRange: Pair<Int, Int>? = null
        val manager = EconomicManager(
            database(),
            Clock.fixed(Instant.parse("2026-07-27T08:00:00Z"), ZoneOffset.UTC),
            awardGenerator = { from, until ->
                requestedRange = from to until
                until - 1
            },
        )

        assertEquals(Pair(250L, true), manager.userCheckIn("qq:10006"))
        assertEquals(150 to 251, requestedRange)
    }

    @Test
    fun `equal numeric IDs on different platforms use isolated accounts`() {
        val manager = manager()

        assertTrue(manager.depositGP("qq:7", 10))
        assertTrue(manager.depositGP("tg:7", 20))

        assertEquals(10, manager.getBalance("qq:7"))
        assertEquals(20, manager.getBalance("tg:7"))
    }
}
