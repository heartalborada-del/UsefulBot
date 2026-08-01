package me.heartalborada.plugins

import org.slf4j.Logger
import java.io.File
import java.nio.file.Files

data class ComicDataMigrationReport(
    val movedEntries: Int,
    val conflicts: List<File>,
)

object ComicDataMigration {
    fun migrate(rootDirectory: File, pluginDirectory: File, logger: Logger): ComicDataMigrationReport {
        val comicDirectory = File(pluginDirectory, "comic")
        val conflicts = mutableListOf<File>()
        var movedEntries = 0

        listOf(
            File(rootDirectory, "data/eh") to File(comicDirectory, "eh"),
            File(rootDirectory, "data/jm") to File(comicDirectory, "jm"),
            File(pluginDirectory, "eh/data") to File(comicDirectory, "eh"),
            File(pluginDirectory, "jm/data") to File(comicDirectory, "jm"),
        ).forEach { (source, target) ->
            if (!source.exists()) return@forEach
            movedEntries += merge(source, target, conflicts)
            deleteEmptyDirectories(source)
        }
        listOf(File(pluginDirectory, "eh"), File(pluginDirectory, "jm"))
            .forEach(::deleteEmptyDirectories)

        if (movedEntries > 0) {
            logger.info("Migrated {} legacy comic data entries into {}.", movedEntries, comicDirectory.absolutePath)
        }
        conflicts.forEach { source ->
            logger.warn("Kept legacy comic data at {} because the destination already exists.", source.absolutePath)
        }
        return ComicDataMigrationReport(movedEntries, conflicts)
    }

    private fun merge(source: File, target: File, conflicts: MutableList<File>): Int {
        if (!target.exists()) {
            require(target.parentFile.mkdirs() || target.parentFile.isDirectory) {
                "Could not create comic plugin directory: ${target.parentFile.absolutePath}"
            }
            Files.move(source.toPath(), target.toPath())
            return 1
        }
        if (!source.isDirectory || !target.isDirectory) {
            conflicts += source
            return 0
        }

        return source.listFiles().orEmpty().sumOf { child ->
            merge(child, File(target, child.name), conflicts)
        }
    }

    private fun deleteEmptyDirectories(directory: File) {
        if (!directory.isDirectory) return
        directory.listFiles().orEmpty().filter(File::isDirectory).forEach(::deleteEmptyDirectories)
        if (directory.listFiles().isNullOrEmpty()) directory.delete()
    }
}
