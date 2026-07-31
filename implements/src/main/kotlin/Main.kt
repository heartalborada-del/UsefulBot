import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.heartalborada.QueueExtraData
import me.heartalborada.QueueUser
import me.heartalborada.bots.napcat.Napcat
import me.heartalborada.bots.telegram.TelegramApiException
import me.heartalborada.bots.telegram.TelegramBot
import me.heartalborada.bots.telegram.TelegramFileIdCache
import me.heartalborada.bots.telegram.TelegramLargeDocumentSender
import me.heartalborada.comics.EHentai
import me.heartalborada.comics.JMComic
import me.heartalborada.comics.ComicProviderRegistry
import me.heartalborada.cache.CacheJanitor
import me.heartalborada.comics.isEHentaiArchiveOverSizeLimit
import me.heartalborada.comics.selectEHentaiArchive
import me.heartalborada.commons.Util
import me.heartalborada.commons.ChatType
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.dto.FileInfo
import me.heartalborada.commons.bots.dto.ForwardMessageNode
import me.heartalborada.commons.bots.dto.InlineQueryResult
import me.heartalborada.commons.bots.dto.MessageSender
import me.heartalborada.commons.bots.dto.UserInfo
import me.heartalborada.commons.bots.events.message.InlineQueryEvent
import me.heartalborada.commons.comic.PDFGenerator
import me.heartalborada.commons.comic.SizeBoundedPdfSplitter
import me.heartalborada.commons.comic.model.ComicInformation
import me.heartalborada.commons.comic.model.ComicSearchOptions
import me.heartalborada.commons.comic.model.ComicSearchPage
import me.heartalborada.commons.comic.model.ComicSearchResult
import me.heartalborada.commons.commands.CommandExecutor
import me.heartalborada.commons.commands.CommandErrorHandler
import me.heartalborada.commons.commands.CommandGuard
import me.heartalborada.commons.comparator.NaturalFileNameComparator
import me.heartalborada.commons.downloader.DownloadManager
import me.heartalborada.commons.economic.ComicPricing
import me.heartalborada.commons.economic.EconomicManager
import me.heartalborada.commons.economic.tables.UsersTable
import me.heartalborada.commons.i18n.Translator
import me.heartalborada.commons.permissions.PermissionContext
import me.heartalborada.commons.permissions.PermissionDefault
import me.heartalborada.commons.permissions.PermissionSubject
import me.heartalborada.commons.permissions.PermissionSubjectType
import me.heartalborada.commands.parseSearchCommandArguments
import me.heartalborada.commons.okhttp.CookieStorageProvider
import me.heartalborada.commons.queue.ProcessingQueue
import me.heartalborada.config.Config
import me.heartalborada.config.LargeFilePolicy
import me.heartalborada.console.JLineConsole
import me.heartalborada.i18n.PropertiesTranslator
import me.heartalborada.errors.CommandErrorLogger
import me.heartalborada.security.AccessController
import me.heartalborada.security.AccessDecision
import me.heartalborada.plugins.PluginManager
import me.heartalborada.plugins.BuiltInPlugin
import me.heartalborada.plugins.PluginDescriptor
import me.heartalborada.plugins.builtin.BuiltInComicProviderPlugin
import me.heartalborada.plugins.builtin.PermissionPlugin
import me.heartalborada.permissions.PersistentPermissionService
import me.heartalborada.state.BotStateStore
import me.heartalborada.state.OutboxDelivery
import me.heartalborada.state.PersistentSubscriber
import me.heartalborada.state.PersistentTask
import me.heartalborada.state.UserPreference
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.h2.jdbcx.JdbcConnectionPool
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private val pdfCache = CacheBuilder.newBuilder()
    .expireAfterWrite(Duration.ofHours(24))
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
private val translator = PropertiesTranslator(config.getConfig().bot.language)
private val economicDataSource = run {
    dataFolder.mkdirs()
    JdbcConnectionPool.create("jdbc:h2:./data/data;LOCK_TIMEOUT=10000", "sa", "").apply {
        maxConnections = (config.getConfig().comicParallelCount + 4).coerceIn(8, 32)
        loginTimeout = 10
        Runtime.getRuntime().addShutdownHook(Thread({ dispose() }, "economic-database-shutdown"))
    }
}
private val economicDatabase = Database.connect(economicDataSource)
private val economic = EconomicManager(economicDatabase)
private lateinit var client: OkHttpClient
private lateinit var eh: EHentai
private lateinit var jm: JMComic

private sealed interface ComicTask {
    val id: String
    val source: String
    val target: String

    data class EHentai(val gallery: Pair<String, String>) : ComicTask {
        override val id = "eh-${gallery.first}-${gallery.second}"
        override val source = "eh"
        override val target = "https://e-hentai.org/g/${gallery.first}/${gallery.second}/"
    }

    data class JMComic(val albumId: String) : ComicTask {
        override val id = "jm-$albumId"
        override val source = "jm"
        override val target = "JM$albumId"
    }
}

private data class ArchiveLimitExceeded(
    val archiveSize: String,
    val maximumSizeMiB: Long,
)

private sealed interface ComicTaskResult {
    data class EHentai(
        val gallery: Pair<String, String>,
        val info: ComicInformation<*>,
        val cover: File,
        val pdf: File?,
        val pages: List<File>,
        val cacheHit: Boolean,
        val cost: Long,
        val archiveLimitExceeded: ArchiveLimitExceeded? = null,
    ) : ComicTaskResult

    data class JMComic(
        val albumId: String,
        val info: ComicInformation<*>,
        val cover: File,
        val pdf: File,
        val pages: List<File>,
        val cacheHit: Boolean,
    ) : ComicTaskResult
}

private data class ComicInformationPreview(
    val info: ComicInformation<*>,
    val cover: File,
)

private val queue = ProcessingQueue<QueueUser, ComicTask, QueueExtraData>(
    globalCapacity = config.getConfig().comicParallelCount,
    userCapacity = config.getConfig().tasks.userCapacity,
    taskId = ComicTask::id,
)
private val comicInformationPreviews = ConcurrentHashMap<ComicTask, ComicInformationPreview>()
private val stateStore = BotStateStore(File(rootFolder, config.getConfig().tasks.stateFile))
private val accessController = AccessController(config.getConfig().access, stateStore)
private val translators = ConcurrentHashMap<String, Translator>()

private fun translatorFor(language: String): Translator =
    language.takeIf(String::isNotBlank)?.let { translators.computeIfAbsent(it, ::PropertiesTranslator) } ?: translator

private fun AbstractBot.adapterKey(): String = when (this) {
    is TelegramBot -> "telegram"
    is Napcat -> "napcat"
    else -> this::class.simpleName.orEmpty().lowercase()
}

private fun preference(bot: AbstractBot, userId: Long): UserPreference =
    stateStore.preference(bot.adapterKey(), userId)

private fun AbstractBot.reply(sender: MessageSender, messageID: Long, text: String): Long {
    return sendCommandMessage(sender, MessageChain.replyTo(messageID, text))
}

private fun listComicPages(directory: File): List<File> {
    val comparator = NaturalFileNameComparator()
    return directory.listFiles { file ->
        file.isFile &&
            file.name.substringBeforeLast(".") != "cover" &&
            ALLOW_SUFFIX.contains(Util.getFileExtensionFromUrl(file.toURI().toURL()))
    }?.sortedWith(comparator).orEmpty()
}

internal fun deleteCacheEntry(root: File, target: File): Boolean {
    val canonicalRoot = root.canonicalFile.toPath()
    val canonicalTarget = target.canonicalFile.toPath()
    require(canonicalTarget != canonicalRoot && canonicalTarget.startsWith(canonicalRoot)) {
        "Refusing to delete a cache entry outside its root: $canonicalTarget"
    }
    if (!target.exists()) return true
    return if (target.isDirectory) target.deleteRecursively() else target.delete()
}

private fun cleanupComicTaskCache(task: ComicTask) {
    val targets = when (task) {
        is ComicTask.EHentai -> {
            val taskName = "${task.gallery.first}-${task.gallery.second}"
            listOf(
                ehArchiveFolder to File(ehArchiveFolder, "$taskName.zip"),
                ehImgFolder to File(ehImgFolder, taskName),
                ehTempFolder to File(File(ehTempFolder, "download"), taskName),
                ehTempFolder to File(File(ehTempFolder, "pdf"), taskName),
            )
        }

        is ComicTask.JMComic -> {
            val taskName = "JM${task.albumId}"
            listOf(
                jmImgFolder to File(jmImgFolder, taskName),
                jmTempFolder to File(File(jmTempFolder, "pdf"), taskName),
            )
        }
    }
    targets.forEach { (root, target) ->
        runCatching {
            check(deleteCacheEntry(root, target)) {
                "Failed to delete cache entry ${target.absolutePath}"
            }
        }.onFailure { exception ->
            logger.warn("Failed to clean comic task cache {}.", target.absolutePath, exception)
        }
    }
}

