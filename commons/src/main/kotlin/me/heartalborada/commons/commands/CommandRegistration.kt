package me.heartalborada.commons.commands

import me.heartalborada.commons.permissions.PermissionDefault

@DslMarker
annotation class CommandRegistrationDsl

internal class SubcommandDefinition(
    val commands: List<String>,
    val usage: String,
    val permissionDefault: PermissionDefault,
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
        permissionDefault: PermissionDefault = PermissionDefault.ALLOW,
        executor: CommandExecutor,
    ) {
        definitions += SubcommandDefinition(
            commands = commands.toList(),
            usage = usage,
            permissionDefault = permissionDefault,
            executor = executor,
        )
    }

    internal fun build(): List<SubcommandDefinition> = definitions.toList()
}
