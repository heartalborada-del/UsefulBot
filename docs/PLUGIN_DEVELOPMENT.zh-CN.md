# 插件开发指南

[English](PLUGIN_DEVELOPMENT.md)

UsefulBot 会在 Bot 适配器连接前，从 `Plugins.Directory` 指定的目录加载插件。
每个插件 JAR 对应一个插件，并在 JAR 根目录提供 `usefulbot.plugin.json5`。

## 插件入口

入口类需要实现 `me.heartalborada.commons.plugins.UsefulBotPlugin`：

```kotlin
class PingPlugin : UsefulBotPlugin {
    override fun onLoad(context: PluginContext) {
        context.registerCommand("ping", usage = "检查插件是否可用") {
            bot, sender, _, _, _ ->
            bot.sendMessage(sender.type, sender.target, MessageChain.text("pong"))
        }
    }
}
```

生命周期包括：

- `onLoad`：注册命令、事件、服务和清理操作。
- `onEnable`：依赖插件已经启用，可以启动后台任务。
- `onDisable`：停止插件主动创建的资源。
- `onUnload`：上下文资源释放后、类加载器关闭前执行最终卸载清理。

通过 `PluginContext` 注册的命令、事件、服务和 `scope` 会在插件关闭时自动清理。
完整顺序为 `onLoad` → `onEnable` → `onDisable` → 上下文清理 → `onUnload`。
显式卸载和程序正常退出都会触发 `onUnload`；卸载一个依赖时，会先按启用顺序的逆序卸载
所有仍依赖它的插件。由于上下文此时已经关闭，不应在 `onUnload` 中新增上下文托管的注册。

## 描述文件

将下面的文件保存为 `src/main/resources/usefulbot.plugin.json5`，构建后它会位于 JAR 根目录：

```json5
{
  id: 'ping',
  name: 'Ping',
  version: '1.0.0',
  main: 'example.PingPlugin',
  apiVersion: 2,
  description: '提供 ping 命令',

  dependencies: [],

  libraries: [
    'com.squareup.okio:okio-jvm:3.16.0',
  ],

  repositories: [
    'https://repo.maven.apache.org/maven2/',
  ],
}
```

描述文件支持注释、单引号、未加引号的字段名和尾逗号。必填字段为：

- `id`：插件唯一 ID，格式为 `[a-z][a-z0-9._-]{0,63}`。
- `name`：显示名称。
- `version`：插件版本。
- `main`：入口类完整类名，入口类必须有公开的无参构造函数。

`apiVersion` 当前为 `2`。版本不兼容、ID 重复、依赖缺失或循环依赖都会阻止插件启用，但不会阻止其他插件和 Bot 运行。

## 依赖方式

插件可以选择以下任一种方式携带第三方依赖：

1. 构建 fat JAR，把依赖 shade 到插件 JAR。
2. 在 `libraries` 中填写 Maven 坐标，由 UsefulBot 下载。

Maven 方式会解析完整的 runtime 传递依赖，并统一处理同一插件依赖树中的版本冲突。
下载结果缓存在 `plugins/.libraries`，但只加入声明它的插件类加载器，不会污染其他插件。
未填写 `repositories` 时使用 Maven Central。远程仓库必须使用 HTTPS，本机仓库可以使用 HTTP。

插件自身类和第三方库采用子优先加载；UsefulBot API、Kotlin、协程和 SLF4J 使用父优先加载。
因此插件可以打包与宿主不同版本的普通第三方库，同时保持公共 API 类型一致。

## 插件依赖

在 `dependencies` 中填写必需插件 ID：

```json5
dependencies: ['permissions', 'another-plugin'],
```

依赖插件会先启用。依赖插件公开的类对下游插件可见，插件服务也可以在 `onLoad` 中获取：

```kotlin
val permissions = context.requireService(PermissionService::class.java)
```

