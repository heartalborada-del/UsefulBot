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
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.comic.model.ArchiveInformation
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.comic.model.ComicInformation
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.comparator.NaturalFileNameComparator
import me.heartalborada.commons.downloader.DownloadManager
import me.heartalborada.commons.economic.EconomicManager
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

    fun jmQueueProcess(albumId: String, sender: MessageSender, messageID: Long, bot: AbstractBot) {
        val cacheKey = "jm:$albumId"
        val pdf = File(jmPdfFolder, "JM$albumId.pdf")
        val imageDirectory = File(jmImgFolder, "JM$albumId")
        imageDirectory.mkdirs()
        val info = jm.getTargetInformation(albumId)
        val cover = jm.downloadCover(albumId, File(imageDirectory, "cover.jpg"))
        sendComicInformation(info, cover, sender, messageID, bot)

        if (pdfCache.getIfPresent(cacheKey) == true || pdf.isFile) {
            bot.reply(sender, messageID, translator.translate("gallery.cache_hit"))
            val sent = bot.sendFile(sender.type, sender.target, "JM$albumId.pdf", pdf)
            if (!sent) {
                bot.reply(sender, messageID, translator.translate("gallery.cache_send_failed"))
            }
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
        val sent = bot.sendFile(sender.type, sender.target, "JM$albumId.pdf", pdf)
        if (!sent) {
            bot.reply(sender, messageID, translator.translate("gallery.send_failed"))
        }
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

    bot.registerCommand(
        commands = arrayOf("about"),
        usage = translator.translate("command.about.usage"),
        executor = object : CommandExecutor {
        override suspend fun execute(sender: MessageSender, command: String, args: MessageChain, messageID: Long) {
            bot.reply(sender, messageID, translator.translate("command.about.content"))
        }
    })

    bot.registerCommand(
        commands = arrayOf("eh"),
        usage = translator.translate("command.eh.usage", config.getConfig().bot.commandOperator),
        executor = object : CommandExecutor {
        override suspend fun execute(sender: MessageSender, command: String, args: MessageChain, messageID: Long) {
            val url = args.toString().trim()
            if (url.isEmpty()) {
                bot.reply(
                    sender,
                    messageID,
                    translator.translate("command.eh.missing_url", config.getConfig().bot.commandOperator)
                )
                return
            }

            val u: Pair<String, String>
            try {
                u = eh.parseUrl(url)
            } catch (_: IllegalArgumentException) {
                bot.reply(sender, messageID, translator.translate("command.eh.invalid_url"))
                return
            }

            val (checkInAmount, checkedIn) = economic.userCheckIn(sender.user.userID.toULong())
            val res = queue.put(
                sender.user.userID,
                ComicTask.EHentai(u),
                QueueExtraData(messageID, sender)
            )
            val checkInMessage = if (checkedIn) {
                translator.translate("command.eh.checkin_bonus", checkInAmount)
            } else {
                ""
            }
            val response = when (res) {
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
    })

    bot.registerCommand(
        commands = arrayOf("jm"),
        usage = translator.translate("command.jm.usage", config.getConfig().bot.commandOperator),
        executor = object : CommandExecutor {
            override suspend fun execute(
                sender: MessageSender,
                command: String,
                args: MessageChain,
                messageID: Long
            ) {
                val target = args.toString().trim()
                if (target.isEmpty()) {
                    bot.reply(
                        sender,
                        messageID,
                        translator.translate("command.jm.missing_target", config.getConfig().bot.commandOperator)
                    )
                    return
                }
                val albumId = try {
                    jm.parseUrl(target)
                } catch (_: IllegalArgumentException) {
                    bot.reply(sender, messageID, translator.translate("command.jm.invalid_target"))
                    return
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
        }
    )

    bot.registerCommand(
        commands = arrayOf("checkin"),
        usage = translator.translate("command.checkin.usage"),
        executor = object : CommandExecutor {
        override suspend fun execute(
            sender: MessageSender,
            command: String,
            args: MessageChain,
            messageID: Long
        ) {
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
    })

    bot.registerCommand(
        commands = arrayOf("info"),
        usage = translator.translate("command.info.usage"),
        executor = object : CommandExecutor {
        override suspend fun execute(
            sender: MessageSender,
            command: String,
            args: MessageChain,
            messageID: Long
        ) {
            val u = economic.getUser(sender.user.userID.toULong())
            val lastCheckIn = u.checkinAt.atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
            bot.reply(
                sender,
                messageID,
                translator.translate("command.info.content", u.role.name, u.balance, lastCheckIn)
            )
        }
    })

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



