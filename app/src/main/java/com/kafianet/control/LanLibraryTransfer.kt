package com.kafianet.control

import android.content.Context
import android.os.ParcelFileDescriptor

import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

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
    private const val BUFFER_SIZE = 1024 * 1024

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
                val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
                val targetPath = target.path
                // Android emulated storage may canonicalize the same "Android" directory
                // with different casing for child paths. Apply case-insensitive comparison
                // only on that storage mount; retain strict comparison everywhere else.
                val isEmulatedStorage = rootPath.startsWith("/storage/emulated/", ignoreCase = true)
                val isInsideRoot = if (isEmulatedStorage) {
                    targetPath.equals(rootPath, ignoreCase = true) ||
                        targetPath.startsWith(rootPath + File.separator, ignoreCase = true)
                } else {
                    targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
                }
                if (!isInsideRoot) {
                    android.util.Log.e(
                        "CN_PLAYHUB_TRANSFER",
                        "Unsafe staging target rejected: relative=[${relative}], " +
                            "staging=[${staging.absolutePath}], root=[${canonicalRoot.path}], " +
                            "target=[${target.path}]"
                    )
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
            var totalBytesDone = 0L
            var totalFilesDone = 0
            var lastProgressAt = startedAt
            var lastStatusAt = startedAt
            val progressPhase = "المرحلة 1/$expectedPhases: Nova SMB2 Multi-Credit + Read-Ahead"

            onStatus("بدأ النقل عبر محرك Nova SMB2 Multi-Credit + Read-Ahead — عامل واحد وقراءة 1 MiB")
            onProgress(TransferProgress(
                bytesDone = 0L,
                totalBytes = totalBytes,
                filesDone = 0,
                totalFiles = scanFiles,
                currentFile = game.sourceFolder,
                bytesPerSecond = 0L,
                phase = progressPhase
            ))

            // The Nova client pipelines large SMB2 reads internally. One stream worker
            // avoids competing SMB sessions while keeping the link busy through multi-credit.
            val buffer = ByteArray(BUFFER_SIZE)
            for (entry in fileEntries) {
                ensureTransferActive(transferContext)
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
                            ensureTransferActive(transferContext)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            fileBytesDone += count
                            totalBytesDone += count.toLong()

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressAt >= 250L) {
                                onProgress(TransferProgress(
                                    bytesDone = totalBytesDone,
                                    totalBytes = totalBytes,
                                    filesDone = totalFilesDone,
                                    totalFiles = scanFiles,
                                    currentFile = entry.relativePath,
                                    bytesPerSecond = speedMeter.update(totalBytesDone, now),
                                    phase = progressPhase
                                ))
                                lastProgressAt = now
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

                totalFilesDone++
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 250L || totalFilesDone == scanFiles) {
                    onProgress(TransferProgress(
                        bytesDone = totalBytesDone,
                        totalBytes = totalBytes,
                        filesDone = totalFilesDone,
                        totalFiles = scanFiles,
                        currentFile = entry.relativePath,
                        bytesPerSecond = speedMeter.update(totalBytesDone, now),
                        phase = progressPhase
                    ))
                    lastProgressAt = now
                }
                if (now - lastStatusAt >= 1000L || totalFilesDone == scanFiles) {
                    onStatus(
                        "Nova SMB: $totalFilesDone/$scanFiles ملفًا — " +
                            CopyEngine.formatBytes(totalBytesDone) + " / " + CopyEngine.formatBytes(totalBytes)
                    )
                    lastStatusAt = now
                }
            }

            ensureTransferActive(transferContext)
            val bytesDone = totalBytesDone
            val filesDone = totalFilesDone
            onStatus("اكتمل النقل عبر محرك Nova؛ جارٍ اعتماد إجمالي الملفات والحجم")
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


    /**
     * Streams SMB files directly into the fixed game destination through Shizuku.
     * No app-owned TransferStaging directory is created for single-destination games.
     * Each payload is written to a sibling partial file and atomically committed only
     * after the received byte count has been verified.
     */
    suspend fun copyGameDirectToTargets(
        game: GameItem,
        onProgress: (TransferProgress) -> Unit,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (game.targetPaths.size != 1) {
            throw IOException("النسخ المباشر يتطلب وجهة واحدة لهذه اللعبة")
        }
        if (!ShizukuServiceConnection.isReady()) {
            throw IOException("خدمة Shizuku ليست جاهزة للكتابة المباشرة")
        }
        if (!ShizukuServiceConnection.prepareCopy()) {
            throw IOException("تعذّر تهيئة خدمة Shizuku للنسخ المباشر؛ أعد ربط الخدمة")
        }

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

        val manifest = ArrayList<SourceEntry>()
        val seenPaths = HashSet<String>()
        var scanFiles = 0
        var totalBytes = 0L
        var lastScanProgressAt = 0L
        var lastScanStatusAt = 0L
        onStatus("فحص كامل ملفات اللعبة على السيرفر قبل الكتابة المباشرة إلى وجهتها النهائية")
        onProgress(TransferProgress(phase = "الفحص الأولي: حساب الحجم الكامل وعدد الملفات قبل النسخ"))

        fun scan(directory: SmbFile, prefix: String, depth: Int, knownChildren: Array<SmbFile>? = null) {
            ensureTransferActive(transferContext)
            if (depth > MAX_DEPTH) throw IOException("المجلدات متداخلة أكثر من الحد المسموح.")
            val children = knownChildren ?: directory.listFiles()
            for (child in children) {
                ensureTransferActive(transferContext)
                val rawName = child.name.trimEnd('/').trim()
                val name = CopyEngine.safeRelativeSegment(rawName)
                val relative = if (prefix.isEmpty()) name else prefix + "/" + name
                val safeRelative = CopyEngine.safeRelativePath(relative)
                if (!seenPaths.add(safeRelative)) {
                    throw IOException("تكرر مسار داخل مصدر اللعبة: " + safeRelative)
                }
                if (child.isDirectory) {
                    manifest.add(SourceEntry(child, safeRelative, true))
                    scan(child, safeRelative, depth + 1)
                } else if (child.isFile) {
                    if (prefix.isEmpty() && rawName.endsWith(".apk", ignoreCase = true)) continue
                    if (scanFiles >= MAX_FILES) {
                        throw IOException("عدد ملفات اللعبة يتجاوز الحد المسموح (" + MAX_FILES + ").")
                    }
                    val size = child.length()
                    if (size < 0L) throw IOException("تعذّر قراءة حجم الملف: " + safeRelative)
                    totalBytes = try {
                        Math.addExact(totalBytes, size)
                    } catch (_: ArithmeticException) {
                        throw IOException("إجمالي حجم اللعبة أكبر من الحد الآمن.")
                    }
                    if (totalBytes > MAX_TOTAL_BYTES) throw IOException("حجم اللعبة يتجاوز الحد الآمن.")
                    scanFiles++
                    manifest.add(SourceEntry(child, safeRelative, false, size))
                    val now = SystemClock.elapsedRealtime()
                    if (scanFiles == 1 || now - lastScanProgressAt >= 250L) {
                        onProgress(TransferProgress(
                            filesDone = scanFiles,
                            totalFiles = 0,
                            currentFile = safeRelative,
                            phase = "الفحص الأولي: العثور على " + scanFiles + " ملفًا وحساب الحجم"
                        ))
                        lastScanProgressAt = now
                    }
                    if (now - lastScanStatusAt >= 1000L) {
                        onStatus(
                            "الفحص الأولي: حصر " + scanFiles + " ملفًا — " +
                                CopyEngine.formatBytes(totalBytes)
                        )
                        lastScanStatusAt = now
                    }
                }
            }
        }

        val rootChildren = folder.listFiles()
        scan(folder, "", 0, rootChildren)
        if (scanFiles == 0) throw IOException("مجلد اللعبة لا يحتوي ملفات قابلة للنسخ: " + game.sourceFolder)
        ensureTransferActive(transferContext)

        val baseRelative = game.targetPaths.single()
        val destinationRootPath = GameCatalog.absoluteTargetPath(baseRelative)
        if (!ShizukuServiceConnection.ensureGameDirectory(destinationRootPath)) {
            throw IOException("تعذّر إنشاء أو فتح مجلد الوجهة عبر Shizuku: " + destinationRootPath)
        }
        val canonicalRoot = File(destinationRootPath).canonicalFile

        fun safeDestination(relative: String): String {
            val safeRelative = CopyEngine.safeRelativePath(relative)
            val combined = CopyEngine.safeRelativePath(baseRelative + "/" + safeRelative)
            val target = File(GameCatalog.absoluteTargetPath(combined)).canonicalFile
            val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
            val targetPath = target.path
            val emulated = rootPath.startsWith("/storage/emulated/", ignoreCase = true)
            val prefix = rootPath + File.separator
            val inside = targetPath.regionMatches(0, prefix, 0, prefix.length, emulated)
            if (!inside) {
                throw IOException("رُفض مسار وجهة غير آمن: " + relative)
            }
            return target.absolutePath
        }

        // Create the original source directory structure at its final destination.
        for (entry in manifest) {
            ensureTransferActive(transferContext)
            if (entry.directory) {
                val directoryPath = safeDestination(entry.relativePath)
                if (!ShizukuServiceConnection.ensureGameDirectory(directoryPath)) {
                    throw IOException("تعذّر إنشاء مجلد الوجهة: " + directoryPath)
                }
            }
        }

        val fileEntries = manifest.filterNot { it.directory }
        if (fileEntries.size != scanFiles) throw IOException("لم يتطابق عدد ملفات SMB بعد الفحص الأولي")

        val startedAt = SystemClock.elapsedRealtime()
        val speedMeter = TransferSpeedMeter().apply { reset(0L, startedAt) }
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytesDone = 0L
        var totalFilesDone = 0
        var lastProgressAt = startedAt
        var lastStatusAt = startedAt
        val phase = "نقل مباشر من SMB إلى مجلد اللعبة عبر Shizuku"

        onStatus("بدأت الكتابة المباشرة إلى: " + destinationRootPath)
        onProgress(TransferProgress(
            totalBytes = totalBytes,
            totalFiles = scanFiles,
            currentFile = game.sourceFolder,
            phase = phase
        ))

        for (entry in fileEntries) {
            ensureTransferActive(transferContext)
            val destinationPath = safeDestination(entry.relativePath)
            val targetFile = File(destinationPath)
            val parent = targetFile.parentFile ?: throw IOException("وجهة الملف غير صالحة: " + entry.relativePath)
            val temporaryPath = File(
                parent,
                "." + targetFile.name + ".kafianet-" + java.util.UUID.randomUUID().toString() + ".partial"
            ).absolutePath
            val descriptor = ShizukuServiceConnection.openTemporaryGameFile(destinationPath, temporaryPath)
                ?: throw IOException("تعذّر فتح ملف مؤقت بجانب الوجهة النهائية: " + entry.relativePath)

            var fileBytesDone = 0L
            var committed = false
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    SmbFileInputStream(entry.source).use { input ->
                        while (true) {
                            ensureTransferActive(transferContext)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            fileBytesDone += count.toLong()
                            totalBytesDone += count.toLong()

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressAt >= 250L) {
                                onProgress(TransferProgress(
                                    bytesDone = totalBytesDone,
                                    totalBytes = totalBytes,
                                    filesDone = totalFilesDone,
                                    totalFiles = scanFiles,
                                    currentFile = entry.relativePath,
                                    bytesPerSecond = speedMeter.update(totalBytesDone, now),
                                    phase = phase
                                ))
                                lastProgressAt = now
                            }
                        }
                    }
                    output.flush()
                }

                if (fileBytesDone != entry.sizeBytes) {
                    throw IOException(
                        "تغيّر حجم المصدر أثناء النقل المباشر: " + entry.relativePath +
                            " (المتوقع " + entry.sizeBytes + "، المقروء " + fileBytesDone + ")"
                    )
                }
                ensureTransferActive(transferContext)
                val commitResult = ShizukuServiceConnection.commitTemporaryGameFile(
                    temporaryPath,
                    destinationPath,
                    entry.sizeBytes
                )
                if (!commitResult.startsWith("SUCCESS:")) {
                    throw IOException("فشل اعتماد الملف في وجهته النهائية: " + entry.relativePath + " — " + commitResult)
                }
                if (ShizukuServiceConnection.fileLength(destinationPath) != entry.sizeBytes) {
                    throw IOException("فشل التحقق من حجم الملف في الوجهة النهائية: " + entry.relativePath)
                }
                committed = true
            } finally {
                if (!committed) ShizukuServiceConnection.deleteTemporaryGameFile(temporaryPath)
            }

            totalFilesDone++
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgressAt >= 250L || totalFilesDone == scanFiles) {
                onProgress(TransferProgress(
                    bytesDone = totalBytesDone,
                    totalBytes = totalBytes,
                    filesDone = totalFilesDone,
                    totalFiles = scanFiles,
                    currentFile = entry.relativePath,
                    bytesPerSecond = speedMeter.update(totalBytesDone, now),
                    phase = phase
                ))
                lastProgressAt = now
            }
            if (now - lastStatusAt >= 1000L || totalFilesDone == scanFiles) {
                onStatus(
                    "نقل مباشر: " + totalFilesDone + "/" + scanFiles + " ملفًا — " +
                        CopyEngine.formatBytes(totalBytesDone) + " / " + CopyEngine.formatBytes(totalBytes)
                )
                lastStatusAt = now
            }
        }

        ensureTransferActive(transferContext)
        if (totalFilesDone != scanFiles || totalBytesDone != totalBytes) {
            throw IOException(
                "لم يطابق النقل المباشر إجمالي الفحص الأولي: ملفات " +
                    totalFilesDone + "/" + scanFiles + "، بايتات " + totalBytesDone + "/" + totalBytes
            )
        }
        val completedDestinationPath = if (game.sourceFolder == "ببجي (PUBG)") {
            GameCatalog.absoluteTargetPath(baseRelative + "/Saved")
        } else {
            destinationRootPath
        }
        onProgress(TransferProgress(
            bytesDone = totalBytes,
            totalBytes = totalBytes,
            filesDone = scanFiles,
            totalFiles = scanFiles,
            currentFile = completedDestinationPath,
            phase = "اكتمل النقل المباشر والتحقق من كل الملفات"
        ))
        onStatus(
            "اكتمل النسخ المباشر والتحقق من " + scanFiles + " ملفًا إلى " +
                completedDestinationPath + " — لا يوجد مجلد تجهيز داخل بيانات التطبيق"
        )
    }

    private fun shareRoot(context: jcifs.CIFSContext): SmbFile {
        val server = SmbFile("smb://" + SERVER_HOST + "/", context)
        return SmbFile(server, SHARE_NAME + "/")
    }

    private fun createContext(responseTimeoutMs: Int = 120_000): jcifs.CIFSContext {
        val properties = Properties()
        properties.setProperty("jcifs.smb.client.enableSMB2", "true")
        properties.setProperty("jcifs.smb.client.disableSMB1", "true")
        // Match Nova's SMB2 multi-credit window for predictable high-throughput reads.
        properties.setProperty("jcifs.smb.client.rcv_buf_size", BUFFER_SIZE.toString())
        properties.setProperty("jcifs.smb.client.transaction_buf_size", BUFFER_SIZE.toString())
        properties.setProperty("jcifs.smb.client.snd_buf_size", BUFFER_SIZE.toString())
        properties.setProperty("jcifs.smb.client.tcpNoDelay", "true")
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
