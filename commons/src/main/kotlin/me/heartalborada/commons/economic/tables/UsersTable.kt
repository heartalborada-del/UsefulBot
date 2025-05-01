package me.heartalborada.commons.economic.tables

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Clock
import java.time.Instant

object UsersTable: IdTable<ULong>("users") {
    override val id: Column<EntityID<ULong>> = ulong("id").entityId()
    val createdAt = timestamp("created_at").default(Clock.systemUTC().instant())
    val updatedAt = timestamp("updated_at").clientDefault { Clock.systemUTC().instant() }
    val balance = long("GP").default(0)
    val checkinAt = timestamp("checkin_at").clientDefault { Instant.ofEpochSecond(0) }
    val role = enumeration("role", Role::class).clientDefault { Role.USER }.default(Role.USER)
    override val primaryKey = PrimaryKey(id)

    enum class Role {
        USER,
        ADMIN
    }
}