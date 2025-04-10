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
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.downloader.MultiThreadedDownloadManager
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
private val tempFolder = File(dataFolder,"temp")
private val pdfFolder = File(dataFolder,"pdf")
private val ehFolder = File(dataFolder,"eh")
private val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1",20171))).build()
private val eh = EHentai(
    parentClient = client,
    cacheFolder = tempFolder,
)
private val logger = LoggerFactory.getLogger("main")
private val ALLOW_SUFFIX = setOf("jpg","jpeg","gif","png","webp")
private const val FILE_PATH_RELATIVE_PREFIX = "/data/pdf"
fun main() {
    val bot = Napcat("ws://127.0.0.1:3002","napcat!",isCommandStartWithAt = false, tempDir = tempFolder)

    bot.registerCommand(commands = arrayOf("info"), executor = object : CommandExecutor {
        override suspend fun execute(sender: MessageSender, command: String, args: MessageChain, messageID: Long) {
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(PlainText("""
                    Powered by @heartalborada-del
                """.trimIndent()))
            })
        }
    })

    bot.registerCommand(commands = arrayOf("eh"), executor = object : CommandExecutor {
        override suspend fun execute(sender: MessageSender, command: String, args: MessageChain, messageID: Long) {
            val u: Pair<String,String>
            try {
                u = eh.parseUrl(args.toString().trim())
            } catch (e: IllegalArgumentException) {
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(Reply(messageID))
                    it.add(PlainText("Invalid URL"))
                })
                return
            }
            val p = File(pdfFolder,"${u.first}-${u.second}.pdf")
            val cf = File(ehFolder, "${u.first}-${u.second}")
            cf.mkdirs()
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(Reply(messageID))
                it.add(PlainText("Processing... Sit back and relax."))
            })
            try {
                val info = eh.getTargetInformation(u)
                val downloader = MultiThreadedDownloadManager(16, client, tempFolder)
                val cover = "cover.${Util.getFileExtensionFromUrl(URL(info.cover))}"
                downloader.downloadFiles(listOf(Pair(info.cover, cover)),cf)
                val image = ImageIO.read(File(cf, cover))
                val blurred = Util.gaussianBlur(Util.resampleImage(Util.resampleImage(image,0.125),8.0))
                val base64 = Util.bufferedImageToBase64(blurred)
                val message = MessageChain().also {
                    it.add(Reply(messageID))
                    if(info.subtitle != null ) it.add(PlainText("Title: ${info.title} - ${info.subtitle}\n"))
                    else it.add(PlainText("Title: ${info.title}\n"))
                    it.add(PlainText("Uploader: ${info.uploader}\n"))
                    it.add(PlainText("Rating: ${info.rating}\n"))
                    it.add(PlainText("Pages: ${info.pages}p\n"))
                    it.add(PlainText("Type: ${info.category.s}"))
                    it.add(Image(FileInfo("${info.title}.jpg", url = "base64://$base64" )))
                }
                bot.sendMessage(sender.type, sender.target,message)
                if (pdfCache.getIfPresent(u) == true) {
                    bot.sendFile(sender.type, sender.target, FileInfo("${u.first}-${u.second}", url = "file://$FILE_PATH_RELATIVE_PREFIX/${p.toRelativeString(pdfFolder)}"))
                    return
                }
                val ps = eh.getAllPages(u)
                val urls = eh.getPageImageUrl(u,ps)
                var imgList = mutableListOf<Pair<String,String?>>()
                for (i in 1..info.pages) {
                    imgList.add(Pair(urls[i]!!,"p$i.${Util.getFileExtensionFromUrl(URL(urls[i]))}"))
                }
                var count = 0
                while (imgList.size > 0) {
                    if(count >= 3) {
                        bot.sendMessage(sender.type, sender.target, MessageChain().also {
                            it.add(PlainText("Error: Download failed, please contact the admin!"))
                        })
                        return
                    }
                    count++
                    imgList = downloader.downloadFiles(imgList,cf)
                }
                pdfFolder.mkdirs()
                cf.listFiles { it ->
                    ALLOW_SUFFIX.contains(Util.getFileExtensionFromUrl(it.toURI().toURL())) && it.name.split(".")[0] != "cover"
                }?.let { PDFGenerator.generatePDF(it.toMutableList(), pdfFile = p, tempDir = tempFolder, password = "${u.first}-${u.second}", signatureText = "Generated at:${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))}") }
                bot.sendFile(sender.type, sender.target, FileInfo("${u.first}-${u.second}.pdf", url = "file://$FILE_PATH_RELATIVE_PREFIX/${p.toRelativeString(pdfFolder)}"))
                pdfCache.put(u,true)
            } catch (e: Exception) {
                logger.error("An unexpected error occurred.", e)
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(PlainText("Error: ${e.javaClass.packageName}.${e.javaClass.name}-${e.message}, please contact the admin!"))
                })
            }
        }
    })
}