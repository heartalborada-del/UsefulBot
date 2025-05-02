package me.heartalborada.commons.economic.tables

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object GPRecordsTable : IdTable<ULong>("records") {
    override val id: Column<EntityID<ULong>> = ulong("id").autoIncrement().entityId()
    val userId = ulong("user_id").references(UsersTable.id)
    val createdAt = timestamp("created_at").default(Instant.now())
    val operation = enumeration("operation", RecordType::class)
    val amount = long("amount").default(0)
    override val primaryKey = PrimaryKey(id)

    enum class RecordType {
        DEPOSIT,
        WITHDRAW
    }
}