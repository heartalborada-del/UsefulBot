package me.heartalborada.commons.economic.dao

import me.heartalborada.commons.economic.tables.UsersTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

class User(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, User>(UsersTable)

    var createdAt by UsersTable.createdAt
    var updatedAt by UsersTable.updatedAt
    var balance by UsersTable.balance
    var checkinAt by UsersTable.checkinAt
    var role by UsersTable.role
}
