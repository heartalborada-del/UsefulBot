package me.heartalborada.commons.bots.dto

import me.heartalborada.commons.bots.MessageChain

/**
 * A node in a merged-forward message.
 *
 * NapCat accepts either an existing message ID or custom message content for
 * each node.
 */
sealed interface ForwardMessageNode {
    data class ExistingMessage(val messageID: Long) : ForwardMessageNode

    data class CustomMessage(
        /**
         * Displayed QQ source. A null value asks the bot implementation to use
         * its own account, with an invalid account as a last-resort fallback.
         */
        val userID: Long? = null,
        val nickname: String,
        val content: MessageChain,
    ) : ForwardMessageNode {
        init {
            require(nickname.isNotBlank()) { "Forward message nickname must not be blank." }
            require(content.isNotEmpty()) { "Forward message content must not be empty." }
        }
    }
}

data class ForwardMessageResult(
    val messageID: Long,
    val resourceID: String? = null,
)
