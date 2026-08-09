package com.android_explorer.archive

import com.android_explorer.job.JobCancelledException
import com.android_explorer.job.JobListener
import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

/** Thrown when an archive is encrypted and no/incorrect password was supplied. */
class PasswordRequiredException(val wrong: Boolean) :
    IOException(if (wrong) "Wrong password" else "Password required")

sealed interface ArchiveResult {
    data class Success(val outputPath: String, val entries: Int) : ArchiveResult
    data class Failure(val message: String) : ArchiveResult
    data object Cancelled : ArchiveResult

    /** The archive is encrypted; [wrong] is true when a password was tried but rejected. */
    data class PasswordRequired(val wrong: Boolean) : ArchiveResult
}

/**
 * Pure archive logic — no Android dependencies. Extracts ZIP / 7z / RAR / TAR(.gz/.bz2/.xz) and
 * standalone GZ/BZ2/XZ; creates ZIP / 7z / TAR(.gz/.bz2/.xz). All I/O is streamed in 256 KB chunks
 * so huge archives never load into memory, and every chunk polls for cancellation.
 *
 * Every file sink is wrapped in a [BufferedOutputStream]: all app-visible Android storage is
 * FUSE-backed, so throughput is dominated by syscall count, and libraries like junrar write in
 * whatever granularity they please (down to a single byte).
 */
object ArchiveManager {

    private const val BUFFER = 256 * 1024
    private const val REPORT_EVERY = 512L * 1024L

    /** Reused copy buffer — [pump] is a leaf call, so one buffer per thread is enough. */
    private val scratch = ThreadLocal.withInitial { ByteArray(BUFFER) }

    /** Buffered file sink; [BufferedOutputStream] passes big writes straight through. */
    private fun sink(file: File): OutputStream = BufferedOutputStream(FileOutputStream(file), BUFFER)

    // ---------------------------------------------------------------------- extract

    fun extract(
        archive: File,
        destDir: File,
        listener: JobListener,
        password: String? = null,
    ): ArchiveResult {
        return try {
            val type = ArchiveType.fromFileName(archive.name)
                ?: return ArchiveResult.Failure("Unsupported archive: ${archive.name}")
            val entries = when (type) {
                ArchiveType.ZIP -> extractZip(archive, destDir, listener, password)
                ArchiveType.SEVEN_Z -> extractSevenZ(archive, destDir, listener, password)
                ArchiveType.RAR -> extractRar(archive, destDir, listener, password)
                ArchiveType.TAR -> extractTar(archive, destDir, listener, Compressor.NONE)
                ArchiveType.TAR_GZ -> extractTar(archive, destDir, listener, Compressor.GZ)
                ArchiveType.TAR_BZ2 -> extractTar(archive, destDir, listener, Compressor.BZ2)
                ArchiveType.TAR_XZ -> extractTar(archive, destDir, listener, Compressor.XZ)
                ArchiveType.GZ -> extractSingle(archive, destDir, listener, Compressor.GZ)
                ArchiveType.BZ2 -> extractSingle(archive, destDir, listener, Compressor.BZ2)
                ArchiveType.XZ -> extractSingle(archive, destDir, listener, Compressor.XZ)
            }
            ArchiveResult.Success(destDir.absolutePath, entries)
        } catch (_: JobCancelledException) {
            ArchiveResult.Cancelled
        } catch (e: PasswordRequiredException) {
            ArchiveResult.PasswordRequired(e.wrong)
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            ArchiveResult.PasswordRequired(!password.isNullOrEmpty())
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // Wrong-password symptoms differ per library (bad CRC / checksum / "password").
            if (!password.isNullOrEmpty() &&
                (msg.contains("password", true) || msg.contains("checksum", true) ||
                    msg.contains("crc", true) || msg.contains("corrupt", true))
            ) {
                ArchiveResult.PasswordRequired(true)
            } else {
                ArchiveResult.Failure(msg.ifBlank { e.javaClass.simpleName })
            }
        }
    }

    // ---------------------------------------------------------------------- list (preview)

    /** One row in an archive preview: a path inside the archive plus its uncompressed size. */
    data class ArchiveEntryInfo(val name: String, val size: Long, val isDirectory: Boolean)

    sealed interface ArchiveListing {
        data class Entries(val items: List<ArchiveEntryInfo>, val truncated: Boolean) : ArchiveListing
        /** The archive's entry list can't be read without a password (encrypted headers). */
        data object Encrypted : ArchiveListing
        data class Failure(val message: String) : ArchiveListing
    }

