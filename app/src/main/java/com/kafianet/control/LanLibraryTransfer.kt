package com.kafianet.control

import android.content.Context

import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads the fixed Windows share over the local Wi-Fi/router network.
 * This is separate from phone-to-phone transfer in HotspotTransfer.
 */
object LanLibraryTransfer {
    const val SERVER_HOST = "192.168.1.106"
    const val SHARE_NAME = "العابي"
    const val UNC_PATH = """\\SERVER06\العابي"""

    private const val MAX_FILES = 50_000
    private const val MAX_DEPTH = 64
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L * 1024L
    // Larger SMB2 READ requests reduce round-trip overhead on fast local networks.
    private const val BUFFER_SIZE = 8 * 1024 * 1024
    private const val SMB_COPY_WORKERS = 4

    private fun ensureTransferActive(context: kotlin.coroutines.CoroutineContext) {
        context.ensureActive()
        if (TransferForegroundService.isCancellationRequested()) {
            throw kotlinx.coroutines.CancellationException("أوقف المستخدم النسخ من السيرفر")
        }
    }

    /**
     * Fast discovery: list only the share root and check known game folders.
     * Deep file enumeration is deferred until the user selects one game to copy.
     */
    suspend fun fetchLibrary(onStatus: (String) -> Unit = {}): List<HotspotTransfer.RemoteGame> =
        withContext(Dispatchers.IO) {
            onStatus("جارٍ الاتصال بمشاركة الكمبيوتر: " + UNC_PATH)
            val root = shareRoot(createContext(responseTimeoutMs = 20_000))
            if (!root.exists() || !root.isDirectory) {
                throw IOException("تعذّر فتح المشاركة " + UNC_PATH + "؛ تحقق من عنوان السيرفر والشبكة وصلاحية Guest.")
            }

            onStatus("تم فتح المشاركة؛ جارٍ مطابقة أسماء مجلدات الألعاب")
            val rootEntries = root.listFiles().associateBy { it.name.trimEnd('/').trim() }
            val result = ArrayList<HotspotTransfer.RemoteGame>()
            for (game in GameCatalog.games) {
                ensureTransferActive(currentCoroutineContext())
                onStatus("فحص مجلد اللعبة: " + game.sourceFolder)
                val folder = rootEntries[game.sourceFolder] ?: continue
                if (!folder.isDirectory) continue
                // Do not list the contents here: a game can contain tens of thousands
                // of files. Enumerate its contents only after the user selects that game.
                result.add(HotspotTransfer.RemoteGame(game.sourceFolder, -1L, -1))
            }

            if (result.isEmpty()) {
                val examples = rootEntries.keys.take(12).joinToString("، ")
                throw IOException(
                    "تم الاتصال بمشاركة السيرفر، لكن لم تتطابق أسماء مجلدات الألعاب مع الكتالوج. " +
                        "أسماء أولية في المشاركة: " + (examples.ifBlank { "لا توجد مجلدات ظاهرة" })
                )
            }
            onStatus("اكتمل اكتشاف المجلدات: " + result.size + " لعبة؛ سيُفحص الحجم وعدد الملفات عند اختيار اللعبة")
            result
        }

    /** Lists APK files placed directly in the selected game's root folder. */
    suspend fun listGameApks(
        game: GameItem,
        onStatus: (String) -> Unit = {}
    ): List<AvailableGameApk> = withContext(Dispatchers.IO) {
        val root = shareRoot(createContext(responseTimeoutMs = 20_000))
        val folder = SmbFile(root, CopyEngine.safeRelativeSegment(game.sourceFolder) + "/")
        if (!folder.exists() || !folder.isDirectory) {
            throw IOException("مجلد اللعبة غير موجود على السيرفر: " + game.sourceFolder)
        }
        onStatus("جاري البحث عن ملفات APK في جذر مجلد " + game.sourceFolder)
        folder.listFiles()
            .filter { file ->
                runCatching { file.isFile && file.name.trimEnd('/').endsWith(".apk", ignoreCase = true) }
                    .getOrDefault(false)
            }
            .map { file ->
                val size = file.length()
                if (size <= 0L) throw IOException("حجم ملف APK غير صالح: " + file.name)
                AvailableGameApk(file.name.trimEnd('/'), size)
            }
            .sortedBy { it.name.lowercase() }
            .also { list ->
                onStatus(if (list.isEmpty()) "لم يوجد ملف APK مباشر في مجلد اللعبة"
                    else "تم العثور على ${list.size} ملفات APK في جذر مجلد اللعبة")
            }
    }

