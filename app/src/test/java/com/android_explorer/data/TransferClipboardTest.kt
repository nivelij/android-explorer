package com.android_explorer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the app-wide [TransferClipboard] used by both browsers for cross-backend paste. */
class TransferClipboardTest {

    private fun driveItem(id: String) = FileItem(
        location = NodeRef.Drive(id = id, parentId = "root", mimeType = "text/plain"),
        name = "$id.txt",
        isDirectory = false,
        size = 1L,
        lastModified = 0L,
        isArchive = false,
    )

    @After
    fun tearDown() = TransferClipboard.clear()

    @Test
    fun `set stores items and the cut flag`() {
        TransferClipboard.set(listOf(driveItem("a")), cut = true)
        val clip = TransferClipboard.current!!
        assertEquals(1, clip.items.size)
        assertEquals("drive:a", clip.items.first().path)
        assertTrue(clip.cut)
    }

    @Test
    fun `setting an empty list clears the clipboard`() {
        TransferClipboard.set(listOf(driveItem("a")), cut = false)
        TransferClipboard.set(emptyList(), cut = false)
        assertNull(TransferClipboard.current)
    }

    @Test
    fun `clear empties the clipboard`() {
        TransferClipboard.set(listOf(driveItem("a")), cut = false)
        TransferClipboard.clear()
        assertNull(TransferClipboard.current)
    }
}
