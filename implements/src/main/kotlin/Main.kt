import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.heartalborada.QueueExtraData
import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.comics.EHentai
import me.heartalborada.comics.JMComic
import me.heartalborada.commons.Util
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.comic.model.ArchiveInformation
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.comic.model.ComicInformation
import me.heartalborada.commons.comic.model.ComicSearchOptions
import me.heartalborada.commons.comic.model.ComicSearchPage
import me.heartalborada.commons.comic.model.ComicSearchResult
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.comparator.NaturalFileNameComparator
import me.heartalborada.commons.downloader.DownloadManager
import me.heartalborada.commons.economic.ComicPricing
import me.heartalborada.commons.economic.EconomicManager
import me.heartalborada.commands.parseSearchCommandArguments
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.commons.okhttp.CookieStorageProvider
import me.heartalborada.commons.queue.ProcessingQueue
import me.heartalborada.config.Config
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
private val translator = Translator(config.getConfig().bot.language)

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

private val queue = ProcessingQueue<Long, ComicTask, QueueExtraData>(
    globalCapacity = config.getConfig().comicParallelCount
)

private fun AbstractBot.reply(sender: MessageSender, messageID: Long, text: String): Long {
    return sendMessage(sender.type, sender.target, MessageChain.replyTo(messageID, text))
}

