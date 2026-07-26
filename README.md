# UsefulBot

UsefulBot 是一个使用 Kotlin 编写、通过 NapCat OneBot 11 WebSocket 接入 QQ 的漫画下载与 PDF 生成机器人。目前支持 E-Hentai / ExHentai 和 JMComic，并内置简体中文、英文消息，未知语言会回退到英文。

## 功能

- `/eh`：下载 E-Hentai 或 ExHentai 画廊，生成带密码的 PDF。
- `/jm`：通过 JM 车号或专辑链接下载 JMComic，自动还原分块图片并生成 PDF。
- `/checkin`：每日签到领取 GP。
- `/info`：查看账户信息和 GP 余额。
- `/help`：查看当前可用命令。
- `/about`：查看机器人信息。
- 支持任务队列、重复任务检查、PDF 缓存、失败退款和中英文消息。
- JMComic 支持移动 API 优先、网页端兜底、API/图片多域名切换、多章节和图片并发下载。
- GP 数据使用 H2 持久化，支持连接池、余额流水、原子扣费和并发安全的每日签到。

## 环境要求

- JDK 11 或更高版本。
- 可用的 NapCatQQ OneBot 11 正向 WebSocket 服务。
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
  "Bot": {
    "WebsocketURL": "ws://127.0.0.1:3000",
    "Token": "napcat!",
    "CommandOperator": "/",
    "IsCommandStartWithAt": false,
    "Language": "zh-CN",
    "FileUpload": {
      "ChunkSize": 524288,
      "UseStreamAPI": false,
      "Stream_ExpireSeconds": 600
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

`Language` 支持 `en`、`en-US`、`zh`、`zh-CN`、`中文` 等写法；无法识别时回退到英文。代理 `Type` 可使用 Java `Proxy.Type` 支持的 `DIRECT`、`HTTP` 或 `SOCKS`。

## 命令示例

```text
/eh https://e-hentai.org/g/123456/abcdef1234/
/jm JM123456
/jm https://18comic.vip/album/123456/
/checkin
/info
```

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
- 每日签到按 UTC 日期判断；同一用户的并发请求只会成功一次。
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
