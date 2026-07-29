package me.heartalborada.commons.economic.dao

import me.heartalborada.commons.economic.tables.GPRecordsTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

class GPRecord(id: EntityID<ULong>) : Entity<ULong>(id) {
    companion object : EntityClass<ULong, GPRecord>(GPRecordsTable)

    var userId by GPRecordsTable.userId
    var createdAt by GPRecordsTable.createdAt
    var operation by GPRecordsTable.operation
    var amount by GPRecordsTable.amount
}
