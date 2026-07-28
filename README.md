# UsefulBot

UsefulBot 是一个 Kotlin 漫画下载机器人，可同时接入 NapCat / OneBot 11 和 Telegram Bot API。目前支持 E-Hentai、ExHentai 与 JMComic，提供搜索、下载、PDF 生成、任务队列、缓存和 GP 计费。

## 主要功能

- 下载 E-Hentai / ExHentai 画廊并生成 PDF。
- 通过 JM 车号或专辑链接下载 JMComic，自动还原分块图片并生成 PDF。
- 搜索 E-Hentai / ExHentai 和 JMComic。
- 支持 NapCat 与 Telegram 同时在线，共享下载任务和生成结果。
- 获取漫画信息和封面后立即回复，下载与 PDF 生成继续在后台执行。
- 支持重复任务合并、PDF 缓存、失败退款和每日签到。
- 内置简体中文和英文消息，未知语言自动回退到英文。
- Telegram 可通过本地 Bot API 服务发送最大 2000 MB 的完整文件。

## 快速开始

### 环境要求

- JDK 11 或更高版本。
- NapCat 模式：可用的 OneBot 11 正向 WebSocket 服务。
- Telegram 模式：通过 BotFather 创建的 Bot Token。
- 访问受限漫画源时，需要自行配置合规可用的代理。

### 构建

Windows：

```powershell
.\gradlew.bat --no-daemon :implements:shadowJar
java -jar .\implements\build\libs\implements-1.1.2.jar
```

Linux / macOS：

```bash
./gradlew --no-daemon :implements:shadowJar
java -jar ./implements/build/libs/implements-1.1.2.jar
```

首次运行会在当前工作目录生成 `config.json`。修改配置后需要重启程序。

## 配置

下面仅列出常用字段；未填写的字段会使用程序默认值。

```json
{
  "version": 8,
  "Bot": {
    "CommandOperator": "/",
    "Language": "zh-CN",
    "napcat": {
      "Enabled": true,
      "BlurImages": true,
      "WebsocketURL": "ws://127.0.0.1:3000",
      "Token": "napcat!"
    },
    "telegram": {
      "Enabled": false,
      "BlurImages": false,
      "Token": "",
      "ApiBaseURL": "https://api.telegram.org",
      "UploadTimeoutMinutes": 60,
      "EnableInlineMode": true,
      "LargeFile": {
        "Policy": "SPLIT_PDF",
        "MaxPartSizeMiB": 48,
        "TempDirectory": "data/telegram/temp"
      }
    }
  },
  "Proxy": {
    "Type": "DIRECT",
    "Address": "127.0.0.1",
    "Port": 1080
  },
  "Ehentai": {
    "ipb_member_id": "",
    "ipb_pass_hash": "",
    "igneous": "",
    "isExHentai": false,
    "MaxArchiveSizeMiB": 0
  },
  "ComicParallelCount": 2
}
```

### Bot 适配器

- `Bot.napcat.Enabled`：启用 NapCat。
- `Bot.telegram.Enabled`：启用 Telegram。
- 两者可同时启用；相同漫画只下载和生成一次，再分别发送给订阅者。
- `BlurImages`：控制对应平台发送漫画信息时是否模糊封面。
- `Language`：支持 `en`、`en-US`、`zh`、`zh-CN`、`中文` 等写法。

### 代理

`Proxy.Type` 可使用 Java 支持的以下类型：

- `DIRECT`：不使用代理。
- `HTTP`：HTTP 代理。
- `SOCKS`：SOCKS 代理。

### E-Hentai / ExHentai

- 使用 ExHentai 时需要配置对应 Cookie，并将 `isExHentai` 设为 `true`。
- `MaxArchiveSizeMiB` 限制下载归档大小，设为 `0` 表示不限制。
- 超过限制时只发送画廊信息，不下载归档或生成 PDF。

JMComic 的 API、网页和图片域名已内置，通常无需手动配置。

## Telegram 大文件

### 官方 Bot API

使用 `https://api.telegram.org` 时，超过官方上传限制的 PDF 按 `LargeFile.Policy` 处理：

- `SPLIT_PDF`：按页拆分为不超过 `MaxPartSizeMiB` 的 PDF 分卷。
- `FAIL`：不进行分卷，上传失败会作为任务失败处理。

分卷首次上传后会缓存 Telegram `file_id`。其他用户请求相同 PDF 时直接复用，失效的分卷才会重新生成和上传。

### 本地 Bot API

