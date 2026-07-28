package me.heartalborada.cache

import java.io.File
import java.time.Clock

data class CacheCleanupResult(val deletedFiles: Int, val deletedBytes: Long, val remainingBytes: Long)

class CacheJanitor(private val clock: Clock = Clock.systemUTC()) {
    fun clean(
        roots: Collection<File>,
        maximumBytes: Long,
        ttlMillis: Long,
        protectedPaths: Set<String> = emptySet(),
    ): CacheCleanupResult {
        val files = roots.asSequence()
            .filter(File::exists)
            .flatMap { root -> root.walkTopDown().filter(File::isFile) }
            .toMutableList()
        var total = files.sumOf(File::length)
        var deletedFiles = 0
        var deletedBytes = 0L
        val expiredBefore = clock.millis() - ttlMillis
        val candidates = files.sortedBy(File::lastModified)
        for (file in candidates) {
            if (file.absolutePath in protectedPaths) continue
            val expired = ttlMillis > 0 && file.lastModified() < expiredBefore
            val overLimit = maximumBytes > 0 && total > maximumBytes
            if (!expired && !overLimit) continue
            val length = file.length()
            if (file.delete()) {
                deletedFiles++
                deletedBytes += length
                total -= length
            }
        }
        return CacheCleanupResult(deletedFiles, deletedBytes, total)
    }
}
