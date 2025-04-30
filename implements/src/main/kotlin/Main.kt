import com.google.common.cache.CacheBuilder
import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.comics.EHentai
import me.heartalborada.commons.Util
import me.heartalborada.commons.bots.Image
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.Reply
import me.heartalborada.commons.bots.beans.FileInfo
import me.heartalborada.commons.bots.beans.MessageSender
import me.heartalborada.commons.comic.ArchiveInformation
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.downloader.DownloadManager
import me.heartalborada.commons.okhttp.CookieStorageProvider
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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

private val config = MainConfig(File(dataFolder, "config.json"))
private lateinit var client: OkHttpClient
private lateinit var eh: EHentai

fun main() {
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
    eh = EHentai(parentClient = client, cacheFolder = File(tempFolder,"okhttp"), isEx = config.getConfig().eHentai.isExHentai, cookieStorage = cookieJar)
    logger.info("Connecting...")
    val bot = Napcat(
        config.getConfig().bot.websocketUrl,
        config.getConfig().bot.token,
        config.getConfig().bot.isCommandStartWithAt,
        commandOperator = config.getConfig().bot.commandOperator,
    )

    bot.registerCommand(commands = arrayOf("info"), executor = object : CommandExecutor {
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
            val p = File(pdfFolder, "${u.first}-${u.second}.pdf")
            val cf = File(imgFolder, "${u.first}-${u.second}")
            cf.mkdirs()
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(Reply(messageID))
                it.add(PlainText("Processing... Sit back and relax."))
            })
            try {
                val info = eh.getTargetInformation(u)
                val downloader = DownloadManager(16, client, File(tempFolder,"okhttp"))
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
                if (pdfCache.getIfPresent(u) == true) {
                    bot.sendFile(
                        sender.type,
                        sender.target,
                        FileInfo(
                            "${u.first}-${u.second}",
                            url = "file://${config.getConfig().bot.fileRelativePath}/${p.toRelativeString(pdfFolder)}"
                        )
                    )
                    return
                }
                val archiveUrl = eh.getArchiveDownloadUrl(u, ArchiveInformation("RESAMPLE"))
                var count = 0
                var list = mutableListOf<Pair<String, String?>>(Pair(archiveUrl, "${u.first}-${u.second}"))
                while (list.isNotEmpty()) {
                    if (count >= 3) {
                        bot.sendMessage(sender.type, sender.target, MessageChain().also {
                            it.add(PlainText("Error: Download failed, please contact the admin!"))
                        })
                        return
                    }
                    count++
                    list = downloader.downloadFiles(list, archiveFolder)
                }
                Util.unzip(File(archiveFolder,"${u.first}-${u.second}"), cf)
                pdfFolder.mkdirs()
                cf.listFiles { it ->
                    ALLOW_SUFFIX.contains(
                        Util.getFileExtensionFromUrl(
                            it.toURI().toURL()
                        )
                    ) && it.name.split(".")[0] != "cover" && it.isFile && it.name.matches(Regex("^\\d+_.*"))
                }?.sortedBy { file ->
                    val numberPart = file.name.substringBefore("_").toIntOrNull() ?: Int.MAX_VALUE
                    numberPart
                }?.let {
                    PDFGenerator.generatePDF(
                        it,
                        pdfFile = p, tempDir = File(tempFolder,"pdf"),
                        password = "${u.first}-${u.second}",
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
                        "${u.first}-${u.second}.pdf",
                        url = "file://${config.getConfig().bot.fileRelativePath}/${p.toRelativeString(pdfFolder)}"
                    )
                )
                pdfCache.put(u, true)
            } catch (e: Exception) {
                logger.error("An unexpected error occurred.", e)
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(PlainText("Error: ${e.javaClass.packageName}.${e.javaClass.name}: ${e.message}, please contact the admin!"))
                })
            }
        }
    })
}