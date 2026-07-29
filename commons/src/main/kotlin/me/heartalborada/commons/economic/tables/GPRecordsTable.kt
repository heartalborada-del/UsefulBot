package me.heartalborada.commons.economic.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Clock

object GPRecordsTable : IdTable<ULong>("records") {
    override val id: Column<EntityID<ULong>> = ulong("id").autoIncrement().entityId()
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val createdAt = timestamp("created_at").clientDefault { Clock.systemUTC().instant() }
    val operation = enumeration("operation", RecordType::class)
    val amount = long("amount")
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_records_user_created", false, userId, createdAt)
    }

    enum class RecordType {
        DEPOSIT,
        WITHDRAW
    }
}
