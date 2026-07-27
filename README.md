# UsefulBot

UsefulBot 是一个使用 Kotlin 编写、可通过 NapCat OneBot 11 接入 QQ 或通过官方 Bot API 接入 Telegram 的漫画下载与 PDF 生成机器人。目前支持 E-Hentai / ExHentai 和 JMComic，并内置简体中文、英文消息，未知语言会回退到英文。

## 功能

- `/get eh`：下载 E-Hentai 或 ExHentai 画廊，生成带密码的 PDF。
- `/get jm`：通过 JM 车号或专辑链接下载 JMComic，自动还原分块图片并生成 PDF，按最终 PDF 大小计费。
- `/search`：搜索 E-Hentai/ExHentai 或 JMComic，以合并转发形式返回结果。
- `/checkin`：每日签到领取 GP。
- `/info`：查看账户信息和 GP 余额。
- `/help`：查看当前可用命令。
- `/about`：查看机器人信息。
- 支持任务队列、重复任务检查、PDF 缓存、失败退款和中英文消息。
- JMComic 支持移动 API 优先、网页端兜底、API/图片多域名切换、多章节和图片并发下载。
- GP 数据使用 H2 持久化，支持连接池、余额流水、原子扣费和并发安全的每日签到。

## 环境要求

- JDK 11 或更高版本。
- NapCat 模式需要可用的 NapCatQQ OneBot 11 正向 WebSocket 服务。
- Telegram 模式需要通过 BotFather 创建的机器人 Token。
- 如需访问受网络限制的漫画源，请自行配置合规可用的代理。

## 构建和运行

Windows：

```powershell
.\gradlew.bat --no-daemon :implements:shadowJar
java -jar .\implements\build\libs\implements-1.0.0.jar
```

Linux / macOS：

```bash
./gradlew --no-daemon :implements:shadowJar
java -jar ./implements/build/libs/implements-1.0.0.jar
```

首次运行会在工作目录创建 `config.json`。修改配置后重新启动程序。

## 配置

以下示例保留了主要配置项。JMComic API 和图片域名已经内置，通常无需手动填写。

```json
{
  "version": 6,
  "Bot": {
    "CommandOperator": "/",
    "IsCommandStartWithAt": false,
    "Language": "zh-CN",
    "napcat": {
      "Enabled": true,
      "BlurImages": true,
      "WebsocketURL": "ws://127.0.0.1:3000",
      "Token": "napcat!",
      "FileUpload": {
        "ChunkSize": 524288,
        "UseStreamAPI": false,
        "Stream_ExpireSeconds": 600
      }
    },
    "telegram": {
      "Enabled": true,
      "BlurImages": false,
      "Token": "",
      "ApiBaseURL": "https://api.telegram.org",
      "EnableInlineMode": true,
      "LargeFile": {
        "Policy": "SPLIT_PDF",
        "MaxPartSizeMiB": 48,
        "TempDirectory": "data/telegram/temp"
      },
      "TelegraphPreview": {
        "Enabled": true,
        "AccessToken": "",
        "AuthorName": "UsefulBot",
        "AuthorURL": ""
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
    "star": "",
    "sk": "",
    "isExHentai": false
  },
  "JMComic": {
    "ApiDomains": [
      "www.cdnhjk.net",
      "www.cdngwc.cc",
      "www.cdngwc.net",
      "www.cdngwc.club"
    ],
    "Domains": [],
    "RedirectURL": "https://jm365.work/3YeBdF",
    "ImageDomains": [
      "cdn-msp.jmapiproxy1.cc",
      "cdn-msp.jmapiproxy2.cc",
      "cdn-msp2.jmapiproxy2.cc",
      "cdn-msp3.jmapiproxy2.cc",
      "cdn-msp.jmapinodeudzn.net",
      "cdn-msp3.jmapinodeudzn.net"
    ],
    "ImageParallelCount": 8
  },
  "ComicParallelCount": 2
}
```

