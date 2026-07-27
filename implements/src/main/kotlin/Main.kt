import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.heartalborada.QueueExtraData
import me.heartalborada.QueueUser
import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.bots.telegram.TelegramApiException
import me.heartalborada.bots.telegram.TelegramBot
import me.heartalborada.bots.telegram.TelegramFileIdCache
import me.heartalborada.bots.telegram.TelegramLargeDocumentSender
import me.heartalborada.comics.EHentai
import me.heartalborada.comics.JMComic
import me.heartalborada.commons.Util
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.InlineQueryResult
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.events.message.InlineQueryEvent
import me.heartalborada.commons.comic.model.ArchiveInformation
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.comic.SizeBoundedPdfSplitter
import me.heartalborada.commons.comic.model.ComicInformation
import me.heartalborada.commons.comic.model.ComicSearchOptions
import me.heartalborada.commons.comic.model.ComicSearchPage
import me.heartalborada.commons.comic.model.ComicSearchResult
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.comparator.NaturalFileNameComparator
import me.heartalborada.commons.downloader.DownloadManager
import me.heartalborada.commons.economic.ComicPricing
import me.heartalborada.commons.economic.EconomicManager
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.commands.parseSearchCommandArguments
import me.heartalborada.commons.okhttp.CookieStorageProvider
import me.heartalborada.commons.queue.ProcessingQueue
import me.heartalborada.config.Config
import me.heartalborada.config.LargeFilePolicy
import me.heartalborada.i18n.PropertiesTranslator
import me.heartalborada.telegraph.TelegraphPublisher
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.h2.jdbcx.JdbcConnectionPool
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private val pdfCache = CacheBuilder.newBuilder()
    .expireAfterWrite(24, java.util.concurrent.TimeUnit.HOURS)
    .maximumSize(1024)
    .build<String, Boolean>()

private val rootFolder = File(".")
private val dataFolder = File("data")
private val ehDataFolder = File(dataFolder, "eh")
private val ehTempFolder = File(ehDataFolder, "temp")
private val ehPdfFolder = File(ehDataFolder, "pdf")
private val ehImgFolder = File(ehDataFolder, "img")
private val ehArchiveFolder = File(ehDataFolder, "archive")
private val jmDataFolder = File(dataFolder, "jm")
private val jmTempFolder = File(jmDataFolder, "temp")
private val jmPdfFolder = File(jmDataFolder, "pdf")
private val jmImgFolder = File(jmDataFolder, "img")

private val logger = LoggerFactory.getLogger("Main")
private val ALLOW_SUFFIX = setOf("jpg", "jpeg", "gif", "png", "webp")

private val config = Config(File(rootFolder, "config.json"))
private val translator = PropertiesTranslator(config.getConfig().bot.language)
private val telegraphPublisher by lazy {
    val telegraph = config.getConfig().bot.telegram.telegraphPreview
    TelegraphPublisher(
        client = client,
        configuredAccessToken = telegraph.accessToken,
        authorName = telegraph.authorName,
        authorUrl = telegraph.authorUrl,
    )
}

private val economicDataSource = run {
    dataFolder.mkdirs()
    JdbcConnectionPool.create("jdbc:h2:./data/gp;LOCK_TIMEOUT=10000", "sa", "").apply {
        maxConnections = (config.getConfig().comicParallelCount + 4).coerceIn(8, 32)
        loginTimeout = 10
        Runtime.getRuntime().addShutdownHook(Thread({ dispose() }, "economic-database-shutdown"))
    }
}
private val economic = EconomicManager(Database.connect(economicDataSource))
private lateinit var client: OkHttpClient
private lateinit var eh: EHentai
private lateinit var jm: JMComic

private sealed interface ComicTask {
    data class EHentai(val gallery: Pair<String, String>) : ComicTask
    data class JMComic(val albumId: String) : ComicTask
}

private sealed interface ComicTaskResult {
    data class EHentai(
        val gallery: Pair<String, String>,
        val info: ComicInformation<*>,
        val cover: File,
        val pdf: File?,
        val pages: List<File>,
        val cacheHit: Boolean,
        val cost: Long,
    ) : ComicTaskResult

    data class JMComic(
        val albumId: String,
        val info: ComicInformation<*>,
        val cover: File,
        val pdf: File,
        val pages: List<File>,
        val cacheHit: Boolean,
    ) : ComicTaskResult
}

private val queue = ProcessingQueue<QueueUser, ComicTask, QueueExtraData>(
    globalCapacity = config.getConfig().comicParallelCount
)

private fun AbstractBot.reply(sender: MessageSender, messageID: Long, text: String): Long {
    return sendMessage(sender.type, sender.target, MessageChain.replyTo(messageID, text))
}

