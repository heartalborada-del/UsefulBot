package me.heartalborada.commons.comic

import me.heartalborada.commons.ActionResponse

abstract class AbstractComicProvider<T> {
    abstract fun getTargetInformation(target: T): ActionResponse<ComicInformation<T>>
}