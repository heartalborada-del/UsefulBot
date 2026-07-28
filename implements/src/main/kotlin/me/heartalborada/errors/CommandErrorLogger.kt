package me.heartalborada.errors

import me.heartalborada.commons.bots.dto.MessageSender
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class CommandErrorReport(val file: File, val timestamp: String)

class CommandErrorLogger(
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Synchronized
    fun record(
        adapter: String,
        sender: MessageSender,
        operation: String,
        messageID: Long,
        error: Throwable,
    ): CommandErrorReport {
        directory.mkdirs()
        require(directory.isDirectory) { "Error report path is not a directory: ${directory.absolutePath}" }
        val occurredAt = clock.instant()
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use(error::printStackTrace)
        }.toString()
        val content = buildString {
            appendLine("timestamp=${DateTimeFormatter.ISO_INSTANT.format(occurredAt)}")
            appendLine("adapter=$adapter")
            appendLine("chat_type=${sender.type}")
            appendLine("chat_id=${sender.target}")
            appendLine("user_id=${sender.user.userID}")
            appendLine("username=${sender.user.username}")
            appendLine("message_id=$messageID")
            appendLine("command=$operation")
            appendLine("error=${error::class.qualifiedName}: ${error.message.orEmpty()}")
            appendLine()
            append(stackTrace)
        }
        repeat(MAX_FILENAME_ATTEMPTS) { attempt ->
            val fileInstant = occurredAt.plusNanos(attempt.toLong())
            val timestamp = FILE_TIMESTAMP_FORMATTER.format(fileInstant.atZone(ZoneOffset.UTC))
            val target = File(directory, "$timestamp.err.log")
            try {
                Files.writeString(
                    target.toPath(),
                    content,
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                return CommandErrorReport(target, timestamp)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // A nanosecond suffix keeps simultaneous reports distinct while preserving timestamp.err.log names.
            }
        }
        throw IllegalStateException("Could not allocate a unique command error report filename.")
    }

    private companion object {
        const val MAX_FILENAME_ATTEMPTS = 10_000
        val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS")
    }
}