private fun listComicPages(directory: File): List<File> {
    val comparator = NaturalFileNameComparator()
    return directory.listFiles { file ->
        file.isFile &&
            file.name.substringBeforeLast(".") != "cover" &&
            ALLOW_SUFFIX.contains(Util.getFileExtensionFromUrl(file.toURI().toURL()))
    }?.sortedWith(comparator).orEmpty()
}

internal fun deleteCacheEntry(root: File, target: File): Boolean {
    val canonicalRoot = root.canonicalFile.toPath()
    val canonicalTarget = target.canonicalFile.toPath()
    require(canonicalTarget != canonicalRoot && canonicalTarget.startsWith(canonicalRoot)) {
        "Refusing to delete a cache entry outside its root: $canonicalTarget"
    }
    if (!target.exists()) return true
    return if (target.isDirectory) target.deleteRecursively() else target.delete()
}

private fun cleanupComicTaskCache(task: ComicTask) {
    val targets = when (task) {
        is ComicTask.EHentai -> {
            val taskName = "${task.gallery.first}-${task.gallery.second}"
            listOf(
                ehArchiveFolder to File(ehArchiveFolder, "$taskName.zip"),
                ehImgFolder to File(ehImgFolder, taskName),
                ehTempFolder to File(File(ehTempFolder, "download"), taskName),
                ehTempFolder to File(File(ehTempFolder, "pdf"), taskName),
            )
        }

        is ComicTask.JMComic -> {
            val taskName = "JM${task.albumId}"
            listOf(
                jmImgFolder to File(jmImgFolder, taskName),
                jmTempFolder to File(File(jmTempFolder, "pdf"), taskName),
            )
        }
    }
    targets.forEach { (root, target) ->
        runCatching {
            check(deleteCacheEntry(root, target)) {
                "Failed to delete cache entry ${target.absolutePath}"
            }
        }.onFailure { exception ->
            logger.warn("Failed to clean comic task cache {}.", target.absolutePath, exception)
        }
    }
}