    /** Streams a selected root APK from the SMB share into app cache for Android's installer. */
    suspend fun downloadGameApk(
        context: Context,
        game: GameItem,
        apkName: String,
        onProgress: (Long, Long, Long) -> Unit,
        onStatus: (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val name = CopyEngine.safeRelativeSegment(apkName)
        if (!name.endsWith(".apk", ignoreCase = true)) throw IOException("اسم APK غير صالح")
        val root = shareRoot(createContext())
        val folder = SmbFile(root, CopyEngine.safeRelativeSegment(game.sourceFolder) + "/")
        if (!folder.exists() || !folder.isDirectory) throw IOException("مجلد اللعبة غير موجود على السيرفر")
        val selected = folder.listFiles().firstOrNull {
            it.name.trimEnd('/') == name &&
                it.name.trimEnd('/').endsWith(".apk", ignoreCase = true) &&
                runCatching { it.isFile }.getOrDefault(false)
        } ?: throw IOException("ملف APK لم يعد موجودًا في جذر مجلد اللعبة: " + name)
        val expectedSize = selected.length()
        if (expectedSize <= 0L) throw IOException("حجم ملف APK غير صالح: " + name)
        onStatus("جاري تنزيل APK من السيرفر: " + name)
        ApkInstaller.copyStreamToCache(
            context, name, expectedSize, { SmbFileInputStream(selected) }, onProgress
        )
    }

    suspend fun copyGameToStaging(
        game: GameItem,
        destinationParent: File,
        onProgress: (TransferProgress) -> Unit,
        onStatus: (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val transferContext = currentCoroutineContext()
        val root = shareRoot(createContext())
        if (!root.exists() || !root.isDirectory) {
            throw IOException("تعذّر فتح المشاركة " + UNC_PATH + "؛ تحقق من الشبكة وصلاحية Guest.")
        }
        val folder = SmbFile(root, CopyEngine.safeRelativeSegment(game.sourceFolder) + "/")
        if (!folder.exists() || !folder.isDirectory) {
            throw IOException("لم يُعثر على مجلد اللعبة في " + UNC_PATH + ": " + game.sourceFolder)
        }

        data class SourceEntry(
            val source: SmbFile,
            val relativePath: String,
            val directory: Boolean,
            val sizeBytes: Long = 0L
        )

        // PASS 1: enumerate every file and calculate the complete total before opening
        // any file stream. Keep the SMB manifest so the transfer pass never rescans folders.
        val rootChildren = folder.listFiles()
        val manifest = ArrayList<SourceEntry>()
        var scanFiles = 0
        var totalBytes = 0L
        var lastScanProgressAt = 0L
        var lastScanStatusAt = 0L
        onStatus("بدأ الفحص الأولي لمجلد اللعبة وحساب عدد الملفات والحجم الكامل قبل النسخ")
        onProgress(TransferProgress(phase = "الفحص الأولي: حساب الحجم الكامل وعدد الملفات قبل النسخ"))

        fun scan(directory: SmbFile, prefix: String, depth: Int, knownChildren: Array<SmbFile>? = null) {
            ensureTransferActive(transferContext)
            if (depth > MAX_DEPTH) throw IOException("المجلدات متداخلة أكثر من الحد المسموح.")
            val children = knownChildren ?: directory.listFiles()
            for (child in children) {
                ensureTransferActive(transferContext)
                val rawName = child.name.trimEnd('/').trim()
                val name = CopyEngine.safeRelativeSegment(rawName)
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    manifest.add(SourceEntry(child, CopyEngine.safeRelativePath(relative), true))
                    scan(child, relative, depth + 1)
                } else if (child.isFile) {
                    // APK installation is a separate action; exclude only an APK in the game root.
                    if (prefix.isEmpty() && rawName.endsWith(".apk", ignoreCase = true)) continue
                    if (scanFiles >= MAX_FILES) throw IOException("عدد ملفات اللعبة يتجاوز الحد المسموح ($MAX_FILES).")
                    val size = child.length()
                    if (size < 0L) throw IOException("تعذّر قراءة حجم الملف: $relative")
                    totalBytes = try {
                        Math.addExact(totalBytes, size)
                    } catch (_: ArithmeticException) {
                        throw IOException("إجمالي حجم اللعبة أكبر من الحد الآمن.")
                    }
                    if (totalBytes > MAX_TOTAL_BYTES) throw IOException("حجم اللعبة يتجاوز الحد الآمن.")
                    scanFiles++
                    manifest.add(SourceEntry(child, CopyEngine.safeRelativePath(relative), false, size))
                    val now = SystemClock.elapsedRealtime()
                    if (scanFiles == 1 || now - lastScanProgressAt >= 250L) {
                        onProgress(TransferProgress(
                            filesDone = scanFiles,
                            totalFiles = 0,
                            currentFile = relative,
                            phase = "الفحص الأولي: العثور على $scanFiles ملفًا وحساب الحجم قبل النسخ"
                        ))
                        lastScanProgressAt = now
                    }
                    if (now - lastScanStatusAt >= 1000L) {
                        onStatus("الفحص الأولي: حصر $scanFiles ملفًا حتى الآن — " + CopyEngine.formatBytes(totalBytes))
                        lastScanStatusAt = now
                    }
                }
            }
        }

        scan(folder, "", 0, rootChildren)
        if (scanFiles == 0) throw IOException("مجلد اللعبة لا يحتوي ملفات قابلة للنسخ: " + game.sourceFolder)
        ensureTransferActive(transferContext)

        val rootNames = rootChildren.map { it.name.trimEnd('/').trim() }
        val alreadyRouted = game.targetPaths.size > 1 && rootNames.any {
            (it == "__kafianet_data" || it == "__kafianet_obb") &&
                rootChildren.firstOrNull { child -> child.name.trimEnd('/').trim() == it }
                    ?.let { child -> runCatching { child.isDirectory }.getOrDefault(false) } == true
        }
        val expectedPhases = if (game.targetPaths.size > 1 && !alreadyRouted) 3 else 2

        // The destination staging directory is deliberately created only AFTER the full pre-scan.
        if (!destinationParent.exists() && !destinationParent.mkdirs()) {
            throw IOException("تعذّر إنشاء مجلد التجهيز المحلي.")
        }
        val baseName = CopyEngine.safeRelativeSegment(game.sourceFolder)
        var staging = File(destinationParent, baseName)
        var suffix = 2
        while (staging.exists() && suffix < 10_000) {
            staging = File(destinationParent, "$baseName ($suffix)")
            suffix++
        }
        if (suffix >= 10_000) throw IOException("تعذّر إنشاء مجلد تجهيز فريد.")
        if (!staging.mkdirs()) throw IOException("تعذّر إنشاء مجلد تجهيز اللعبة: " + staging.path)

        try {
            val canonicalRoot = staging.canonicalFile
            onProgress(TransferProgress(
                bytesDone = 0L,
                totalBytes = totalBytes,
                filesDone = 0,
                totalFiles = scanFiles,
                currentFile = game.sourceFolder,
                bytesPerSecond = 0L,
                phase = "اكتمل الفحص: $scanFiles ملفًا، " + CopyEngine.formatBytes(totalBytes) + " — بدء النسخ من السيرفر"
            ))
            onStatus("اكتمل حساب الحجم قبل النسخ: $scanFiles ملفًا، " + CopyEngine.formatBytes(totalBytes) + "؛ بدأ النقل")

            val fileEntries = manifest.filterNot { it.directory }
            if (fileEntries.size != scanFiles) {
                throw IOException("لم يتطابق عدد ملفات SMB بعد الفحص الأولي")
            }

            fun safeTarget(relative: String): File {
                val target = File(staging, relative).canonicalFile
                if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw IOException("رُفض مسار غير آمن: " + relative)
                }
                return target
            }

            // Create and validate the directory tree before workers start writing files.
            for (entry in manifest) {
                ensureTransferActive(transferContext)
                val target = safeTarget(entry.relativePath)
                if (entry.directory) {
                    if (target.exists() && !target.isDirectory) {
                        throw IOException("مسار مجلد موجود كملف: " + entry.relativePath)
                    }
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("تعذّر إنشاء مجلد التجهيز: " + entry.relativePath)
                    }
                } else {
                    if (target.exists()) throw IOException("تكرر مسار ملف في التجهيز: " + entry.relativePath)
                    val parent = target.parentFile ?: throw IOException("وجهة الملف غير صالحة: " + entry.relativePath)
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IOException("تعذّر إنشاء مجلد التجهيز: " + parent.path)
                    }
                }
            }

