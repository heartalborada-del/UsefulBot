package me.heartalborada.commons.utils

import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


@OptIn(ExperimentalEncodingApi::class)
fun File.toBase64(): String {
    val fileBytes = Files.readAllBytes(this.toPath())
    return Base64.encode(fileBytes)
}

fun File.calculateSHA256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val fileBytes = Files.readAllBytes(this.toPath())
    val hashBytes = digest.digest(fileBytes)
    return hashBytes.joinToString("") { "%02x".format(it) }
}

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64(): String {
    return Base64.encode(this)
}
