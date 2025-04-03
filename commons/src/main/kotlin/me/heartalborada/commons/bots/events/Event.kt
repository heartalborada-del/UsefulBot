package me.heartalborada.commons.bots.events

interface Event {
    val isIntercepted: Boolean
    fun intercept()
}