fun main() = runBlocking {
    fun sendComicInformation(
        info: ComicInformation<*>,
        coverFile: File,
        sender: MessageSender,
        messageID: Long,
        bot: AbstractBot,
    ) {
        val image = ImageIO.read(coverFile) ?: throw IllegalStateException("Failed to decode comic cover.")
        val blurred = Util.gaussianBlur(Util.resampleImage(Util.resampleImage(image, 0.125), 8.0), radius = 10)
        val base64 = Util.bufferedImageToBase64(blurred)
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

    fun queueProcess(gallery: Pair<String, String>, sender: MessageSender, messageID: Long, bot: AbstractBot) {
        val cacheKey = "eh:${gallery.first}:${gallery.second}"
        val p = File(ehPdfFolder, "${gallery.first}-${gallery.second}.pdf")
        val cf = File(ehImgFolder, "${gallery.first}-${gallery.second}")
        if (!cf.isDirectory || !cf.exists()) {
            cf.delete()
            cf.mkdirs()
        }
        val info = eh.getTargetInformation(gallery)
        val downloader = DownloadManager(16, client, File(ehTempFolder, "download"))
        val cover = "cover.${Util.getFileExtensionFromUrl(URL(info.cover))}"
        downloader.downloadFiles(listOf(Pair(info.cover, cover)), cf, 2)
        sendComicInformation(info, File(cf, cover), sender, messageID, bot)
        if (pdfCache.getIfPresent(cacheKey) == true || p.exists()) {
            bot.reply(sender, messageID, translator.translate("gallery.cache_hit"))
            val sent = bot.sendFile(
                sender.type,
                sender.target,
                "${gallery.first}-${gallery.second}.pdf",
                File(ehPdfFolder, "${gallery.first}-${gallery.second}.pdf")
            )
            if (!sent) {
                bot.reply(sender, messageID, translator.translate("gallery.cache_send_failed"))
            }
            return
        }

        val archive = eh.getArchiveInformation(gallery).firstOrNull { it.name == "RESAMPLE" }
        if (archive == null) {
            bot.reply(sender, messageID, translator.translate("gallery.archive_unavailable"))
            return
        }
        val cost = kotlin.math.ceil(
            Util.convertToBytes(archive.size.replace(" ", "")).toDouble() / 1024 / 1024
        ).toLong().coerceAtLeast(1L)
        val userId = sender.user.userID.toULong()
        if (!economic.withdrawGP(userId, cost)) {
            bot.reply(
                sender,
                messageID,
                translator.translate("gallery.insufficient_gp", cost, economic.getBalance(userId))
            )
            return
        }

        var refunded = false
        try {
            bot.reply(
                sender,
                messageID,
                translator.translate("gallery.preparing", cost, economic.getBalance(userId))
            )

            val archiveUrl = eh.getArchiveDownloadUrl(gallery, ArchiveInformation("RESAMPLE"))
            var count = 0
            var list = mutableListOf<Pair<String, String?>>(Pair(archiveUrl, "${gallery.first}-${gallery.second}.zip"))
            while (list.isNotEmpty()) {
                if (count >= 3) {
                    economic.depositGP(userId, cost)
                    refunded = true
                    bot.reply(
                        sender,
                        messageID,
                        translator.translate("gallery.download_failed_refunded", cost)
                    )
                    return
                }
                count++
                list = downloader.downloadFiles(list, ehArchiveFolder, 4)
            }
            Util.unzip(File(ehArchiveFolder, "${gallery.first}-${gallery.second}.zip"), cf)
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
                pdfFile = p, tempDir = File(ehTempFolder, "pdf"),
                password = "${gallery.first}-${gallery.second}",
                signatureText = "Generated at:${
                    Instant.now().atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))
                }"
            )
            pdfCache.put(cacheKey, true)
            val sent = bot.sendFile(
                sender.type,
                sender.target,
                "${gallery.first}-${gallery.second}.pdf",
                File(ehPdfFolder, "${gallery.first}-${gallery.second}.pdf")
            )
            if (!sent) {
                bot.reply(
                    sender,
                    messageID,
                    translator.translate("gallery.send_failed")
                )
            }
        } catch (exception: Exception) {
            if (!refunded) {
                economic.depositGP(userId, cost)
            }
            throw exception
        }
    }

    fun sendJmPdf(pdf: File, sender: MessageSender, messageID: Long, bot: AbstractBot) {
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
            if (!bot.sendFile(sender.type, sender.target, pdf.name, pdf)) {
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

    fun jmQueueProcess(albumId: String, sender: MessageSender, messageID: Long, bot: AbstractBot) {
        val cacheKey = "jm:$albumId"
        val pdf = File(jmPdfFolder, "JM$albumId.pdf")
        val imageDirectory = File(jmImgFolder, "JM$albumId")
        imageDirectory.mkdirs()
        val info = jm.getTargetInformation(albumId)
        val cover = jm.downloadCover(albumId, File(imageDirectory, "cover.jpg"))
        sendComicInformation(info, cover, sender, messageID, bot)

        if (pdfCache.getIfPresent(cacheKey) == true || pdf.isFile) {
            bot.reply(sender, messageID, translator.translate("jm.cache_hit"))
            sendJmPdf(pdf, sender, messageID, bot)
            return
        }

        bot.reply(sender, messageID, translator.translate("jm.preparing", albumId, info.pages))
        val pages = jm.downloadAlbum(albumId, imageDirectory)
        jmPdfFolder.mkdirs()
        PDFGenerator.generatePDF(
            pages,
            pdfFile = pdf,
            tempDir = File(jmTempFolder, "pdf"),
            password = "JM$albumId",
            signatureText = "Generated at:${
                Instant.now().atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))
            }"
        )
        pdfCache.put(cacheKey, true)
        sendJmPdf(pdf, sender, messageID, bot)
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
    val bot = Napcat(
        config.getConfig().bot.websocketUrl,
        config.getConfig().bot.token,
        config.getConfig().bot.isCommandStartWithAt,
        commandOperator = config.getConfig().bot.commandOperator,
        useStreamAPI = config.getConfig().bot.fileUpload.useStreamAPI,
        streamAPIChunkSize = config.getConfig().bot.fileUpload.chunkSize,
        streamAPIExpireSeconds = config.getConfig().bot.fileUpload.expireSeconds,
        translator = translator,
    )

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

        val (checkInAmount, checkedIn) = economic.userCheckIn(sender.user.userID.toULong())
        val result = queue.put(
            sender.user.userID,
            ComicTask.EHentai(gallery),
            QueueExtraData(messageID, sender)
        )
        val checkInMessage = if (checkedIn) {
            translator.translate("command.eh.checkin_bonus", checkInAmount)
        } else {
            ""
        }
        val response = when (result) {
            ProcessingQueue.PutStatus.QUEUE_FULL ->
                translator.translate("command.eh.queue_full")
            ProcessingQueue.PutStatus.USER_QUEUE_FULL ->
                translator.translate("command.eh.user_queue_full")
            ProcessingQueue.PutStatus.DUPLICATE_TASK ->
                translator.translate("command.eh.duplicate")
            ProcessingQueue.PutStatus.SUCCESS ->
                translator.translate("command.eh.accepted")
            ProcessingQueue.PutStatus.FAILURE ->
                translator.translate("command.eh.queue_failed")
        }
        bot.reply(sender, messageID, listOf(response, checkInMessage).filter(String::isNotEmpty).joinToString(" "))
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
        val result = queue.put(
            sender.user.userID,
            ComicTask.JMComic(albumId),
            QueueExtraData(messageID, sender)
        )
        val response = when (result) {
            ProcessingQueue.PutStatus.QUEUE_FULL ->
                translator.translate("command.jm.queue_full")
            ProcessingQueue.PutStatus.USER_QUEUE_FULL ->
                translator.translate("command.jm.user_queue_full")
            ProcessingQueue.PutStatus.DUPLICATE_TASK ->
                translator.translate("command.jm.duplicate")
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
                content = MessageChain.text(
                    formatSearchResult(
                        source = source,
                                result = result,
                                index = index + 1,
                                commandOperator = config.getConfig().bot.commandOperator,
                                translator = translator,
                            )
                ),
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

    for (i in 1..config.getConfig().comicParallelCount) {
        async(Dispatchers.IO) {
            while (true) {
                val (_, task, extra) = queue.take()
                try {
                    when (task) {
                        is ComicTask.EHentai ->
                            queueProcess(task.gallery, extra.sender, extra.messageID, bot)
                        is ComicTask.JMComic ->
                            jmQueueProcess(task.albumId, extra.sender, extra.messageID, bot)
                    }
                } catch (e: Exception) {
                    logger.error("An unexpected error occurred.", e)
                    bot.reply(
                        extra.sender,
                        extra.messageID,
                        translator.translate(
                            if (task is ComicTask.EHentai) {
                                "gallery.task_failed_refunded"
                            } else {
                                "jm.task_failed"
                            }
                        )
                    )
                } finally {
                    queue.complete(extra.sender.user.userID, task)
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
    val sourceLabel = if (source == "eh") "E-Hentai" else "JMComic · JM${result.id}"
    appendLine(translator.translate("search.result.header", index, sourceLabel))
    appendLine(translator.translate("gallery.title", result.title))
    result.subtitle?.takeIf(String::isNotBlank)?.let {
        appendLine(
            translator.translate(
                if (source == "eh") "gallery.uploader" else "search.result.author",
                it,
            )
        )
    }
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
            "${commandOperator}get $source $target",
        )
    )
}

private const val SEARCH_RESULT_LIMIT = 10
private const val SEARCH_TAG_LIMIT = 16
