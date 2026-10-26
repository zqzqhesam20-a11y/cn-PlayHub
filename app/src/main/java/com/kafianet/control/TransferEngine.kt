package com.kafianet.control

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** Progress is aggregated across the complete transfer, not reset for each file. */
data class TransferProgress(
    val bytesDone: Long = 0L,
    val totalBytes: Long = 0L,
    val filesDone: Int = 0,
    val totalFiles: Int = 0,
    val currentFile: String = "",
    val bytesPerSecond: Long = 0L,
    val phase: String = "جارٍ التحضير"
) {
    val percent: Int
        get() = if (totalBytes > 0L) {
            ((bytesDone.coerceAtMost(totalBytes) * 100.0) / totalBytes).toInt().coerceIn(0, 100)
        } else if (totalFiles > 0 && filesDone >= totalFiles) 100 else 0

    val remainingBytes: Long
        get() = (totalBytes - bytesDone).coerceAtLeast(0L)
}

/**
 * Measures real byte deltas over a minimum time window, rather than dividing all bytes
 * by an arbitrarily short callback interval (which can create huge, false-looking spikes).
 */
class TransferSpeedMeter(private val sampleIntervalMs: Long = 500L) {
    private var initialized = false
    private var sampleAtMs = 0L
    private var sampleBytes = 0L
    private var lastSpeedBytesPerSecond = 0L

    fun reset(bytesDone: Long = 0L, nowMs: Long = SystemClock.elapsedRealtime()) {
        initialized = true
        sampleAtMs = nowMs
        sampleBytes = bytesDone
        lastSpeedBytesPerSecond = 0L
    }

    fun update(bytesDone: Long, nowMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!initialized) {
            reset(bytesDone, nowMs)
            return 0L
        }
        val elapsed = nowMs - sampleAtMs
        if (elapsed >= sampleIntervalMs) {
            val deltaBytes = (bytesDone - sampleBytes).coerceAtLeast(0L)
            lastSpeedBytesPerSecond = if (elapsed > 0L) {
                (deltaBytes.toDouble() * 1000.0 / elapsed.toDouble()).toLong().coerceAtLeast(0L)
            } else 0L
            sampleAtMs = nowMs
            sampleBytes = bytesDone
        }
        return lastSpeedBytesPerSecond
    }
}

data class TransferSourceFile(
    val uri: Uri,
    val relativePath: String,
    val sizeBytes: Long
)

private data class TransferDocumentEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

object CopyEngine {
    private const val BUFFER_SIZE = 1024 * 1024
    private const val MAX_FILES = 50_000
    private const val MAX_DEPTH = 64
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L * 1024L

