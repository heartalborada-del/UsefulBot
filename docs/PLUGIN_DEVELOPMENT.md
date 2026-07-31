# Plugin development

[简体中文版](PLUGIN_DEVELOPMENT.zh-CN.md)

UsefulBot loads plugin JARs from the configured `Plugins.Directory` before bot
adapters connect. Each JAR contains one `usefulbot.plugin.json5` descriptor.

## Entry point

Implement `me.heartalborada.commons.plugins.UsefulBotPlugin`:

Minimal Kotlin example:

```kotlin
class PingPlugin : UsefulBotPlugin {
    override fun onLoad(context: PluginContext) {
        context.registerCommand("ping", usage = "Check plugin availability.") {
            bot, sender, _, _, _ ->
            bot.sendMessage(sender.type, sender.target, MessageChain.text("pong"))
        }
    }
}
```

Add `usefulbot.plugin.json5` to the JAR root:

```json5
{
  id: 'ping',
  name: 'Ping',
  version: '1.0.0',
  main: 'example.PingPlugin',
  apiVersion: 2,
  description: 'Adds a ping command',
  dependencies: [],
  libraries: [
    'com.squareup.okio:okio-jvm:3.16.0',
  ],
  repositories: [
    'https://repo.maven.apache.org/maven2/',
  ],
}
```

Comments, single-quoted strings, unquoted field names and trailing commas are
accepted. `id`, `name`, `version` and `main` are required.

Use `PluginContext.listen` and `PluginContext.registerCommand` whenever possible.
Those registrations and `PluginContext.scope` are released automatically on
shutdown. Settings belong in `PluginContext.configDirectory`; generated state and
caches belong in `PluginContext.dataDirectory`. Their default locations are
`plugins/<id>/config` and `plugins/<id>/data`.

Lifecycle order is `onLoad` -> `onEnable` -> `onDisable` -> context cleanup ->
`onUnload`. `onUnload` runs for explicit unloads and normal application shutdown.
Unloading a dependency first unloads all enabled dependents in reverse enable
order. Do not register new context-owned resources from `onUnload` because the
context has already been closed.

Required plugin IDs can be listed in descriptor `dependencies`. The manager
enables dependencies first, rejects cycles and duplicate IDs, and isolates
lifecycle failures. Public classes from required plugins are visible to dependent
plugins. IDs must match `[a-z][a-z0-9._-]{0,63}`. The host automatically adds
`permissions` as a required dependency of every other plugin. If it is missing,
disabled, or fails to start, dependent plugins are not enabled.

Third-party code can be shaded into the plugin JAR. Alternatively, list Maven
coordinates under `libraries`; UsefulBot resolves runtime transitive dependencies
into `plugins/.libraries` and adds them only to that plugin class loader. Maven
Central is used when `repositories` is omitted. Remote repositories must use
HTTPS; localhost repositories may use HTTP.

## Events and adapter methods

Public event classes and bot methods use `@SupportedBotTypes` to document their
built-in adapter support. The annotation is also available through reflection.

- NapCat + Telegram: private/group messages, online/offline events, messaging,
  forwarding, recall, and file methods.
- Telegram: inline queries, callback queries, and their answer methods.
- NapCat: heartbeat and group notices, friend/group requests, and request response
  methods.

Telegram publishes `CallbackQueryEvent` before its default button handling. When
not intercepted, the adapter acknowledges it and dispatches its data as a command.
A plugin taking ownership should answer and then intercept it:

```kotlin
context.listen(CallbackQueryEvent::class.java) { bot, event ->
    bot.answerCallbackQuery(event.queryID, "Handled")
    event.intercept()
}
```

An unsupported platform method returns `false`. Pass a NapCat request event's
`requestFlag` unchanged to the matching response method.

## Built-in plugins and services