fun main() = runBlocking {
    val telegramFileIdCache = TelegramFileIdCache(economicDatabase)
    val telegramLargeDocumentSenders = ConcurrentHashMap<TelegramBot, TelegramLargeDocumentSender>()
    val commandErrorLogger = CommandErrorLogger(File(rootFolder, "error"))

    fun operationFor(task: ComicTask): String =
        "${config.getConfig().bot.commandOperator}get ${task.source} ${task.target}"

    fun recordCommandError(
        bot: AbstractBot,
        sender: MessageSender,
        messageID: Long,
        operation: String,
        error: Throwable,
    ): String? = runCatching {
        commandErrorLogger.record(bot.adapterKey(), sender, operation, messageID, error).file.name
    }.onFailure { logger.error("Failed to write command error report for {}.", operation, it) }.getOrNull()

    fun commandErrorMessage(language: String, fileName: String?): String =
        if (fileName == null) {
            translatorFor(language).translate("command.execution_failed")
        } else {
            translatorFor(language).translate("command.execution_failed_report", fileName)
        }

    fun persistentSubscriber(extra: QueueExtraData) = PersistentSubscriber(
        adapter = extra.adapter,
        target = extra.sender.target,
        userId = extra.sender.user.userID,
        username = extra.sender.user.username,
        role = extra.sender.user.role,
        card = extra.sender.user.card,
        chatType = extra.sender.type,
        messageId = extra.messageID,
        blurImages = extra.blurImages,
        language = extra.language,
        notifyProgress = extra.notifyProgress,
    )

    fun queueExtra(
        bot: AbstractBot,
        sender: MessageSender,
        messageID: Long,
        defaultBlurImages: Boolean,
    ): QueueExtraData {
        val userPreference = preference(bot, sender.user.userID)
        return QueueExtraData(
            messageID = messageID,
            sender = sender,
            bot = bot,
            blurImages = userPreference.blurImages ?: defaultBlurImages,
            adapter = bot.adapterKey(),
            language = userPreference.language,
            notifyProgress = userPreference.notifyProgress,
        )
    }

    suspend fun submitTask(task: ComicTask, extra: QueueExtraData, consumeQuota: Boolean = true): ProcessingQueue.PutStatus {
        val result = queue.putOrJoin(QueueUser(extra.bot, extra.sender.user.userID), task, extra)
        if (result == ProcessingQueue.PutStatus.SUCCESS || result == ProcessingQueue.PutStatus.JOINED_TASK) {
            if (consumeQuota && !accessController.consumeDownload(extra.adapter, extra.sender.user.userID)) {
                queue.cancel(QueueUser(extra.bot, extra.sender.user.userID), task.id)
                return ProcessingQueue.PutStatus.USER_QUEUE_FULL
            }
            stateStore.addSubscriber(
                PersistentTask(id = task.id, source = task.source, target = task.target),
                persistentSubscriber(extra),
            )
        }
        return result
    }

    fun enqueueFailedDelivery(
        extra: QueueExtraData,
        name: String,
        pdf: File,
        password: String?,
    ) {
        if (!config.getConfig().deliveryRetry.enabled || !pdf.isFile) return
        stateStore.enqueueDelivery(
            OutboxDelivery(
                adapter = extra.adapter,
                chatType = extra.sender.type,
                target = extra.sender.target,
                messageId = extra.messageID,
                name = name,
                filePath = pdf.absolutePath,
                password = password,
            )
        )
    }

    fun sendComicInformation(
        info: ComicInformation<*>,
        coverFile: File,
        sender: MessageSender,
        messageID: Long,
        bot: AbstractBot,
        blurImages: Boolean,
        messageTranslator: Translator = translator,
    ) {
        val image = ImageIO.read(coverFile) ?: throw IllegalStateException("Failed to decode comic cover.")
        val displayImage = if (blurImages) {
            Util.gaussianBlur(Util.resampleImage(Util.resampleImage(image, 0.125), 8.0), radius = 10)
        } else {
            image
        }
        val base64 = Util.bufferedImageToBase64(displayImage)
        val message = MessageChain().also {
            it.add(Reply(messageID))
            val title = if (info.subtitle != null) {
                messageTranslator.translate("gallery.title_with_subtitle", info.title, info.subtitle)
            } else {
                messageTranslator.translate("gallery.title", info.title)
            }
            it.add(PlainText("$title\n"))
            it.add(PlainText("${messageTranslator.translate("gallery.uploader", info.uploader)}\n"))
            if (info.rating > 0) {
                it.add(PlainText("${messageTranslator.translate("gallery.rating", info.rating)}\n"))
            }
            it.add(PlainText("${messageTranslator.translate("gallery.pages", info.pages)}\n"))
            it.add(PlainText(messageTranslator.translate("gallery.type", info.category.s)))
            it.add(Image(FileInfo("${info.title}.jpg", url = "base64://$base64")))
        }
        bot.sendMessage(sender.type, sender.target, message)
    }

    fun sendComicInformationOnce(preview: ComicInformationPreview, extra: QueueExtraData): Boolean {
        if (!extra.tryStartComicInformationDelivery()) return false
        return try {
            sendComicInformation(
                info = preview.info,
                coverFile = preview.cover,
                sender = extra.sender,
                messageID = extra.messageID,
                bot = extra.bot,
                blurImages = extra.blurImages,
                messageTranslator = translatorFor(extra.language),
            )
            true
        } catch (exception: Exception) {
            extra.retryComicInformationDelivery()
            throw exception
        }
    }

    fun trySendComicInformation(preview: ComicInformationPreview, extra: QueueExtraData) {
        if (!extra.notifyProgress) return
        runCatching { sendComicInformationOnce(preview, extra) }
            .onFailure { exception ->
                logger.warn(
                    "Failed to send early comic information to user {} through {}; it will be retried on delivery.",
                    extra.sender.user.userID,
                    extra.bot::class.simpleName,
                    exception,
                )
            }
    }

    suspend fun publishComicInformation(
        task: ComicTask,
        info: ComicInformation<*>,
        cover: File,
    ) {
        val preview = ComicInformationPreview(info, cover)
        comicInformationPreviews[task] = preview
        queue.getSubscribers(task).forEach { (_, extra) ->
            trySendComicInformation(preview, extra)
        }
    }

    fun sendPublishedComicInformation(task: ComicTask, extra: QueueExtraData) {
        comicInformationPreviews[task]?.let { preview ->
            trySendComicInformation(preview, extra)
        }
    }

    fun sendFileWithLargeFilePolicy(
        bot: AbstractBot,
        sender: MessageSender,
        messageID: Long,
        name: String,
        pdf: File,
        pdfPassword: String?,
    ): Boolean {
        if (bot !is TelegramBot) {
            return bot.sendFile(sender.type, sender.target, name, pdf)
        }
        val telegramConfig = config.getConfig().bot.telegram
        val largeFileConfig = telegramConfig.largeFile

        fun telegramTempDirectory(): File {
            val configured = File(largeFileConfig.tempDirectory)
            return if (configured.isAbsolute) configured else File(rootFolder, largeFileConfig.tempDirectory)
        }

        fun sendUnlockedPdf(): Boolean {
            if (pdfPassword == null) {
                return bot.sendFile(sender.type, sender.target, name, pdf)
            }
            val tempDirectory = telegramTempDirectory().apply { mkdirs() }
            val temporaryFile = File.createTempFile(
                "usefulbot-tg-part-unlocked-",
                ".pdf",
                tempDirectory,
            )
            return try {
                SizeBoundedPdfSplitter().writeUnlockedCopy(
                    source = pdf,
                    password = pdfPassword,
                    target = temporaryFile,
                )
                bot.sendFile(sender.type, sender.target, name, temporaryFile)
            } finally {
                temporaryFile.delete()
            }
        }

        fun sendSplitPdf(): Boolean {
            require(largeFileConfig.maxPartSizeMiB in 1..49) {
                "Telegram MaxPartSizeMiB must be between 1 and 49."
            }
            bot.reply(
                sender,
                messageID,
                translator.translate(
                    "telegram.pdf_split",
                    largeFileConfig.maxPartSizeMiB,
                ),
            )
            val tempDirectory = telegramTempDirectory()
            val largeDocumentSender = telegramLargeDocumentSenders.computeIfAbsent(bot) {
                TelegramLargeDocumentSender(
                    client = bot,
                    cache = telegramFileIdCache,
                    splitter = SizeBoundedPdfSplitter(),
                    tempDirectory = tempDirectory,
                    maximumPartBytes = largeFileConfig.maxPartSizeMiB.toLong() * 1024 * 1024,
                )
            }
            return largeDocumentSender.send(
                type = sender.type,
                target = sender.target,
                displayName = name,
                source = pdf,
                password = pdfPassword,
            )
        }

        if (bot.exceedsOfficialUploadLimit(pdf)) {
            return when (largeFileConfig.policy) {
                LargeFilePolicy.SPLIT_PDF -> sendSplitPdf()
                LargeFilePolicy.FAIL -> sendUnlockedPdf()
            }
        }
        return try {
            sendUnlockedPdf()
        } catch (exception: TelegramApiException) {
            if (!exception.isRequestEntityTooLarge()) {
                throw exception
            }
            logger.info(
                "Telegram rejected {} ({} bytes); applying configured large-file policy {}.",
                pdf.name,
                pdf.length(),
                largeFileConfig.policy,
            )
            when (largeFileConfig.policy) {
                LargeFilePolicy.SPLIT_PDF -> sendSplitPdf()
                LargeFilePolicy.FAIL -> throw exception
            }
        }
    }

    suspend fun generateEHentai(gallery: Pair<String, String>): ComicTaskResult.EHentai {
        val currentTask = ComicTask.EHentai(gallery)
        queue.updateProgress(currentTask, "metadata", 5)
        val cacheKey = "eh:${gallery.first}:${gallery.second}"
        val taskName = "${gallery.first}-${gallery.second}"
        val p = File(ehPdfFolder, "$taskName.pdf")
        val cf = File(ehImgFolder, taskName)
        if (!cf.isDirectory || !cf.exists()) {
            cf.delete()
            cf.mkdirs()
        }
        val info = eh.getTargetInformation(gallery)
        val downloader = DownloadManager(
            16,
            client,
            File(File(ehTempFolder, "download"), taskName),
        )
        try {
        val cover = "cover.${Util.getFileExtensionFromUrl(URI.create(info.cover).toURL())}"
        downloader.downloadFiles(listOf(Pair(info.cover, cover)), cf, 2)
        val coverFile = File(cf, cover)
        publishComicInformation(
            task = currentTask,
            info = info,
            cover = coverFile,
        )
        val cacheHit = pdfCache.getIfPresent(cacheKey) == true || p.exists()
        val maximumArchiveSizeMiB = config.getConfig().eHentai.maxArchiveSizeMiB
        fun cachedResult(): ComicTaskResult.EHentai =
            ComicTaskResult.EHentai(
                gallery = gallery,
                info = info,
                cover = coverFile,
                pdf = p,
                pages = listComicPages(cf),
                cacheHit = true,
                cost = 0,
            )

        if (cacheHit && maximumArchiveSizeMiB == 0L) {
            return cachedResult()
        }

        val archive = selectEHentaiArchive(eh.getArchiveInformation(gallery).asIterable())
        if (archive == null) {
            if (cacheHit) return cachedResult()
            return ComicTaskResult.EHentai(
                gallery = gallery,
                info = info,
                cover = coverFile,
                pdf = null,
                pages = emptyList(),
                cacheHit = false,
                cost = 0,
            )
        }
        val archiveSizeBytes = Util.convertToBytes(archive.size.replace(" ", ""))
        if (isEHentaiArchiveOverSizeLimit(archiveSizeBytes, maximumArchiveSizeMiB)) {
            return ComicTaskResult.EHentai(
                gallery = gallery,
                info = info,
                cover = coverFile,
                pdf = null,
                pages = emptyList(),
                cacheHit = false,
                cost = 0,
                archiveLimitExceeded = ArchiveLimitExceeded(
                    archiveSize = archive.size,
                    maximumSizeMiB = maximumArchiveSizeMiB,
                ),
            )
        }
        if (cacheHit) return cachedResult()

        val cost = kotlin.math.ceil(archiveSizeBytes.toDouble() / 1024 / 1024)
            .toLong()
            .coerceAtLeast(1L)
        val subscribers = queue.getSubscribers(ComicTask.EHentai(gallery)).map { it.second }
        if (subscribers.none { extra ->
                economic.getBalance(AccessController.identity(extra.adapter, extra.sender.user.userID)) >= cost
            }
        ) {
            return ComicTaskResult.EHentai(
                gallery = gallery,
                info = info,
                cover = coverFile,
                pdf = null,
                pages = emptyList(),
                cacheHit = false,
                cost = cost,
            )
        }

        val archiveUrl = eh.getArchiveDownloadUrl(gallery, archive)
        queue.updateProgress(currentTask, "downloading", 20)
        val archiveFileName = "$taskName.zip"
        val archiveFile = File(ehArchiveFolder, archiveFileName)
        var archiveReady = false
        for (attempt in 1..3) {
            val failed = downloader.downloadFiles(
                mutableListOf(Pair(archiveUrl, archiveFileName)),
                ehArchiveFolder,
                4,
            )
            if (failed.isEmpty() && Util.isValidZip(archiveFile)) {
                archiveReady = true
                break
            } else {
                logger.warn(
                    "E-Hentai archive download attempt {} was incomplete or corrupt; retrying.",
                    attempt,
                )
                downloader.discardDownload(ehArchiveFolder, archiveFileName)
            }
        }
        check(archiveReady) { "Failed to download a valid E-Hentai archive after 3 attempts." }
        Util.unzip(archiveFile, cf)
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
        queue.updateProgress(currentTask, "generating_pdf", 75)
        PDFGenerator.generatePDF(
            pages,
            pdfFile = p,
            tempDir = File(File(ehTempFolder, "pdf"), taskName),
            password = "${gallery.first}-${gallery.second}",
            signatureText = "Generated at:${
                Instant.now().atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))
            }"
        )
        pdfCache.put(cacheKey, true)
        return ComicTaskResult.EHentai(
            gallery = gallery,
            info = info,
            cover = coverFile,
            pdf = p,
            pages = pages,
            cacheHit = false,
            cost = cost,
        )
        } finally {
            downloader.close()
        }
    }

    fun deliverEHentai(result: ComicTaskResult.EHentai, extra: QueueExtraData) {
        val bot = extra.bot
        val sender = extra.sender
        val messageID = extra.messageID
        val messageTranslator = translatorFor(extra.language)
        sendComicInformationOnce(ComicInformationPreview(result.info, result.cover), extra)

        val pdf = result.pdf
        if (pdf == null) {
            val archiveLimit = result.archiveLimitExceeded
            if (archiveLimit != null) {
                bot.reply(
                    sender,
                    messageID,
                    messageTranslator.translate(
                        "gallery.archive_too_large",
                        archiveLimit.archiveSize,
                        archiveLimit.maximumSizeMiB,
                    ),
                )
            } else if (result.cost > 0) {
                val userId = AccessController.identity(bot.adapterKey(), sender.user.userID)
                bot.reply(
                    sender,
                    messageID,
                    messageTranslator.translate("gallery.insufficient_gp", result.cost, economic.getBalance(userId)),
                )
            } else {
                bot.reply(sender, messageID, messageTranslator.translate("gallery.archive_unavailable"))
            }
            return
        }
        if (result.cacheHit) {
            bot.reply(sender, messageID, messageTranslator.translate("gallery.cache_hit"))
        } else {
            val userId = AccessController.identity(bot.adapterKey(), sender.user.userID)
            if (!economic.withdrawGP(userId, result.cost)) {
                bot.reply(
                    sender,
                    messageID,
                    messageTranslator.translate("gallery.insufficient_gp", result.cost, economic.getBalance(userId))
                )
                return
            }
            bot.reply(
                sender,
                messageID,
                messageTranslator.translate("gallery.preparing", result.cost, economic.getBalance(userId))
            )
        }

        val sent = try {
            sendFileWithLargeFilePolicy(
                bot = bot,
                sender = sender,
                messageID = messageID,
                name = "${result.gallery.first}-${result.gallery.second}.pdf",
                pdf = pdf,
                pdfPassword = "${result.gallery.first}-${result.gallery.second}",
            )
        } catch (exception: Exception) {
            if (!result.cacheHit) {
                economic.depositGP(AccessController.identity(bot.adapterKey(), sender.user.userID), result.cost)
            }
            enqueueFailedDelivery(
                extra,
                "${result.gallery.first}-${result.gallery.second}.pdf",
                pdf,
                "${result.gallery.first}-${result.gallery.second}",
            )
            throw exception
        }
        if (!sent) {
            if (!result.cacheHit) {
                economic.depositGP(AccessController.identity(bot.adapterKey(), sender.user.userID), result.cost)
            }
            enqueueFailedDelivery(
                extra,
                "${result.gallery.first}-${result.gallery.second}.pdf",
                pdf,
                "${result.gallery.first}-${result.gallery.second}",
            )
            bot.reply(
                sender,
                messageID,
                messageTranslator.translate(
                    if (result.cacheHit) "gallery.cache_send_failed" else "gallery.send_failed"
                )
            )
        }
    }

    fun sendJmPdf(
        pdf: File,
        sender: MessageSender,
        messageID: Long,
        bot: AbstractBot,
        pdfPassword: String,
        messageTranslator: Translator = translator,
    ) {
        check(pdf.isFile) { "JMComic PDF does not exist: ${pdf.absolutePath}" }
        val cost = ComicPricing.jmPdfCost(pdf.length())
        val userId = AccessController.identity(bot.adapterKey(), sender.user.userID)
        val charged = cost > 0
        if (charged && !economic.withdrawGP(userId, cost)) {
            bot.reply(
                sender,
                messageID,
                messageTranslator.translate("jm.insufficient_gp", cost, economic.getBalance(userId))
            )
            return
        }

        try {
            bot.reply(
                sender,
                messageID,
                if (charged) {
                    messageTranslator.translate("jm.charged", cost, economic.getBalance(userId))
                } else {
                    messageTranslator.translate("jm.free")
                }
            )
            if (
                !sendFileWithLargeFilePolicy(
                    bot = bot,
                    sender = sender,
                    messageID = messageID,
                    name = pdf.name,
                    pdf = pdf,
                pdfPassword = pdfPassword,
                )
            ) {
                stateStore.enqueueDelivery(
                    OutboxDelivery(
                        adapter = bot.adapterKey(),
                        chatType = sender.type,
                        target = sender.target,
                        messageId = messageID,
                        name = pdf.name,
                        filePath = pdf.absolutePath,
                        password = pdfPassword,
                    )
                )
                if (charged) {
                    economic.depositGP(userId, cost)
                    bot.reply(sender, messageID, messageTranslator.translate("jm.send_failed_refunded", cost))
                } else {
                    bot.reply(sender, messageID, messageTranslator.translate("jm.send_failed"))
                }
            }
        } catch (exception: Exception) {
            if (charged) {
                economic.depositGP(userId, cost)
            }
            stateStore.enqueueDelivery(
                OutboxDelivery(
                    adapter = bot.adapterKey(),
                    chatType = sender.type,
                    target = sender.target,
                    messageId = messageID,
                    name = pdf.name,
                    filePath = pdf.absolutePath,
                    password = pdfPassword,
                )
            )
            throw exception
        }
    }

    suspend fun generateJMComic(albumId: String): ComicTaskResult.JMComic {
        val currentTask = ComicTask.JMComic(albumId)
        queue.updateProgress(currentTask, "metadata", 5)
        val cacheKey = "jm:$albumId"
        val pdf = File(jmPdfFolder, "JM$albumId.pdf")
        val imageDirectory = File(jmImgFolder, "JM$albumId")
        imageDirectory.mkdirs()
        val info = jm.getTargetInformation(albumId)
        val cover = jm.downloadCover(albumId, File(imageDirectory, "cover.jpg"))
        publishComicInformation(
            task = currentTask,
            info = info,
            cover = cover,
        )

        if (pdfCache.getIfPresent(cacheKey) == true || pdf.isFile) {
            return ComicTaskResult.JMComic(
                albumId = albumId,
                info = info,
                cover = cover,
                pdf = pdf,
                pages = listComicPages(imageDirectory),
                cacheHit = true,
            )
        }

        queue.updateProgress(currentTask, "downloading", 20)
        val pages = jm.downloadAlbum(albumId, imageDirectory)
        jmPdfFolder.mkdirs()
        queue.updateProgress(currentTask, "generating_pdf", 75)
        PDFGenerator.generatePDF(
            pages,
            pdfFile = pdf,
            tempDir = File(File(jmTempFolder, "pdf"), "JM$albumId"),
            password = "JM$albumId",
            signatureText = "Generated at:${
                Instant.now().atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZZZZZ"))
            }"
        )
        pdfCache.put(cacheKey, true)
        return ComicTaskResult.JMComic(
            albumId = albumId,
            info = info,
            cover = cover,
            pdf = pdf,
            pages = pages,
            cacheHit = false,
        )
    }

    fun deliverJMComic(result: ComicTaskResult.JMComic, extra: QueueExtraData) {
        val bot = extra.bot
        val sender = extra.sender
        val messageID = extra.messageID
        val messageTranslator = translatorFor(extra.language)
        sendComicInformationOnce(ComicInformationPreview(result.info, result.cover), extra)
        if (result.cacheHit) {
            bot.reply(sender, messageID, messageTranslator.translate("jm.cache_hit"))
        } else {
            bot.reply(
                sender,
                messageID,
                messageTranslator.translate("jm.preparing", result.albumId, result.info.pages),
            )
        }
        sendJmPdf(
            pdf = result.pdf,
            sender = sender,
            messageID = messageID,
            bot = bot,
            pdfPassword = "JM${result.albumId}",
            messageTranslator = messageTranslator,
        )
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
        cookieJar.saveFromResponse("https://exhentai.org/".toHttpUrl(), ck)
    else
        cookieJar.saveFromResponse("https://e-hentai.org/".toHttpUrl(), ck)
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
    val botConfig = config.getConfig().bot
    val bots = buildList {
        if (botConfig.napcat.enabled) {
            add(
                Napcat(
                    botConfig.napcat.websocketUrl,
                    botConfig.napcat.token,
                    botConfig.isCommandStartWithAt,
                    commandOperator = botConfig.commandOperator,
                    useStreamAPI = botConfig.napcat.fileUpload.useStreamAPI,
                    streamAPIChunkSize = botConfig.napcat.fileUpload.chunkSize,
                    streamAPIExpireSeconds = botConfig.napcat.fileUpload.expireSeconds,
                    translator = translator,
                    autoConnect = false,
                ) to botConfig.napcat.blurImages
            )
        }
        if (botConfig.telegram.enabled) {
            add(
                TelegramBot(
                    token = botConfig.telegram.token,
                    apiBaseUrl = botConfig.telegram.apiBaseUrl,
                    parentClient = client,
                    commandOperator = botConfig.commandOperator,
                    translator = translator,
                    inlineModeEnabled = botConfig.telegram.enableInlineMode,
                    uploadTimeoutMinutes = botConfig.telegram.uploadTimeoutMinutes,
                    autoConnect = false,
                ) to botConfig.telegram.blurImages
            )
        }
    }
    check(bots.isNotEmpty()) { "At least one bot adapter must be enabled." }

    data class ProviderDefinition(
        val parse: (String) -> ComicTask,
        val query: (String) -> ComicInformationPreview,
        val search: (String, Int, ComicSearchOptions) -> ComicSearchPage<*>,
        val generate: suspend (ComicTask) -> ComicTaskResult,
        val deliver: (ComicTaskResult, QueueExtraData) -> Unit,
    )
    val providers = ComicProviderRegistry<ProviderDefinition>()
    val permissionService = PersistentPermissionService(stateStore)
    listOf(
        "eh.get",
        "eh.query",
        "eh.search",
        "jm.get",
        "jm.query",
        "jm.search",
        "usefulbot.health",
        "usefulbot.admin.status",
        "usefulbot.admin.gp",
        "usefulbot.admin.cache",
        "usefulbot.admin.retry",
        "usefulbot.admin.cancel",
    ).forEach(permissionService::register)
    val cacheJanitor = CacheJanitor()
    val connectedAdapters = ConcurrentHashMap.newKeySet<String>()
    lateinit var pluginManager: PluginManager

    fun runCacheCleanup() = cacheJanitor.clean(
        roots = listOf(ehPdfFolder, jmPdfFolder),
        maximumBytes = config.getConfig().cache.maxSizeMiB * 1024 * 1024,
        ttlMillis = TimeUnit.DAYS.toMillis(config.getConfig().cache.ttlDays),
        protectedPaths = stateStore.outboxFilePaths(),
    )

    fun probe(url: String): String = runCatching {
        client.newBuilder().callTimeout(HEALTH_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).get().build())
            .execute()
            .use { response -> "HTTP ${response.code}" }
    }.getOrElse { "unreachable (${it::class.simpleName})" }

    fun configureBot(bot: AbstractBot, blurImages: Boolean) {
        fun permissionContext(sender: MessageSender): PermissionContext {
            val platform = AccessController.platform(bot.adapterKey())
            return PermissionContext(
                user = PermissionSubject(platform, PermissionSubjectType.USER, sender.user.userID),
                group = sender.target.takeIf { sender.type == ChatType.GROUP }
                    ?.let { PermissionSubject(platform, PermissionSubjectType.GROUP, it) },
            )
        }

        fun hasPermission(
            sender: MessageSender,
            node: String,
            default: PermissionDefault = PermissionDefault.DENY,
        ): Boolean = sender.type == ChatType.SELF ||
            permissionService.hasPermission(permissionContext(sender), node, default)

        fun requirePermission(
            sender: MessageSender,
            messageID: Long,
            node: String,
            default: PermissionDefault = PermissionDefault.DENY,
        ): Boolean {
            if (hasPermission(sender, node, default)) return true
            bot.reply(
                sender,
                messageID,
                translatorFor(preference(bot, sender.user.userID).language).translate("admin.denied"),
            )
            return false
        }

        fun permissionExecutor(node: String, executor: CommandExecutor): CommandExecutor =
            CommandExecutor { sender, command, arguments, messageID ->
                if (!hasPermission(sender, node, PermissionDefault.ALLOW)) {
                    bot.reply(
                        sender,
                        messageID,
                        translatorFor(preference(bot, sender.user.userID).language)
                            .translate("permission.command_denied", node),
                    )
                    return@CommandExecutor
                }
                executor.execute(sender, command, arguments, messageID)
            }

        bot.onCommandError(CommandErrorHandler { sender, operation, messageID, error ->
            val fileName = recordCommandError(bot, sender, messageID, operation, error)
            commandErrorMessage(preference(bot, sender.user.userID).language, fileName)
        })
        bot.guardCommands(CommandGuard { sender, _, _, messageID ->
            val decision = accessController.check(bot.adapterKey(), sender.user.userID, sender.target)
            if (decision != AccessDecision.ALLOWED) {
                val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
                val key = when (decision) {
                    AccessDecision.BLOCKED -> "access.blocked"
                    AccessDecision.RATE_LIMITED -> "access.rate_limited"
                    AccessDecision.ALLOWED -> error("Unreachable")
                }
                bot.reply(sender, messageID, userTranslator.translate(key))
                false
            } else {
                true
            }
        })
        bot.beforeCommandExecution(CommandExecutor { sender, command, _, messageID ->
        if (command == "checkin") {
            return@CommandExecutor
        }
        val userId = AccessController.identity(bot.adapterKey(), sender.user.userID)
        val (amount, checkedIn) = economic.userCheckIn(userId)
        if (checkedIn) {
            bot.reply(
                sender,
                messageID,
                translatorFor(preference(bot, sender.user.userID).language)
                    .translate("command.checkin.success", amount, economic.getBalance(userId)),
            )
        }
    })

    bot.registerCommand("about", usage = translator.translate("command.about.usage")) {
        sender, _, _, messageID ->
        bot.reply(sender, messageID, translator.translate("command.about.content"))
    }

    val getEHExecutor = CommandExecutor { sender, _, args, messageID ->
        val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
        val url = args.toString().trim()
        if (url.isEmpty()) {
            bot.reply(
                sender,
                messageID,
                userTranslator.translate("command.eh.missing_url", config.getConfig().bot.commandOperator)
            )
            return@CommandExecutor
        }

        val task = try {
            checkNotNull(providers.resolve("eh")) { "E-Hentai plugin is disabled or unavailable." }.parse(url)
        } catch (_: IllegalArgumentException) {
            bot.reply(sender, messageID, userTranslator.translate("command.eh.invalid_url"))
            return@CommandExecutor
        } catch (_: IllegalStateException) {
            bot.reply(sender, messageID, userTranslator.translate("command.execution_failed"))
            return@CommandExecutor
        }

        val extra = queueExtra(bot, sender, messageID, blurImages)
        val result = submitTask(task, extra)
        val response = when (result) {
            ProcessingQueue.PutStatus.QUEUE_FULL ->
                userTranslator.translate("command.eh.queue_full")
            ProcessingQueue.PutStatus.USER_QUEUE_FULL ->
                userTranslator.translate("command.eh.user_queue_full")
            ProcessingQueue.PutStatus.DUPLICATE_TASK ->
                userTranslator.translate("command.eh.duplicate")
            ProcessingQueue.PutStatus.JOINED_TASK ->
                userTranslator.translate("command.eh.joined")
            ProcessingQueue.PutStatus.SUCCESS ->
                userTranslator.translate("command.eh.accepted")
            ProcessingQueue.PutStatus.FAILURE ->
                userTranslator.translate("command.eh.queue_failed")
        }
        val taskReference = if (result == ProcessingQueue.PutStatus.SUCCESS || result == ProcessingQueue.PutStatus.JOINED_TASK) {
            "\n${userTranslator.translate("task.id", task.id)}"
        } else ""
        bot.reply(sender, messageID, response + taskReference)
        if (result == ProcessingQueue.PutStatus.SUCCESS || result == ProcessingQueue.PutStatus.JOINED_TASK) {
            sendPublishedComicInformation(task, extra)
        }
    }

    val getJMExecutor = CommandExecutor { sender, _, args, messageID ->
        val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
        val target = args.toString().trim()
        if (target.isEmpty()) {
            bot.reply(
                sender,
                messageID,
                userTranslator.translate("command.jm.missing_target", config.getConfig().bot.commandOperator)
            )
            return@CommandExecutor
        }
        val task = try {
            checkNotNull(providers.resolve("jm")) { "JM plugin is disabled or unavailable." }.parse(target)
        } catch (_: IllegalArgumentException) {
            bot.reply(sender, messageID, userTranslator.translate("command.jm.invalid_target"))
            return@CommandExecutor
        } catch (_: IllegalStateException) {
            bot.reply(sender, messageID, userTranslator.translate("command.execution_failed"))
            return@CommandExecutor
        }
        val extra = queueExtra(bot, sender, messageID, blurImages)
        val result = submitTask(task, extra)
        val response = when (result) {
            ProcessingQueue.PutStatus.QUEUE_FULL ->
                userTranslator.translate("command.jm.queue_full")
            ProcessingQueue.PutStatus.USER_QUEUE_FULL ->
                userTranslator.translate("command.jm.user_queue_full")
            ProcessingQueue.PutStatus.DUPLICATE_TASK ->
                userTranslator.translate("command.jm.duplicate")
            ProcessingQueue.PutStatus.JOINED_TASK ->
                userTranslator.translate("command.jm.joined")
            ProcessingQueue.PutStatus.SUCCESS ->
                userTranslator.translate("command.jm.accepted")
            ProcessingQueue.PutStatus.FAILURE ->
                userTranslator.translate("command.jm.queue_failed")
        }
        val taskReference = if (result == ProcessingQueue.PutStatus.SUCCESS || result == ProcessingQueue.PutStatus.JOINED_TASK) {
            "\n${userTranslator.translate("task.id", task.id)}"
        } else ""
        bot.reply(sender, messageID, response + taskReference)
        if (result == ProcessingQueue.PutStatus.SUCCESS || result == ProcessingQueue.PutStatus.JOINED_TASK) {
            sendPublishedComicInformation(task, extra)
        }
    }

    bot.registerCommand(
        "get",
        usage = translator.translate("command.get.usage", config.getConfig().bot.commandOperator),
    ) {
        subcommand(
            "eh",
            usage = translator.translate("command.get.eh.usage", config.getConfig().bot.commandOperator),
            executor = permissionExecutor("eh.get", getEHExecutor),
        )
        subcommand(
            "jm",
            usage = translator.translate("command.get.jm.usage", config.getConfig().bot.commandOperator),
            executor = permissionExecutor("jm.get", getJMExecutor),
        )
    }

    fun searchExecutor(
        source: String,
    ): CommandExecutor = CommandExecutor { sender, _, args, messageID ->
        val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
        val arguments = try {
            parseSearchCommandArguments(
                input = args.toString(),
                allowGalleryFilters = source == "eh",
            )
        } catch (_: IllegalArgumentException) {
            bot.reply(
                sender,
                messageID,
                userTranslator.translate("command.search.invalid", config.getConfig().bot.commandOperator)
            )
            return@CommandExecutor
        }

        val searchPage = try {
            checkNotNull(providers.resolve(source)) { "Comic provider $source is unavailable." }
                .search(arguments.keyword, arguments.page, arguments.options)
        } catch (exception: Exception) {
            logger.warn(
                "Search failed for {} page {}: {}",
                source,
                arguments.page,
                arguments.keyword,
                exception,
            )
            bot.reply(sender, messageID, userTranslator.translate("command.search.failed"))
            return@CommandExecutor
        }
        if (searchPage.results.isEmpty()) {
            bot.reply(sender, messageID, userTranslator.translate("command.search.empty", arguments.keyword))
            return@CommandExecutor
        }

        val nodes = searchPage.results.take(SEARCH_RESULT_LIMIT).mapIndexed { index, result ->
            ForwardMessageNode.CustomMessage(
                nickname = if (source == "eh") "E-Hentai #${index + 1}" else "JMComic #${index + 1}",
                content = MessageChain().apply {
                    if (bot is TelegramBot) add(Reply(messageID))
                    add(
                        PlainText(
                            formatSearchResult(
                                source = source,
                                result = result,
                                index = index + 1,
                                commandOperator = config.getConfig().bot.commandOperator,
                                translator = userTranslator,
                            )
                        )
                    )
                    val target = if (source == "eh") result.url else "JM${result.id}"
                    val command = "${config.getConfig().bot.commandOperator}get $source $target"
                    if (bot is TelegramBot && command.toByteArray(Charsets.UTF_8).size <= 64) {
                        add(ActionKeyboard(listOf(listOf(ActionButton(userTranslator.translate("search.action.download"), command)))))
                    }
                },
            )
        }
        bot.sendForwardMessage(sender.type, sender.target, nodes)
        if (bot is TelegramBot) {
            val navigation = buildList {
                if (arguments.page > 1) {
                    add(ActionButton(
                        userTranslator.translate("search.action.previous"),
                        "${botConfig.commandOperator}search $source --page=${arguments.page - 1} ${arguments.keyword}",
                    ))
                }
                if (searchPage.hasNextPage) {
                    add(ActionButton(
                        userTranslator.translate("search.action.next"),
                        "${botConfig.commandOperator}search $source --page=${arguments.page + 1} ${arguments.keyword}",
                    ))
                }
            }.filter { it.command.toByteArray(Charsets.UTF_8).size <= 64 }
            if (navigation.isNotEmpty()) {
                bot.sendMessage(
                    sender.type,
                    sender.target,
                    MessageChain().apply {
                        add(PlainText(userTranslator.translate("search.action.page", arguments.page)))
                        add(ActionKeyboard(listOf(navigation)))
                    },
                )
            }
        }
    }

    bot.registerCommand(
        "search",
        usage = translator.translate("command.search.usage", config.getConfig().bot.commandOperator),
    ) {
        subcommand(
            "eh",
            usage = translator.translate("command.search.eh.usage", config.getConfig().bot.commandOperator),
            executor = permissionExecutor("eh.search", searchExecutor("eh")),
        )
        subcommand(
            "jm",
            usage = translator.translate("command.search.jm.usage", config.getConfig().bot.commandOperator),
            executor = permissionExecutor("jm.search", searchExecutor("jm")),
        )
    }

    fun queryExecutor(source: String): CommandExecutor = CommandExecutor { sender, _, args, messageID ->
        val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
        val target = args.toString().trim()
        if (target.isEmpty()) {
            bot.reply(
                sender,
                messageID,
                userTranslator.translate(
                    if (source == "eh") "command.query.eh.usage" else "command.query.jm.usage",
                    config.getConfig().bot.commandOperator,
                ),
            )
            return@CommandExecutor
        }
        val preview = try {
            checkNotNull(providers.resolve(source)) { "Comic provider $source is unavailable." }.query(target)
        } catch (_: IllegalArgumentException) {
            bot.reply(
                sender,
                messageID,
                userTranslator.translate(if (source == "eh") "command.eh.invalid_url" else "command.jm.invalid_target"),
            )
            return@CommandExecutor
        } catch (exception: Exception) {
            logger.warn("Comic query failed for {} target {}: {}", source, target, exception)
            bot.reply(sender, messageID, userTranslator.translate("command.query.failed"))
            return@CommandExecutor
        }
        val userPreference = preference(bot, sender.user.userID)
        sendComicInformation(
            info = preview.info,
            coverFile = preview.cover,
            sender = sender,
            messageID = messageID,
            bot = bot,
            blurImages = userPreference.blurImages ?: blurImages,
            messageTranslator = userTranslator,
        )
    }

    bot.registerCommand(
        "query",
        usage = translator.translate("command.query.usage", config.getConfig().bot.commandOperator),
    ) {
        subcommand(
            "eh",
            usage = translator.translate("command.query.eh.usage", config.getConfig().bot.commandOperator),
            executor = permissionExecutor("eh.query", queryExecutor("eh")),
        )
        subcommand(
            "jm",
            usage = translator.translate("command.query.jm.usage", config.getConfig().bot.commandOperator),
            executor = permissionExecutor("jm.query", queryExecutor("jm")),
        )
    }

    bot.getEventBus().register(InlineQueryEvent::class.java) { event ->
        launch(Dispatchers.IO) {
            val query = event.query.trim()
            val source = query.substringBefore(' ').lowercase()
            val rawArguments = query.substringAfter(' ', "")
            val arguments = runCatching {
                require(source == "eh" || source == "jm")
                parseSearchCommandArguments(
                    input = rawArguments,
                    allowGalleryFilters = source == "eh",
                )
            }.getOrElse {
                bot.answerInlineQuery(event.queryID, emptyList())
                return@launch
            }
            val page = event.offset.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: arguments.page
            val searchPage = runCatching {
                checkNotNull(providers.resolve(source)) { "Comic provider $source is unavailable." }
                    .search(arguments.keyword, page, arguments.options)
            }.getOrElse { exception ->
                logger.warn("Telegram inline search failed for {} page {}: {}", source, page, query, exception)
                bot.answerInlineQuery(event.queryID, emptyList())
                return@launch
            }
            val inlineResults = searchPage.results.take(SEARCH_RESULT_LIMIT).mapIndexed { index, result ->
                InlineQueryResult(
                    id = "${source}_${page}_${result.id.hashCode().toUInt()}_$index",
                    title = result.title,
                    description = listOfNotNull(
                        result.subtitle,
                        result.category,
                        result.pages?.let { translator.translate("gallery.pages", it) },
                        result.rating?.let { translator.translate("gallery.rating", it) },
                    ).joinToString(" · ").takeIf(String::isNotBlank),
                    url = result.url,
                    message = formatSearchResult(
                        source = source,
                        result = result,
                        index = index + 1,
                        commandOperator = botConfig.commandOperator,
                        translator = translator,
                    ),
                )
            }
            bot.answerInlineQuery(
                queryID = event.queryID,
                results = inlineResults,
                nextOffset = (page + 1).toString().takeIf { searchPage.hasNextPage },
            )
        }
    }

    bot.registerCommand("checkin", usage = translator.translate("command.checkin.usage")) {
        sender, _, _, messageID ->
        val userId = AccessController.identity(bot.adapterKey(), sender.user.userID)
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

    bot.registerCommand("info", usage = translator.translate("command.info.usage")) {
        sender, _, _, messageID ->
        val user = economic.getUser(AccessController.identity(bot.adapterKey(), sender.user.userID))
        val lastCheckIn = user.checkinAt.atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
        bot.reply(
            sender,
            messageID,
            translator.translate(
                "command.info.content",
                if (hasPermission(sender, "usefulbot.admin")) {
                    UsersTable.Role.ADMIN.name
                } else {
                    UsersTable.Role.USER.name
                },
                user.balance,
                lastCheckIn,
            )
        )
    }

    bot.registerCommand("tasks", usage = translator.translate("command.tasks.usage")) {
        sender, _, _, messageID ->
        val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
        val snapshots = queue.snapshots(QueueUser(bot, sender.user.userID))
        val text = if (snapshots.isEmpty()) {
            userTranslator.translate("command.tasks.empty")
        } else {
            buildString {
                appendLine(userTranslator.translate("command.tasks.header"))
                snapshots.forEach { snapshot ->
                    append("${snapshot.id} · ${snapshot.state.name.lowercase()} · ${snapshot.progress.stage}")
                    snapshot.progress.percent?.let { append(" $it%") }
                    snapshot.position?.let { append(" · #$it") }
                    if (snapshot.subscriberCount > 1) append(" · ${snapshot.subscriberCount} users")
                    appendLine()
                }
            }.trimEnd()
        }
        bot.reply(sender, messageID, text)
    }

    bot.registerCommand("cancel", usage = translator.translate("command.cancel.usage")) {
        sender, _, args, messageID ->
        val id = args.toString().trim()
        val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
        if (id.isEmpty()) {
            bot.reply(sender, messageID, userTranslator.translate("command.cancel.missing"))
            return@registerCommand
        }
        val result = queue.cancel(QueueUser(bot, sender.user.userID), id)
        if (result.status == ProcessingQueue.CancelStatus.CANCELLED ||
            result.status == ProcessingQueue.CancelStatus.UNSUBSCRIBED
        ) {
            stateStore.removeSubscriber(id, bot.adapterKey(), sender.user.userID)
        }
        val key = when (result.status) {
            ProcessingQueue.CancelStatus.CANCELLED -> "command.cancel.cancelled"
            ProcessingQueue.CancelStatus.UNSUBSCRIBED -> "command.cancel.unsubscribed"
            ProcessingQueue.CancelStatus.NOT_FOUND -> "command.cancel.not_found"
            ProcessingQueue.CancelStatus.NOT_SUBSCRIBED -> "command.cancel.not_subscribed"
        }
        bot.reply(sender, messageID, userTranslator.translate(key, id))
    }

    bot.registerCommand("history", usage = translator.translate("command.history.usage")) {
        sender, _, args, messageID ->
        val limit = args.toString().trim().toIntOrNull()?.coerceIn(1, 50) ?: 10
        val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
        val records = economic.queryRecord(AccessController.identity(bot.adapterKey(), sender.user.userID), limit)
        val text = if (records.isEmpty()) {
            userTranslator.translate("command.history.empty")
        } else {
            buildString {
                appendLine(userTranslator.translate("command.history.header"))
                records.forEach { record ->
                    val sign = if (record.operation.name == "DEPOSIT") "+" else "-"
                    appendLine("${record.createdAt.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} · $sign${record.amount} GP")
                }
            }.trimEnd()
        }
        bot.reply(sender, messageID, text)
    }

    if (config.getConfig().batch.enabled) {
        bot.registerCommand("batch", usage = translator.translate("command.batch.usage")) {
            sender, _, args, messageID ->
            val userTranslator = translatorFor(preference(bot, sender.user.userID).language)
            val items = args.toString().split('\n', ';').map(String::trim).filter(String::isNotEmpty)
            if (items.isEmpty() || items.size > config.getConfig().batch.maxItems) {
                bot.reply(
                    sender,
                    messageID,
                    userTranslator.translate("command.batch.invalid", config.getConfig().batch.maxItems),
                )
                return@registerCommand
            }
            var accepted = 0
            val failures = mutableListOf<String>()
            items.forEach { item ->
                val source = item.substringBefore(' ').lowercase()
                val target = item.substringAfter(' ', "").trim()
                val provider = providers.resolve(source)
                val task = runCatching { require(target.isNotEmpty()); provider?.parse?.invoke(target) }.getOrNull()
                if (task == null) {
                    failures += item
                    return@forEach
                }
                val extra = queueExtra(bot, sender, messageID, blurImages)
                val status = submitTask(task, extra)
                if (status == ProcessingQueue.PutStatus.SUCCESS || status == ProcessingQueue.PutStatus.JOINED_TASK) {
                    accepted++
                    sendPublishedComicInformation(task, extra)
                } else {
                    failures += "${task.id} (${status.name.lowercase()})"
                }
            }
            bot.reply(
                sender,
                messageID,
                userTranslator.translate("command.batch.result", accepted, failures.size) +
                    failures.takeIf(List<String>::isNotEmpty)?.joinToString("\n", prefix = "\n").orEmpty(),
            )
        }
    }

    bot.registerCommand("prefs", usage = translator.translate("command.prefs.usage")) {
        subcommand("show", usage = translator.translate("command.prefs.show.usage")) { sender, _, _, messageID ->
            val current = preference(bot, sender.user.userID)
            val userTranslator = translatorFor(current.language)
            bot.reply(
                sender,
                messageID,
                userTranslator.translate(
                    "command.prefs.content",
                    current.language.ifBlank { config.getConfig().bot.language },
                    current.blurImages?.toString() ?: "default",
                    current.notifyProgress,
                ),
            )
        }
        subcommand("language", usage = translator.translate("command.prefs.language.usage")) { sender, _, args, messageID ->
            val language = args.toString().trim()
            val current = preference(bot, sender.user.userID)
            val supported = language.lowercase() in setOf("en", "en-us", "zh", "zh-cn", "中文")
            if (!supported) {
                bot.reply(sender, messageID, translatorFor(current.language).translate("command.prefs.invalid"))
            } else {
                stateStore.updatePreference(bot.adapterKey(), sender.user.userID, current.copy(language = language))
                bot.reply(sender, messageID, translatorFor(language).translate("command.prefs.saved"))
            }
        }
        subcommand("blur", usage = translator.translate("command.prefs.blur.usage")) { sender, _, args, messageID ->
            val value = args.toString().trim().lowercase()
            val current = preference(bot, sender.user.userID)
            if (bot !is TelegramBot) {
                bot.reply(sender, messageID, translatorFor(current.language).translate("command.prefs.blur.telegram_only"))
                return@subcommand
            }
            val blur = when (value) { "on", "true" -> true; "off", "false" -> false; "default" -> null; else -> null }
            if (value !in setOf("on", "true", "off", "false", "default")) {
                bot.reply(sender, messageID, translatorFor(current.language).translate("command.prefs.invalid"))
            } else {
                stateStore.updatePreference(bot.adapterKey(), sender.user.userID, current.copy(blurImages = blur))
                bot.reply(sender, messageID, translatorFor(current.language).translate("command.prefs.saved"))
            }
        }
        subcommand("progress", usage = translator.translate("command.prefs.progress.usage")) { sender, _, args, messageID ->
            val value = args.toString().trim().lowercase()
            val current = preference(bot, sender.user.userID)
            if (value !in setOf("on", "off")) {
                bot.reply(sender, messageID, translatorFor(current.language).translate("command.prefs.invalid"))
            } else {
                stateStore.updatePreference(bot.adapterKey(), sender.user.userID, current.copy(notifyProgress = value == "on"))
                bot.reply(sender, messageID, translatorFor(current.language).translate("command.prefs.saved"))
            }
        }
    }

    bot.registerCommand(
        "health",
        usage = translator.translate("command.health.usage"),
        permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
    ) {
        sender, _, _, messageID ->
        if (!requirePermission(sender, messageID, "usefulbot.health", PermissionDefault.ADMIN)) {
            return@registerCommand
        }
        val freeMiB = dataFolder.usableSpace / 1024 / 1024
        val ehEndpoint = if (config.getConfig().eHentai.isExHentai) "https://exhentai.org/" else "https://e-hentai.org/"
        val jmEndpoint = config.getConfig().jmComic.apiDomains.firstOrNull()
            ?.let { "https://$it" }
            ?: config.getConfig().jmComic.redirectUrl
        bot.reply(sender, messageID, "health\nchecking endpoints...")
        val endpointHealth = probeHealthEndpoints(
            linkedMapOf(
                "eh" to ehEndpoint,
                "jm" to jmEndpoint,
            ),
            ::probe,
        )
        bot.reply(
            sender,
            messageID,
            "health\nadapters=${connectedAdapters.joinToString()}\neh=${endpointHealth.getValue("eh")}\njm=${endpointHealth.getValue("jm")}\nproxy=${config.getConfig().proxy.type}\ndatabase=ok (${economic.userCount()} users)\nqueue=${queue.snapshots().size}\noutbox=${stateStore.outboxSize()}\ndisk_free=${freeMiB} MiB\nproviders=${providers.entries().joinToString { it.first }}\nplugins=${pluginManager.snapshots().joinToString { "${it.metadata.id}:${it.status.name.lowercase()}" }}",
        )
    }

    bot.registerCommand("admin", usage = translator.translate("command.admin.usage")) {
        subcommand(
            "status",
            usage = translator.translate("command.admin.status.usage"),
            permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
        ) { sender, _, _, messageID ->
            if (!requirePermission(sender, messageID, "usefulbot.admin.status", PermissionDefault.ADMIN)) return@subcommand
            bot.reply(sender, messageID, "queue=${queue.snapshots().size}, outbox=${stateStore.outboxSize()}, users=${economic.userCount()}")
        }
        subcommand(
            "gp",
            usage = translator.translate("command.admin.gp.usage"),
            permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
        ) { sender, _, args, messageID ->
            if (!requirePermission(sender, messageID, "usefulbot.admin.gp", PermissionDefault.ADMIN)) return@subcommand
            val parts = args.toString().trim().split(Regex("\\s+"))
            val userId = parts.getOrNull(0)?.let(AccessController::normalizeScopedIdentity)
            val amount = parts.getOrNull(1)?.toLongOrNull()
            val success = when {
                userId == null || amount == null || amount == 0L -> false
                amount > 0 -> economic.depositGP(userId, amount)
                amount == Long.MIN_VALUE -> false
                else -> economic.withdrawGP(userId, -amount)
            }
            bot.reply(sender, messageID, if (success) "OK: ${economic.getBalance(userId!!)} GP" else "Invalid arguments or insufficient balance.")
        }
        subcommand(
            "cache",
            usage = translator.translate("command.admin.cache.usage"),
            permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
        ) { sender, _, _, messageID ->
            if (!requirePermission(sender, messageID, "usefulbot.admin.cache", PermissionDefault.ADMIN)) return@subcommand
            val result = runCacheCleanup()
            bot.reply(sender, messageID, "Deleted ${result.deletedFiles} files (${result.deletedBytes / 1024 / 1024} MiB); remaining ${result.remainingBytes / 1024 / 1024} MiB.")
        }
        subcommand(
            "retry",
            usage = translator.translate("command.admin.retry.usage"),
            permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
        ) { sender, _, _, messageID ->
            if (!requirePermission(sender, messageID, "usefulbot.admin.retry", PermissionDefault.ADMIN)) return@subcommand
            val count = stateStore.retryAllDeliveries()
            bot.reply(sender, messageID, "Reactivated $count pending deliveries.")
        }
        subcommand(
            "cancel",
            usage = translator.translate("command.admin.cancel.usage"),
            permissionDefault = PermissionDefault.ADMIN or PermissionDefault.ALLOW_CONSOLE,
        ) { sender, _, args, messageID ->
            if (!requirePermission(sender, messageID, "usefulbot.admin.cancel", PermissionDefault.ADMIN)) return@subcommand
            val id = args.toString().trim()
            val result = queue.cancelTask(id)
            if (result.status != ProcessingQueue.CancelStatus.NOT_FOUND) stateStore.completeTask(id)
            bot.reply(sender, messageID, result.status.name)
        }
    }
    bot.setCommandVisibility("health") { sender ->
        hasPermission(sender, "usefulbot.health", PermissionDefault.ADMIN)
    }
    bot.setCommandVisibility("admin") { sender ->
        listOf("status", "gp", "cache", "retry", "cancel").any { action ->
            hasPermission(sender, "usefulbot.admin.$action")
        } || hasPermission(sender, "usefulbot.admin", PermissionDefault.ADMIN)
    }
    }

    bots.forEach { (bot, blurImages) -> configureBot(bot, blurImages) }
    val pluginConfig = config.getConfig().plugins
    val builtInPlugins = listOf(
        BuiltInPlugin(
            descriptor = PluginDescriptor(
                id = "permissions",
                name = "Built-in Permissions",
                version = "1.0.0",
                main = PermissionPlugin::class.java.name,
                description = "Persistent permission nodes and dynamic bans.",
            ),
            instance = PermissionPlugin(
                permissions = permissionService,
                state = stateStore,
                adapterKey = { it.adapterKey() },
                translatorFor = { targetBot, userId ->
                    translatorFor(preference(targetBot, userId).language)
                },
                defaultTranslator = translator,
            ),
            essential = true,
        ),
        BuiltInPlugin(
            descriptor = PluginDescriptor(
                id = "eh",
                name = "E-Hentai Provider",
                version = "1.0.0",
                main = BuiltInComicProviderPlugin::class.java.name,
                description = "Built-in E-Hentai and ExHentai comic provider.",
            ),
            instance = BuiltInComicProviderPlugin(
                id = "eh",
                aliases = arrayOf("ex"),
                registry = providers,
                provider = ProviderDefinition(
                    parse = { ComicTask.EHentai(eh.parseUrl(it)) },
                    query = { target ->
                        val gallery = eh.parseUrl(target)
                        val info = eh.getTargetInformation(gallery)
                        val taskName = "${gallery.first}-${gallery.second}"
                        val imageDirectory = File(ehImgFolder, taskName).apply { mkdirs() }
                        val extension = Util.getFileExtensionFromUrl(URI.create(info.cover).toURL())
                            ?.takeIf(String::isNotBlank)
                            ?: "jpg"
                        val cover = File(imageDirectory, "cover.$extension")
                        if (!cover.isFile) {
                            DownloadManager(1, client, File(ehTempFolder, "query")).use { downloader ->
                                val failed = downloader.downloadFiles(
                                    listOf(info.cover to cover.name),
                                    imageDirectory,
                                    1,
                                )
                                check(failed.isEmpty() && cover.isFile) { "Failed to download the gallery cover." }
                            }
                        }
                        ComicInformationPreview(info, cover)
                    },
                    search = eh::search,
                    generate = { task -> generateEHentai((task as ComicTask.EHentai).gallery) },
                    deliver = { result, extra -> deliverEHentai(result as ComicTaskResult.EHentai, extra) },
                ),
            ),
        ),
        BuiltInPlugin(
            descriptor = PluginDescriptor(
                id = "jm",
                name = "JMComic Provider",
                version = "1.0.0",
                main = BuiltInComicProviderPlugin::class.java.name,
                description = "Built-in JMComic provider.",
            ),
            instance = BuiltInComicProviderPlugin(
                id = "jm",
                aliases = emptyArray(),
                registry = providers,
                provider = ProviderDefinition(
                    parse = { ComicTask.JMComic(jm.parseUrl(it)) },
                    query = { target ->
                        val albumId = jm.parseUrl(target)
                        val info = jm.getTargetInformation(albumId)
                        val imageDirectory = File(jmImgFolder, "JM$albumId").apply { mkdirs() }
                        val cover = File(imageDirectory, "cover.jpg").let { targetCover ->
                            targetCover.takeIf(File::isFile) ?: jm.downloadCover(albumId, targetCover)
                        }
                        ComicInformationPreview(info, cover)
                    },
                    search = jm::search,
                    generate = { task -> generateJMComic((task as ComicTask.JMComic).albumId) },
                    deliver = { result, extra -> deliverJMComic(result as ComicTaskResult.JMComic, extra) },
                ),
            ),
        ),
    )
    pluginManager = PluginManager(
        pluginDirectory = File(rootFolder, pluginConfig.directory),
        rootDirectory = rootFolder.canonicalFile,
        bots = bots.map { it.first },
        disabledPluginIds = pluginConfig.disabled.toSet(),
        builtInPlugins = builtInPlugins,
        externalPluginsEnabled = pluginConfig.enabled,
        mandatoryPluginIds = setOf("permissions"),
        platformResolver = { AccessController.platform(it.adapterKey()) },
    )
    pluginManager.loadAndEnableAll()
    val console = JLineConsole(
        bot = bots.first().first,
        permissionNodeSuggestions = permissionService::suggestions,
    )
    Runtime.getRuntime().addShutdownHook(Thread({
        runCatching { console.close() }
        pluginManager.close()
        bots.asReversed().forEach { (bot, _) -> runCatching { bot.close() } }
    }, "plugin-and-bot-shutdown"))
    val connectedBotCount = bots.count { (bot, _) ->
        runCatching { bot.connect() }
            .onFailure { exception ->
                logger.error(
                    "Failed to connect {}; other enabled bot adapters will keep running.",
                    bot::class.simpleName,
                    exception,
                )
            }
            .getOrDefault(false)
            .also { connected -> if (connected) connectedAdapters.add(bot.adapterKey()) }
    }
    check(connectedBotCount > 0) { "None of the enabled bot adapters could be connected." }
    launch(Dispatchers.IO) {
        runCatching { console.run() }
            .onFailure { logger.error("Console stopped unexpectedly.", it) }
    }

    val botsByAdapter = bots.associate { (bot, _) -> bot.adapterKey() to bot }
    stateStore.pendingTasks().forEach { persistent ->
        val task = runCatching {
            checkNotNull(providers.resolve(persistent.source)) { "Unknown comic provider: ${persistent.source}" }
                .parse(persistent.target)
        }
            .onFailure { logger.warn("Discarding invalid persisted task {}.", persistent.id, it) }
            .getOrNull() ?: return@forEach
        persistent.subscribers.forEach { subscriber ->
            val bot = botsByAdapter[subscriber.adapter] ?: return@forEach
            val sender = MessageSender(
                target = subscriber.target,
                user = UserInfo(
                    userID = subscriber.userId,
                    username = subscriber.username,
                    role = subscriber.role,
                    card = subscriber.card,
                ),
                type = subscriber.chatType,
            )
            val extra = QueueExtraData(
                messageID = subscriber.messageId,
                sender = sender,
                bot = bot,
                blurImages = subscriber.blurImages,
                adapter = subscriber.adapter,
                language = subscriber.language,
                notifyProgress = subscriber.notifyProgress,
            )
            val status = queue.putOrJoin(QueueUser(bot, subscriber.userId), task, extra)
            if (status != ProcessingQueue.PutStatus.SUCCESS && status != ProcessingQueue.PutStatus.JOINED_TASK) {
                logger.warn("Could not restore task {} subscriber {}: {}.", task.id, subscriber.userId, status)
            }
        }
    }

    if (config.getConfig().deliveryRetry.enabled) {
        launch(Dispatchers.IO) {
            while (true) {
                stateStore.dueDeliveries().forEach { delivery ->
                    val bot = botsByAdapter[delivery.adapter]
                    val file = File(delivery.filePath)
                    val sent = bot != null && file.isFile && runCatching {
                        sendFileWithLargeFilePolicy(
                            bot = bot,
                            sender = MessageSender(
                                delivery.target,
                                UserInfo(delivery.target, "delivery-retry"),
                                delivery.chatType,
                            ),
                            messageID = delivery.messageId,
                            name = delivery.name,
                            pdf = file,
                            pdfPassword = delivery.password,
                        )
                    }.onFailure { logger.warn("Delivery retry {} failed.", delivery.id, it) }.getOrDefault(false)
                    if (sent) {
                        stateStore.deliverySucceeded(delivery.id)
                    } else {
                        stateStore.deliveryFailed(
                            delivery.id,
                            TimeUnit.SECONDS.toMillis(config.getConfig().deliveryRetry.intervalSeconds),
                            config.getConfig().deliveryRetry.maxAttempts,
                        )
                    }
                }
                delay(TimeUnit.SECONDS.toMillis(config.getConfig().deliveryRetry.intervalSeconds.coerceAtLeast(1)))
            }
        }
    }

    launch(Dispatchers.IO) {
        while (true) {
            val result = runCacheCleanup()
            if (result.deletedFiles > 0) {
                logger.info("Cache cleanup deleted {} files and {} bytes.", result.deletedFiles, result.deletedBytes)
            }
            val freeMiB = dataFolder.usableSpace / 1024 / 1024
            if (freeMiB < config.getConfig().cache.minimumFreeSpaceMiB) {
                logger.warn("Low disk space: {} MiB available under data directory.", freeMiB)
            }
            delay(TimeUnit.MINUTES.toMillis(config.getConfig().cache.cleanupIntervalMinutes.coerceAtLeast(1)))
        }
    }

    for (i in 1..config.getConfig().comicParallelCount) {
        async(Dispatchers.IO) {
            while (true) {
                val (_, task) = queue.take()
                val result = runCatching<ComicTaskResult> {
                    checkNotNull(providers.resolve(task.source)) { "Provider ${task.source} is not registered." }
                        .generate(task)
                }
                val subscribers = queue.sealAndGetSubscribers(task).map { it.second }
                try {
                    result.fold(
                        onSuccess = { taskResult ->
                            try {
                                subscribers.forEach { subscriber ->
                                    if (!queue.isSubscribed(
                                            task,
                                            QueueUser(subscriber.bot, subscriber.sender.user.userID),
                                        )) return@forEach
                                    runCatching {
                                        checkNotNull(providers.resolve(task.source)).deliver(taskResult, subscriber)
                                    }.onFailure { exception ->
                                        logger.error(
                                            "Failed to deliver {} to user {} through {}.",
                                            task,
                                            subscriber.sender.user.userID,
                                            subscriber.bot::class.simpleName,
                                            exception,
                                        )
                                        val fileName = recordCommandError(
                                            subscriber.bot,
                                            subscriber.sender,
                                            subscriber.messageID,
                                            operationFor(task),
                                            exception,
                                        )
                                        runCatching {
                                            subscriber.bot.reply(
                                                subscriber.sender,
                                                subscriber.messageID,
                                                commandErrorMessage(subscriber.language, fileName),
                                            )
                                        }
                                    }
                                }
                            } finally {
                                cleanupComicTaskCache(task)
                            }
                        },
                        onFailure = { exception ->
                            logger.error("Failed to process shared task {}.", task, exception)
                            val fileName = subscribers.firstOrNull()?.let { subscriber ->
                                recordCommandError(
                                    subscriber.bot,
                                    subscriber.sender,
                                    subscriber.messageID,
                                    operationFor(task),
                                    exception,
                                )
                            }
                            subscribers.forEach { subscriber ->
                                runCatching {
                                    subscriber.bot.reply(
                                        subscriber.sender,
                                        subscriber.messageID,
                                        commandErrorMessage(subscriber.language, fileName),
                                    )
                                }
                            }
                        },
                    )
                } finally {
                    comicInformationPreviews.remove(task)
                    queue.completeSealed(task)
                    stateStore.completeTask(task.id)
                }
            }
        }
    }
}