如需发送完整大文件，请运行官方的本地 [`telegram-bot-api`](https://github.com/tdlib/telegram-bot-api) 服务，并修改：

```json
"ApiBaseURL": "http://127.0.0.1:8081",
"UploadTimeoutMinutes": 60
```

本地服务允许上传最大 2000 MB。UsefulBot 会直接使用 `sendDocument` multipart 上传，不会因为 50 MiB 限制预先拆分 PDF。

从云端 Bot API 切换到本地服务前，需要按照上游说明对 Bot 调用 `logOut`。构建步骤可使用官方的 [`telegram-bot-api` 构建说明生成器](https://tdlib.github.io/telegram-bot-api/build.html)。

Telegram 发送的普通 PDF 和 PDF 分卷均不设置打开密码；NapCat 发送原始完整 PDF。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/help` | 显示帮助和可用命令 |
| `/about` | 显示机器人信息 |
| `/get eh <链接>` | 下载 E-Hentai / ExHentai 画廊 |
| `/get jm <车号或链接>` | 下载 JMComic 专辑 |
| `/search eh <关键词>` | 搜索 E-Hentai / ExHentai |
| `/search jm <关键词>` | 搜索 JMComic |
| `/checkin` | 每日签到领取 GP |
| `/info` | 查看 GP 余额和账户信息 |

示例：

```text
/get eh https://e-hentai.org/g/123456/abcdef1234/
/get jm JM123456
/get jm https://18comic.vip/album/123456/
/search eh --category=manga --min-stars=4 language:chinese
/search jm --page=2 中文 全彩
/checkin
/info
```

E-Hentai 搜索支持：

- `--category=分类,...`
- `--min-stars=0..5`
- `language:chinese`、`artist:name` 等官方标签语法

可用分类包括 `misc`、`doujinshi`、`manga`、`artist-cg`、`game-cg`、`image-set`、`cosplay`、`asian-porn`、`non-h` 和 `western`。

### Telegram 命令菜单

在 BotFather 中执行 `/setcommands` 后粘贴：

```text
help - 显示可用命令和子命令帮助
about - 显示机器人信息
get - 下载 E-Hentai 或 JMComic 漫画
search - 搜索 E-Hentai 或 JMComic 漫画
checkin - 每日签到领取 GP
info - 显示账户信息和 GP 余额
```

### Telegram Inline 搜索

1. 在 BotFather 中执行 `/setinline`。
2. 保持 `EnableInlineMode` 为 `true`。
3. 在任意聊天中输入：

```text
@机器人用户名 eh <关键词>
@机器人用户名 jm <关键词>
```

## PDF、缓存与计费

- E-Hentai PDF 密码为 `<gallery-id>-<token>`。
- JMComic PDF 密码为 `JM<车号>`。
- JMComic 按 `floor(PDF 大小 MiB × 1.1)` 收取 GP。
- 每日签到奖励为 `150～250 GP`，按 UTC 日期判断。
- 发送或任务失败时自动退款。
- 同一漫画的并发请求会合并为一个共享任务。
- 任务完成后自动清理下载归档、解压图片、封面、断点进度和临时文件，仅保留最终 PDF。

## 数据目录

```text
data/
├─ eh/
│  ├─ archive/    # E-Hentai 下载归档
│  ├─ img/        # 封面和页面
│  ├─ pdf/        # 最终 PDF
│  └─ temp/       # 下载与 PDF 临时文件
├─ jm/
│  ├─ img/        # 封面和还原后的页面
│  ├─ pdf/        # 最终 PDF
│  └─ temp/       # PDF 临时文件
├─ telegram/
│  └─ temp/       # Telegram PDF 分卷临时目录
└─ gp.*           # H2 数据库
```

GP 数据使用 H2 持久化。请在程序停止后备份 `data/gp.mv.db`，避免得到不一致的文件快照。

## 配置兼容

旧版本配置会在启动时自动升级到当前格式，并保留未知字段。v7 升级到 v8 时会删除失效的 Telegraph 配置，并将旧的 `TELEGRAPH` 策略迁移为 `SPLIT_PDF`。高于当前支持版本的配置不会被自动降级或重写。

## 测试

```powershell
.\gradlew.bat --offline --no-daemon test
```

构建可执行 JAR：

```powershell
.\gradlew.bat --offline --no-daemon :implements:shadowJar
```

## 相关项目

- [NapCatQQ](https://github.com/NapNeko/NapCatQQ)
- [NapCat OneBot 11 文档](https://napneko.github.io/onebot/index)
- [Telegram Bot API server](https://github.com/tdlib/telegram-bot-api)
- [E-Hentai API](https://ehwiki.org/wiki/API)
- [JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python)
- [JetBrains Exposed](https://www.jetbrains.com/help/exposed/about.html)
- [H2 Database](https://h2database.com/html/main.html)

## 许可与使用说明

本项目使用 [BSD 3-Clause License](LICENSE)。请遵守所在地区法律、目标站点服务条款及内容版权要求，仅下载和处理你有权访问的内容。
