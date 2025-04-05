import com.google.common.cache.CacheBuilder
import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.comics.EHentai
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.Reply
import me.heartalborada.commons.bots.beans.MessageSender
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.commands.CommandExecutor
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy


private val pdfCache = CacheBuilder.newBuilder()
    .expireAfterWrite(24, java.util.concurrent.TimeUnit.HOURS)
    .maximumSize(1024)
    .build<Pair<String, String>, Boolean>()

private val dataFolder = File("data")
private val tempFolder = File(dataFolder,"temp")
private val pdfFolder = File(dataFolder,"pdf")
private val ehFolder = File(dataFolder,"eh")

private val eh = EHentai(
    parentClient = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1",7890))).build(),
    cacheFolder = tempFolder,
)
fun main() {
    val bot = Napcat("ws://127.0.0.1:3001","napcat!",isCommandStartWithAt = false)
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
            val cf = File(ehFolder, "${u.first}-${u.second}")
            cf.mkdirs()
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(PlainText("Processing... Sit back and relax."))
            })
            try {
                val info = eh.getTargetInformation(u)

                info.cover
            } catch (e: Exception) {
                bot.sendMessage(sender.type, sender.target, MessageChain().also {
                    it.add(PlainText("Error: ${e.message}, please contact the admin!"))
                })
            }
        }
    })
}