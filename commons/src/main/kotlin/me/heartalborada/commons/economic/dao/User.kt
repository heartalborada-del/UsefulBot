package me.heartalborada.commons.economic.dao

import me.heartalborada.commons.economic.tables.UsersTable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID

class User(id: EntityID<ULong>): Entity<ULong>(id) {
    companion object : EntityClass<ULong, User>(UsersTable)
    var createdAt by UsersTable.createdAt
    var updatedAt by UsersTable.updatedAt
    var balance by UsersTable.balance
    var checkinAt by UsersTable.checkinAt
    var role by UsersTable.role
}