分别通过 `Bot.napcat.Enabled` 和 `Bot.telegram.Enabled` 启用 NapCat 与 Telegram；
二者都设为 `true` 时会同时连接，并共享同一个漫画任务队列。不同用户请求同一漫画时，
后续请求会加入正在处理的共享任务，只下载并生成一次，完成后分别向所有请求者发送。
Telegram 使用
`Bot.telegram.Token`，NapCat 使用 `Bot.napcat.Token`。`Language` 支持 `en`、`en-US`、
`zh`、`zh-CN`、`中文` 等写法；无法识别时回退到英文。代理 `Type` 可使用 Java
`Proxy.Type` 支持的 `DIRECT`、`HTTP` 或 `SOCKS`。

`Bot.napcat.BlurImages` 和 `Bot.telegram.BlurImages` 分别控制两个平台发送漫画信息时
是否模糊封面；Telegram 通常设为 `false`，需要规避平台图片审核时可设为 `true`。
Telegram 使用官方 Bot API 且 PDF 超过上传限制时，默认按页拆成不超过
`Bot.telegram.LargeFile.MaxPartSizeMiB` 的临时 PDF 分卷。程序同一时刻只保留一个临时
分卷；首次上传后按 Bot ID、原 PDF SHA-256 和分卷序号持久化 `file_id`，其他请求方直接
复用 Telegram 文件，不再拆分或上传。Telegram 拒绝旧 `file_id` 时只重新生成并上传对应
分卷。`Policy` 可设为 `SPLIT_PDF`、`TELEGRAPH` 或 `FAIL`。使用 `TELEGRAPH` 时还需启用
`TelegraphPreview.Enabled`；`AccessToken` 留空会在首次使用时自动创建 Telegraph 账户。
Telegram 发送的普通 PDF 与 PDF 分卷均不设置打开密码；NapCat 始终发送原始完整 PDF。

`version` 是配置格式版本。没有该字段的旧配置会按 v1 读取，并在启动时自动升级：
原 `Bot.WebsocketURL`、`Bot.Token`、`Bot.FileUpload` 会迁移至
`Bot.napcat`，原 `Bot.Telegram` 会迁移至 `Bot.telegram`。迁移时会递归补齐新版本中
缺失的字段及其默认值，同时保留已有值和其他未知字段；升级至 v4 时，旧 `Adapter`
会迁移为对应适配器的 `Enabled`，旧全局 `BlurImages` 会迁移到原适配器配置中。
升级至 v5 时会补充 Telegram Telegraph 预览配置；升级至 v6 时会补充 Telegram 大文件
分卷策略。
高于当前支持版本的配置不会被自动降级或重写。

### Telegram

Telegram adapter 使用官方 HTTP Bot API 的 `getUpdates` 长轮询，因此机器人不能同时配置
Webhook。群组命令支持 Telegram 的 `/command@bot_username` 格式；NapCat 合并转发在
Telegram 中会降级为带分隔线的普通文本消息。

#### 注册 Telegram 命令

在 BotFather 中执行 `/setcommands`，选择 UsefulBot 后粘贴以下内容：

```text
help - 显示可用命令和子命令帮助
about - 显示机器人信息
get - 下载 E-Hentai 或 JMComic 漫画
search - 搜索 E-Hentai 或 JMComic 漫画
checkin - 每日签到领取 GP
info - 显示账户信息和 GP 余额
```

这里只注册顶级命令。`eh`、`jm` 是 `/get` 和 `/search` 的子命令，例如
`/get eh <画廊链接>`、`/search jm <关键词>`，不需要单独注册。BotFather 中的命令菜单
只影响 Telegram 客户端展示，实际命令仍由 UsefulBot 的命令注册器处理。

#### Inline 搜索

如需启用 inline 搜索：

1. 在 BotFather 中执行 `/setinline` 并为机器人开启 Inline Mode。
2. 保持 `EnableInlineMode` 为 `true`。
3. 在任意聊天输入 `@你的机器人用户名 eh <关键词>` 或
   `@你的机器人用户名 jm <关键词>`。

例如：

```text
@UsefulBot eh --category=manga --min-stars=4 language:chinese
@UsefulBot jm 中文 全彩
```

## 命令示例

