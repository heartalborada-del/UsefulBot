package me.heartalborada.commons.economic

import me.heartalborada.commons.economic.dao.GPRecord
import me.heartalborada.commons.economic.dao.User
import me.heartalborada.commons.economic.tables.GPRecordsTable
import me.heartalborada.commons.economic.tables.UsersTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

class EconomicManager(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val awardGenerator: (from: Int, until: Int) -> Int = Random::nextInt,
) {
    private val userLocks = Array(USER_LOCK_STRIPES) { ReentrantLock() }

    init {
        transaction(db) {
            val tables = arrayOf(UsersTable, GPRecordsTable)
            SchemaUtils.create(*tables)
            SchemaUtils.addMissingColumnsStatements(*tables).forEach { statement ->
                exec(statement)
            }
            val existingIndexNames = currentDialect.existingIndices(*tables)
                .values
                .flatten()
                .mapTo(mutableSetOf()) { it.indexName.lowercase() }
            tables
                .flatMap { it.indices }
                .filterNot { it.indexName.lowercase() in existingIndexNames }
                .flatMap(SchemaUtils::createIndex)
                .forEach { statement -> exec(statement) }
        }
    }

    fun getUser(id: String): User {
        return withUserLock(id) {
            transaction(db) {
                ensureUser(id)
                User.findById(id)!!.also { user ->
                    user.createdAt
                    user.updatedAt
                    user.balance
                    user.checkinAt
                    user.role
                }
            }
        }
    }

    fun getBalance(userId: String): Long {
        return withUserLock(userId) {
            transaction(db) {
                ensureUser(userId)
                UsersTable
                    .select(UsersTable.balance)
                    .where { UsersTable.id eq userId }
                    .single()[UsersTable.balance]
            }
        }
    }

    private fun ensureUser(id: String) {
        val exists = UsersTable.selectAll()
            .where { UsersTable.id eq id }
            .limit(1)
            .any()
        if (!exists) {
            UsersTable.insert {
                it[UsersTable.id] = EntityID(id, UsersTable)
            }
        }
    }

    fun depositGP(userId: String, amount: Long): Boolean {
        if (amount <= 0) return false
        return withUserLock(userId) {
            transaction(db) {
                ensureUser(userId)
                val now = clock.instant()
                val updatedRows = UsersTable.update({
                    (UsersTable.id eq userId) and (UsersTable.balance lessEq Long.MAX_VALUE - amount)
                }) {
                    with(SqlExpressionBuilder) {
                        it.update(UsersTable.balance, UsersTable.balance + amount)
                    }
                    it[updatedAt] = now
                }
                if (updatedRows == 0) {
                    return@transaction false
                }
                createRecord(userId, GPRecordsTable.RecordType.DEPOSIT, amount, now)
                true
            }
        }
    }

    fun withdrawGP(userId: String, amount: Long): Boolean {
        if (amount <= 0) return false
        return withUserLock(userId) {
            transaction(db) {
                ensureUser(userId)
                val now = clock.instant()
                val updatedRows = UsersTable.update({
                    (UsersTable.id eq userId) and (UsersTable.balance greaterEq amount)
                }) {
                    with(SqlExpressionBuilder) {
                        it.update(UsersTable.balance, UsersTable.balance - amount)
                    }
                    it[updatedAt] = now
                }
                if (updatedRows == 0) {
                    return@transaction false
                }
                createRecord(userId, GPRecordsTable.RecordType.WITHDRAW, amount, now)
                true
            }
        }
    }

    fun userCheckIn(userId: String, minAward: Int = 150, maxAward: Int = 250): Pair<Long, Boolean> {
        require(minAward > 0) { "Check-in reward must be positive." }
        require(maxAward >= minAward) { "Check-in reward upper bound must not be smaller than the lower bound." }
        require(maxAward < Int.MAX_VALUE) { "Check-in reward upper bound is too large." }
        return withUserLock(userId) {
            transaction(db) {
                ensureUser(userId)
                val now = clock.instant()
                val start = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
                val award = if (minAward == maxAward) {
                    minAward
                } else {
                    awardGenerator(minAward, maxAward + 1)
                }
                val updatedRows = UsersTable.update({
                    (UsersTable.id eq userId) and
                        (UsersTable.checkinAt less start) and
                        (UsersTable.balance lessEq Long.MAX_VALUE - award)
                }) {
                    with(SqlExpressionBuilder) {
                        it.update(UsersTable.balance, UsersTable.balance + award.toLong())
                    }
                    it[updatedAt] = now
                    it[checkinAt] = now
                }
                if (updatedRows == 0) {
                    return@transaction Pair(0L, false)
                }
                createRecord(userId, GPRecordsTable.RecordType.DEPOSIT, award.toLong(), now)
                Pair(award.toLong(), true)
            }
        }
    }

    fun queryRecord(userId: String, limit: Int = 10): List<GPRecord> {
        if (limit <= 0) return emptyList()
        val safeLimit = limit.coerceAtMost(MAX_RECORD_QUERY_LIMIT)
        return transaction(db) {
            GPRecord.find { GPRecordsTable.userId eq userId }
                .orderBy(GPRecordsTable.createdAt to SortOrder.DESC, GPRecordsTable.id to SortOrder.DESC)
                .limit(safeLimit)
                .toList()
                .onEach { record ->
                    record.userId
                    record.createdAt
                    record.operation
                    record.amount
                }
        }
    }

    fun userCount(): Long = transaction(db) { UsersTable.selectAll().count() }

    fun setRole(userId: String, role: UsersTable.Role): Boolean = withUserLock(userId) {
        transaction(db) {
            ensureUser(userId)
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.role] = role
                it[updatedAt] = clock.instant()
            } == 1
        }
    }

    private fun createRecord(
        userId: String,
        operation: GPRecordsTable.RecordType,
        amount: Long,
        createdAt: Instant,
    ) {
        GPRecord.new {
            this.userId = userId
            this.operation = operation
            this.amount = amount
            this.createdAt = createdAt
        }
    }

    private inline fun <T> withUserLock(userId: String, block: () -> T): T {
        val index = (userId.hashCode() and Int.MAX_VALUE) % userLocks.size
        return userLocks[index].withLock(block)
    }

    private companion object {
        const val USER_LOCK_STRIPES = 256
        const val MAX_RECORD_QUERY_LIMIT = 1_000
    }
}