宿主会自动把 `permissions` 注入其他所有插件的必需依赖，不要求每个描述文件重复填写。
显式写入 `dependencies` 仍然合法。如果权限插件缺失、被禁用或启动失败，其他插件不会启用，
从而保证命令执行前权限服务一定存在。

## 配置与数据目录

每个插件会获得两个自动创建的独立目录：

- `PluginContext.configDirectory`：`plugins/<id>/config`，用于用户可编辑配置。
- `PluginContext.dataDirectory`：`plugins/<id>/data`，用于状态、缓存和生成文件。

插件不应把运行时文件写回 JAR，也不应直接使用其他插件的私有目录。

## 事件与命令

监听所有 Bot 适配器上的消息事件：

```kotlin
context.listen(MessageEvent::class.java, priority = EventPriority.NORMAL) { bot, event ->
    context.logger.info("{} received message {}", bot.javaClass.simpleName, event.messageID)
}
```

事件支持优先级、父类型监听、拦截和异常隔离。监听器返回的订阅以及通过上下文注册的命令会在插件关闭时自动移除。

公共事件类和 Bot 方法通过 `@SupportedBotTypes` 标注内置适配器支持范围，运行时也可以读取该注解：

- NapCat + Telegram：`PrivateMessageEvent`、`GroupMessageEvent`、`BotOnlineEvent`、`BotOfflineEvent`，以及消息、转发、撤回和文件方法。
- Telegram：`InlineQueryEvent`、`CallbackQueryEvent`、`answerInlineQuery`、`answerCallbackQuery`。
- NapCat：心跳、群通知、好友/加群请求事件，以及 `respondFriendRequest`、`respondGroupRequest`。

Telegram 按钮回调会先发布事件。未拦截时，适配器会自动应答并继续把按钮数据作为命令分发；
插件接管时应自行应答，然后拦截事件：

```kotlin
context.listen(CallbackQueryEvent::class.java) { bot, event ->
    bot.answerCallbackQuery(event.queryID, "已处理")
    event.intercept()
}
```

平台未实现的方法返回 `false`。NapCat 请求事件中的 `requestFlag` 应原样传给对应响应方法。

## 内置插件

EH、JM 和权限管理也使用相同插件生命周期：

- `eh`：E-Hentai / ExHentai Provider，包含别名 `ex`。
- `jm`：JMComic Provider。
- `permissions`：持久化权限节点和动态封禁。

它们会出现在健康状态的插件列表中。EH、JM 可以通过 `Plugins.Disabled` 单独关闭；
权限管理属于基础内置插件，配置中的禁用请求会被忽略，也不能通过运行时 `unload` 卸载。
程序退出仍会正常触发它的 `onDisable` 和 `onUnload`。
`Plugins.Enabled` 只控制外部 JAR 扫描，不会一次性关闭全部内置插件。
内置插件 ID 是保留 ID，外部 JAR 不能通过声明同名 ID 覆盖内置插件。

### 权限管理

推荐在注册命令时声明权限，而不是在执行器中手动查询：

```kotlin
context.registerCommand(
    "publish",
    usage = "/publish",
    permissionDefault = PermissionDefault.DENY,
) { bot, sender, _, _, _ ->
    // 未显式填写 permission，自动生成 ping.publish（假设插件 ID 为 ping）
}

context.registerCommand("manage", usage = "/manage <action>") {
    subcommand(
        "reload",
        usage = "/manage reload",
        permission = "ping.config.reload", // 可选：覆盖自动节点
        permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
    ) { bot, sender, _, _, _ ->
        // 进入回调前已经完成鉴权
    }
}
```

叶命令默认生成 `<插件ID>.<命令>`，子命令默认生成
`<插件ID>.<父命令>.<子命令>`，命令及子命令都以第一个别名作为规范名称。
`PermissionDefault` 的含义如下：

- `ALLOW`：没有匹配规则时允许，适合普通公开命令，也是默认值。
- `DENY`：没有匹配规则时拒绝，适合需要显式授权的功能。
- `ADMIN`：没有匹配规则时回退检查当前用户的 `usefulbot.admin` 节点。
- `ALLOW_CONSOLE`（别名 `ALLOWCONSOLE`）：允许 JLine 控制台补全并执行该命令。

