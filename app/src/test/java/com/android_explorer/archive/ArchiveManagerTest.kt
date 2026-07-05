package com.android_explorer.archive

import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JVM unit tests for the pure archive engine (no Android dependencies). */
class ArchiveManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noop = object : ArchiveListener {
        override fun onProgress(entry: String, entriesDone: Int, entriesTotal: Int, bytesDone: Long, bytesTotal: Long) {}
        override fun isCancelled() = false
    }

    /** Builds src/{readme.txt, docs/notes.txt, images/pic.bin} with known content. */
    private fun sampleTree(): File {
        val src = tmp.newFolder("src")
        File(src, "readme.txt").writeText("hello android_explorer")
        File(src, "docs").mkdirs()
        File(src, "docs/notes.txt").writeText("a".repeat(5000))
        File(src, "images").mkdirs()
        File(src, "images/pic.bin").writeBytes(ByteArray(4096) { (it % 256).toByte() })
        return src
    }

    private fun assertTreeEquals(expected: File, actual: File) {
        val exp = expected.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(expected).path }.toList()
        val act = actual.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(actual).path }.toList()
        assertEquals("file count", exp.size, act.size)
        exp.zip(act).forEach { (e, a) ->
            assertEquals("relative path", e.relativeTo(expected).path, a.relativeTo(actual).path)
            assertArrayEquals("content of ${e.name}", e.readBytes(), a.readBytes())
        }
    }

    @Test
    fun zipRoundTrip() {
        val src = sampleTree()
        val zip = File(tmp.root, "out.zip")
        val c = ArchiveManager.compress(listOf(src), zip, ArchiveType.ZIP, noop)
        assertTrue("compress succeeded", c is ArchiveResult.Success)

        val out = tmp.newFolder("zip_out")
        val e = ArchiveManager.extract(zip, out, noop)
        assertTrue("extract succeeded", e is ArchiveResult.Success)
        assertTreeEquals(src, File(out, "src"))
    }

    @Test
    fun sevenZRoundTrip() {
        val src = sampleTree()
        val sevenZ = File(tmp.root, "out.7z")
        assertTrue(ArchiveManager.compress(listOf(src), sevenZ, ArchiveType.SEVEN_Z, noop) is ArchiveResult.Success)

        val out = tmp.newFolder("sevenz_out")
        assertTrue(ArchiveManager.extract(sevenZ, out, noop) is ArchiveResult.Success)
        assertTreeEquals(src, File(out, "src"))
    }

    @Test
    fun tarGzRoundTrip() {
        val src = sampleTree()
        val tgz = File(tmp.root, "out.tar.gz")
        assertTrue(ArchiveManager.compress(listOf(src), tgz, ArchiveType.TAR_GZ, noop) is ArchiveResult.Success)

        val out = tmp.newFolder("tgz_out")
        assertTrue(ArchiveManager.extract(tgz, out, noop) is ArchiveResult.Success)
        assertTreeEquals(src, File(out, "src"))
    }

    @Test
    fun encryptedZip_correctPassword_extracts() {
        val enc = makeEncryptedZip("secret")
        val out = tmp.newFolder("enc_out")
        val result = ArchiveManager.extract(enc, out, noop, password = "secret")
        assertTrue("correct password extracts", result is ArchiveResult.Success)
        assertEquals("hello android_explorer", File(out, "readme.txt").readText())
    }

    @Test
    fun encryptedZip_noPassword_reportsPasswordRequired() {
        val enc = makeEncryptedZip("secret")
        val out = tmp.newFolder("enc_out2")
        val result = ArchiveManager.extract(enc, out, noop, password = null)
        assertTrue(result is ArchiveResult.PasswordRequired)
        assertFalse("not flagged wrong when none tried", (result as ArchiveResult.PasswordRequired).wrong)
    }

    @Test
    fun encryptedZip_wrongPassword_reportsWrong() {
        val enc = makeEncryptedZip("secret")
        val out = tmp.newFolder("enc_out3")
        val result = ArchiveManager.extract(enc, out, noop, password = "nope")
        assertTrue(result is ArchiveResult.PasswordRequired)
        assertTrue((result as ArchiveResult.PasswordRequired).wrong)
    }

    @Test
    fun zipSlip_isBlocked() {
        // Craft a malicious zip whose entry escapes the target directory.
        val evil = File(tmp.root, "evil.zip")
        ZipArchiveOutputStream(evil).use { z ->
            z.putArchiveEntry(ZipArchiveEntry("../escaped.txt"))
            z.write("pwned".toByteArray())
            z.closeArchiveEntry()
        }
        val out = tmp.newFolder("slip_out")
        val result = ArchiveManager.extract(evil, out, noop)
        assertTrue("traversal must fail", result is ArchiveResult.Failure)
        assertFalse("no file written outside target", File(out.parentFile, "escaped.txt").exists())
    }

    @Test
    fun listEntries_zip_listsWithoutExtracting() {
        val src = sampleTree()
        val zip = File(tmp.root, "list.zip")
        assertTrue(ArchiveManager.compress(listOf(src), zip, ArchiveType.ZIP, noop) is ArchiveResult.Success)

        val listing = ArchiveManager.listEntries(zip)
        assertTrue(listing is ArchiveManager.ArchiveListing.Entries)
        val entries = (listing as ArchiveManager.ArchiveListing.Entries).items
        val files = entries.filterNot { it.isDirectory }.map { it.name }
        assertTrue("readme listed", files.any { it.endsWith("readme.txt") })
        assertTrue("nested notes listed", files.any { it.endsWith("docs/notes.txt") })
        // Sizes come from headers (no extraction): notes.txt was 5000 'a's.
        val notes = entries.first { it.name.endsWith("docs/notes.txt") }
        assertEquals(5000L, notes.size)
    }

    @Test
    fun listEntries_encryptedZip_stillListsNames() {
        // ZIP encrypts content, not the central directory — names remain readable without a password.
        val enc = makeEncryptedZip("secret")
        val listing = ArchiveManager.listEntries(enc)
        assertTrue(listing is ArchiveManager.ArchiveListing.Entries)
        val files = (listing as ArchiveManager.ArchiveListing.Entries).items.map { it.name }
        assertTrue(files.any { it.endsWith("readme.txt") })
    }

    @Test
    fun formatDetection() {
        assertEquals(ArchiveType.TAR_GZ, ArchiveType.fromFileName("backup.tar.gz"))
        assertEquals(ArchiveType.TAR_GZ, ArchiveType.fromFileName("BACKUP.TGZ"))
        assertEquals(ArchiveType.SEVEN_Z, ArchiveType.fromFileName("data.7z"))
        assertEquals(ArchiveType.RAR, ArchiveType.fromFileName("movie.rar"))
        assertEquals(ArchiveType.ZIP, ArchiveType.fromFileName("photos.zip"))
        assertEquals(null, ArchiveType.fromFileName("notes.txt"))
    }

    private fun makeEncryptedZip(password: String): File {
        val plain = File(tmp.newFolder("payload"), "readme.txt").apply { writeText("hello android_explorer") }
        val enc = File(tmp.root, "enc.zip")
        net.lingala.zip4j.ZipFile(enc, password.toCharArray()).use { zf ->
            val params = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
            }
            zf.addFile(plain, params)
        }
        return enc
    }
}
