package me.heartalborada.commons.bots

/** Built-in bot adapters represented by the public UsefulBot API. */
enum class BotType {
    /** OneBot 11 adapter backed by NapCat. */
    NAPCAT,

    /** Telegram Bot API adapter. */
    TELEGRAM,
}

/**
 * Declares the adapters that currently publish an event or implement an API method.
 *
 * The annotation describes built-in adapter support, not whether a remote server
 * has enabled the corresponding platform feature. It is retained at runtime so
 * plugin loaders and documentation tools can inspect it through reflection.
 *
 * A method with a default unsupported implementation, such as returning `false`,
 * is only implemented by the adapters listed in [value]. An event is only
 * published by the listed adapters.
 *
 * @property value adapters that support the annotated API element
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SupportedBotTypes(vararg val value: BotType)