    suspend fun copyTreeToAppStorage(
        context: Context,
        sourceRoot: DocumentFile,
        destinationParent: File,
        onProgress: (TransferProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val transferContext = currentCoroutineContext()
        fun ensureTransferActive() {
            transferContext.ensureActive()
            if (TransferForegroundService.isCancellationRequested()) {
                throw kotlinx.coroutines.CancellationException("أوقف المستخدم النسخ")
            }
        }
        if (!sourceRoot.exists()) throw IOException("مصدر النسخ لم يعد متاحًا")
        if (!sourceRoot.isDirectory) throw IOException("المصدر المحدد ليس مجلدًا")
        if (!destinationParent.exists() && !destinationParent.mkdirs()) {
            throw IOException("تعذّر إنشاء مجلد الوجهة")
        }
        if (!destinationParent.isDirectory || !destinationParent.canWrite()) {
            throw IOException("مجلد الوجهة غير قابل للكتابة")
        }

        // Pre-scan all files once, calculate exact totals, then copy from that manifest.
        var lastScanAt = SystemClock.elapsedRealtime()
        val manifest = collectFiles(context, sourceRoot) { scannedFiles, currentPath ->
            ensureTransferActive()
            val now = SystemClock.elapsedRealtime()
            if (scannedFiles == 1 || now - lastScanAt >= 200L) {
                onProgress(TransferProgress(
                    filesDone = scannedFiles,
                    totalFiles = 0,
                    currentFile = currentPath,
                    phase = "فحص كامل ملفات USB وحساب الحجم قبل النسخ"
                ))
                lastScanAt = now
            }
        }
        ensureTransferActive()
        if (manifest.isEmpty()) throw IOException("المجلد المحدد لا يحتوي ملفات قابلة للنسخ")

        var totalBytes = 0L
        for (entry in manifest) {
            totalBytes = Math.addExact(totalBytes, entry.sizeBytes)
            if (totalBytes > MAX_TOTAL_BYTES) throw IOException("حجم البيانات يتجاوز الحد الآمن")
        }
        val totalFiles = manifest.size
        onProgress(TransferProgress(
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            phase = "اكتمل حساب الحجم الكامل — بدء النسخ السريع من USB"
        ))

        val folderName = safeRelativeSegment(sourceRoot.name ?: "KafiaNet-Transfer")
        var uniqueName = folderName
        var suffix = 2
        while (File(destinationParent, uniqueName).exists() && suffix < 10_000) {
            uniqueName = "$folderName ($suffix)"
            suffix++
        }
        val destinationRoot = File(destinationParent, uniqueName)
        if (!destinationRoot.exists() && !destinationRoot.mkdirs()) {
            throw IOException("تعذّر إنشاء مجلد النسخ: ${destinationRoot.path}")
        }
        val canonicalRoot = destinationRoot.canonicalFile
        val startedAt = SystemClock.elapsedRealtime()
        val speedMeter = TransferSpeedMeter().apply { reset(0L, startedAt) }
        var bytesDone = 0L
        var filesDone = 0
        var lastProgressAt = startedAt
        var lastCurrentFile = ""
        val buffer = ByteArray(BUFFER_SIZE)

        fun reportProgress(currentFile: String, complete: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            onProgress(TransferProgress(
                bytesDone = bytesDone,
                totalBytes = totalBytes,
                filesDone = filesDone,
                totalFiles = totalFiles,
                currentFile = currentFile,
                bytesPerSecond = if (complete) 0L else speedMeter.update(bytesDone, now),
                phase = if (complete) "اكتمل النسخ إلى مجلد التجهيز" else "نسخ من USB بعد حساب الحجم الكامل"
            ))
        }

        fun copyFile(source: TransferSourceFile) {
            val relativePath = source.relativePath
            val expectedSourceBytes = source.sizeBytes
            ensureTransferActive()
            // The full scan already recorded this file's length. Do not issue multiple
            // additional SAF metadata queries per file here; openInputStream and byte-count
            // verification below report files that disappeared or changed after the scan.
            val target = File(destinationRoot, relativePath).canonicalFile
            if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
                throw IOException("مسار ملف غير آمن: $relativePath")
            }
            val parent = target.parentFile ?: throw IOException("وجهة الملف غير صالحة: $relativePath")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("تعذّر إنشاء مجلد: ${parent.path}")
            if (target.exists() && target.isDirectory) throw IOException("وجهة الملف مجلد وليست ملفًا: $relativePath")

            val tempFile = File(parent, ".${target.name}.kafianet-${System.nanoTime()}.partial")
            var fileBytesWritten = 0L
            try {
                val input = context.contentResolver.openInputStream(source.uri)
                    ?: throw IOException("تعذّر فتح المصدر: $relativePath")
                input.use { inputStream ->
                    FileOutputStream(tempFile, false).use { output ->
                        while (true) {
                            ensureTransferActive()
                            val count = inputStream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            fileBytesWritten += count
                            bytesDone += count
                            lastCurrentFile = relativePath
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressAt >= 250L) {
                                reportProgress(lastCurrentFile)
                                lastProgressAt = now
                            }
                        }
                    }
                }
                if (fileBytesWritten != expectedSourceBytes ||
                    !tempFile.isFile || tempFile.length() != fileBytesWritten
                ) {
                    throw IOException(
                        "فشل التحقق من ملف USB بعد النسخ: $relativePath " +
                            "(المقروء $fileBytesWritten، المتوقع $expectedSourceBytes، الوجهة ${tempFile.length()})"
                    )
                }
                ensureTransferActive()
                try {
                    java.nio.file.Files.move(
                        tempFile.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        tempFile.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
                if (!target.isFile || target.length() != fileBytesWritten) {
                    throw IOException("الملف لم يصل إلى وجهة التجهيز بالحجم الصحيح: $relativePath")
                }
                filesDone++
                lastCurrentFile = relativePath
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 250L || filesDone == totalFiles) {
                    reportProgress(lastCurrentFile)
                    lastProgressAt = now
                }
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }

        try {
            for (entry in manifest) {
                ensureTransferActive()
                copyFile(entry)
            }
            ensureTransferActive()
            if (filesDone != totalFiles || bytesDone != totalBytes) {
                throw IOException("لم يطابق النسخ إجمالي الفحص الأولي: ملفات $filesDone/$totalFiles، بايتات $bytesDone/$totalBytes")
            }
            reportProgress(lastCurrentFile, complete = true)
            destinationRoot
        } catch (error: Exception) {
            runCatching { destinationRoot.deleteRecursively() }
            throw error
        }
    }

    /**
     * Reads SAF children using one DocumentsContract query per directory, collecting display
     * name, type and size together. AndroidX DocumentFile calls can make repeated provider
     * queries for those fields, which becomes expensive on USB trees containing many files.
     * A DocumentFile fallback keeps compatibility with providers that reject the tree query.
     */
    fun collectFiles(
        context: Context,
        root: DocumentFile,
        onScanned: (scannedFiles: Int, currentPath: String) -> Unit = { _, _ -> }
    ): List<TransferSourceFile> {
        val result = ArrayList<TransferSourceFile>()
        val treeUri = root.uri

        fun fallbackChildren(directoryUri: Uri): List<TransferDocumentEntry> {
            val directory = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("تعذّر فتح شجرة مستندات USB")
            val targetDocumentId = DocumentsContract.getDocumentId(directoryUri)
            fun findNode(node: DocumentFile, depth: Int): DocumentFile? {
                if (depth > MAX_DEPTH) return null
                if (runCatching { DocumentsContract.getDocumentId(node.uri) }.getOrNull() == targetDocumentId) return node
                if (!runCatching { node.isDirectory }.getOrDefault(false)) return null
                return runCatching { node.listFiles().asSequence().mapNotNull { findNode(it, depth + 1) }.firstOrNull() }
                    .getOrNull()
            }
            val node = findNode(directory, 0)
                ?: throw IOException("تعذّر فتح مجلد USB: $directoryUri")
            return node.listFiles().map { child ->
                TransferDocumentEntry(
                    uri = child.uri,
                    name = child.name ?: "file",
                    isDirectory = child.isDirectory,
                    sizeBytes = child.length().coerceAtLeast(0L)
                )
            }
        }

        fun queryChildren(directoryUri: Uri): List<TransferDocumentEntry> {
            return try {
                val documentId = DocumentsContract.getDocumentId(directoryUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                )
                val rows = context.contentResolver.query(childrenUri, projection, null, null, null)
                    ?: throw IOException("لم يقدّم موفّر USB قائمة الملفات")
                rows.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    val children = ArrayList<TransferDocumentEntry>(cursor.count)
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn)
                            ?: throw IOException("عنصر USB بلا معرّف مستند")
                        val name = cursor.getString(nameColumn) ?: "file"
                        val mimeType = if (cursor.isNull(typeColumn)) "" else cursor.getString(typeColumn).orEmpty()
                        val size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn).coerceAtLeast(0L)
                        children.add(
                            TransferDocumentEntry(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name = name,
                                isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                                sizeBytes = size
                            )
                        )
                    }
                    children
                }
            } catch (error: Exception) {
                // Some third-party providers implement only part of the SAF tree contract.
                runCatching { fallbackChildren(directoryUri) }.getOrElse { throw IOException(
                    "تعذّر فحص مجلد USB: ${error.message ?: error.javaClass.simpleName}", error
                ) }
            }
        }

        fun walk(directoryUri: Uri, prefix: String, depth: Int) {
            if (TransferForegroundService.isCancellationRequested()) {
                throw kotlinx.coroutines.CancellationException("أوقف المستخدم النسخ أثناء فحص الملفات")
            }
            if (depth > MAX_DEPTH) throw IOException("المجلدات متداخلة أكثر من الحد المسموح")
            for (child in queryChildren(directoryUri)) {
                if (TransferForegroundService.isCancellationRequested()) {
                    throw kotlinx.coroutines.CancellationException("أوقف المستخدم النسخ أثناء فحص الملفات")
                }
                val rawName = child.name
                if (prefix.isEmpty() && !child.isDirectory && rawName.endsWith(".apk", ignoreCase = true)) continue
                val name = safeRelativeSegment(rawName)
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child.uri, relative, depth + 1)
                } else {
                    result.add(TransferSourceFile(child.uri, relative, child.sizeBytes))
                    if (result.size > MAX_FILES) throw IOException("عدد الملفات يتجاوز الحد المسموح ($MAX_FILES)")
                    onScanned(result.size, relative)
                }
            }
        }

        walk(treeUri, "", 0)
        return result
    }

    fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unit = -1
        while (amount >= 1024.0 && unit < units.lastIndex) {
            amount /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    fun safeRelativePath(path: String): String {
        if (path.isBlank() || path.length > 2_048) throw IOException("اسم/مسار الملف غير صالح")
        if (path.startsWith("/") || path.startsWith("\\") || path.contains(":")) {
            throw IOException("تم رفض مسار مطلق أو غير آمن")
        }
        val parts = path.replace('\\', '/').split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.any { ch -> ch == '\u0000' } }) {
            throw IOException("تم رفض مسار ملف غير آمن")
        }
        return parts.joinToString("/") { safeRelativeSegment(it) }
    }

    fun safeRelativeSegment(raw: String): String {
        val cleaned = raw
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace("\u0000", "")
            .trim()
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return "file"
        return cleaned.take(240)
    }
}
