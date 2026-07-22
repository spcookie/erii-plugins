package uesugi.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class SeedDreamHistoryTest {
    @Test
    fun `takes only images directly before the latest prompt`() {
        val records = listOf("prompt", "new-image", "old-image", "break", "stale-image")

        val selected = selectContiguousReferences(records) { it.endsWith("image") }

        assertEquals(listOf("old-image", "new-image"), selected)
    }

    @Test
    fun `includes a latest record that is itself an image and limits references to three`() {
        val records = listOf("image-4", "image-3", "image-2", "image-1")

        val selected = selectContiguousReferences(records, maxImages = 3) { it.startsWith("image-") }

        assertEquals(listOf("image-2", "image-3", "image-4"), selected)
    }

    @Test
    fun `returns no references when the previous record is not an image`() {
        val records = listOf("prompt", "break", "image")

        val selected = selectContiguousReferences(records) { it == "image" }

        assertEquals(emptyList(), selected)
    }
}
