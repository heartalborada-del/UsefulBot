package me.heartalborada.console

import me.heartalborada.commons.bots.AbstractBot
import org.jline.keymap.KeyMap
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Interactive JLine frontend backed by an adapter's shared command registry.
 *
 * [onStop] is invoked by the console-only `stop` command. The default exits the
 * process normally so registered JVM shutdown hooks can unload plugins and close
 * bot adapters.
 */
class JLineConsole(
    private val bot: AbstractBot,
    private val terminal: Terminal = TerminalBuilder.builder().system(true).build(),
    private val onStop: () -> Unit = { exitProcess(0) },
    private val permissionNodeSuggestions: (String) -> List<String> = { emptyList() },
) : AutoCloseable {
    private val lineReader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .parser(DefaultParser())
        .completer(Completer { _, line, candidates ->
            val currentWord = line.word()
            val slashPrefixed = line.wordIndex() == 0 && currentWord.startsWith('/')
            val values = when (line.wordIndex()) {
                0 -> completeTopLevelCommands(
                    bot.completeConsoleCommand(null, currentWord.removePrefix("/")),
                    currentWord.removePrefix("/"),
                )
                1 -> bot.completeConsoleCommand(
                    line.words().firstOrNull()?.removePrefix("/"),
                    currentWord,
                )
                else -> completePermissionNodes(
                    words = line.words(),
                    wordIndex = line.wordIndex(),
                    prefix = currentWord,
                    suggestions = permissionNodeSuggestions,
                )
            }
            values.forEach { value -> candidates += Candidate(if (slashPrefixed) "/$value" else value) }
        })
        .build()

    private val closed = AtomicBoolean()
    private val streamsInstalled = AtomicBoolean()
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private lateinit var routedOut: PrintStream
    private lateinit var routedErr: PrintStream

    init {
        installClearInputBindings(lineReader)
    }

    suspend fun run() {
        installSystemStreams()
        print("UsefulBot console ready. Press Tab to complete commands.")
        try {
            while (!closed.get()) {
                val line = try {
                    lineReader.readLine("> ")
                } catch (exception: UserInterruptException) {
                    if (shouldStopAfterInterrupt(exception.partialLine)) {
                        requestStop()
                        break
                    }
                    continue
                } catch (_: EndOfFileException) {
                    break
                }
                if (line.isBlank()) continue
                if (isStopCommand(line)) {
                    requestStop()
                    break
                }
                bot.executeConsoleCommand(line, ::print)
            }
        } finally {
            restoreSystemStreams()
        }
    }

    private fun print(message: String) {
        lineReader.printAbove(message)
    }

    private fun requestStop() {
        print("Stopping UsefulBot...")
        // Restore the terminal on its reader thread before JVM shutdown hooks start.
        close()
        onStop()
    }

    private fun installSystemStreams() {
        if (!streamsInstalled.compareAndSet(false, true)) return
        synchronized(STREAM_REDIRECT_LOCK) {
            originalOut = System.out
            originalErr = System.err
            routedOut = redirectedPrintStream(originalOut)
            routedErr = redirectedPrintStream(originalErr)
            System.setOut(routedOut)
            System.setErr(routedErr)
        }
    }

    private fun redirectedPrintStream(fallback: PrintStream): PrintStream = PrintStream(
        LineRedirectingOutputStream(fallback, lineReader::printAbove),
        true,
        StandardCharsets.UTF_8,
    )

    private fun restoreSystemStreams() {
        if (!streamsInstalled.compareAndSet(true, false)) return
        synchronized(STREAM_REDIRECT_LOCK) {
            if (System.out === routedOut) System.setOut(originalOut)
            if (System.err === routedErr) System.setErr(originalErr)
            routedOut.close()
            routedErr.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        restoreSystemStreams()
        terminal.close()
    }

    private companion object {
        val STREAM_REDIRECT_LOCK = Any()
    }
}

internal fun installClearInputBindings(lineReader: LineReader) {
    lineReader.widgets[CLEAR_INPUT_WIDGET] = Widget { clearInput(lineReader) }
    lineReader.keyMaps.values.distinct().forEach { keyMap ->
        keyMap.bind(Reference(CLEAR_INPUT_WIDGET), KeyMap.esc())
        keyMap.ambiguousTimeout = ESCAPE_AMBIGUOUS_TIMEOUT_MILLIS
    }
}

internal fun clearInput(lineReader: LineReader, redisplay: Boolean = true): Boolean {
    lineReader.buffer.clear()
    if (redisplay) lineReader.callWidget(LineReader.REDISPLAY)
    return true
}

internal fun shouldStopAfterInterrupt(partialLine: String?): Boolean = partialLine.isNullOrEmpty()

internal class LineRedirectingOutputStream(
    private val fallback: PrintStream,
    private val printAbove: (String) -> Unit,
) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    private var closed = false

    @Synchronized
    override fun write(value: Int) {
        check(!closed) { "Output stream is closed." }
        if (value == '\n'.code) emitLine() else buffer.write(value)
    }

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Output stream is closed." }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        for (index in offset until offset + length) write(bytes[index].toInt() and 0xff)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        if (buffer.size() > 0) emitLine()
        closed = true
    }

    private fun emitLine() {
        val line = buffer.toString(StandardCharsets.UTF_8).removeSuffix("\r")
        buffer.reset()
        runCatching { printAbove(line) }
            .onFailure { fallback.println(line) }
    }
}

internal fun isStopCommand(line: String): Boolean =
    line.trim().removePrefix("/").equals(STOP_COMMAND, ignoreCase = true)

internal fun completeTopLevelCommands(commands: List<String>, prefix: String): List<String> =
    buildList {
        addAll(commands)
        if (STOP_COMMAND.startsWith(prefix, ignoreCase = true)) add(STOP_COMMAND)
    }.distinct().sorted()

internal fun completePermissionNodes(
    words: List<String>,
    wordIndex: Int,
    prefix: String,
    suggestions: (String) -> List<String>,
): List<String> {
    if (wordIndex != 3) return emptyList()
    val command = words.getOrNull(0)?.removePrefix("/")?.lowercase()
    val action = words.getOrNull(1)?.lowercase()
    if (command !in setOf("permission", "perm") || action !in setOf("grant", "deny", "revoke")) {
        return emptyList()
    }
    return suggestions(prefix).distinct().sorted()
}

private const val STOP_COMMAND = "stop"
internal const val CLEAR_INPUT_WIDGET = "usefulbot-clear-input"
private const val ESCAPE_AMBIGUOUS_TIMEOUT_MILLIS = 100L