`PermissionDefault` 是可组合位标志；Kotlin 使用中缀 `or` 组合，而不是 `|`。
例如 `DENY or ALLOW_CONSOLE` 表示聊天侧默认拒绝、控制台允许。显式用户或群规则会覆盖聊天侧默认值。
控制台没有用户或群主体，只能执行明确带 `ALLOW_CONSOLE` 的叶命令或子命令，并绕过聊天权限查询。
命令需要使用 `bot.sendCommandMessage(sender, message)` 返回结果，才能同时正确输出到聊天和控制台。

旧的 `AdminUserIds`、`AllowedUserIds`、`AllowedChatIds`、`BlockedUserIds` 配置已经移除。
首次启动时可直接在控制台授权首个管理员：

```text
permission grant tg:user:123 usefulbot.admin
permission grant qq:user:456 usefulbot.admin
```

权限主体同时包含平台、类型和 ID，因此不同平台以及用户与群不会串权：

- 全局所有主体：`*`
- QQ 平台所有主体：`qq:*`
- QQ 全部用户：`qq:user:*`
- QQ 全部群：`qq:group:*`
- Telegram 用户：`tg:user:123`
- Telegram 群：`tg:group:-100`
- QQ 用户：`qq:user:123`
- QQ 群：`qq:group:456`

主体按 `* < qq:* < qq:user:* / qq:group:* < 精确主体` 的顺序逐级具体。
群消息会同时匹配用户分支和群分支，因此不需要也不接受 `qq:group:user:*` 这种嵌套写法。
`telegram` 与 `tg`、`napcat` 与 `qq` 在读取、写入和旧状态迁移时都会统一成 `tg`、`qq`。
在当前命令的平台内可以简写成 `user:123`、`group:456`；旧格式 `tg:123`、`qq:123`
仍兼容并按用户主体解释。`self`/`user` 表示当前用户，`here`/`group` 表示当前群。

可以授予 `usefulbot.permissions.manage`，让其他用户管理权限和动态封禁：

```text
/permission show [<主体>]
/permission grant <主体> <权限节点>
/permission deny <主体> <权限节点>
/permission revoke <主体> <权限节点>
/permission ban <用户主体>
/permission unban <用户主体>
```

交互式终端会在 `permission grant`、`permission deny`、`permission revoke`
的权限节点参数位置补全当前已注册节点。候选仅包含 `eh.query` 这样的纯节点，
不附加 `+` 或 `-` 前缀。

`grant` 写入允许规则，`deny` 写入显式拒绝规则，`revoke` 清除该精确节点的所有规则写法。
群和通配主体可以配置权限，但只有精确用户能够被封禁。

状态文件中的规则可以用 `+` 显式允许、用 `-` 显式拒绝；省略前缀同样表示允许。
权限节点支持：

- 精确节点：`example.feature.use`
- 命名空间通配符：`example.*`
- 全局通配符：`*`
- 父节点回退：授权 `example.manage` 会覆盖 `example.manage.reload`

匹配时先选择最具体的主体层级，再选择该层最具体的权限节点。用户分支与群分支
处于同一精度；主体和权限节点精度都相同时，拒绝优先。
例如，`+a.b.*` 与 `-a.b.c` 会只拒绝 `a.b.c`；
`-a.b.*` 与 `+a.b.c` 则会只允许这个例外节点。没有规则命中时才使用命令默认策略；
`ADMIN` 默认策略最后检查用户的 `usefulbot.admin`。
插件也可以通过 `PermissionService`
查询或修改权限。

## 宿主配置

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

- `Enabled`：是否扫描和加载外部插件 JAR。
- `Directory`：插件 JAR、插件配置和插件数据的根目录。
- `Disabled`：禁止启用的插件 ID，适用于外部插件和非基础内置插件；基础插件不受影响。
