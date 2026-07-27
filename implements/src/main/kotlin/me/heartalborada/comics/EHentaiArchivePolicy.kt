package me.heartalborada.comics

import me.heartalborada.commons.comic.model.ArchiveInformation

internal fun selectEHentaiArchive(archives: Iterable<ArchiveInformation>): ArchiveInformation? {
    val resample = archives.firstOrNull { it.name == EHentai.ArchiveType.RESAMPLE.name }
    if (resample != null && !resample.size.trim().equals("N/A", ignoreCase = true)) {
        return resample
    }
    return archives.firstOrNull { it.name == EHentai.ArchiveType.ORIGINAL.name }
}

internal fun isEHentaiArchiveOverSizeLimit(archiveSizeBytes: Long, maximumSizeMiB: Long): Boolean {
    require(archiveSizeBytes >= 0) { "E-Hentai archive size must not be negative." }
    require(maximumSizeMiB >= 0) { "Ehentai.MaxArchiveSizeMiB must not be negative." }
    if (maximumSizeMiB == 0L) return false
    val bytesPerMiB = 1024L * 1024L
    val maximumSizeBytes = if (maximumSizeMiB > Long.MAX_VALUE / bytesPerMiB) {
        Long.MAX_VALUE
    } else {
        maximumSizeMiB * bytesPerMiB
    }
    return archiveSizeBytes > maximumSizeBytes
}
