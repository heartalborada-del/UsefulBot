import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.heartalborada.QueueExtraData
import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.comics.EHentai
import me.heartalborada.commons.Util
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.beans.MessageSender
import me.heartalborada.commons.comic.ArchiveInformation
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.comparator.NaturalFileNameComparator
import me.heartalborada.commons.downloader.DownloadManager
import me.heartalborada.commons.economic.EconomicManager
import me.heartalborada.commons.okhttp.CookieStorageProvider
import me.heartalborada.commons.queue.ProcessingQueue
import me.heartalborada.config.Config
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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
    .build<Pair<String, String>, Boolean>()

private val dataFolder = File("data")
private val tempFolder = File(dataFolder, "temp")
private val pdfFolder = File(dataFolder, "pdf")
private val imgFolder = File(dataFolder, "img")
private val archiveFolder = File(dataFolder, "archive")

private val logger = LoggerFactory.getLogger("Main")
private val ALLOW_SUFFIX = setOf("jpg", "jpeg", "gif", "png", "webp")

private val config = Config(File(dataFolder, "config.json"))

private val economic = EconomicManager(Database.connect("jdbc:h2:./data/gp", "org.h2.Driver"))
private lateinit var client: OkHttpClient
private lateinit var eh: EHentai

private val queue =
    ProcessingQueue<Long, Pair<String, String>, QueueExtraData>(globalCapacity = config.getConfig().comicParallelCount)

