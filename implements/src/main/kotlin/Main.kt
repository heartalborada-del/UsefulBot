import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.ChatType

fun main() {
    //PDFGenerator.generatePDF(listOf(File("C:\\Users\\heart\\Downloads\\QQ20250213-131430.png")))
    val bot = Napcat("ws://127.0.0.1:3001","napcat!")
    bot.registerCommand(commands = arrayOf("ping"), executor = object : CommandExecutor {
        override fun execute(sender: ChatType, command: String, args: MessageChain) {
            TODO("Not yet implemented")
        }
    })
}