            val startedAt = SystemClock.elapsedRealtime()
            val speedMeter = TransferSpeedMeter().apply { reset(0L, startedAt) }
            val totalBytesDone = AtomicLong(0L)
            val totalFilesDone = AtomicInteger(0)
            val nextFileIndex = AtomicInteger(0)
            var lastProgressAt = startedAt
            var lastStatusAt = startedAt
            val progressLock = Any()
            val workerCount = minOf(SMB_COPY_WORKERS, fileEntries.size)

            onStatus("بدء النقل المتوازي من SMB باستخدام $workerCount عمال؛ حجم قراءة SMB ${BUFFER_SIZE / (1024 * 1024)} ميغابايت")
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.IO) {
                        val workerContext = currentCoroutineContext()
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            ensureTransferActive(workerContext)
                            val index = nextFileIndex.getAndIncrement()
                            if (index >= fileEntries.size) break

                            val entry = fileEntries[index]
                            val target = safeTarget(entry.relativePath)
                            if (target.exists()) {
                                throw IOException("ملف التجهيز موجود قبل بدء كتابته: " + entry.relativePath)
                            }
                            val parent = target.parentFile
                                ?: throw IOException("وجهة الملف غير صالحة: " + entry.relativePath)
                            if (!parent.exists() && !parent.mkdirs()) {
                                throw IOException("تعذّر إنشاء مجلد التجهيز: " + parent.path)
                            }

                            var fileBytesDone = 0L
                            SmbFileInputStream(entry.source).use { input ->
                                FileOutputStream(target, false).use { output ->
                                    while (true) {
                                        ensureTransferActive(workerContext)
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        output.write(buffer, 0, count)
                                        fileBytesDone += count
                                        totalBytesDone.addAndGet(count)
                                        val now = SystemClock.elapsedRealtime()
                                        synchronized(progressLock) {
                                            val currentTotal = totalBytesDone.get()
                                            if (now - lastProgressAt >= 250L) {
                                                onProgress(TransferProgress(
                                                    bytesDone = currentTotal,
                                                    totalBytes = totalBytes,
                                                    filesDone = totalFilesDone.get(),
                                                    totalFiles = scanFiles,
                                                    currentFile = entry.relativePath,
                                                    bytesPerSecond = speedMeter.update(currentTotal, now),
                                                    phase = "المرحلة 1/$expectedPhases: نسخ SMB متوازٍ بعد حساب الحجم الكامل"
                                                ))
                                                lastProgressAt = now
                                            }
                                        }
                                    }
                                }
                            }

                            if (fileBytesDone != entry.sizeBytes || !target.isFile || target.length() != entry.sizeBytes) {
                                throw IOException(
                                    "فشل التحقق من الملف المستلَم " + entry.relativePath +
                                        " (المتوقع " + entry.sizeBytes + " بايت، المستلَم " + fileBytesDone + " بايت)"
                                )
                            }

                            val completedFiles = totalFilesDone.incrementAndGet()
                            val now = SystemClock.elapsedRealtime()
                            synchronized(progressLock) {
                                val currentTotal = totalBytesDone.get()
                                if (now - lastProgressAt >= 250L || completedFiles == scanFiles) {
                                    onProgress(TransferProgress(
                                        bytesDone = currentTotal,
                                        totalBytes = totalBytes,
                                        filesDone = completedFiles,
                                        totalFiles = scanFiles,
                                        currentFile = entry.relativePath,
                                        bytesPerSecond = speedMeter.update(currentTotal, now),
                                        phase = "المرحلة 1/$expectedPhases: نسخ SMB متوازٍ بعد حساب الحجم الكامل"
                                    ))
                                    lastProgressAt = now
                                }
                                if (now - lastStatusAt >= 1000L || completedFiles == scanFiles) {
                                    onStatus(
                                        "النسخ المتوازي من SMB: $completedFiles/$scanFiles ملفًا — " +
                                            CopyEngine.formatBytes(currentTotal) + " من " + CopyEngine.formatBytes(totalBytes)
                                    )
                                    lastStatusAt = now
                                }
                            }
                        }
                    }
                }
            }

            ensureTransferActive(transferContext)
            val bytesDone = totalBytesDone.get()
            val filesDone = totalFilesDone.get()
            if (filesDone != scanFiles || bytesDone != totalBytes) {
                throw IOException("لم يطابق النسخ المتوازي إجمالي الفحص الأولي: ملفات $filesDone/$scanFiles، بايتات $bytesDone/$totalBytes")
            }
            onProgress(TransferProgress(
                bytesDone = totalBytes,
                totalBytes = totalBytes,
                filesDone = scanFiles,
                totalFiles = scanFiles,
                currentFile = game.sourceFolder,
                bytesPerSecond = 0L,
                phase = "اكتمل النسخ من السيرفر والتحقق من $scanFiles ملفًا"
            ))
            onStatus("اكتمل النسخ والتحقق من المصدر: $scanFiles ملفًا، " + CopyEngine.formatBytes(totalBytes) + "؛ جارٍ تجهيز الوجهة النهائية")
            staging
        } catch (error: Exception) {
            runCatching { staging.deleteRecursively() }
            throw error
        }
    }

    private fun shareRoot(context: jcifs.CIFSContext): SmbFile {
        val server = SmbFile("smb://" + SERVER_HOST + "/", context)
        return SmbFile(server, SHARE_NAME + "/")
    }

    private fun createContext(responseTimeoutMs: Int = 120_000): jcifs.CIFSContext {
        val properties = Properties()
        properties.setProperty("jcifs.smb.client.enableSMB2", "true")
        properties.setProperty("jcifs.smb.client.disableSMB1", "true")
        // The copy loop already uses a 1 MiB buffer. Raise jcifs-ng SMB read requests to match it,
        // allowing SMB2 to use larger reads where the server negotiation supports them.
        properties.setProperty("jcifs.smb.client.rcv_buf_size", BUFFER_SIZE.toString())
        properties.setProperty("jcifs.smb.client.connTimeout", "10000")
        properties.setProperty("jcifs.smb.client.responseTimeout", responseTimeoutMs.toString())
        properties.setProperty("jcifs.smb.client.soTimeout", responseTimeoutMs.toString())
        properties.setProperty("jcifs.smb.client.allowGuestFallback", "true")
        properties.setProperty("jcifs.smb.client.guestUsername", "Guest")
        properties.setProperty("jcifs.smb.client.guestPassword", "")
        val base = BaseContext(PropertyConfiguration(properties))
        return base.withCredentials(NtlmPasswordAuthenticator("", "Guest", ""))
    }

}