执行任意有效命令前会自动进行每日签到；当天首次执行时会获得并显示签到 GP。显式执行
`/checkin` 时仍会返回签到成功或今日已签到的状态。

```text
/get eh https://e-hentai.org/g/123456/abcdef1234/
/get jm JM123456
/get jm https://18comic.vip/album/123456/
/search eh language:chinese artist:example
/search eh --category=doujinshi,manga --min-stars=4 language:chinese
/search jm --page=2 中文 全彩
/checkin
/info
```

E-Hentai 搜索支持 `--category=分类,...` 和 `--min-stars=0..5`。分类可使用
`misc`、`doujinshi`、`manga`、`artist-cg`、`game-cg`、`image-set`、
`cosplay`、`asian-porn`、`non-h`、`western`；`language:chinese`、
`artist:name` 等官方标签语法仍作为普通搜索关键词直接传递。

E-Hentai PDF 密码为 `<gallery-id>-<token>`，JMComic PDF 密码为 `JM<车号>`。

## 数据目录

下载数据按漫画源分类，避免不同来源的缓存、图片和 PDF 混在一起：

```text
data/
├─ eh/
│  ├─ archive/    # E-Hentai 下载的压缩包
│  ├─ img/        # 封面和解压后的页面
│  ├─ pdf/        # 生成的 PDF
│  └─ temp/       # HTTP 与 PDF 临时文件
├─ jm/
│  ├─ img/        # 封面和还原后的页面
│  ├─ pdf/        # 生成的 PDF
│  └─ temp/       # PDF 临时文件
└─ gp.*           # H2 用户与 GP 数据库
```

目录会在首次使用相应功能时自动创建。旧版本位于 `data/img`、`data/pdf`、`data/archive`、`data/temp` 的数据不会自动迁移。

## 数据库

- 数据库文件位于 `data/gp.mv.db`，启动时会自动创建或补齐缺失表、字段和索引。
- 用户余额变更与流水记录在同一事务中提交，扣费使用带余额条件的原子更新。
- 每日签到奖励为 `150～250 GP`（包含两端），按 UTC 日期判断；同一用户的并发请求只会成功一次。
- JMComic 按 `floor(PDF 大小 MiB × 1.1)` 收取 GP；缓存文件使用相同规则，发送失败自动退款。
- 流水按用户、创建时间建立组合索引，并默认按最新记录优先查询。
- 应在机器人停止运行后备份或复制 `data/gp.mv.db`，避免得到不一致的文件级快照。

## 参考文档

### NapCat / OneBot

- [NapCatQQ 项目](https://github.com/NapNeko/NapCatQQ)
- [NapCat OneBot 11 文档](https://napneko.github.io/onebot/index)
- [NapCat API 文档](https://napneko.github.io/api/)

### E-Hentai

- [E-Hentai API 文档](https://ehwiki.org/wiki/API)

### 数据库

- [JetBrains Exposed 文档](https://www.jetbrains.com/help/exposed/about.html)
- [H2 Database 文档](https://h2database.com/html/main.html)

### JMComic

JMComic 实现参考了 `JMComic-Crawler-Python` 的移动 API 客户端、域名配置、响应解密和图片分块还原算法：

- [JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python)
- [jmcomic 常用类和方法](https://jmcomic.readthedocs.io/zh-cn/latest/tutorial/0_common_usage/)
- [JMComic 客户端实现](https://github.com/hect0x7/JMComic-Crawler-Python/blob/master/src/jmcomic/jm_client_impl.py)
- [JMComic 配置及 API 常量](https://github.com/hect0x7/JMComic-Crawler-Python/blob/master/src/jmcomic/jm_config.py)
- [JMComic 图片处理工具](https://github.com/hect0x7/JMComic-Crawler-Python/blob/master/src/jmcomic/jm_toolkit.py)

## 测试

```powershell
.\gradlew.bat --offline --no-daemon test
```

## 许可与使用说明

本项目使用 [BSD 3-Clause License](LICENSE)。请遵守所在地区法律、目标站点服务条款及内容版权要求，仅下载和处理你有权访问的内容。
