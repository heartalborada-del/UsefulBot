package me.heartalborada.commons.events

interface Event {
    val isIntercepted: Boolean
    fun intercept()
}