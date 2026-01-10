package me.heartalborada.comics

import me.heartalborada.commons.comic.AbstractComicProvider
import me.heartalborada.commons.comic.model.ArchiveInformation
import me.heartalborada.commons.comic.model.ComicInformation

class JMComic: AbstractComicProvider<String>() {
    override fun getTargetInformation(target: String): ComicInformation<String> {
        TODO("Not yet implemented")
    }

    override fun getPageImageUrl(
        target: String,
        pages: Map<Int, String>
    ): Map<Int, String> {
        TODO("Not yet implemented")
    }

    override fun getAllPages(target: String): Map<Int, String> {
        TODO("Not yet implemented")
    }

    override fun parseUrl(url: String): String {
        TODO("Not yet implemented")
    }

    override fun getArchiveDownloadUrl(
        target: String,
        type: ArchiveInformation
    ): String {
        TODO("Not yet implemented")
    }

    override fun getArchiveInformation(target: String): Array<ArchiveInformation> {
        TODO("Not yet implemented")
    }
}