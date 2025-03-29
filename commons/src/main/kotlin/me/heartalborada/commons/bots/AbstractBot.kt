package me.heartalborada.commons.bots

import me.heartalborada.commons.events.EventBus

abstract class AbstractBot{
    abstract fun close(): Boolean
    abstract fun connect(): Boolean
    abstract fun getEventBus(): EventBus
}