    /**
     * Reads an archive's entry list WITHOUT extracting. Entry names are readable for most encrypted
     * ZIP/RAR archives (only the content is encrypted); 7z/RAR with encrypted headers report
     * [ArchiveListing.Encrypted]. Capped at [limit] entries so pathological archives stay bounded.
     */
    fun listEntries(archive: File, limit: Int = 5000): ArchiveListing {
        return try {
            val type = ArchiveType.fromFileName(archive.name)
                ?: return ArchiveListing.Failure("Unsupported archive: ${archive.name}")
            val all = when (type) {
                ArchiveType.ZIP -> listZip(archive)
                ArchiveType.SEVEN_Z -> listSevenZ(archive)
                ArchiveType.RAR -> listRar(archive)
                ArchiveType.TAR -> listTar(archive, Compressor.NONE)
                ArchiveType.TAR_GZ -> listTar(archive, Compressor.GZ)
                ArchiveType.TAR_BZ2 -> listTar(archive, Compressor.BZ2)
                ArchiveType.TAR_XZ -> listTar(archive, Compressor.XZ)
                ArchiveType.GZ, ArchiveType.BZ2, ArchiveType.XZ ->
                    listOf(ArchiveEntryInfo(archive.name.substringBeforeLast('.', archive.name), -1L, false))
            }
            ArchiveListing.Entries(all.take(limit), truncated = all.size > limit)
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            ArchiveListing.Encrypted
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("password", true) || msg.contains("encrypt", true) ||
                msg.contains("header", true) && msg.contains("crypt", true)
            ) {
                ArchiveListing.Encrypted
            } else {
                ArchiveListing.Failure(msg.ifBlank { e.javaClass.simpleName })
            }
        }
    }

    private fun listZip(archive: File): List<ArchiveEntryInfo> =
        ZipFile.builder().setFile(archive).get().use { zip ->
            Collections.list(zip.entries).map {
                ArchiveEntryInfo(it.name, it.size.coerceAtLeast(0), it.isDirectory)
            }
        }

    private fun listSevenZ(archive: File): List<ArchiveEntryInfo> {
        val out = ArrayList<ArchiveEntryInfo>()
        sevenZ(archive, null).use { sz ->
            var e = sz.nextEntry
            while (e != null) {
                out += ArchiveEntryInfo(e.name, e.size.coerceAtLeast(0), e.isDirectory)
                e = sz.nextEntry
            }
        }
        return out
    }

    private fun listRar(archive: File): List<ArchiveEntryInfo> =
        Archive(archive).use { arch ->
            arch.fileHeaders.map { h ->
                val name = (h.fileName ?: h.fileNameString).replace('\\', '/')
                ArchiveEntryInfo(name, h.fullUnpackSize.coerceAtLeast(0), h.isDirectory)
            }
        }

    private fun listTar(archive: File, c: Compressor): List<ArchiveEntryInfo> {
        val out = ArrayList<ArchiveEntryInfo>()
        BufferedInputStream(FileInputStream(archive)).use { raw ->
            TarArchiveInputStream(wrapDecompress(raw, c)).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    out += ArchiveEntryInfo(e.name, e.size.coerceAtLeast(0), e.isDirectory)
                    e = tar.nextEntry
                }
            }
        }
        return out
    }

    private fun extractZip(archive: File, destDir: File, listener: JobListener, password: String?): Int {
        val encrypted = runCatching { net.lingala.zip4j.ZipFile(archive).isEncrypted }.getOrDefault(false)
        if (encrypted) return extractZipEncrypted(archive, destDir, listener, password)

        return extractZipPlain(archive, destDir, listener)
    }

    /** Encrypted ZIP path via Zip4j (ZipCrypto + AES). Progress is per-entry. */
    private fun extractZipEncrypted(archive: File, destDir: File, listener: JobListener, password: String?): Int {
        if (password.isNullOrEmpty()) throw PasswordRequiredException(false)
        val zip = net.lingala.zip4j.ZipFile(archive).apply { setPassword(password.toCharArray()) }
        try {
            val headers = zip.fileHeaders
            val total = headers.filterNot { it.isDirectory }.sumOf { it.uncompressedSize.coerceAtLeast(0) }
            var done = 0
            var bytes = 0L
            headers.forEach { h ->
                checkCancel(listener)
                // Zip4j resolves paths safely under destDir.
                if (!h.isDirectory) {
                    zip.extractFile(h, destDir.absolutePath)
                    bytes += h.uncompressedSize.coerceAtLeast(0)
                    done++
                    listener.onProgress(h.fileName, done, headers.size, bytes, total)
                }
            }
            return done
        } catch (e: net.lingala.zip4j.exception.ZipException) {
            if (e.type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD ||
                (e.message?.contains("password", true) == true)
            ) {
                throw PasswordRequiredException(true)
            }
            throw e
        } finally {
            zip.close()
        }
    }

    private fun extractZipPlain(archive: File, destDir: File, listener: JobListener): Int {
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = Collections.list(zip.entries)
            val total = entries.filterNot { it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
            val dest = DestRoot(destDir)
            var done = 0
            var bytes = 0L
            entries.forEachIndexed { i, entry ->
                checkCancel(listener)
                val out = dest.child(entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    dest.ensureParent(out)
                    zip.getInputStream(entry).use { input ->
                        sink(out).use { fos ->
                            bytes = pump(input, fos, entry.name, i, entries.size, bytes, total, listener)
                        }
                    }
                    done++
                }
            }
            return done
        }
    }

    private fun sevenZ(archive: File, password: String?): SevenZFile {
        val builder = SevenZFile.builder().setFile(archive)
        if (!password.isNullOrEmpty()) builder.setPassword(password.toCharArray())
        return builder.get()
    }

    private fun extractSevenZ(archive: File, destDir: File, listener: JobListener, password: String?): Int {
        var done = 0
        var bytes = 0L
        sevenZ(archive, password).use { sz ->
            // Totals come from the parsed headers, so there is no second open and no second pass.
            val entries = sz.entries
            val count = entries.count { !it.isDirectory }
            val total = entries.filterNot { it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }

            val dest = DestRoot(destDir)
            // Read STRICTLY sequentially: getNextEntry() + SevenZFile.read(). Never
            // getInputStream(entry) — that takes the random-access path (buildDecodingStream with
            // isRandomAccess=true), which re-decodes a solid block from its start for every entry.
            // 7z archives are solid by default, so that turns extraction into O(n^2) decompression
            // and is what made large 7z files take hours.
            val stream = object : InputStream() {
                override fun read(): Int = sz.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
            }
            var entry = sz.nextEntry
            while (entry != null) {
                checkCancel(listener)
                val out = dest.child(entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    dest.ensureParent(out)
                    sink(out).use { fos ->
                        bytes = pump(stream, fos, entry.name, done, count, bytes, total, listener)
                    }
                    done++
                }
                entry = sz.nextEntry
            }
        }
        return done
    }

    private fun extractRar(archive: File, destDir: File, listener: JobListener, password: String?): Int {
        val rar = if (password.isNullOrEmpty()) Archive(archive) else Archive(archive, password)
        rar.use { arch ->
            val headers = arch.fileHeaders
            if (password.isNullOrEmpty() && headers.any { it.isEncrypted }) {
                throw PasswordRequiredException(false)
            }
            val total = headers.filterNot { it.isDirectory }.sumOf { it.fullUnpackSize.coerceAtLeast(0) }
            val dest = DestRoot(destDir)
            var done = 0
            var bytes = 0L
            var sinceReport = 0L
            headers.forEach { header ->
                checkCancel(listener)
                val name = (header.fileName ?: header.fileNameString).replace('\\', '/')
                val out = dest.child(name)
                if (header.isDirectory) {
                    out.mkdirs()
                } else {
                    dest.ensureParent(out)
                    val counting = ProgressOutputStream(
                        sink(out),
                        onBytes = { delta ->
                            bytes += delta
                            // junrar decides the write granularity (down to one byte), so throttle
                            // to the same cadence as pump() instead of publishing per write.
                            sinceReport += delta
                            if (sinceReport >= REPORT_EVERY) {
                                listener.onProgress(name, done, headers.size, bytes, total)
                                sinceReport = 0
                            }
                        },
                        cancelled = listener::isCancelled,
                    )
                    counting.use { arch.extractFile(header, it) }
                    done++
                    listener.onProgress(name, done, headers.size, bytes, total)
                }
            }
            return done
        }
    }

    private enum class Compressor { NONE, GZ, BZ2, XZ }

    private fun wrapDecompress(raw: InputStream, c: Compressor): InputStream = when (c) {
        Compressor.NONE -> raw
        Compressor.GZ -> GzipCompressorInputStream(raw)
        Compressor.BZ2 -> BZip2CompressorInputStream(raw)
        Compressor.XZ -> XZCompressorInputStream(raw)
    }

    private fun extractTar(archive: File, destDir: File, listener: JobListener, c: Compressor): Int {
        BufferedInputStream(FileInputStream(archive)).use { raw ->
            TarArchiveInputStream(wrapDecompress(raw, c)).use { tar ->
                val dest = DestRoot(destDir)
                var done = 0
                var bytes = 0L
                var entry = tar.nextEntry
                while (entry != null) {
                    checkCancel(listener)
                    val out = dest.child(entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        dest.ensureParent(out)
                        sink(out).use { fos ->
                            // tar over a compressor is a single stream — total is unknown up front.
                            bytes = pump(tar, fos, entry.name, done, -1, bytes, -1L, listener, closeInput = false)
                        }
                        done++
                    }
                    entry = tar.nextEntry
                }
                return done
            }
        }
    }

    /** A bare .gz/.bz2/.xz wrapping a single file; output name drops the compression suffix. */
    private fun extractSingle(archive: File, destDir: File, listener: JobListener, c: Compressor): Int {
        val outName = archive.name.substringBeforeLast('.', archive.name).ifBlank { "output" }
        val dest = DestRoot(destDir)
        val out = dest.child(outName)
        dest.ensureParent(out)
        BufferedInputStream(FileInputStream(archive)).use { raw ->
            wrapDecompress(raw, c).use { input ->
                sink(out).use { fos ->
                    pump(input, fos, outName, 0, 1, 0L, -1L, listener, closeInput = false)
                }
            }
        }
        return 1
    }

    // ---------------------------------------------------------------------- create

    fun compress(
        sources: List<File>,
        dest: File,
        type: ArchiveType,
        listener: JobListener,
    ): ArchiveResult {
        return try {
            require(sources.isNotEmpty()) { "Nothing selected" }
            val base = sources.first().parentFile ?: dest.parentFile!!
            val files = sources.flatMap { collectFiles(it) }
            val total = files.sumOf { it.length() }
            dest.parentFile?.mkdirs()

            val written = when (type) {
                ArchiveType.ZIP -> compressZip(files, base, dest, total, listener)
                ArchiveType.SEVEN_Z -> compressSevenZ(files, base, dest, total, listener)
                ArchiveType.TAR -> compressTar(files, base, dest, total, listener, Compressor.NONE)
                ArchiveType.TAR_GZ -> compressTar(files, base, dest, total, listener, Compressor.GZ)
                ArchiveType.TAR_BZ2 -> compressTar(files, base, dest, total, listener, Compressor.BZ2)
                ArchiveType.TAR_XZ -> compressTar(files, base, dest, total, listener, Compressor.XZ)
                else -> return ArchiveResult.Failure("Cannot create ${type.label} archives")
            }
            ArchiveResult.Success(dest.absolutePath, written)
        } catch (_: JobCancelledException) {
            dest.delete()
            ArchiveResult.Cancelled
        } catch (e: Exception) {
            dest.delete()
            ArchiveResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun compressZip(files: List<File>, base: File, dest: File, total: Long, l: JobListener): Int {
        ZipArchiveOutputStream(dest).use { zip ->
            var bytes = 0L
            files.forEachIndexed { i, f ->
                checkCancel(l)
                val name = f.toRelativeString(base)
                zip.putArchiveEntry(ZipArchiveEntry(f, name))
                FileInputStream(f).use { input ->
                    bytes = pump(input, zip, name, i, files.size, bytes, total, l, closeOutput = false)
                }
                zip.closeArchiveEntry()
            }
            zip.finish()
        }
        return files.size
    }

    private fun compressSevenZ(files: List<File>, base: File, dest: File, total: Long, l: JobListener): Int {
        SevenZOutputFile(dest).use { sz ->
            var bytes = 0L
            // SevenZOutputFile is not an OutputStream; adapt it so pump() can drive it.
            val adapter = object : OutputStream() {
                override fun write(b: Int) = sz.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = sz.write(b, off, len)
            }
            files.forEachIndexed { i, f ->
                checkCancel(l)
                val name = f.toRelativeString(base)
                sz.putArchiveEntry(sz.createArchiveEntry(f, name))
                FileInputStream(f).use { input ->
                    bytes = pump(input, adapter, name, i, files.size, bytes, total, l, closeOutput = false)
                }
                sz.closeArchiveEntry()
            }
        }
        return files.size
    }

    private fun wrapCompress(raw: OutputStream, c: Compressor): OutputStream = when (c) {
        Compressor.NONE -> raw
        Compressor.GZ -> GzipCompressorOutputStream(raw)
        Compressor.BZ2 -> BZip2CompressorOutputStream(raw)
        Compressor.XZ -> XZCompressorOutputStream(raw)
    }

    private fun compressTar(files: List<File>, base: File, dest: File, total: Long, l: JobListener, c: Compressor): Int {
        BufferedOutputStream(FileOutputStream(dest)).use { fileOut ->
            wrapCompress(fileOut, c).use { compressed ->
                TarArchiveOutputStream(compressed).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    var bytes = 0L
                    files.forEachIndexed { i, f ->
                        checkCancel(l)
                        val name = f.toRelativeString(base)
                        tar.putArchiveEntry(tar.createArchiveEntry(f, name))
                        FileInputStream(f).use { input ->
                            bytes = pump(input, tar, name, i, files.size, bytes, total, l, closeOutput = false)
                        }
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
        return files.size
    }

    // ---------------------------------------------------------------------- helpers

    private fun collectFiles(root: File): List<File> =
        if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList()
        else listOf(root)

    /** Streams input→output, accumulating a global byte total and reporting throttled progress. */
    private fun pump(
        input: InputStream,
        output: OutputStream,
        entry: String,
        entriesDone: Int,
        entriesTotal: Int,
        startBytes: Long,
        bytesTotal: Long,
        listener: JobListener,
        closeInput: Boolean = false,
        closeOutput: Boolean = false,
    ): Long {
        val buf = scratch.get()
        var running = startBytes
        var sinceReport = 0L
        listener.onProgress(entry, entriesDone, entriesTotal, running, bytesTotal)
        try {
            while (true) {
                if (listener.isCancelled()) throw JobCancelledException()
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                running += n
                sinceReport += n
                if (sinceReport >= REPORT_EVERY) {
                    listener.onProgress(entry, entriesDone, entriesTotal, running, bytesTotal)
                    sinceReport = 0
                }
            }
        } finally {
            if (closeInput) input.close()
            if (closeOutput) output.close()
        }
        listener.onProgress(entry, entriesDone, entriesTotal, running, bytesTotal)
        return running
    }

    private fun checkCancel(listener: JobListener) {
        if (listener.isCancelled()) throw JobCancelledException()
    }

    /**
     * Resolves entry names under one destination directory for the life of a job.
     *
     * [File.canonicalPath] is a syscall, so the destination's own path is resolved once per job
     * rather than recomputed identically for every entry, and repeated parent `mkdirs()` calls are
     * collapsed. An archive of many small files otherwise pays several redundant filesystem
     * round-trips before a single byte is written. The per-child canonical check stays — it is what
     * makes the zip-slip guard resistant to symlinks, and it is not the bottleneck.
     */
    private class DestRoot(private val dir: File) {
        private val canonical: String = dir.canonicalPath
        private var lastParent: File? = null

        /** Guards against zip-slip: the resolved child must stay inside [dir]. */
        fun child(entryName: String): File {
            val child = File(dir, entryName.replace('\\', '/'))
            val childPath = child.canonicalPath
            if (childPath != canonical && !childPath.startsWith(canonical + File.separator)) {
                throw IOException("Blocked path traversal: $entryName")
            }
            return child
        }

        /** Creates the entry's parent, skipping the call when it is the same as the last entry's. */
        fun ensureParent(child: File) {
            val parent = child.parentFile ?: return
            if (parent != lastParent) {
                parent.mkdirs()
                lastParent = parent
            }
        }
    }

    private class ProgressOutputStream(
        private val delegate: OutputStream,
        private val onBytes: (Long) -> Unit,
        private val cancelled: () -> Boolean,
    ) : OutputStream() {
        override fun write(b: Int) {
            if (cancelled()) throw JobCancelledException()
            delegate.write(b)
            onBytes(1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (cancelled()) throw JobCancelledException()
            delegate.write(b, off, len)
            onBytes(len.toLong())
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
