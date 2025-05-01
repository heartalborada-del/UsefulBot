package me.heartalborada.commons.economic.dao

import me.heartalborada.commons.economic.tables.GPRecordsTable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID

class GPRecord(id: EntityID<ULong>): Entity<ULong>(id) {
    companion object : EntityClass<ULong, GPRecord>(GPRecordsTable)
    var userId by GPRecordsTable.userId
    val createdAt by GPRecordsTable.createdAt
    var operation by GPRecordsTable.operation
    var amount by GPRecordsTable.amount
}