The host ships `permissions`, `eh` and `jm` through the same lifecycle as external
plugins. They appear in plugin health output. `eh` and `jm` can be listed in
`Plugins.Disabled`; `permissions` is an essential built-in, so disable requests
are ignored and runtime unload is rejected. Normal shutdown still invokes its
`onDisable` and `onUnload` callbacks.
`Plugins.Enabled` controls external JAR discovery only, so disabling external
plugins does not remove built-in providers unexpectedly. Their IDs are reserved;
an external JAR cannot replace a built-in plugin by declaring the same ID.

The `permissions` service is available by the time another plugin's `onLoad`
runs. Declaring it explicitly is allowed but not required because the host adds
the dependency automatically:

```json5
dependencies: ['permissions'],
```

```kotlin
val permissions = context.requireService(PermissionService::class.java)
val subject = PermissionSubject("tg", PermissionSubjectType.USER, 123)
if (permissions.hasPermission(PermissionContext(subject), "example.feature.use")) {
    // Authorized.
}
```

Prefer declaring permissions on commands instead of calling the service manually.
The generated leaf node is `<pluginId>.<command>`; a subcommand uses
`<pluginId>.<command>.<subcommand>`. The canonical name (the first alias) is used.

```kotlin
context.registerCommand(
    "publish",
    usage = "/publish",
    permissionDefault = PermissionDefault.DENY,
) { bot, sender, _, _, _ ->
    // Node: example.publish
}

context.registerCommand("manage", usage = "/manage <action>") {
    subcommand(
        "reload",
        usage = "/manage reload",
        permission = "example.config.reload", // Optional override.
        permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
    ) { bot, sender, _, _, _ ->
        // Authorized before this callback runs.
    }
}
```

`PermissionDefault` is a type-safe bit flag. Kotlin combines flags with the infix
`or` function rather than `|`. `ALLOW` permits an unconfigured node, `DENY`
rejects it, and `ADMIN` falls back to the user's `usefulbot.admin` permission.
`ALLOW_CONSOLE` (also exposed as `ALLOWCONSOLE`) makes that leaf command or
subcommand visible and executable in the JLine console. For example,
`DENY or ALLOW_CONSOLE` denies unconfigured chat users while allowing console
execution. Explicit user or group rules override the chat default.

The console has no user or group subject and only executes commands explicitly
marked with `ALLOW_CONSOLE`. Use `bot.sendCommandMessage(sender, message)` for
responses that must work in both chat and the console. The legacy
`AdminUserIds`, `AllowedUserIds`, `AllowedChatIds`, and `BlockedUserIds` settings
have been removed. Bootstrap the first administrator from the console with:

```text
permission grant tg:user:123 usefulbot.admin
```

Subjects are isolated by platform and type. Canonical keys are `tg:user:123`,
`tg:group:-100`, `qq:user:123`, and `qq:group:456`. Within a command on the current
platform, `user:123` and `group:456` are accepted as shorthand. Legacy `tg:123`
and `qq:123` keys are read as user subjects. Delegated managers need
`usefulbot.permissions.manage`. Runtime management commands are:

```text
/permission show [<subject>]
/permission grant <subject> <node>
/permission deny <subject> <node>
/permission revoke <subject> <node>
/permission ban <user-subject>
/permission unban <user-subject>
```

`grant` stores an allow rule, `deny` stores an explicit deny, and `revoke` removes
both forms for that exact node. Groups can receive permission rules but cannot be
banned. `self`/`user` and `here`/`group` address the current command context.

Permission nodes support exact matches, namespace wildcards such as `example.*`,
and the global `*` wildcard. Parent fallback is automatic: granting
`example.manage` also permits `example.manage.reload`. The most specific matching
rule wins; an explicit deny wins when allow and deny have equal specificity.

## Configuration

```json
{
  "...": "...",
  "Plugins": {
    "Enabled": true,
    "Directory": "plugins",
    "Disabled": ["example-plugin"]
  }
}
```

`Enabled` controls external JAR loading. Non-essential built-ins are disabled
individually through `Disabled`, for example `["eh"]`; essential built-ins are
never disabled by configuration.
