package com.kafianet.control

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections

/** Transfer over the source phone's manually enabled hotspot. Game folders and destinations are fixed in GameCatalog. */
object HotspotTransfer {
    const val PORT = 39555
    private const val MAGIC = "KAFIANET_GAME_LIBRARY_V4"
    private const val MAX_FILES = 50_000
    private const val MAX_GAMES = 100
    private const val MAX_DEPTH = 64
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L * 1024L
    private const val BUFFER_SIZE = 1024 * 1024
    private const val SOCKET_TIMEOUT_MS = 120_000
    private const val FLUSH_THRESHOLD_BYTES = 4L * 1024L * 1024L

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null

    data class RemoteGame(val name: String, val sizeBytes: Long, val fileCount: Int)
    private data class SourceFile(val absolutePath: String, val relativePath: String, val sizeBytes: Long)
    private data class ManifestItem(val path: String, val size: Long)

    private fun ensureTransferActive(context: kotlin.coroutines.CoroutineContext) {
        context.ensureActive()
        if (TransferForegroundService.isCancellationRequested()) {
            throw kotlinx.coroutines.CancellationException("أوقف المستخدم النقل")
        }
    }

    fun localIpv4Addresses(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String>({
                when {
                    it.startsWith("192.168.43.") -> 0
                    it.startsWith("192.168.137.") -> 1
                    it.startsWith("192.168.") -> 2
                    it.startsWith("172.") -> 3
                    it.startsWith("10.") -> 4
                    else -> 5
                }
            }, { it }))
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Default Wi-Fi route, usually the source phone hotspot gateway. */
    fun wifiGatewayIpv4Addresses(context: Context): List<String> = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.allNetworks.asSequence()
            .filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { manager.getLinkProperties(it)?.routes.orEmpty().asSequence() }
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway as? Inet4Address }
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun serveLibrary(
        context: Context,
        libraryPath: String,
        bindIpv4: String,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        cancel()
        awaitShizukuReady()
        if (ShizukuServiceConnection.pathType(libraryPath) != 2) {
            throw IOException("لم يُعثر على مجلد «العابي» في جذر الهاتف: $libraryPath")
        }
        if (listGames(libraryPath).isEmpty()) {
            throw IOException("لم يُعثر على مجلد لعبة معروف داخل «العابي»")
        }
        require(bindIpv4 in localIpv4Addresses()) { "عنوان IP المحدد لم يعد موجودًا على الهاتف؛ حدّث العناوين وأعد المحاولة" }

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(bindIpv4), PORT), 4)
        serverSocket = server
        onStatus("مكتبة الألعاب جاهزة — $libraryPath")

        try {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (closed: IOException) {
                    if (server.isClosed) break
                    onStatus("تعذّر قبول اتصال جديد: ${closed.message ?: "خطأ شبكة"}")
                    continue
                }
                activeSocket = client
                try {
                    client.use { handleClient(libraryPath, it, onStatus) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onStatus("تم إنهاء اتصال: ${error.message ?: "خطأ غير معروف"}")
                } finally {
                    activeSocket = null
                }
            }
        } finally {
            activeSocket = null
            if (serverSocket === server) serverSocket = null
            runCatching { server.close() }
        }
    }

    suspend fun fetchLibrary(host: String, onStatus: (String) -> Unit = {}): List<RemoteGame> =
        withContext(Dispatchers.IO) {
            validateEndpoint(host)
            onStatus("جارٍ الاتصال بهاتف المصدر…")
            connect(host).use { socket ->
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
                startRequest(output, "LIST")
                checkServerAccepted(input)
                val count = input.readInt()
                if (count !in 0..MAX_GAMES) throw IOException("عدد الألعاب غير صالح")
                val games = ArrayList<RemoteGame>(count)
                repeat(count) {
                    val name = CopyEngine.safeRelativeSegment(input.readUTF())
                    val size = input.readLong()
                    val files = input.readInt()
                    val unknownDetails = size == -1L && files == -1
                    val knownDetails = size >= 0L && files >= 0 && files <= MAX_FILES
                    if (!unknownDetails && !knownDetails) throw IOException("معلومات لعبة غير صالحة")
                    games.add(RemoteGame(name, size, files))
                }
                onStatus("تم العثور على ${games.size} لعبة مطابقة للكتالوج")
                games
            }
        }

    suspend fun listGameApks(
        host: String, gameSourceFolder: String, onStatus: (String) -> Unit = {}
    ): List<AvailableGameApk> = withContext(Dispatchers.IO) {
        validateEndpoint(host)
        val game = GameCatalog.bySourceFolder(gameSourceFolder)
            ?: throw IOException("اللعبة غير موجودة في كتالوج التطبيق")
        onStatus("البحث عن ملفات APK في جذر مجلد " + game.arabic)
        connect(host).use { socket ->
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
            startRequest(output, "LIST_APKS")
            output.writeUTF(game.sourceFolder)
            output.flush()
            checkServerAccepted(input)
            val count = input.readInt()
            if (count !in 0..128) throw IOException("عدد ملفات APK في المجلد غير صالح")
            val result = ArrayList<AvailableGameApk>(count)
            repeat(count) {
                val name = CopyEngine.safeRelativeSegment(input.readUTF())
                val size = input.readLong()
                if (!name.endsWith(".apk", ignoreCase = true) || size <= 0L || size > MAX_TOTAL_BYTES) {
                    throw IOException("بيانات ملف APK غير صالحة")
                }
                result.add(AvailableGameApk(name, size))
            }
            onStatus(if (result.isEmpty()) "لا يوجد APK مباشر في جذر المجلد المحدد" else "تم العثور على ${result.size} ملفات APK")
            result
        }
    }

    suspend fun downloadGameApk(
        context: Context, host: String, gameSourceFolder: String, apkName: String,
        onProgress: (Long, Long, Long) -> Unit, onStatus: (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        validateEndpoint(host)
        val game = GameCatalog.bySourceFolder(gameSourceFolder)
            ?: throw IOException("اللعبة غير موجودة في كتالوج التطبيق")
        val name = CopyEngine.safeRelativeSegment(apkName)
        if (!name.endsWith(".apk", ignoreCase = true)) throw IOException("اسم APK غير صالح")
        onStatus("تنزيل APK من الهاتف المصدر: " + name)
        connect(host).use { socket ->
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
            startRequest(output, "FETCH_APK")
            output.writeUTF(game.sourceFolder)
            output.writeUTF(name)
            output.flush()
            checkServerAccepted(input)
            val expectedSize = input.readLong()
            if (expectedSize !in 1L..MAX_TOTAL_BYTES) throw IOException("حجم APK من المصدر غير صالح")
            ApkInstaller.copyStreamToCache(context, name, expectedSize, { input }, onProgress)
        }
    }

    /** Receives to an app-owned staging folder, verifies SHA-256, then copies to the built-in game path(s). */
    suspend fun downloadGame(
        context: Context,
        host: String,
        gameSourceFolder: String,
        onProgress: (TransferProgress) -> Unit,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        awaitShizukuReady()
        validateEndpoint(host)
        val game = GameCatalog.bySourceFolder(gameSourceFolder)
            ?: throw IOException("اللعبة غير موجودة في كتالوج التطبيق")
        val stagingParent = File(context.getExternalFilesDir(null) ?: context.filesDir, "KafiaNet/TransferStaging/Wireless")
        if (!stagingParent.exists() && !stagingParent.mkdirs()) throw IOException("تعذّر إنشاء مجلد النقل المؤقت")
        val stagingGame = uniqueDirectory(stagingParent, CopyEngine.safeRelativeSegment(game.sourceFolder))
        if (!stagingGame.mkdirs()) throw IOException("تعذّر إنشاء مجلد الاستقبال المؤقت")

        try {
            connect(host).use { socket ->
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
                startRequest(output, "FETCH")
                output.writeUTF(game.sourceFolder)
                output.flush()
                checkServerAccepted(input)

                val totalFiles = input.readInt()
                val totalBytes = input.readLong()
                if (totalFiles !in 1..MAX_FILES || totalBytes !in 0L..MAX_TOTAL_BYTES) {
                    throw IOException("قائمة ملفات اللعبة غير صالحة")
                }
                val manifest = ArrayList<ManifestItem>(totalFiles)
                var calculatedTotal = 0L
                repeat(totalFiles) {
                    val path = CopyEngine.safeRelativePath(input.readUTF())
                    val size = input.readLong()
                    if (size < 0L) throw IOException("حجم ملف غير صالح: $path")
                    calculatedTotal = Math.addExact(calculatedTotal, size)
                    if (calculatedTotal > MAX_TOTAL_BYTES) throw IOException("حجم اللعبة يتجاوز الحد الآمن")
                    manifest.add(ManifestItem(path, size))
                }
                if (calculatedTotal != totalBytes) throw IOException("قائمة الملفات لا تتطابق مع الحجم الإجمالي")
                output.writeBoolean(true)
                output.flush()

                val startedAt = SystemClock.elapsedRealtime()
                val speedMeter = TransferSpeedMeter().apply { reset(0L, startedAt) }
                var bytesDone = 0L
                var filesDone = 0
                var lastProgressAt = startedAt
                val receiveBuffer = ByteArray(BUFFER_SIZE)
                onProgress(TransferProgress(totalBytes = totalBytes, totalFiles = totalFiles, phase = "المرحلة 1/2: بدء استقبال اللعبة والتحقق منها"))

                for (item in manifest) {
                    val targetRelative = if (game.targetPaths.size > 1) stageRelativeForMultipleTargets(item.path) else item.path
                    val target = File(stagingGame, targetRelative).canonicalFile
                    val canonicalRoot = stagingGame.canonicalFile
                    if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
                        throw IOException("مسار ملف غير آمن: ${item.path}")
                    }
                    val parent = target.parentFile ?: throw IOException("وجهة الملف غير صالحة: ${item.path}")
                    if (!parent.exists() && !parent.mkdirs()) throw IOException("تعذّر إنشاء مجلد: ${parent.path}")
                    if (target.exists()) throw IOException("تكرر مسار ملف في اللعبة: ${item.path}")

                    val digest = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(target, false).use { dest ->
                        var remaining = item.size
                        while (remaining > 0L) {
                            val wanted = minOf(receiveBuffer.size.toLong(), remaining).toInt()
                            val read = input.read(receiveBuffer, 0, wanted)
                            if (read < 0) throw EOFException("انقطع الاتصال أثناء استقبال: ${item.path}")
                            if (read == 0) continue
                            dest.write(receiveBuffer, 0, read)
                            digest.update(receiveBuffer, 0, read)
                            remaining -= read
                            bytesDone += read
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressAt >= 100L) {
                                onProgress(TransferProgress(
                                    bytesDone = (bytesDone / 2L).coerceAtMost(totalBytes), totalBytes = totalBytes,
                                    filesDone = (filesDone / 2).coerceAtMost(totalFiles), totalFiles = totalFiles,
                                    currentFile = item.path, bytesPerSecond = speedMeter.update(bytesDone, now),
                                    phase = "المرحلة 1/2: استقبال ملفات اللعبة والتحقق من سلامتها"
                                ))
                                lastProgressAt = now
                            }
                        }
                    }
                    val expectedDigest = ByteArray(32)
                    input.readFully(expectedDigest)
                    if (!MessageDigest.isEqual(digest.digest(), expectedDigest)) {
                        throw IOException("فشل التحقق من سلامة الملف بعد النقل: ${item.path}")
                    }
                    filesDone++
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 100L || filesDone == totalFiles) {
                        onProgress(TransferProgress(
                            bytesDone = (bytesDone / 2L).coerceAtMost(totalBytes), totalBytes = totalBytes,
                            filesDone = (filesDone / 2).coerceAtMost(totalFiles), totalFiles = totalFiles,
                            currentFile = item.path, bytesPerSecond = speedMeter.update(bytesDone, now),
                            phase = if (filesDone == totalFiles) "المرحلة 1/2: اكتمل الاستقبال والتحقق؛ يجري النسخ إلى الوجهة" else "المرحلة 1/2: استقبال ملفات اللعبة والتحقق من سلامتها"
                        ))
                        lastProgressAt = now
                    }
                }
                // One round-trip acknowledgement after every file has passed SHA-256 verification.
                output.writeBoolean(true)
                output.flush()
                if (input.readUTF() != "DONE") throw IOException("لم يؤكد هاتف المصدر اكتمال نقل اللعبة")
            }

            onStatus("تم الاستقبال والتحقق — جارٍ النسخ إلى المسار المسجل للعبة")
            copyStagedGameToTargets(stagingGame, game, onProgress, onStatus)
            runCatching { stagingGame.deleteRecursively() }
            onStatus("اكتمل نسخ ${game.arabic} إلى ${game.destinationLabel}")
        } catch (error: Exception) {
            onStatus("توقف النقل؛ النسخة المؤقتة محفوظة للفحص في ${stagingGame.absolutePath}")
            throw error
        }
    }

    /** Copy a staged USB or Wireless game into its fixed in-code destination path(s). */
    private data class StagedFile(val file: File, val relativePath: String, val sizeBytes: Long)
    private data class DestinationPlan(
        val source: File,
        val destination: String,
        val label: String,
        val bytes: Long,
        val files: Int
    )

    /** Copies to the fixed game path and only reports success after destination verification. */
    suspend fun copyStagedGameToTargets(
        stagingGame: File,
        game: GameItem,
        onProgress: (TransferProgress) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        awaitShizukuReady()
        if (!stagingGame.exists() || !stagingGame.isDirectory) throw IOException("مجلد اللعبة المؤقت غير موجود")

        val transferContext = kotlinx.coroutines.currentCoroutineContext()

        // One destination: complete the full pre-scan first, then copy from the known-size manifest.
        if (game.targetPaths.size == 1) {
            ensureTransferActive(transferContext)
            val destination = GameCatalog.absoluteTargetPath(game.targetPaths.single())
            if (!ShizukuServiceConnection.prepareCopy()) {
                throw IOException("تعذّر تهيئة خدمة Shizuku لعملية نسخ جديدة؛ أعد ربط الخدمة")
            }
            ensureTransferActive(transferContext)
            onStatus("اكتمل حساب الحجم الكامل؛ بدأ النسخ السريع إلى وجهة اللعبة")
            val copySpeed = TransferSpeedMeter()
            val copyReturned = java.util.concurrent.atomic.AtomicBoolean(false)
            val result = ShizukuServiceConnection.moveDirectoryWithProgressAndVerify(
                stagingGame.absolutePath,
                destination
            ) { bytesDone, callbackTotalBytes, filesDone, callbackTotalFiles, file ->
                if (!copyReturned.get() && file.startsWith("__KAFIANET_SCAN__:")) {
                    val now = SystemClock.elapsedRealtime()
                    if (callbackTotalBytes > 0L) {
                        // Start the speed measurement only after the scan finishes.
                        copySpeed.reset(0L, now)
                    }
                    onProgress(TransferProgress(
                        bytesDone = 0L,
                        totalBytes = callbackTotalBytes,
                        filesDone = filesDone,
                        totalFiles = callbackTotalFiles,
                        currentFile = file.removePrefix("__KAFIANET_SCAN__:"),
                        bytesPerSecond = 0L,
                        phase = if (callbackTotalBytes > 0L)
                            "اكتمل حساب الحجم الكامل — بدء النسخ السريع إلى وجهة اللعبة"
                        else "فحص كامل ملفات المصدر وحساب الحجم قبل النسخ"
                    ))
                } else if (!copyReturned.get()) {
                    val now = SystemClock.elapsedRealtime()
                    onProgress(TransferProgress(
                        bytesDone = bytesDone,
                        totalBytes = callbackTotalBytes,
                        filesDone = filesDone,
                        totalFiles = callbackTotalFiles,
                        currentFile = file,
                        bytesPerSecond = copySpeed.update(bytesDone, now),
                        phase = "نسخ سريع إلى وجهة اللعبة — الحجم الإجمالي محسوب مسبقًا"
                    ))
                }
            }
            copyReturned.set(true)
            ensureTransferActive(transferContext)
            if (result.startsWith("CANCELLED:")) {
                throw kotlinx.coroutines.CancellationException(result.removePrefix("CANCELLED:").trim())
            }
            val summary = result.split(':')
            if (!result.startsWith("SUCCESS:") || summary.size != 3) {
                throw IOException("فشل النسخ إلى $destination — $result")
            }
            val verifiedBytes = summary[1].toLongOrNull()
                ?: throw IOException("لم ترجع خدمة Shizuku حجم النسخ المتحقق")
            val verifiedFiles = summary[2].toIntOrNull()
                ?: throw IOException("لم ترجع خدمة Shizuku عدد الملفات المتحقق")
            if (verifiedFiles <= 0 || verifiedBytes < 0L) {
                throw IOException("تقرير التحقق النهائي غير صالح للوجهة $destination")
            }
            onProgress(TransferProgress(
                bytesDone = verifiedBytes,
                totalBytes = verifiedBytes,
                filesDone = verifiedFiles,
                totalFiles = verifiedFiles,
                currentFile = game.destinationLabel,
                bytesPerSecond = 0L,
                phase = "اكتمل النسخ والتحقق من كل الملفات في الوجهة"
            ))
            onStatus("تم التحقق من وصول $verifiedFiles ملفًا (" + CopyEngine.formatBytes(verifiedBytes) + ") إلى: " + game.destinationLabel)
            return@withContext
        }

        val stagedFiles = collectStagedFiles(stagingGame) { ensureTransferActive(transferContext) }
        if (stagedFiles.isEmpty()) throw IOException("مجلد التجهيز لا يحتوي أي ملفات")
        val stagedBytes = stagedFiles.sumOf { it.sizeBytes }
        val stagedCount = stagedFiles.size
        val routeData = File(stagingGame, "__kafianet_data")
        val routeObb = File(stagingGame, "__kafianet_obb")
        val needsRouting = game.targetPaths.size > 1 && !routeData.exists() && !routeObb.exists()
        val phaseCount = if (needsRouting) 3L else 2L
        fun normalizedBytes(completedPhases: Int, currentPhaseBytes: Long): Long =
            ((stagedBytes.toDouble() * completedPhases + currentPhaseBytes.toDouble()) / phaseCount)
                .toLong().coerceIn(0L, stagedBytes)
        fun normalizedFiles(completedPhases: Int, currentPhaseFiles: Int): Int =
            ((stagedCount.toDouble() * completedPhases + currentPhaseFiles.toDouble()) / phaseCount)
                .toInt().coerceIn(0, stagedCount)
        val plannedDestinations = ArrayList<DestinationPlan>()

        if (game.targetPaths.size == 1) {
            val destination = GameCatalog.absoluteTargetPath(game.targetPaths.single())
            plannedDestinations.add(DestinationPlan(stagingGame, destination, destination, stagedBytes, stagedCount))
        } else {
            if (needsRouting) {
                onStatus("المرحلة 2/3: تجهيز مسارات البيانات وOBB")
                val routeTotalBytes = stagedBytes
                val routeTotalFiles = stagedCount
                var fileBytes = 0L
                var filesDone = 0
                var lastUpdate = SystemClock.elapsedRealtime()

                // __kafianet_data and __kafianet_obb live under the same staging tree.
                // Route each file with a filesystem rename instead of reading/writing its
                // entire payload again before the final protected-destination transfer.
                for (entry in stagedFiles) {
                    ensureTransferActive(transferContext)
                    val originalRelative = entry.relativePath
                    val obb = routeFileToObb(originalRelative)
                    val routeRoot = if (obb) routeObb else routeData
                    val stripped = stripDestinationPrefix(originalRelative, obb)
                    val output = File(routeRoot, stripped).canonicalFile
                    if (!output.path.startsWith(routeRoot.canonicalPath + File.separator)) {
                        throw IOException("رُفض مسار توجيه غير آمن: " + originalRelative)
                    }
                    val outputParent = output.parentFile ?: throw IOException("مسار توجيه غير صالح: " + output)
                    if (!outputParent.exists() && !outputParent.mkdirs()) {
                        throw IOException("تعذّر إنشاء مجلد التوجيه: " + outputParent)
                    }
                    if (output.exists()) throw IOException("تكرر مسار التوجيه: " + originalRelative)

                    try {
                        Files.move(entry.file.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(entry.file.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    if (entry.file.exists() || !output.isFile || output.length() != entry.sizeBytes) {
                        throw IOException("فشل التحقق من نقل الملف إلى مسار البيانات/OBB: " + originalRelative)
                    }
                    fileBytes = Math.addExact(fileBytes, entry.sizeBytes)
                    filesDone++
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 250L || filesDone == routeTotalFiles) {
                        onProgress(TransferProgress(
                            bytesDone = normalizedBytes(1, fileBytes),
                            totalBytes = stagedBytes,
                            filesDone = normalizedFiles(1, filesDone),
                            totalFiles = stagedCount,
                            currentFile = originalRelative,
                            bytesPerSecond = 0L,
                            phase = "المرحلة 2/3: نقل ملفات التجهيز لمسارات البيانات وOBB دون إعادة نسخها"
                        ))
                        lastUpdate = now
                    }
                }
                if (fileBytes != routeTotalBytes || filesDone != routeTotalFiles) {
                    throw IOException("لم يكتمل تجهيز مسارات البيانات وOBB")
                }
                // The routing phase is tracked against the original payload size, not duplicated bytes.
            }

            val dataTarget = GameCatalog.absoluteTargetPath(game.targetPaths[0])
            val obbTarget = GameCatalog.absoluteTargetPath(game.targetPaths[1])
            val dataStats = if (routeData.isDirectory) collectStagedFiles(routeData) { ensureTransferActive(transferContext) } else emptyList()
            val obbStats = if (routeObb.isDirectory) collectStagedFiles(routeObb) { ensureTransferActive(transferContext) } else emptyList()
            if (dataStats.isNotEmpty()) {
                plannedDestinations.add(DestinationPlan(routeData, dataTarget, "Android/data: " + dataTarget, dataStats.sumOf { it.sizeBytes }, dataStats.size))
            }
            if (obbStats.isNotEmpty()) {
                plannedDestinations.add(DestinationPlan(routeObb, obbTarget, "Android/obb: " + obbTarget, obbStats.sumOf { it.sizeBytes }, obbStats.size))
            }
            if (plannedDestinations.isEmpty()) throw IOException("لا توجد ملفات لتوجيهها إلى مسارات eFootball")
        }

        val targetBytes = plannedDestinations.sumOf { it.bytes }
        val targetFiles = plannedDestinations.sumOf { it.files }
        if (targetBytes != stagedBytes || targetFiles != stagedCount) {
            throw IOException(
                "توقف النسخ قبل الكتابة: محتوى الوجهة المخطط لا يطابق المصدر (بايتات $targetBytes/$stagedBytes، ملفات $targetFiles/$stagedCount)"
            )
        }

        var completedTargetBytes = 0L
        var completedTargetFiles = 0

        plannedDestinations.forEach { plan ->
            ensureTransferActive(transferContext)
            val phaseNumber = if (needsRouting) "3/3" else "2/2"
            onStatus("المرحلة $phaseNumber: كتابة الملفات إلى الوجهة والتحقق منها — " + plan.label)
            val destinationSpeed = TransferSpeedMeter()

            ensureTransferActive(transferContext)
            if (!ShizukuServiceConnection.prepareCopy()) {
                throw IOException("تعذّر تهيئة خدمة Shizuku لعملية نسخ جديدة؛ أعد ربط الخدمة")
            }
            // The second check closes the cancellation race between preparing the remote copy
            // and issuing its blocking Binder transaction.
            ensureTransferActive(transferContext)
            val targetCopyReturned = java.util.concurrent.atomic.AtomicBoolean(false)
            val errorMessage = ShizukuServiceConnection.moveDirectoryWithProgressAndVerify(
                plan.source.absolutePath,
                plan.destination
            ) { bytesDone, callbackTotalBytes, filesDone, callbackTotalFiles, currentFile ->
                if (targetCopyReturned.get()) {
                    // A one-way Binder callback can arrive after the result summary was handled.
                } else if (currentFile.startsWith("__KAFIANET_SCAN__:")) {
                    val completedPhasesBeforeTarget = if (needsRouting) 2 else 1
                    if (callbackTotalBytes > 0L) {
                        destinationSpeed.reset(0L, SystemClock.elapsedRealtime())
                    }
                    onProgress(TransferProgress(
                        bytesDone = normalizedBytes(completedPhasesBeforeTarget, completedTargetBytes),
                        totalBytes = stagedBytes,
                        filesDone = normalizedFiles(completedPhasesBeforeTarget, completedTargetFiles),
                        totalFiles = stagedCount,
                        currentFile = currentFile.removePrefix("__KAFIANET_SCAN__:"),
                        bytesPerSecond = 0L,
                        phase = if (callbackTotalBytes > 0L)
                            "اكتمل حساب الحجم الكامل — بدء النسخ السريع إلى الوجهة"
                        else "فحص كامل ملفات الوجهة قبل بدء الكتابة"
                    ))
                } else {
                    val completedPhasesBeforeTarget = if (needsRouting) 2 else 1
                    val isMetadataMove = currentFile.startsWith("__KAFIANET_MOVE__:")
                    val actualCopy = currentFile.startsWith("__KAFIANET_COPY__:")
                    val cleanFile = currentFile
                        .removePrefix("__KAFIANET_MOVE__:")
                        .removePrefix("__KAFIANET_COPY__:")
                    val now = SystemClock.elapsedRealtime()
                    val verifiedProgressBytes = normalizedBytes(
                        completedPhasesBeforeTarget,
                        completedTargetBytes + bytesDone
                    ).coerceAtMost((stagedBytes - 1L).coerceAtLeast(0L))
                    onProgress(TransferProgress(
                        bytesDone = verifiedProgressBytes,
                        totalBytes = stagedBytes,
                        filesDone = normalizedFiles(completedPhasesBeforeTarget, completedTargetFiles + filesDone),
                        totalFiles = stagedCount,
                        currentFile = cleanFile,
                        bytesPerSecond = if (isMetadataMove) 0L else destinationSpeed.update(bytesDone, now),
                        phase = when {
                            isMetadataMove -> "نقل الملفات المجهزة إلى الوجهة دون إعادة نسخ البيانات"
                            actualCopy -> "نسخ بيانات الوجهة (احتياطيًا) والتحقق من الأحجام"
                            else -> "المرحلة $phaseNumber: نقل الملفات المجهزة والتحقق من الوجهة"
                        }
                    ))
                }
            }
            targetCopyReturned.set(true)
            ensureTransferActive(transferContext)
            if (errorMessage.startsWith("CANCELLED:")) {
                throw kotlinx.coroutines.CancellationException(errorMessage.removePrefix("CANCELLED:").trim())
            }
            val copySummary = errorMessage.split(':')
            if (!errorMessage.startsWith("SUCCESS:") || copySummary.size != 3) {
                throw IOException("فشل النسخ إلى " + plan.destination + " — " + errorMessage)
            }
            val copiedBytes = copySummary[1].toLongOrNull()
                ?: throw IOException("حجم النسخ المتحقق غير صالح للوجهة " + plan.destination)
            val copiedFiles = copySummary[2].toIntOrNull()
                ?: throw IOException("عدد الملفات المتحقق غير صالح للوجهة " + plan.destination)
            if (copiedBytes != plan.bytes || copiedFiles != plan.files) {
                throw IOException(
                    "فشل التحقق النهائي للوجهة " + plan.destination +
                        ": بايتات $copiedBytes/${plan.bytes}، ملفات $copiedFiles/${plan.files}"
                )
            }
            completedTargetBytes += plan.bytes
            completedTargetFiles += plan.files
        }

        if (completedTargetBytes != targetBytes || completedTargetFiles != targetFiles) {
            throw IOException("خدمة Shizuku لم تؤكد كتابة كل الملفات إلى الوجهة")
        }
        onProgress(TransferProgress(
            bytesDone = stagedBytes,
            totalBytes = stagedBytes,
            filesDone = stagedCount,
            totalFiles = stagedCount,
            currentFile = game.destinationLabel,
            bytesPerSecond = 0L,
            phase = "اكتمل النقل والتحقق من الملفات في الوجهة"
        ))
        onStatus("تم التحقق من وصول كل الملفات وأحجامها إلى: " + game.destinationLabel)
    }

    private fun collectStagedFiles(root: File, ensureActive: () -> Unit = {}): List<StagedFile> {
        val canonicalRoot = root.canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        val result = ArrayList<StagedFile>()
        fun walk(node: File, depth: Int) {
            ensureActive()
            if (depth > MAX_DEPTH) throw IOException("تداخل المجلدات في التجهيز تجاوز الحد")
            val canonical = node.canonicalFile
            if (canonical.path != canonicalRoot.path && !canonical.path.startsWith(rootPrefix)) {
                throw IOException("رُفض عنصر خارج مجلد التجهيز: " + node)
            }
            if (canonical.isDirectory) {
                val children = canonical.listFiles() ?: throw IOException("تعذّرت قراءة مجلد التجهيز: " + canonical)
                children.sortedBy { it.name.lowercase() }.forEach { walk(it, depth + 1) }
            } else if (canonical.isFile) {
                val relative = canonical.path.removePrefix(rootPrefix).replace(File.separatorChar, '/')
                val size = canonical.length()
                if (relative.isBlank() || size < 0L) throw IOException("ملف تجهيز غير صالح: " + canonical)
                result.add(StagedFile(canonical, CopyEngine.safeRelativePath(relative), size))
                if (result.size > MAX_FILES) throw IOException("عدد ملفات التجهيز تجاوز الحد")
            } else {
                throw IOException("عنصر تجهيز غير معروف: " + canonical)
            }
        }
        walk(canonicalRoot, 0)
        return result
    }

    fun cancel() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun listGames(libraryPath: String): List<RemoteGame> {
        requireShizukuReady()
        val children = ShizukuServiceConnection.listChildren(libraryPath).associateBy { File(it).name }
        if (children.size > MAX_GAMES * 4) throw IOException("مكتبة المصدر تحتوي عناصر أكثر من المتوقع")
        // Inspect only the known game folders. Do not recurse through their files at discovery time.
        return GameCatalog.games.mapNotNull { game ->
            val path = children[game.sourceFolder] ?: return@mapNotNull null
            if (ShizukuServiceConnection.pathType(path) != 2) return@mapNotNull null
            RemoteGame(game.sourceFolder, -1L, -1)
        }
    }

    private fun collectSourceFiles(rootPath: String): List<SourceFile> {
        requireShizukuReady()
        val root = File(rootPath).absoluteFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        val result = ArrayList<SourceFile>()

        fun walk(path: String, depth: Int) {
            if (depth > MAX_DEPTH) throw IOException("المجلدات متداخلة أكثر من الحد المسموح")
            when (ShizukuServiceConnection.pathType(path)) {
                1 -> {
                    val size = ShizukuServiceConnection.fileLength(path)
                    if (size < 0L) throw IOException("تعذّر قراءة حجم الملف: $path")
                    val file = File(path).absoluteFile
                    val rel = file.path.removePrefix(rootPrefix)
                    if (rel == file.path) throw IOException("مسار ملف خارج مجلد اللعبة")
                    if (!rel.contains(File.separatorChar) && file.name.endsWith(".apk", ignoreCase = true)) return
                    result.add(SourceFile(file.path, CopyEngine.safeRelativePath(rel), size))
                    if (result.size > MAX_FILES) throw IOException("عدد الملفات يتجاوز الحد المسموح ($MAX_FILES)")
                }
                2 -> {
                    ShizukuServiceConnection.listChildren(path).forEach { child -> walk(child, depth + 1) }
                }
                else -> throw IOException("تعذّر الوصول إلى الملف أو المجلد: $path")
            }
        }
        walk(root.path, 0)
        return result
    }

    private fun handleClient(libraryPath: String, socket: Socket, onStatus: (String) -> Unit) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
        val magic = input.readUTF()
        if (magic != MAGIC) {
            output.writeBoolean(false)
            output.writeUTF("إصدار بروتوكول النقل غير متوافق؛ حدّث التطبيق على الهاتفين")
            output.flush()
            return
        }

        when (input.readUTF()) {
            "LIST" -> {
                val games = listGames(libraryPath)
                output.writeBoolean(true)
                output.writeInt(games.size)
                games.forEach {
                    output.writeUTF(it.name)
                    output.writeLong(it.sizeBytes)
                    output.writeInt(it.fileCount)
                }
                output.flush()
                onStatus("تم عرض ${games.size} لعبة معروفة على جهاز متصل")
            }
            "LIST_APKS" -> {
                val requestedName = CopyEngine.safeRelativeSegment(input.readUTF())
                val directoryPath = if (GameCatalog.bySourceFolder(requestedName) != null) {
                    ShizukuServiceConnection.listChildren(libraryPath)
                        .firstOrNull { File(it).name == requestedName && ShizukuServiceConnection.pathType(it) == 2 }
                } else null
                if (directoryPath == null) {
                    output.writeBoolean(false)
                    output.writeUTF("مجلد اللعبة غير موجود أو غير مدعوم")
                    output.flush()
                    return
                }
                val apks = ShizukuServiceConnection.listChildren(directoryPath).mapNotNull { path ->
                    val name = File(path).name
                    if (!name.endsWith(".apk", ignoreCase = true) || ShizukuServiceConnection.pathType(path) != 1) return@mapNotNull null
                    val size = ShizukuServiceConnection.fileLength(path)
                    if (size <= 0L || size > MAX_TOTAL_BYTES) return@mapNotNull null
                    AvailableGameApk(name, size)
                }.sortedBy { it.name.lowercase() }.take(128)
                output.writeBoolean(true)
                output.writeInt(apks.size)
                apks.forEach {
                    output.writeUTF(CopyEngine.safeRelativeSegment(it.name))
                    output.writeLong(it.sizeBytes)
                }
                output.flush()
                onStatus("تم العثور على ${apks.size} ملفات APK في جذر " + requestedName)
            }
            "FETCH_APK" -> {
                val requestedName = CopyEngine.safeRelativeSegment(input.readUTF())
                val requestedApk = CopyEngine.safeRelativeSegment(input.readUTF())
                val directoryPath = if (GameCatalog.bySourceFolder(requestedName) != null) {
                    ShizukuServiceConnection.listChildren(libraryPath)
                        .firstOrNull { File(it).name == requestedName && ShizukuServiceConnection.pathType(it) == 2 }
                } else null
                val apkPath = if (directoryPath != null && requestedApk.endsWith(".apk", ignoreCase = true)) {
                    ShizukuServiceConnection.listChildren(directoryPath)
                        .firstOrNull { File(it).name == requestedApk && ShizukuServiceConnection.pathType(it) == 1 }
                } else null
                if (apkPath == null) {
                    output.writeBoolean(false)
                    output.writeUTF("ملف APK غير موجود في جذر مجلد اللعبة المحددة")
                    output.flush()
                    return
                }
                val size = ShizukuServiceConnection.fileLength(apkPath)
                if (size <= 0L || size > MAX_TOTAL_BYTES) {
                    output.writeBoolean(false)
                    output.writeUTF("حجم ملف APK غير صالح")
                    output.flush()
                    return
                }
                output.writeBoolean(true)
                output.writeLong(size)
                output.flush()
                val descriptor = ShizukuServiceConnection.openFileForRead(apkPath)
                    ?: throw IOException("تعذّر فتح APK من الهاتف المصدر")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { src ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var remaining = size
                    while (remaining > 0L) {
                        val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = src.read(buffer, 0, wanted)
                        if (read < 0) throw EOFException("انقطع APK أثناء الإرسال")
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    if (src.read() != -1) throw IOException("تغيّر حجم APK أثناء الإرسال")
                }
                output.flush()
                onStatus("تم إرسال APK " + requestedApk + " (" + CopyEngine.formatBytes(size) + ")")
            }
            "FETCH" -> {
                val requestedName = CopyEngine.safeRelativeSegment(input.readUTF())
                val catalogGame = GameCatalog.bySourceFolder(requestedName)
                val directoryPath = if (catalogGame != null) {
                    ShizukuServiceConnection.listChildren(libraryPath)
                        .firstOrNull { File(it).name == requestedName && ShizukuServiceConnection.pathType(it) == 2 }
                } else null
                if (directoryPath == null) {
                    output.writeBoolean(false)
                    output.writeUTF("هذه اللعبة ليست ضمن كتالوج النقل أو لم تعد موجودة في «العابي»")
                    output.flush()
                    return
                }

                val files = collectSourceFiles(directoryPath)
                if (files.isEmpty() || files.size > MAX_FILES) {
                    output.writeBoolean(false)
                    output.writeUTF("مجلد اللعبة فارغ أو يحتوي عدد ملفات غير مسموح")
                    output.flush()
                    return
                }
                val totalBytes = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
                if (totalBytes > MAX_TOTAL_BYTES) {
                    output.writeBoolean(false)
                    output.writeUTF("حجم اللعبة يتجاوز الحد الآمن")
                    output.flush()
                    return
                }

                output.writeBoolean(true)
                output.writeInt(files.size)
                output.writeLong(totalBytes)
                files.forEach {
                    output.writeUTF(CopyEngine.safeRelativePath(it.relativePath))
                    output.writeLong(it.sizeBytes)
                }
                output.flush()
                if (!input.readBoolean()) throw IOException("رفض الهاتف المستقبِل قائمة ملفات اللعبة")

                var sentBytes = 0L
                var lastFlushBytes = 0L
                var filesSinceFlush = 0
                val sendBuffer = ByteArray(BUFFER_SIZE)
                files.forEachIndexed { fileIndex, file ->
                    val descriptor = ShizukuServiceConnection.openFileForRead(file.absolutePath)
                        ?: throw IOException("تعذّر فتح ملف المصدر: ${file.relativePath}")
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { src ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        var remaining = file.sizeBytes
                        while (remaining > 0L) {
                            val wanted = minOf(sendBuffer.size.toLong(), remaining).toInt()
                            val read = src.read(sendBuffer, 0, wanted)
                            if (read < 0) throw EOFException("تغيّر حجم ملف المصدر: ${file.relativePath}")
                            if (read == 0) continue
                            output.write(sendBuffer, 0, read)
                            digest.update(sendBuffer, 0, read)
                            remaining -= read
                            sentBytes += read
                        }
                        if (src.read() != -1) throw IOException("تغيّر حجم ملف المصدر أثناء النسخ: ${file.relativePath}")
                        output.write(digest.digest())
                    }
                    filesSinceFlush++
                    if (sentBytes - lastFlushBytes >= FLUSH_THRESHOLD_BYTES ||
                        filesSinceFlush >= 32 || fileIndex == files.lastIndex
                    ) {
                        output.flush()
                        lastFlushBytes = sentBytes
                        filesSinceFlush = 0
                    }
                }
                output.flush()
                if (!input.readBoolean()) throw IOException("لم يؤكد الهاتف المستقبِل سلامة جميع الملفات")
                output.writeUTF("DONE")
                output.flush()
                onStatus("تم إرسال ${catalogGame?.arabic ?: requestedName} (${CopyEngine.formatBytes(sentBytes)})")
            }
            else -> {
                output.writeBoolean(false)
                output.writeUTF("طلب غير معروف")
                output.flush()
            }
        }
    }

    private fun connect(host: String): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, PORT), 12_000)
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.tcpNoDelay = true
        activeSocket = socket
        return socket
    }

    private fun startRequest(output: DataOutputStream, command: String) {
        output.writeUTF(MAGIC)
        output.writeUTF(command)
        output.flush()
    }

    private fun checkServerAccepted(input: DataInputStream) {
        if (!input.readBoolean()) {
            val message = runCatching { input.readUTF() }.getOrDefault("رفض هاتف المصدر الاتصال")
            throw IOException(message)
        }
    }

    private fun validateEndpoint(host: String) {
        require(host.matches(Regex("^[0-9a-fA-F:.%]+$"))) { "عنوان IP غير صالح؛ اكتب عنوان الهاتف المصدر فقط" }
    }

    /** Public preflight so the UI can fail on Shizuku before copying gigabytes into staging. */
    suspend fun ensureShizukuReady() {
        awaitShizukuReady()
    }

    private suspend fun awaitShizukuReady() {
        if (!ShizukuHelper.hasPermission()) {
            throw IOException("خدمة Shizuku غير متاحة أو إذن كافية نت غير ممنوح؛ افتح Shizuku وتحقق من الإذن.")
        }

        if (ShizukuServiceConnection.isReady()) return
        ShizukuServiceConnection.bind()
        repeat(100) {
            ensureTransferActive(kotlinx.coroutines.currentCoroutineContext())
            if (ShizukuServiceConnection.isReady()) return
            // If a stale service fails its remote ping, bind() must be retried instead of
            // waiting the full timeout with a dead Binder reference.
            if (!ShizukuServiceConnection.isBinding()) ShizukuServiceConnection.bind()
            kotlinx.coroutines.delay(100)
        }

        // A live old UserService can have an alive Binder but a stale AIDL interface.
        // Retry by recreating the versioned UserService, then require a successful ping RPC.
        ShizukuServiceConnection.retryBinding()
        repeat(150) {
            ensureTransferActive(kotlinx.coroutines.currentCoroutineContext())
            if (ShizukuServiceConnection.isReady()) return
            if (!ShizukuServiceConnection.isBinding()) ShizukuServiceConnection.bind()
            kotlinx.coroutines.delay(100)
        }

        throw IOException(
            "تعذّر تشغيل خدمة ملفات Shizuku بعد إعادة الربط: " +
                (ShizukuServiceConnection.bindFailureReason() ?: "الخدمة لم تجتز اختبار الاتصال الفعلي")
        )
    }

    private fun requireShizukuReady() {
        if (!ShizukuHelper.hasPermission()) throw IOException("يجب تشغيل Shizuku ومنح كافية نت الإذن للوصول إلى المسارات الثابتة")
        if (!ShizukuServiceConnection.isReady()) throw IOException("خدمة Shizuku غير جاهزة؛ أعد فحص الإذن ثم حاول مجددًا")
    }

    private fun uniqueDirectory(parent: File, requestedName: String): File {
        var candidate = File(parent, requestedName)
        var suffix = 2
        while (candidate.exists() && suffix < 10_000) {
            candidate = File(parent, "$requestedName ($suffix)")
            suffix++
        }
        if (suffix >= 10_000) throw IOException("تعذّر إنشاء اسم مجلد مؤقت فريد")
        return candidate
    }

    private fun routeFileToObb(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        return p.startsWith("obb/") || p.startsWith("android/obb/") ||
            p.startsWith("android/obb/com.konami.pesam/") || p.substringAfterLast('/').endsWith(".obb")
    }

    private fun stageRelativeForMultipleTargets(path: String): String {
        val isObb = routeFileToObb(path)
        val stripped = stripDestinationPrefix(path, isObb)
        return (if (isObb) "__kafianet_obb" else "__kafianet_data") + "/" + stripped
    }

    private fun stripDestinationPrefix(path: String, obb: Boolean): String {
        val normalized = CopyEngine.safeRelativePath(path)
        val parts = normalized.split('/')
        val lower = parts.map { it.lowercase() }
        var drop = 0
        val expectedRoot = if (obb) "obb" else "data"
        if (lower.size >= 4 && lower[0] == "android" && lower[1] == expectedRoot &&
            lower[2] == "com.konami.pesam") {
            drop = 3
        } else if (lower.size >= 2 && lower[0] == "android" && lower[1] == expectedRoot) {
            drop = 2
        } else if (lower.isNotEmpty() && lower[0] == expectedRoot) {
            drop = 1
        }
        val stripped = parts.drop(drop).joinToString("/")
        return CopyEngine.safeRelativePath(stripped.ifBlank { parts.last() })
    }
}