fun main() = runBlocking {
    val telegramFileIdCache = TelegramFileIdCache(economicDataSource)
    val telegramLargeDocumentSenders = ConcurrentHashMap<TelegramBot, TelegramLargeDocumentSender>()

    fun sendComicInformation(
        info: ComicInformation<*>,
        coverFile: File,
        sender: MessageSender,
        messageID: Long,
        bot: AbstractBot,
        blurImages: Boolean,
    ) {
        val image = ImageIO.read(coverFile) ?: throw IllegalStateException("Failed to decode comic cover.")
        val displayImage = if (blurImages) {
            Util.gaussianBlur(Util.resampleImage(Util.resampleImage(image, 0.125), 8.0), radius = 10)
        } else {
            image
        }
        val base64 = Util.bufferedImageToBase64(displayImage)
        val message = MessageChain().also {
            it.add(Reply(messageID))
            val title = if (info.subtitle != null) {
                translator.translate("gallery.title_with_subtitle", info.title, info.subtitle)
            } else {
                translator.translate("gallery.title", info.title)
            }
            it.add(PlainText("$title\n"))
            it.add(PlainText("${translator.translate("gallery.uploader", info.uploader)}\n"))
            if (info.rating > 0) {
                it.add(PlainText("${translator.translate("gallery.rating", info.rating)}\n"))
            }
            it.add(PlainText("${translator.translate("gallery.pages", info.pages)}\n"))
            it.add(PlainText(translator.translate("gallery.type", info.category.s)))
            it.add(Image(FileInfo("${info.title}.jpg", url = "base64://$base64")))
        }
        bot.sendMessage(sender.type, sender.target, message)
    }

    fun sendFileWithTelegraphFallback(
        bot: AbstractBot,
        sender: MessageSender,
        messageID: Long,
        name: String,
        pdf: File,
        pdfPassword: String?,
        title: String,
        pages: List<File>,
    ): Boolean {
        if (bot !is TelegramBot) {
            return bot.sendFile(sender.type, sender.target, name, pdf)
        }
        val telegramConfig = config.getConfig().bot.telegram
        val telegraphConfig = telegramConfig.telegraphPreview
        val largeFileConfig = telegramConfig.largeFile
        require(largeFileConfig.maxPartSizeMiB in 1..49) {
            "Telegram MaxPartSizeMiB must be between 1 and 49."
        }

        fun telegramTempDirectory(): File {
            val configured = File(largeFileConfig.tempDirectory)
            return if (configured.isAbsolute) configured else File(rootFolder, largeFileConfig.tempDirectory)
        }

        fun sendUnlockedPdf(): Boolean {
            if (pdfPassword == null) {
                return bot.sendFile(sender.type, sender.target, name, pdf)
            }
            val tempDirectory = telegramTempDirectory().apply { mkdirs() }
            val temporaryFile = File.createTempFile(
                "usefulbot-tg-part-unlocked-",
                ".pdf",
                tempDirectory,
            )
            return try {
                SizeBoundedPdfSplitter().writeUnlockedCopy(
                    source = pdf,
                    password = pdfPassword,
                    target = temporaryFile,
                )
                bot.sendFile(sender.type, sender.target, name, temporaryFile)
            } finally {
                temporaryFile.delete()
            }
        }

        fun publishPreview(): Boolean {
            check(telegraphConfig.enabled) {
                "Telegram Telegraph preview fallback is disabled."
            }
            val url = telegraphPublisher.publish(
                title = title,
                cacheKey = pdf.canonicalPath,
                pages = pages,
            )
            bot.reply(
                sender,
                messageID,
                translator.translate("telegram.telegraph_preview", url),
            )
            return true
        }

        fun sendSplitPdf(): Boolean {
            bot.reply(
                sender,
                messageID,
                translator.translate(
                    "telegram.pdf_split",
                    largeFileConfig.maxPartSizeMiB,
                ),
            )
            val tempDirectory = telegramTempDirectory()
            val largeDocumentSender = telegramLargeDocumentSenders.computeIfAbsent(bot) {
                TelegramLargeDocumentSender(
                    client = bot,
                    cache = telegramFileIdCache,
                    splitter = SizeBoundedPdfSplitter(),
                    tempDirectory = tempDirectory,
                    maximumPartBytes = largeFileConfig.maxPartSizeMiB.toLong() * 1024 * 1024,
                )
            }
            return largeDocumentSender.send(
                type = sender.type,
                target = sender.target,
                displayName = name,
                source = pdf,
                password = pdfPassword,
            )
        }

        if (bot.shouldUseTelegraphPreview(pdf)) {
            return when (largeFileConfig.policy) {
                LargeFilePolicy.SPLIT_PDF -> sendSplitPdf()
                LargeFilePolicy.TELEGRAPH -> publishPreview()
                LargeFilePolicy.FAIL -> sendUnlockedPdf()
            }
        }
        return try {
            sendUnlockedPdf()
        } catch (exception: TelegramApiException) {
            if (!exception.isRequestEntityTooLarge()) {
                throw exception
            }
            logger.info(
                "Telegram rejected {} ({} bytes); applying configured large-file policy {}.",
                pdf.name,
                pdf.length(),
                largeFileConfig.policy,
            )
            when (largeFileConfig.policy) {
                LargeFilePolicy.SPLIT_PDF -> sendSplitPdf()
                LargeFilePolicy.TELEGRAPH -> publishPreview()
                LargeFilePolicy.FAIL -> throw exception
            }
        }
    }

    suspend fun generateEHentai(gallery: Pair<String, String>): ComicTaskResult.EHentai {
        val cacheKey = "eh:${gallery.first}:${gallery.second}"
        val taskName = "${gallery.first}-${gallery.second}"
        val p = File(ehPdfFolder, "$taskName.pdf")
        val cf = File(ehImgFolder, taskName)
        if (!cf.isDirectory || !cf.exists()) {
            cf.delete()
            cf.mkdirs()
        }
        val info = eh.getTargetInformation(gallery)
        val downloader = DownloadManager(
            16,
            client,
            File(File(ehTempFolder, "download"), taskName),
        )
        try {
        val cover = "cover.${Util.getFileExtensionFromUrl(URL(info.cover))}"
        downloader.downloadFiles(listOf(Pair(info.cover, cover)), cf, 2)
        if (pdfCache.getIfPresent(cacheKey) == true || p.exists()) {
            return ComicTaskResult.EHentai(
                gallery = gallery,
                info = info,
                cover = File(cf, cover),
                pdf = p,
                pages = listComicPages(cf),
                cacheHit = true,
                cost = 0,
            )
        }

        val archive = eh.getArchiveInformation(gallery).firstOrNull { it.name == "RESAMPLE" }
        if (archive == null) {
            return ComicTaskResult.EHentai(
                gallery = gallery,
                info = info,
                cover = File(cf, cover),
                pdf = null,
                pages = emptyList(),
                cacheHit = false,
                cost = 0,
            )
        }
        val cost = kotlin.math.ceil(
            Util.convertToBytes(archive.size.replace(" ", "")).toDouble() / 1024 / 1024
        ).toLong().coerceAtLeast(1L)
        val subscribers = queue.getSubscribers(ComicTask.EHentai(gallery)).map { it.second }
        if (subscribers.none { extra ->
                economic.getBalance(extra.sender.user.userID.toULong()) >= cost
            }
        ) {
            return ComicTaskResult.EHentai(
                gallery = gallery,
                info = info,
                cover = File(cf, cover),
                pdf = null,
                pages = emptyList(),
                cacheHit = false,
                cost = cost,
            )
        }

        val archiveUrl = eh.getArchiveDownloadUrl(gallery, ArchiveInformation("RESAMPLE"))
        var count = 0
        var list = mutableListOf<Pair<String, String?>>(Pair(archiveUrl, "$taskName.zip"))
        while (list.isNotEmpty()) {
            check(count < 3) { "Failed to download the E-Hentai archive after 3 attempts." }
            count++
            list = downloader.downloadFiles(list, ehArchiveFolder, 4)
        }
        Util.unzip(File(ehArchiveFolder, "$taskName.zip"), cf)
        ehPdfFolder.mkdirs()
        val comparator = NaturalFileNameComparator()
        val pages = cf.listFiles { file ->
            ALLOW_SUFFIX.contains(
                Util.getFileExtensionFromUrl(
                    file.toURI().toURL()
                )
            ) && file.name.substringBeforeLast(".") != "cover" && file.isFile
        }?.sortedWith(comparator).orEmpty()
        check(pages.isNotEmpty()) { "No supported images were found in the downloaded archive." }
        PDFGenerator.generatePDF(
            pages,
            pdfFile = p,
            tempDir = File(File(ehTempFolder, "pdf"), taskName),
            password = "${gallery.first}-${gallery.second}",
            signatureText = "Generated at:${
                Instant.now().atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))
            }"
        )
        pdfCache.put(cacheKey, true)
        return ComicTaskResult.EHentai(
            gallery = gallery,
            info = info,
            cover = File(cf, cover),
            pdf = p,
            pages = pages,
            cacheHit = false,
            cost = cost,
        )
        } finally {
            downloader.close()
        }
    }

    fun deliverEHentai(result: ComicTaskResult.EHentai, extra: QueueExtraData) {
        val bot = extra.bot
        val sender = extra.sender
        val messageID = extra.messageID
        sendComicInformation(result.info, result.cover, sender, messageID, bot, extra.blurImages)

        val pdf = result.pdf
        if (pdf == null) {
            if (result.cost > 0) {
                val userId = sender.user.userID.toULong()
                bot.reply(
                    sender,
                    messageID,
                    translator.translate("gallery.insufficient_gp", result.cost, economic.getBalance(userId)),
                )
            } else {
                bot.reply(sender, messageID, translator.translate("gallery.archive_unavailable"))
            }
            return
        }
        if (result.cacheHit) {
            bot.reply(sender, messageID, translator.translate("gallery.cache_hit"))
        } else {
            val userId = sender.user.userID.toULong()
            if (!economic.withdrawGP(userId, result.cost)) {
                bot.reply(
                    sender,
                    messageID,
                    translator.translate("gallery.insufficient_gp", result.cost, economic.getBalance(userId))
                )
                return
            }
            bot.reply(
                sender,
                messageID,
                translator.translate("gallery.preparing", result.cost, economic.getBalance(userId))
            )
        }

        val sent = try {
            sendFileWithTelegraphFallback(
                bot = bot,
                sender = sender,
                messageID = messageID,
                name = "${result.gallery.first}-${result.gallery.second}.pdf",
                pdf = pdf,
                pdfPassword = "${result.gallery.first}-${result.gallery.second}",
                title = result.info.title,
                pages = result.pages,
            )
        } catch (exception: Exception) {
            if (!result.cacheHit) {
                economic.depositGP(sender.user.userID.toULong(), result.cost)
            }
            throw exception
        }
        if (!sent) {
            bot.reply(
                sender,
                messageID,
                translator.translate(
                    if (result.cacheHit) "gallery.cache_send_failed" else "gallery.send_failed"
                )
            )
        }
    }

    fun sendJmPdf(
        pdf: File,
        sender: MessageSender,
        messageID: Long,
        bot: AbstractBot,
        pdfPassword: String,
        previewTitle: String,
        previewPages: List<File>,
    ) {
        check(pdf.isFile) { "JMComic PDF does not exist: ${pdf.absolutePath}" }
        val cost = ComicPricing.jmPdfCost(pdf.length())
        val userId = sender.user.userID.toULong()
        val charged = cost > 0
        if (charged && !economic.withdrawGP(userId, cost)) {
            bot.reply(
                sender,
                messageID,
                translator.translate("jm.insufficient_gp", cost, economic.getBalance(userId))
            )
            return
        }

        try {
            bot.reply(
                sender,
                messageID,
                if (charged) {
                    translator.translate("jm.charged", cost, economic.getBalance(userId))
                } else {
                    translator.translate("jm.free")
                }
            )
            if (
                !sendFileWithTelegraphFallback(
                    bot = bot,
                    sender = sender,
                    messageID = messageID,
                    name = pdf.name,
                    pdf = pdf,
                    pdfPassword = pdfPassword,
                    title = previewTitle,
                    pages = previewPages,
                )
            ) {
                if (charged) {
                    economic.depositGP(userId, cost)
                    bot.reply(sender, messageID, translator.translate("jm.send_failed_refunded", cost))
                } else {
                    bot.reply(sender, messageID, translator.translate("jm.send_failed"))
                }
            }
        } catch (exception: Exception) {
            if (charged) {
                economic.depositGP(userId, cost)
            }
            throw exception
        }
    }

    fun generateJMComic(albumId: String): ComicTaskResult.JMComic {
        val cacheKey = "jm:$albumId"
        val pdf = File(jmPdfFolder, "JM$albumId.pdf")
        val imageDirectory = File(jmImgFolder, "JM$albumId")
        imageDirectory.mkdirs()
        val info = jm.getTargetInformation(albumId)
        val cover = jm.downloadCover(albumId, File(imageDirectory, "cover.jpg"))

        if (pdfCache.getIfPresent(cacheKey) == true || pdf.isFile) {
            return ComicTaskResult.JMComic(
                albumId = albumId,
                info = info,
                cover = cover,
                pdf = pdf,
                pages = listComicPages(imageDirectory),
                cacheHit = true,
            )
        }

        val pages = jm.downloadAlbum(albumId, imageDirectory)
        jmPdfFolder.mkdirs()
        PDFGenerator.generatePDF(
            pages,
            pdfFile = pdf,
            tempDir = File(File(jmTempFolder, "pdf"), "JM$albumId"),
            password = "JM$albumId",
            signatureText = "Generated at:${
                Instant.now().atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))
            }"
        )
        pdfCache.put(cacheKey, true)
        return ComicTaskResult.JMComic(
            albumId = albumId,
            info = info,
            cover = cover,
            pdf = pdf,
            pages = pages,
            cacheHit = false,
        )
    }

    fun deliverJMComic(result: ComicTaskResult.JMComic, extra: QueueExtraData) {
        val bot = extra.bot
        val sender = extra.sender
        val messageID = extra.messageID
        sendComicInformation(result.info, result.cover, sender, messageID, bot, extra.blurImages)
        if (result.cacheHit) {
            bot.reply(sender, messageID, translator.translate("jm.cache_hit"))
        } else {
            bot.reply(
                sender,
                messageID,
                translator.translate("jm.preparing", result.albumId, result.info.pages),
            )
        }
        sendJmPdf(
            pdf = result.pdf,
            sender = sender,
            messageID = messageID,
            bot = bot,
            pdfPassword = "JM${result.albumId}",
            previewTitle = result.info.title,
            previewPages = result.pages,
        )
    }

    logger.info("Initializing...")
    val cookieJar = CookieStorageProvider()
    val ck = mutableListOf<Cookie>().let {
        it.add(
            Cookie.Builder().name("ipb_member_id").domain("e-hentai.org").value(config.getConfig().eHentai.ipbMemberId)
                .path("/").build()
        )
        it.add(
            Cookie.Builder().name("ipb_pass_hash").domain("e-hentai.org")
                .value(config.getConfig().eHentai.ipbPassHash).path("/").build()
        )
        it.add(
            Cookie.Builder().name("igneous").domain("e-hentai.org").value(config.getConfig().eHentai.igneous).path("/")
                .build()
        )
        it.add(
            Cookie.Builder().name("star").domain("e-hentai.org").value(config.getConfig().eHentai.star).path("/").build()
        )
        it.add(
            Cookie.Builder().name("sk").domain("e-hentai.org").value(config.getConfig().eHentai.sk).path("/").build()
        )
        it
    }
    if (config.getConfig().eHentai.isExHentai)
        cookieJar.saveFromResponse(URL("https://exhentai.org/").toHttpUrlOrNull()!!, ck)
    else
        cookieJar.saveFromResponse(URL("https://e-hentai.org/").toHttpUrlOrNull()!!, ck)
    client = if (config.getConfig().proxy.type == Proxy.Type.DIRECT) {
        OkHttpClient.Builder().build()
    } else {
        OkHttpClient.Builder()
            .proxy(
                Proxy(
                    config.getConfig().proxy.type,
                    InetSocketAddress(config.getConfig().proxy.address, config.getConfig().proxy.port)
                )
            ).build()
    }
    eh = EHentai(
        parentClient = client,
        cacheFolder = File(ehTempFolder, "okhttp-cache"),
        isEx = config.getConfig().eHentai.isExHentai,
        cookieStorage = cookieJar
    )
    jm = JMComic(
        parentClient = client,
        apiDomains = config.getConfig().jmComic.apiDomains,
        domains = config.getConfig().jmComic.domains,
        redirectUrl = config.getConfig().jmComic.redirectUrl,
        imageDomains = config.getConfig().jmComic.imageDomains,
        imageParallelCount = config.getConfig().jmComic.imageParallelCount,
    )

    logger.info("Connecting...")
    val botConfig = config.getConfig().bot
    val bots = buildList {
        if (botConfig.napcat.enabled) {
            add(
                Napcat(
                    botConfig.napcat.websocketUrl,
                    botConfig.napcat.token,
                    botConfig.isCommandStartWithAt,
                    commandOperator = botConfig.commandOperator,
                    useStreamAPI = botConfig.napcat.fileUpload.useStreamAPI,
                    streamAPIChunkSize = botConfig.napcat.fileUpload.chunkSize,
                    streamAPIExpireSeconds = botConfig.napcat.fileUpload.expireSeconds,
                    translator = translator,
                    autoConnect = false,
                ) to botConfig.napcat.blurImages
            )
        }
        if (botConfig.telegram.enabled) {
            add(
                TelegramBot(
                    token = botConfig.telegram.token,
                    apiBaseUrl = botConfig.telegram.apiBaseUrl,
                    parentClient = client,
                    commandOperator = botConfig.commandOperator,
                    translator = translator,
                    inlineModeEnabled = botConfig.telegram.enableInlineMode,
                    autoConnect = false,
                ) to botConfig.telegram.blurImages
            )
        }
    }
    check(bots.isNotEmpty()) { "At least one bot adapter must be enabled." }

    fun configureBot(bot: AbstractBot, blurImages: Boolean) {
        bot.beforeCommandExecution(CommandExecutor { sender, command, _, messageID ->
        if (command == "checkin") {
            return@CommandExecutor
        }
        val userId = sender.user.userID.toULong()
        val (amount, checkedIn) = economic.userCheckIn(userId)
        if (checkedIn) {
            bot.reply(
                sender,
                messageID,
                translator.translate("command.checkin.success", amount, economic.getBalance(userId)),
            )
        }
    })

    bot.registerCommand("about", usage = translator.translate("command.about.usage")) {
        sender, _, _, messageID ->
        bot.reply(sender, messageID, translator.translate("command.about.content"))
    }

    val getEHExecutor = CommandExecutor { sender, _, args, messageID ->
        val url = args.toString().trim()
        if (url.isEmpty()) {
            bot.reply(
                sender,
                messageID,
                translator.translate("command.eh.missing_url", config.getConfig().bot.commandOperator)
            )
            return@CommandExecutor
        }

        val gallery = try {
            eh.parseUrl(url)
        } catch (_: IllegalArgumentException) {
            bot.reply(sender, messageID, translator.translate("command.eh.invalid_url"))
            return@CommandExecutor
        }

        val result = queue.putOrJoin(
            QueueUser(bot, sender.user.userID),
            ComicTask.EHentai(gallery),
            QueueExtraData(messageID, sender, bot, blurImages)
        )
        val response = when (result) {
            ProcessingQueue.PutStatus.QUEUE_FULL ->
                translator.translate("command.eh.queue_full")
            ProcessingQueue.PutStatus.USER_QUEUE_FULL ->
                translator.translate("command.eh.user_queue_full")
            ProcessingQueue.PutStatus.DUPLICATE_TASK ->
                translator.translate("command.eh.duplicate")
            ProcessingQueue.PutStatus.JOINED_TASK ->
                translator.translate("command.eh.joined")
            ProcessingQueue.PutStatus.SUCCESS ->
                translator.translate("command.eh.accepted")
            ProcessingQueue.PutStatus.FAILURE ->
                translator.translate("command.eh.queue_failed")
        }
        bot.reply(sender, messageID, response)
    }

    val getJMExecutor = CommandExecutor { sender, _, args, messageID ->
        val target = args.toString().trim()
        if (target.isEmpty()) {
            bot.reply(
                sender,
                messageID,
                translator.translate("command.jm.missing_target", config.getConfig().bot.commandOperator)
            )
            return@CommandExecutor
        }
        val albumId = try {
            jm.parseUrl(target)
        } catch (_: IllegalArgumentException) {
            bot.reply(sender, messageID, translator.translate("command.jm.invalid_target"))
            return@CommandExecutor
        }
        val result = queue.putOrJoin(
            QueueUser(bot, sender.user.userID),
            ComicTask.JMComic(albumId),
            QueueExtraData(messageID, sender, bot, blurImages)
        )
        val response = when (result) {
            ProcessingQueue.PutStatus.QUEUE_FULL ->
                translator.translate("command.jm.queue_full")
            ProcessingQueue.PutStatus.USER_QUEUE_FULL ->
                translator.translate("command.jm.user_queue_full")
            ProcessingQueue.PutStatus.DUPLICATE_TASK ->
                translator.translate("command.jm.duplicate")
            ProcessingQueue.PutStatus.JOINED_TASK ->
                translator.translate("command.jm.joined")
            ProcessingQueue.PutStatus.SUCCESS ->
                translator.translate("command.jm.accepted")
            ProcessingQueue.PutStatus.FAILURE ->
                translator.translate("command.jm.queue_failed")
        }
        bot.reply(sender, messageID, response)
    }

    bot.registerCommand(
        "get",
        usage = translator.translate("command.get.usage", config.getConfig().bot.commandOperator),
    ) {
        subcommand(
            "eh",
            usage = translator.translate("command.get.eh.usage", config.getConfig().bot.commandOperator),
            executor = getEHExecutor,
        )
        subcommand(
            "jm",
            usage = translator.translate("command.get.jm.usage", config.getConfig().bot.commandOperator),
            executor = getJMExecutor,
        )
    }

    fun searchExecutor(
        source: String,
        search: (String, Int, ComicSearchOptions) -> ComicSearchPage<*>,
    ): CommandExecutor = CommandExecutor { sender, _, args, messageID ->
        val arguments = try {
            parseSearchCommandArguments(
                input = args.toString(),
                allowGalleryFilters = source == "eh",
            )
        } catch (_: IllegalArgumentException) {
            bot.reply(
                sender,
                messageID,
                translator.translate("command.search.invalid", config.getConfig().bot.commandOperator)
            )
            return@CommandExecutor
        }

        val searchPage = try {
            search(arguments.keyword, arguments.page, arguments.options)
        } catch (exception: Exception) {
            logger.warn(
                "Search failed for {} page {}: {}",
                source,
                arguments.page,
                arguments.keyword,
                exception,
            )
            bot.reply(sender, messageID, translator.translate("command.search.failed"))
            return@CommandExecutor
        }
        if (searchPage.results.isEmpty()) {
            bot.reply(sender, messageID, translator.translate("command.search.empty", arguments.keyword))
            return@CommandExecutor
        }

        val nodes = searchPage.results.take(SEARCH_RESULT_LIMIT).mapIndexed { index, result ->
            ForwardMessageNode.CustomMessage(
                nickname = if (source == "eh") "E-Hentai #${index + 1}" else "JMComic #${index + 1}",
                content = MessageChain().apply {
                    if (bot is TelegramBot) add(Reply(messageID))
                    add(
                        PlainText(
                            formatSearchResult(
                                source = source,
                                result = result,
                                index = index + 1,
                                commandOperator = config.getConfig().bot.commandOperator,
                                translator = translator,
                            )
                        )
                    )
                },
            )
        }
        bot.sendForwardMessage(sender.type, sender.target, nodes)
    }

    bot.registerCommand(
        "search",
        usage = translator.translate("command.search.usage", config.getConfig().bot.commandOperator),
    ) {
        subcommand(
            "eh",
            usage = translator.translate("command.search.eh.usage", config.getConfig().bot.commandOperator),
            executor = searchExecutor("eh", eh::search),
        )
        subcommand(
            "jm",
            usage = translator.translate("command.search.jm.usage", config.getConfig().bot.commandOperator),
            executor = searchExecutor("jm", jm::search),
        )
    }

    bot.getEventBus().register(InlineQueryEvent::class.java) { event ->
        launch(Dispatchers.IO) {
            val query = event.query.trim()
            val source = query.substringBefore(' ').lowercase()
            val rawArguments = query.substringAfter(' ', "")
            val arguments = runCatching {
                require(source == "eh" || source == "jm")
                parseSearchCommandArguments(
                    input = rawArguments,
                    allowGalleryFilters = source == "eh",
                )
            }.getOrElse {
                bot.answerInlineQuery(event.queryID, emptyList())
                return@launch
            }
            val page = event.offset.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: arguments.page
            val searchPage = runCatching {
                if (source == "eh") {
                    eh.search(arguments.keyword, page, arguments.options)
                } else {
                    jm.search(arguments.keyword, page, arguments.options)
                }
            }.getOrElse { exception ->
                logger.warn("Telegram inline search failed for {} page {}: {}", source, page, query, exception)
                bot.answerInlineQuery(event.queryID, emptyList())
                return@launch
            }
            val inlineResults = searchPage.results.take(SEARCH_RESULT_LIMIT).mapIndexed { index, result ->
                InlineQueryResult(
                    id = "${source}_${page}_${result.id.hashCode().toUInt()}_$index",
                    title = result.title,
                    description = listOfNotNull(
                        result.subtitle,
                        result.category,
                        result.pages?.let { translator.translate("gallery.pages", it) },
                        result.rating?.let { translator.translate("gallery.rating", it) },
                    ).joinToString(" · ").takeIf(String::isNotBlank),
                    url = result.url,
                    message = formatSearchResult(
                        source = source,
                        result = result,
                        index = index + 1,
                        commandOperator = botConfig.commandOperator,
                        translator = translator,
                    ),
                )
            }
            bot.answerInlineQuery(
                queryID = event.queryID,
                results = inlineResults,
                nextOffset = (page + 1).toString().takeIf { searchPage.hasNextPage },
            )
        }
    }

    bot.registerCommand("checkin", usage = translator.translate("command.checkin.usage")) {
        sender, _, _, messageID ->
        val userId = sender.user.userID.toULong()
        val (amount, success) = economic.userCheckIn(userId)
        if (success) {
            bot.reply(
                sender,
                messageID,
                translator.translate("command.checkin.success", amount, economic.getBalance(userId))
            )
        } else {
            bot.reply(
                sender,
                messageID,
                translator.translate("command.checkin.already_done", economic.getBalance(userId))
            )
        }
    }

    bot.registerCommand("info", usage = translator.translate("command.info.usage")) {
        sender, _, _, messageID ->
        val user = economic.getUser(sender.user.userID.toULong())
        val lastCheckIn = user.checkinAt.atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
        bot.reply(
            sender,
            messageID,
            translator.translate("command.info.content", user.role.name, user.balance, lastCheckIn)
        )
    }
    }

    bots.forEach { (bot, blurImages) -> configureBot(bot, blurImages) }
    val connectedBotCount = bots.count { (bot, _) ->
        runCatching { bot.connect() }
            .onFailure { exception ->
                logger.error(
                    "Failed to connect {}; other enabled bot adapters will keep running.",
                    bot::class.simpleName,
                    exception,
                )
            }
            .getOrDefault(false)
    }
    check(connectedBotCount > 0) { "None of the enabled bot adapters could be connected." }

    for (i in 1..config.getConfig().comicParallelCount) {
        async(Dispatchers.IO) {
            while (true) {
                val (_, task) = queue.take()
                val result = runCatching<ComicTaskResult> {
                    when (task) {
                        is ComicTask.EHentai -> generateEHentai(task.gallery)
                        is ComicTask.JMComic -> generateJMComic(task.albumId)
                    }
                }
                val subscribers = queue.sealAndGetSubscribers(task).map { it.second }
                try {
                    result.fold(
                        onSuccess = { taskResult ->
                            try {
                                subscribers.forEach { subscriber ->
                                    runCatching {
                                        when (taskResult) {
                                            is ComicTaskResult.EHentai -> deliverEHentai(taskResult, subscriber)
                                            is ComicTaskResult.JMComic -> deliverJMComic(taskResult, subscriber)
                                        }
                                    }.onFailure { exception ->
                                        logger.error(
                                            "Failed to deliver {} to user {} through {}.",
                                            task,
                                            subscriber.sender.user.userID,
                                            subscriber.bot::class.simpleName,
                                            exception,
                                        )
                                        runCatching {
                                            subscriber.bot.reply(
                                                subscriber.sender,
                                                subscriber.messageID,
                                                translator.translate(
                                                    if (task is ComicTask.EHentai) {
                                                        "gallery.task_failed_refunded"
                                                    } else {
                                                        "jm.task_failed"
                                                    }
                                                )
                                            )
                                        }
                                    }
                                }
                            } finally {
                                cleanupComicTaskCache(task)
                            }
                        },
                        onFailure = { exception ->
                            logger.error("Failed to process shared task {}.", task, exception)
                            subscribers.forEach { subscriber ->
                                runCatching {
                                    subscriber.bot.reply(
                                        subscriber.sender,
                                        subscriber.messageID,
                                        translator.translate(
                                            if (task is ComicTask.EHentai) {
                                                "gallery.task_failed_refunded"
                                            } else {
                                                "jm.task_failed"
                                            }
                                        )
                                    )
                                }
                            }
                        },
                    )
                } finally {
                    queue.completeSealed(task)
                }
            }
        }
    }
}

