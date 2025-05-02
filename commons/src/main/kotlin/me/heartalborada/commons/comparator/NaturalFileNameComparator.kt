package me.heartalborada.commons.comparator

import java.io.File

class NaturalFileNameComparator:Comparator<File> {
    private val naturalComparator = NaturalComparator()
    override fun compare(o1: File?, o2: File?): Int {
        return naturalComparator.compare(o1?.path, o2?.path)
    }
}