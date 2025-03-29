import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.commons.events.GroupMessageEvent
import me.heartalborada.commons.events.HeartBeatEvent

fun main() {
    val bot = Napcat("ws://127.0.0.1:3001","napcat!")
    bot.getEventBus().register(GroupMessageEvent::class.java) {
        println(it)
    }
}