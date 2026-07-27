package me.heartalborada.commons.comic

import me.heartalborada.commons.comic.model.ArchiveInformation
import me.heartalborada.commons.comic.model.ComicInformation
import me.heartalborada.commons.comic.model.ComicSearchPage

abstract class AbstractComicProvider<T> {
    abstract fun search(keyword: String, page: Int = 1): ComicSearchPage<T>
    abstract fun getTargetInformation(target: T): ComicInformation<T>
    abstract fun getPageImageUrl(target: T, pages: Map<Int, String>): Map<Int, String>
    abstract fun getAllPages(target: T): Map<Int, String>
    abstract fun parseUrl(url: String): T
    abstract fun getArchiveDownloadUrl(target: T, type: ArchiveInformation): String
    abstract fun getArchiveInformation(target: T): Array<ArchiveInformation>
}
