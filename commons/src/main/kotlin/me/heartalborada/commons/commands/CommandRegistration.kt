package me.heartalborada.commons.commands

@DslMarker
annotation class CommandRegistrationDsl

internal class SubcommandDefinition(
    val commands: List<String>,
    val usage: String,
    val executor: CommandExecutor,
)

/**
 * Builds the direct children of a command.
 *
 * The first name passed to [subcommand] is canonical. Any additional names are
 * aliases and dispatch to the same executor.
 */
@CommandRegistrationDsl
class SubcommandBuilder internal constructor() {
    private val definitions = mutableListOf<SubcommandDefinition>()

    fun subcommand(
        vararg commands: String,
        usage: String,
        executor: CommandExecutor,
    ) {
        definitions += SubcommandDefinition(
            commands = commands.toList(),
            usage = usage,
            executor = executor,
        )
    }

    internal fun build(): List<SubcommandDefinition> = definitions.toList()
}
