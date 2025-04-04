import kotlinx.coroutines.awaitAll
import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.PlainText
import me.heartalborada.commons.bots.beans.MessageSender

fun main() {
    //PDFGenerator.generatePDF(listOf(File("C:\\Users\\heart\\Downloads\\QQ20250213-131430.png")))
    val bot = Napcat("ws://127.0.0.1:3001","napcat!")
    bot.registerCommand(commands = arrayOf("ping"), executor = object : CommandExecutor {
        override suspend fun execute(sender: MessageSender, command: String, args: MessageChain) {
            bot.sendMessage(sender.type, sender.target, MessageChain().also {
                it.add(PlainText("Hello World"))
            })
        }
    })
}