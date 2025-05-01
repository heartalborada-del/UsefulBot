package me.heartalborada.commons.economic

import me.heartalborada.commons.economic.dao.GPRecord
import me.heartalborada.commons.economic.dao.User
import me.heartalborada.commons.economic.tables.GPRecordsTable
import me.heartalborada.commons.economic.tables.UsersTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

class EconomicManager(private val db: Database) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    init {
        transaction(db) {
            addLogger(Slf4jSqlDebugLogger)
            SchemaUtils.create(UsersTable)
            SchemaUtils.create(GPRecordsTable)
        }
    }

    fun getUser(id: ULong): User {
        return getUserOrCreate(id)
    }

    fun getBalance(userId: ULong): Long {
        return transaction(db) {
            val user = getUserOrCreate(userId)
            return@transaction user.balance
        }
    }

    private fun getUserOrCreate(id: ULong): User = transaction(db) {
        var u = User.findById(id)
        if (u == null) {
            u = User.new(id) {
                balance = 0
            }
        }
        return@transaction u
    }

    fun depositGP(userId: ULong, amount: Long): Boolean {
        if (amount <= 0) return false
        return transaction(db) {
            val user = getUserOrCreate(userId)
            user.balance += amount
            user.updatedAt = Clock.systemUTC().instant()
            GPRecord.new {
                this.userId = user.id.value
                this.operation = GPRecordsTable.RecordType.DEPOSIT
                this.amount = amount
            }
            return@transaction true
        }
    }

    fun withdrawGP(userId: ULong, amount: Long): Boolean {
        if(amount <= 0) return false
        return transaction(db) {
            val user = getUserOrCreate(userId)
            if (user.balance < amount) {
                return@transaction false
            }
            user.balance -= amount
            user.updatedAt = Clock.systemUTC().instant()
            GPRecord.new {
                this.userId = user.id.value
                this.operation = GPRecordsTable.RecordType.WITHDRAW
                this.amount = amount
            }
            return@transaction true
        }
    }

    fun userCheckIn(userId: ULong, from: Int = 50, to: Int = 100): Pair<Long, Boolean> {
        return transaction(db) {
            val user = getUserOrCreate(userId)
            val start = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant()
            if (user.checkinAt > start) {
                return@transaction Pair(0L, false)
            }
            val award = Random.nextInt(from,to)
            depositGP(userId, award.toLong())
            user.checkinAt = Clock.systemUTC().instant()
            return@transaction Pair(award.toLong(),true)
        }
    }

    fun queryRecord(usedId: ULong, limit: Int = 10): List<GPRecord> {
        return transaction(db) {
            GPRecord.find { GPRecordsTable.userId eq usedId }
                .limit(limit)
                .toList()
        }
    }
}