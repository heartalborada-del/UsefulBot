package me.heartalborada.commons.configurations

abstract class AbstractConfiguration<T> {
    abstract fun load()
    abstract fun save()
    abstract fun getConfig(): T
}