internal fun formatSearchResult(
    source: String,
    result: ComicSearchResult<*>,
    index: Int,
    commandOperator: Char,
    translator: Translator,
): String = buildString {
    val target = if (source == "eh") result.url else "JM${result.id}"
    appendLine("#$index")
    appendLine(translator.translate("gallery.title", result.title))
    result.category?.takeIf(String::isNotBlank)?.let {
        appendLine(translator.translate("gallery.type", it))
    }
    result.pages?.let { appendLine(translator.translate("gallery.pages", it)) }
    result.rating?.let { appendLine(translator.translate("gallery.rating", it)) }
    if (result.tags.isNotEmpty()) {
        appendLine()
        appendLine(translator.translate("search.result.tags"))
        val distinctTags = result.tags.distinct()
        val visibleTags = distinctTags.take(SEARCH_TAG_LIMIT)
        val groupedTags = linkedMapOf<String?, MutableList<String>>()
        visibleTags.forEach { tag ->
            val separator = tag.indexOf(':')
            val namespace = if (separator > 0) tag.substring(0, separator).trim() else null
            val value = if (separator > 0) tag.substring(separator + 1).trim() else tag.trim()
            groupedTags.getOrPut(namespace) { mutableListOf() } += value
        }
        groupedTags.forEach { (namespace, values) ->
            append("  ")
            if (namespace != null) append("$namespace: ")
            appendLine(values.joinToString(", "))
        }
        val omittedCount = distinctTags.size - visibleTags.size
        if (omittedCount > 0) {
            appendLine(translator.translate("search.result.more_tags", omittedCount))
        }
    }
    appendLine()
    appendLine(translator.translate("search.result.link", result.url))
    append(
        translator.translate(
            "search.result.command",
            "`${commandOperator}get $source $target`",
        )
    )
}

private const val SEARCH_RESULT_LIMIT = 10
private const val SEARCH_TAG_LIMIT = 16
