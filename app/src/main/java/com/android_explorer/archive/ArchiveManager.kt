package com.android_explorer.archive

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

/** Thrown when the user cancels an in-flight job. */
class ArchiveCancelledException : IOException("Cancelled")

/** Thrown when an archive is encrypted and no/incorrect password was supplied. */
class PasswordRequiredException(val wrong: Boolean) :
    IOException(if (wrong) "Wrong password" else "Password required")

/** Callbacks the [ArchiveManager] uses to report progress and honour cancellation. */
interface ArchiveListener {
    fun onProgress(
        entry: String,
        entriesDone: Int,
        entriesTotal: Int,
        bytesDone: Long,
        bytesTotal: Long,
    )

    fun isCancelled(): Boolean
}

sealed interface ArchiveResult {
    data class Success(val outputPath: String, val entries: Int) : ArchiveResult
    data class Failure(val message: String) : ArchiveResult
    data object Cancelled : ArchiveResult

    /** The archive is encrypted; [wrong] is true when a password was tried but rejected. */
    data class PasswordRequired(val wrong: Boolean) : ArchiveResult
}

/**
 * Pure archive logic — no Android dependencies. Extracts ZIP / 7z / RAR / TAR(.gz/.bz2/.xz) and
 * standalone GZ/BZ2/XZ; creates ZIP / 7z / TAR(.gz/.bz2/.xz). All I/O is streamed in 64 KB chunks
 * so huge archives never load into memory, and every chunk polls for cancellation.
 */
object ArchiveManager {

    private const val BUFFER = 64 * 1024
    private const val REPORT_EVERY = 512L * 1024L

    // ---------------------------------------------------------------------- extract

    fun extract(
        archive: File,
        destDir: File,
        listener: ArchiveListener,
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
        } catch (_: ArchiveCancelledException) {
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

    private fun extractZip(archive: File, destDir: File, listener: ArchiveListener, password: String?): Int {
        val encrypted = runCatching { net.lingala.zip4j.ZipFile(archive).isEncrypted }.getOrDefault(false)
        if (encrypted) return extractZipEncrypted(archive, destDir, listener, password)

        return extractZipPlain(archive, destDir, listener)
    }

    /** Encrypted ZIP path via Zip4j (ZipCrypto + AES). Progress is per-entry. */
    private fun extractZipEncrypted(archive: File, destDir: File, listener: ArchiveListener, password: String?): Int {
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

    private fun extractZipPlain(archive: File, destDir: File, listener: ArchiveListener): Int {
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = Collections.list(zip.entries)
            val total = entries.filterNot { it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
            var done = 0
            var bytes = 0L
            entries.forEachIndexed { i, entry ->
                checkCancel(listener)
                val out = safeChild(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { fos ->
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

    private fun extractSevenZ(archive: File, destDir: File, listener: ArchiveListener, password: String?): Int {
        // First pass: sum sizes for a determinate bar (headers only, no data read).
        var total = 0L
        var count = 0
        sevenZ(archive, password).use { sz ->
            var e = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory) { total += e.size.coerceAtLeast(0); count++ }
                e = sz.nextEntry
            }
        }
        var done = 0
        var bytes = 0L
        sevenZ(archive, password).use { sz ->
            var entry = sz.nextEntry
            while (entry != null) {
                checkCancel(listener)
                val out = safeChild(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    sz.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { fos ->
                            bytes = pump(input, fos, entry.name, done, count, bytes, total, listener)
                        }
                    }
                    done++
                }
                entry = sz.nextEntry
            }
        }
        return done
    }

    private fun extractRar(archive: File, destDir: File, listener: ArchiveListener, password: String?): Int {
        val rar = if (password.isNullOrEmpty()) Archive(archive) else Archive(archive, password)
        rar.use { arch ->
            val headers = arch.fileHeaders
            if (password.isNullOrEmpty() && headers.any { it.isEncrypted }) {
                throw PasswordRequiredException(false)
            }
            val total = headers.filterNot { it.isDirectory }.sumOf { it.fullUnpackSize.coerceAtLeast(0) }
            var done = 0
            var bytes = 0L
            headers.forEach { header ->
                checkCancel(listener)
                val name = (header.fileName ?: header.fileNameString).replace('\\', '/')
                val out = safeChild(destDir, name)
                if (header.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    val counting = ProgressOutputStream(
                        FileOutputStream(out),
                        onBytes = { delta ->
                            bytes += delta
                            listener.onProgress(name, done, headers.size, bytes, total)
                        },
                        cancelled = listener::isCancelled,
                    )
                    counting.use { arch.extractFile(header, it) }
                    done++
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

    private fun extractTar(archive: File, destDir: File, listener: ArchiveListener, c: Compressor): Int {
        BufferedInputStream(FileInputStream(archive)).use { raw ->
            TarArchiveInputStream(wrapDecompress(raw, c)).use { tar ->
                var done = 0
                var bytes = 0L
                var entry = tar.nextEntry
                while (entry != null) {
                    checkCancel(listener)
                    val out = safeChild(destDir, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
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
    private fun extractSingle(archive: File, destDir: File, listener: ArchiveListener, c: Compressor): Int {
        val outName = archive.name.substringBeforeLast('.', archive.name).ifBlank { "output" }
        val out = safeChild(destDir, outName)
        out.parentFile?.mkdirs()
        BufferedInputStream(FileInputStream(archive)).use { raw ->
            wrapDecompress(raw, c).use { input ->
                FileOutputStream(out).use { fos ->
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
        listener: ArchiveListener,
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
        } catch (_: ArchiveCancelledException) {
            dest.delete()
            ArchiveResult.Cancelled
        } catch (e: Exception) {
            dest.delete()
            ArchiveResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun compressZip(files: List<File>, base: File, dest: File, total: Long, l: ArchiveListener): Int {
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

    private fun compressSevenZ(files: List<File>, base: File, dest: File, total: Long, l: ArchiveListener): Int {
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

    private fun compressTar(files: List<File>, base: File, dest: File, total: Long, l: ArchiveListener, c: Compressor): Int {
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
        listener: ArchiveListener,
        closeInput: Boolean = false,
        closeOutput: Boolean = false,
    ): Long {
        val buf = ByteArray(BUFFER)
        var running = startBytes
        var sinceReport = 0L
        listener.onProgress(entry, entriesDone, entriesTotal, running, bytesTotal)
        try {
            while (true) {
                if (listener.isCancelled()) throw ArchiveCancelledException()
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

    private fun checkCancel(listener: ArchiveListener) {
        if (listener.isCancelled()) throw ArchiveCancelledException()
    }

    /** Guards against zip-slip: the resolved child must stay inside [dir]. */
    private fun safeChild(dir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        val child = File(dir, normalized)
        val dirPath = dir.canonicalPath
        val childPath = child.canonicalPath
        if (childPath != dirPath && !childPath.startsWith(dirPath + File.separator)) {
            throw IOException("Blocked path traversal: $entryName")
        }
        return child
    }

    private class ProgressOutputStream(
        private val delegate: OutputStream,
        private val onBytes: (Long) -> Unit,
        private val cancelled: () -> Boolean,
    ) : OutputStream() {
        override fun write(b: Int) {
            if (cancelled()) throw ArchiveCancelledException()
            delegate.write(b)
            onBytes(1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (cancelled()) throw ArchiveCancelledException()
            delegate.write(b, off, len)
            onBytes(len.toLong())
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