fun main() = runBlocking {
    fun queueProcess(gallery: Pair<String, String>, sender: MessageSender, messageID: Long, bot: AbstractBot) {
        val p = File(pdfFolder, "${gallery.first}-${gallery.second}.pdf")
        val cf = File(imgFolder, "${gallery.first}-${gallery.second}")
        if (!cf.isDirectory || !cf.exists()) {
            cf.delete()
            cf.mkdirs()
        }
        val info = eh.getTargetInformation(gallery)
        val downloader = DownloadManager(16, client, File(tempFolder, "okhttp"))
        val cover = "cover.${Util.getFileExtensionFromUrl(URL(info.cover))}"
        downloader.downloadFiles(listOf(Pair(info.cover, cover)), cf, 2)
        val image = ImageIO.read(File(cf, cover))
        val blurred = Util.gaussianBlur(Util.resampleImage(Util.resampleImage(image, 0.125), 8.0), radius = 10)
        val base64 = Util.bufferedImageToBase64(blurred)
        val message = MessageChain().also {
            it.add(Reply(messageID))
            if (info.subtitle != null) it.add(PlainText("Title: ${info.title} - ${info.subtitle}\n"))
            else it.add(PlainText("Title: ${info.title}\n"))
            it.add(PlainText("Uploader: ${info.uploader}\n"))
            it.add(PlainText("Rating: ${info.rating}\n"))
            it.add(PlainText("Pages: ${info.pages}p\n"))
            it.add(PlainText("Type: ${info.category.s}"))
            it.add(Image(FileInfo("${info.title}.jpg", url = "base64://$base64")))
        }
        bot.sendMessage(sender.type, sender.target, message)
        if (pdfCache.getIfPresent(gallery) == true || p.exists()) {
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(PlainText("Hit the cache, no cost!"))
            })
            bot.sendFile(
                sender.type,
                sender.target,
                FileInfo(
                    "${gallery.first}-${gallery.second}.pdf",
                    url = "file://${config.getConfig().bot.fileRelativePath}/${p.toRelativeString(pdfFolder)}"
                )
            )
            return
        }
        eh.getArchiveInformation(gallery).forEach { it ->
            if (it.name == "RESAMPLE") {
                val cost =
                    kotlin.math.ceil(Util.convertToBytes(it.size.replace(" ", "")).toDouble() / 1024 / 1024).toLong()
                if (!economic.withdrawGP(sender.user.userID.toULong(), cost)) {
                    bot.sendMessage(sender.type, sender.target, MessageChain().also {
                        it.add(Reply(messageID))
                        it.add(PlainText("Not enough GP. Please check your balance!"))
                    })
                    return
                } else {
                    bot.sendMessage(sender.type, sender.target, MessageChain().also {
                        it.add(Reply(messageID))
                        it.add(PlainText("Cost: $cost GP, remaining: ${economic.getUser(sender.user.userID.toULong()).balance} GP. Preparing PDF..."))
                    })
                }
            }
        }
        val archiveUrl = eh.getArchiveDownloadUrl(gallery, ArchiveInformation("RESAMPLE"))
        var count = 0
        var list = mutableListOf<Pair<String, String?>>(Pair(archiveUrl, "${gallery.first}-${gallery.second}.zip"))
        while (list.isNotEmpty()) {
            if (count >= 3) {
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(Reply(messageID))
                    it.add(PlainText("Error: Download failed, please contact the admin!"))
                })
                return
            }
            count++
            list = downloader.downloadFiles(list, archiveFolder, 4)
        }
        Util.unzip(File(archiveFolder, "${gallery.first}-${gallery.second}.zip"), cf)
        pdfFolder.mkdirs()
        val comparator = NaturalFileNameComparator()
        cf.listFiles { it ->
            ALLOW_SUFFIX.contains(
                Util.getFileExtensionFromUrl(
                    it.toURI().toURL()
                )
            ) && it.name.split(".")[0] != "cover" && it.isFile
        }?.sortedWith(comparator)?.let {
            PDFGenerator.generatePDF(
                it,
                pdfFile = p, tempDir = File(tempFolder, "pdf"),
                password = "${gallery.first}-${gallery.second}",
                signatureText = "Generated at:${
                    Instant.now().atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))
                }"
            )
        }
        bot.sendFile(
            sender.type,
            sender.target,
            FileInfo(
                "${gallery.first}-${gallery.second}.pdf",
                url = "file://${config.getConfig().bot.fileRelativePath}/${p.toRelativeString(pdfFolder)}"
            )
        )
        pdfCache.put(gallery, true)
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
        cacheFolder = File(tempFolder, "okhttp"),
        isEx = config.getConfig().eHentai.isExHentai,
        cookieStorage = cookieJar
    )
    logger.info("Connecting...")
    val bot = Napcat(
        config.getConfig().bot.websocketUrl,
        config.getConfig().bot.token,
        config.getConfig().bot.isCommandStartWithAt,
        commandOperator = config.getConfig().bot.commandOperator,
    )

    bot.registerCommand(commands = arrayOf("about"), executor = object : CommandExecutor {
        override suspend fun execute(sender: MessageSender, command: String, args: MessageChain, messageID: Long) {
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(
                    PlainText(
                        """
                    Powered by @heartalborada-del
                    See code on: heartalborada-del/UsefulBot
                """.trimIndent()
                    )
                )
            })
        }
    })

    bot.registerCommand(commands = arrayOf("eh"), executor = object : CommandExecutor {
        override suspend fun execute(sender: MessageSender, command: String, args: MessageChain, messageID: Long) {
            val (amount, success) = economic.userCheckIn(sender.user.userID.toULong())
            if (success)
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(Reply(messageID))
                    it.add(PlainText("Auto check in successful! You have gained $amount GP."))
                })
            val u: Pair<String, String>
            try {
                u = eh.parseUrl(args.toString().trim())
            } catch (_: IllegalArgumentException) {
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(Reply(messageID))
                    it.add(PlainText("Invalid URL"))
                })
                return
            }
            val res = queue.put(sender.user.userID, u, QueueExtraData(messageID, sender))
            MessageChain().also {
                it.add(Reply(messageID))
                val m = when (res) {
                    ProcessingQueue.PutStatus.QUEUE_FULL -> "You're queued at ${queue.getCurrentQueueSize()}. Sit back and relax."
                    ProcessingQueue.PutStatus.USER_QUEUE_FULL -> "User process queue is full. Skip."
                    ProcessingQueue.PutStatus.DUPLICATE_TASK -> "Another using same gallery task already in progress. Sit back and relax."
                    ProcessingQueue.PutStatus.SUCCESS -> "Task added to queue. Sit back and relax."
                    ProcessingQueue.PutStatus.FAILURE -> "Failed to add task to queue. Please contact the admin."
                }
                it.add(PlainText(m))
            }.let {
                bot.sendMessage(sender.type, sender.target, it)
            }
        }
    })

    bot.registerCommand(commands = arrayOf("checkin"), executor = object : CommandExecutor {
        override suspend fun execute(
            sender: MessageSender,
            command: String,
            args: MessageChain,
            messageID: Long
        ) {
            val userId = sender.user.userID.toULong()
            val (amount, success) = economic.userCheckIn(userId)
            if (success) {
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(Reply(messageID))
                    it.add(PlainText("Check in successful! You have gained $amount GP."))
                })
            } else {
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(Reply(messageID))
                    it.add(PlainText("You have already checked in today."))
                })
            }
        }
    })

    bot.registerCommand(commands = arrayOf("info"), executor = object : CommandExecutor {
        override suspend fun execute(
            sender: MessageSender,
            command: String,
            args: MessageChain,
            messageID: Long
        ) {
            val u = economic.getUser(sender.user.userID.toULong())
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(Reply(messageID))
                it.add(PlainText("Role: ${u.role.name}\n"))
                it.add(PlainText("Balance: ${u.balance} GP\n"))
                it.add(
                    PlainText(
                        "Last Checkin time: ${
                            u.checkinAt.atZone(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"))
                        }"
                    )
                )
            })
        }
    })

    for (i in 1..config.getConfig().comicParallelCount) {
        async(Dispatchers.IO) {
            while (true) {
                if (queue.getCurrentQueueSize() < 0) continue
                val (_, u, extra) = queue.take()
                try {
                    queueProcess(u, extra.sender, extra.messageID, bot)
                } catch (e: Exception) {
                    logger.error("An unexpected error occurred.", e)
                    bot.sendMessage(extra.sender.type, extra.sender.target, MessageChain().also {
                        it.add(PlainText("Error: ${e.javaClass.packageName}.${e.javaClass.name}: ${e.message}, please contact the admin!"))
                    })
                }
            }
        }
    }
}



