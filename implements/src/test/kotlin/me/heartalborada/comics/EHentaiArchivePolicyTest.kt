package me.heartalborada.comics

import me.heartalborada.commons.comic.model.ArchiveInformation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EHentaiArchivePolicyTest {
    @Test
    fun `uses original archive when resample is unavailable`() {
        val original = ArchiveInformation(name = "ORIGINAL", size = "1.25 GiB")

        val selected = selectEHentaiArchive(
            listOf(
                original,
                ArchiveInformation(name = "RESAMPLE", size = "N/A"),
            )
        )

        assertEquals(original, selected)
    }

    @Test
    fun `prefers an available resample archive`() {
        val resample = ArchiveInformation(name = "RESAMPLE", size = "245.6 MiB")

        val selected = selectEHentaiArchive(
            listOf(
                ArchiveInformation(name = "ORIGINAL", size = "1.25 GiB"),
                resample,
            )
        )

        assertEquals(resample, selected)
    }

    @Test
    fun `uses original archive when resample entry is absent`() {
        val original = ArchiveInformation(name = "ORIGINAL", size = "1.25 GiB")

        assertEquals(original, selectEHentaiArchive(listOf(original)))
        assertNull(selectEHentaiArchive(emptyList()))
    }

    @Test
    fun `archive size limit is disabled by zero and rejects only larger archives`() {
        val oneHundredMiB = 100L * 1024 * 1024

        assertFalse(isEHentaiArchiveOverSizeLimit(oneHundredMiB, 0))
        assertFalse(isEHentaiArchiveOverSizeLimit(oneHundredMiB, 100))
        assertTrue(isEHentaiArchiveOverSizeLimit(oneHundredMiB + 1, 100))
        assertFailsWith<IllegalArgumentException> {
            isEHentaiArchiveOverSizeLimit(oneHundredMiB, -1)
        }
    }
}
