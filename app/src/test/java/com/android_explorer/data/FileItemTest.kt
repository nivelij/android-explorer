package com.android_explorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for the backend-agnostic [FileItem] / [NodeRef] logic — the core of letting the same
 * UI render local and Google Drive entries. No Android or network dependencies.
 */
class FileItemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- Local items ----

    @Test
    fun `local file exposes its File, absolute path, and name-based extension`() {
        val f = File(tmp.newFolder("d"), "Photo.JPG").apply { writeText("x") }
        val item = FileItem.from(f)

        assertTrue(item.location is NodeRef.Local)
        assertEquals(f, item.file)
        assertEquals(f, item.requireFile())
        assertEquals(f.absolutePath, item.path)
        assertFalse(item.isRemote)
        assertFalse(item.isDirectory)
        // extension is derived from the name, lower-cased
        assertEquals("jpg", item.extension)
        assertTrue(item.isImage)
        assertFalse(item.isPdf)
    }

    @Test
    fun `local directory has blank extension and is not a media type`() {
        val dir = tmp.newFolder("Movies")
        val item = FileItem.from(dir)

        assertTrue(item.isDirectory)
        assertEquals("", item.extension)
        assertFalse(item.isImage)
        assertFalse(item.isEditableText)
    }

    @Test
    fun `editable-text extensions are recognised`() {
        val f = File(tmp.newFolder("t"), "notes.md").apply { writeText("# hi") }
        assertTrue(FileItem.from(f).isEditableText)
    }

    // ---- Drive items ----

    private fun driveItem(
        id: String = "abc123",
        name: String = "report.pdf",
        mimeType: String = "application/pdf",
        isDirectory: Boolean = false,
    ) = FileItem(
        location = NodeRef.Drive(id = id, parentId = "root", mimeType = mimeType),
        name = name,
        isDirectory = isDirectory,
        size = 100L,
        lastModified = 0L,
        isArchive = false,
    )

    @Test
    fun `drive file has no local File and a drive-prefixed stable path`() {
        val item = driveItem(id = "XYZ", name = "report.pdf")

        assertTrue(item.location is NodeRef.Drive)
        assertTrue(item.isRemote)
        assertNull(item.file)
        assertEquals("drive:XYZ", item.path)
        assertEquals("pdf", item.extension)
        assertTrue(item.isPdf)
    }

    @Test
    fun `requireFile throws on a remote item`() {
        val item = driveItem()
        assertThrows(IllegalStateException::class.java) { item.requireFile() }
    }

    @Test
    fun `drive image is detected by name extension`() {
        val item = driveItem(name = "vacation.PNG", mimeType = "image/png")
        assertTrue(item.isImage)
        assertEquals("png", item.extension)
    }

    @Test
    fun `two drive items with the same id share a stable key`() {
        // path is used as the LazyColumn key / selection id, so it must be stable per Drive id.
        assertEquals(driveItem(id = "same").path, driveItem(id = "same", name = "other.pdf").path)
    }
}