internal fun formatSearchResult(
    source: String,
    result: ComicSearchResult<*>,
    index: Int,
    commandOperator: Char,
    translator: Translator,
): String = buildString {
    val target = if (source == "eh") result.url else "JM${result.id}"
    appendLine("#$index")
    appendLine(translator.translate("gallery.title", result.title))
    result.category?.takeIf(String::isNotBlank)?.let {
        appendLine(translator.translate("gallery.type", it))
    }
    result.pages?.let { appendLine(translator.translate("gallery.pages", it)) }
    result.rating?.let { appendLine(translator.translate("gallery.rating", it)) }
    if (result.tags.isNotEmpty()) {
        appendLine()
        appendLine(translator.translate("search.result.tags"))
        val distinctTags = result.tags.distinct()
        val visibleTags = distinctTags.take(SEARCH_TAG_LIMIT)
        val groupedTags = linkedMapOf<String?, MutableList<String>>()
        visibleTags.forEach { tag ->
            val separator = tag.indexOf(':')
            val namespace = if (separator > 0) tag.substring(0, separator).trim() else null
            val value = if (separator > 0) tag.substring(separator + 1).trim() else tag.trim()
            groupedTags.getOrPut(namespace) { mutableListOf() } += value
        }
        groupedTags.forEach { (namespace, values) ->
            append("  ")
            if (namespace != null) append("$namespace: ")
            appendLine(values.joinToString(", "))
        }
        val omittedCount = distinctTags.size - visibleTags.size
        if (omittedCount > 0) {
            appendLine(translator.translate("search.result.more_tags", omittedCount))
        }
    }
    appendLine()
    appendLine(translator.translate("search.result.link", result.url))
    append(
        translator.translate(
            "search.result.command",
            "`${commandOperator}get $source $target`",
        )
    )
}

private const val SEARCH_RESULT_LIMIT = 10
private const val SEARCH_TAG_LIMIT = 16
private const val HEALTH_PROBE_TIMEOUT_SECONDS = 4L

internal suspend fun probeHealthEndpoints(
    endpoints: Map<String, String>,
    probe: (String) -> String,
): Map<String, String> = coroutineScope {
    endpoints.mapValues { (_, endpoint) ->
        async(Dispatchers.IO) { probe(endpoint) }
    }.mapValues { (_, result) -> result.await() }
}
