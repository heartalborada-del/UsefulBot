package me.heartalborada.commons.bots.events

import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.dto.UserInfo

/**
 * Base contract for events published by a bot adapter.
 *
 * Interception is cooperative: once [intercept] is called, the event bus skips
 * listeners that did not explicitly opt in to intercepted events. This is useful
 * for command filters and plugins that consume an event before normal handlers.
 */
interface Event {
    /** Whether a previous listener has consumed this event. */
    val isIntercepted: Boolean

    /** Marks this event as consumed. Calling this method more than once is safe. */
    fun intercept()
}

/** Event emitted by a bot adapter, with a platform bot ID and epoch-second timestamp. */
interface BotEvent : Event {
    val botID: Long
    val timestamp: Long
}

/** Common view of incoming private and group messages. */
interface MessageEvent : BotEvent {
    val sender: UserInfo
    val message: MessageChain
    val messageID: Long
}
