package me.heartalborada.commons

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtilZipTest {
    @Test
    fun `valid zip passes full integrity check`() {
        val zip = createZip()
        try {
            assertTrue(Util.isValidZip(zip))
        } finally {
            zip.delete()
        }
    }

    @Test
    fun `corrupt zip fails full integrity check`() {
        val zip = createZip()
        try {
            val bytes = zip.readBytes()
            val content = "test content".toByteArray()
            val contentOffset = bytes.indices.firstOrNull { offset ->
                offset + content.size <= bytes.size &&
                    content.indices.all { bytes[offset + it] == content[it] }
            } ?: -1
            check(contentOffset >= 0)
            bytes[contentOffset] = (bytes[contentOffset].toInt() xor 0xff).toByte()
            zip.writeBytes(bytes)

            assertFalse(Util.isValidZip(zip))
        } finally {
            zip.delete()
        }
    }

    private fun createZip(): File {
        val zip = File.createTempFile("usefulbot-", ".zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("page.txt").apply {
                method = ZipEntry.STORED
                val content = "test content".toByteArray()
                size = content.size.toLong()
                compressedSize = content.size.toLong()
                crc = java.util.zip.CRC32().apply { update(content) }.value
            })
            output.write("test content".toByteArray())
            output.closeEntry()
        }
        return zip
    }
}
