package com.kafianet.control

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku UserService Binder. Runs file operations with Shizuku's shell/root identity.
 */
class ShizukuFileService : IShizukuFileService.Stub() {

    private data class CopyEntry(val file: File, val relativePath: String, val directory: Boolean, val sizeBytes: Long = 0L)
    private val copyCancelled = AtomicBoolean(false)

    override fun prepareCopy() { copyCancelled.set(false) }
    override fun cancelCopy() { copyCancelled.set(true) }

    override fun destroy() {
        System.exit(0)
    }

    override fun ping(): Boolean = true

    override fun pathType(path: String): Int = try {
        val file = File(path).canonicalFile
        when {
            !file.exists() -> 0
            file.isFile -> 1
            file.isDirectory -> 2
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    override fun listChildren(path: String): Array<String> = try {
        val directory = File(path).canonicalFile
        if (!directory.isDirectory) emptyArray()
        else directory.listFiles()
            ?.map { it.absolutePath }
            ?.toTypedArray()
            ?: emptyArray()
    } catch (_: Exception) {
        emptyArray()
    }

    override fun fileLength(path: String): Long = try {
        val file = File(path).canonicalFile
        if (file.isFile) file.length() else -1L
    } catch (_: Exception) {
        -1L
    }

    override fun openFileForRead(path: String): ParcelFileDescriptor? = try {
        val file = File(path).canonicalFile
        if (!file.isFile) null
        else ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (_: Exception) {
        null
    }

    override fun copyDirectory(source: String, destination: String): Boolean {
        copyCancelled.set(false)
        return copyDirectoryInternal(source, destination, null).startsWith("SUCCESS:")
    }

    override fun copyDirectoryWithProgressAndVerify(
        source: String,
        destination: String,
        listener: IShizukuProgressListener?
    ): String = copyDirectoryInternal(source, destination, listener)

    override fun moveDirectoryWithProgressAndVerify(
        source: String,
        destination: String,
        listener: IShizukuProgressListener?
    ): String = copyDirectoryInternal(source, destination, listener, moveFiles = true)

    private fun copyDirectoryInternal(
        source: String,
        destination: String,
        listener: IShizukuProgressListener?,
        moveFiles: Boolean = false
    ): String {
        var temporaryFile: File? = null
        return try {
            val sourceRoot = File(source).canonicalFile
            val destinationRoot = File(destination).canonicalFile
            if (!sourceRoot.isDirectory) throw IOException("مصدر النسخ غير موجود أو غير مجلد: $sourceRoot")
            if (sourceRoot == destinationRoot ||
                destinationRoot.path.startsWith(sourceRoot.path + File.separator)
            ) {
                throw IOException("وجهة النسخ تقع داخل مجلد المصدر؛ أُوقف النسخ لمنع التكرار")
            }
            if (destinationRoot.exists() && !destinationRoot.isDirectory) {
                throw IOException("مسار الوجهة موجود لكنه ليس مجلدًا: $destinationRoot")
            }

            // Pass 1: enumerate the complete tree and calculate the exact file/byte totals.
            // Keep the manifest so the copy pass does not walk every directory a second time.
            val sourcePrefix = sourceRoot.path.trimEnd(File.separatorChar) + File.separator
            val entries = ArrayList<CopyEntry>()
            var totalBytes = 0L
            var totalFiles = 0
            var scanFiles = 0
            var lastScanUpdate = System.currentTimeMillis()

            fun scan(node: File, depth: Int) {
                if (copyCancelled.get()) throw CancellationException("أوقف المستخدم النسخ أثناء حساب الحجم")
                if (depth > MAX_DEPTH) throw IOException("تداخل المجلدات تجاوز الحد $MAX_DEPTH")
                val canonical = node.canonicalFile
                if (canonical.path != sourceRoot.path && !canonical.path.startsWith(sourcePrefix)) {
                    throw IOException("رُفض عنصر خارج مجلد المصدر: $node")
                }
                if (!canonical.exists()) throw IOException("اختفى عنصر من المصدر أثناء حساب الحجم: $canonical")
                val relative = if (canonical == sourceRoot) "" else
                    canonical.path.removePrefix(sourcePrefix).replace(File.separatorChar, '/')

                when {
                    canonical.isDirectory -> {
                        if (relative.isNotEmpty()) entries.add(CopyEntry(canonical, relative, true))
                        val children = canonical.listFiles()
                            ?: throw IOException("تعذّرت قراءة محتويات المجلد: $canonical")
                        children.forEach { scan(it, depth + 1) }
                    }
                    canonical.isFile -> {
                        if (relative.isBlank()) throw IOException("مسار ملف المصدر غير صالح: $canonical")
                        val size = canonical.length()
                        if (size < 0L) throw IOException("تعذّرت قراءة حجم الملف: $relative")
                        totalBytes = Math.addExact(totalBytes, size)
                        if (totalBytes > MAX_TOTAL_BYTES) throw IOException("حجم المصدر يتجاوز الحد الآمن")
                        totalFiles++
                        if (totalFiles > MAX_FILES) throw IOException("عدد الملفات يتجاوز الحد المسموح ($MAX_FILES)")
                        entries.add(CopyEntry(canonical, relative, false, size))
                        scanFiles++
                        val now = System.currentTimeMillis()
                        if (now - lastScanUpdate >= 200L) {
                            runCatching {
                                listener?.onProgress(0L, 0L, scanFiles, 0, "__KAFIANET_SCAN__:$relative")
                            }
                            lastScanUpdate = now
                        }
                    }
                    else -> throw IOException("عنصر مصدر ليس ملفًا أو مجلدًا صالحًا: $canonical")
                }
            }

            scan(sourceRoot, 0)
            if (totalFiles == 0) throw IOException("مجلد المصدر لا يحتوي ملفات قابلة للنسخ")
            if (copyCancelled.get()) throw CancellationException("أوقف المستخدم النسخ بعد حساب الحجم")

            runCatching {
                listener?.onProgress(
                    0L, totalBytes, 0, totalFiles,
                    "__KAFIANET_SCAN__:اكتمل حساب الحجم الكامل؛ جارٍ بدء النسخ"
                )
            }

            if (!destinationRoot.exists() && !destinationRoot.mkdirs()) {
                throw IOException("تعذّر إنشاء مجلد الوجهة: $destinationRoot")
            }

            // Pass 2: copy from the prepared manifest with the final totals known from byte 1.
            var bytesDone = 0L
            var filesDone = 0
            var lastUpdate = System.currentTimeMillis()
            val buffer = ByteArray(BUFFER_SIZE)

            fun notifyProgress(current: String, complete: Boolean = false) {
                runCatching {
                    listener?.onProgress(
                        bytesDone,
                        totalBytes,
                        filesDone,
                        totalFiles,
                        if (complete) "" else current
                    )
                }
            }

            for (entry in entries) {
                if (copyCancelled.get()) throw CancellationException("أوقف المستخدم النسخ")
                val target = File(destinationRoot, entry.relativePath).canonicalFile
                if (!target.path.startsWith(destinationRoot.path + File.separator)) {
                    throw IOException("مسار الوجهة غير آمن: ${entry.relativePath}")
                }

                if (entry.directory) {
                    if (target.exists() && !target.isDirectory) {
                        throw IOException("عنصر الوجهة ليس مجلدًا: $target")
                    }
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("تعذّر إنشاء مجلد الوجهة: $target")
                    }
                    continue
                }

                if (!entry.file.isFile) throw IOException("اختفى ملف المصدر بعد حساب الحجم: ${entry.relativePath}")
                val expectedLength = entry.sizeBytes
                if (entry.file.length() != expectedLength) {
                    throw IOException("تغيّر حجم المصدر بعد حساب الحجم: ${entry.relativePath}")
                }
                val parent = target.parentFile ?: throw IOException("مجلد الوجهة غير صالح: $target")
                if (!parent.exists() && !parent.mkdirs()) throw IOException("تعذّر إنشاء مجلد: $parent")
                if (target.exists() && target.isDirectory) throw IOException("وجهة الملف مجلد وليست ملفًا: $target")

                var movedWithoutCopy = false
                if (moveFiles) {
                    // FileStore equality is provider-specific; compare its stable volume name/type
                    // rather than object identity so same-volume Android emulated paths can rename.
                    val sameStore = runCatching {
                        val sourceStore = Files.getFileStore(entry.file.toPath())
                        val targetStore = Files.getFileStore(parent.toPath())
                        sourceStore.name() == targetStore.name() && sourceStore.type() == targetStore.type()
                    }.getOrDefault(false)
                    if (sameStore) {
                        try {
                            Files.move(
                                entry.file.toPath(),
                                target.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING
                            )
                            movedWithoutCopy = !entry.file.exists()
                        } catch (_: AtomicMoveNotSupportedException) {
                            try {
                                Files.move(entry.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                movedWithoutCopy = !entry.file.exists()
                            } catch (_: IOException) {
                                movedWithoutCopy = !entry.file.exists() && target.isFile && target.length() == expectedLength
                            }
                        } catch (_: IOException) {
                            movedWithoutCopy = !entry.file.exists() && target.isFile && target.length() == expectedLength
                        }
                    }
                }

                if (!movedWithoutCopy) {
                    if (!entry.file.isFile || entry.file.length() != expectedLength) {
                        throw IOException("اختفى الملف أو تغيّر حجمه قبل نسخه: ${entry.relativePath}")
                    }
                    val tempFile = File(parent, ".${target.name}.kafianet-${System.nanoTime()}.partial")
                    temporaryFile = tempFile
                    var fileBytes = 0L
                    FileInputStream(entry.file).use { input ->
                        FileOutputStream(tempFile, false).use { output ->
                            while (true) {
                                if (copyCancelled.get()) throw CancellationException("أوقف المستخدم النسخ أثناء كتابة الملف")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                fileBytes += count
                                bytesDone += count
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= 250L) {
                                    notifyProgress(if (moveFiles) "__KAFIANET_COPY__:${entry.relativePath}" else entry.relativePath)
                                    lastUpdate = now
                                }
                            }
                        }
                    }

                    if (fileBytes != expectedLength || entry.file.length() != expectedLength) {
                        throw IOException("حجم المصدر تغيّر أثناء النسخ: ${entry.relativePath} (المتوقع $expectedLength، المقروء $fileBytes)")
                    }
                    if (!tempFile.isFile || tempFile.length() != expectedLength) {
                        throw IOException("فشل التحقق من الملف المؤقت: ${entry.relativePath}")
                    }
                    if (copyCancelled.get()) throw CancellationException("أوقف المستخدم النسخ قبل اعتماد الملف")

                    try {
                        Files.move(
                            tempFile.toPath(),
                            target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    temporaryFile = null
                } else {
                    // This was a same-filesystem rename: payload bytes were not re-copied.
                    bytesDone = Math.addExact(bytesDone, expectedLength)
                }

                if (!target.isFile || target.length() != expectedLength) {
                    throw IOException("الملف لم يصل إلى الوجهة بالحجم الصحيح: ${entry.relativePath}")
                }
                // For the fallback-copy case, remove staging only after destination verification.
                if (moveFiles && !movedWithoutCopy) runCatching { entry.file.delete() }
                filesDone++
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 250L || filesDone == totalFiles) {
                    notifyProgress(
                        when {
                            !moveFiles -> entry.relativePath
                            movedWithoutCopy -> "__KAFIANET_MOVE__:${entry.relativePath}"
                            else -> "__KAFIANET_COPY__:${entry.relativePath}"
                        }
                    )
                    lastUpdate = now
                }
            }

            if (copyCancelled.get()) throw CancellationException("أوقف المستخدم النسخ")
            if (filesDone != totalFiles || bytesDone != totalBytes) {
                throw IOException("لم يتطابق إجمالي النسخ مع الفحص الأولي: ملفات $filesDone/$totalFiles، بايتات $bytesDone/$totalBytes")
            }
            notifyProgress("", complete = true)
            "SUCCESS:$bytesDone:$filesDone"
        } catch (error: Exception) {
            runCatching { temporaryFile?.delete() }
            if (copyCancelled.get() || error is CancellationException) "CANCELLED: تم إيقاف النسخ"
            else "${error.javaClass.simpleName}: ${error.message ?: "خطأ غير محدد"}"
        }
    }

    private companion object {
        const val MAX_FILES = 50_000
        const val MAX_DEPTH = 64
        const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L * 1024L
        const val BUFFER_SIZE = 1024 * 1024
    }
}
