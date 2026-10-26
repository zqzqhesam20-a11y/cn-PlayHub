package com.kafianet.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException

private const val APP_PASSWORD = "dontplaywithreal"
private const val PREFS_NAME = "kafianet_control_prefs"
private const val PREF_USB_URI = "usb_tree_uri"

private val Forest = Color(0xFF09110D)
private val Panel = Color(0xFF121F18)
private val CardGreen = Color(0xFF17271E)
private val Neon = Color(0xFFA5F36B)
private val Mint = Color(0xFF48D6A0)
private val Muted = Color(0xFF9BAFA2)
private val Amber = Color(0xFFFFC56E)
private val Danger = Color(0xFFFF7777)

class MainActivity : ComponentActivity() {
    companion object { const val SHIZUKU_PERMISSION_CODE = 100 }
    private val shizukuRevision = mutableIntStateOf(0)
    private var lastShizukuMonitorSnapshot = ""

    /** Poll Binder, permission, and actual remote file-service health while the UI is visible. */
    fun refreshShizukuMonitor() {
        val available = ShizukuHelper.isAvailable()
        val permission = available && ShizukuHelper.hasPermission()
        if (available && permission &&
            !ShizukuServiceConnection.isReady() &&
            !ShizukuServiceConnection.isBinding() &&
            ShizukuServiceConnection.bindFailureReason() == null
        ) {
            runCatching { ShizukuServiceConnection.bind() }
        }
        ShizukuServiceConnection.pollHealth()

        val ready = ShizukuServiceConnection.isReady()
        val binding = ShizukuServiceConnection.isBinding()
        val failure = ShizukuServiceConnection.bindFailureReason().orEmpty()
        val snapshot = "$available|$permission|$ready|$binding|$failure"
        if (snapshot != lastShizukuMonitorSnapshot) {
            lastShizukuMonitorSnapshot = snapshot
            shizukuRevision.intValue++
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE) runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) runCatching { ShizukuServiceConnection.bind() }
            shizukuRevision.intValue++
        }
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            if (ShizukuHelper.hasPermission()) runCatching { ShizukuServiceConnection.bind() }
            shizukuRevision.intValue++
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            runCatching { ShizukuServiceConnection.unbind() }
            shizukuRevision.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        ShizukuServiceConnection.setStateListener { runOnUiThread { shizukuRevision.intValue++ } }
        if (ShizukuHelper.hasPermission()) runCatching { ShizukuServiceConnection.bind() }

        setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl
            ) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Neon, onPrimary = Color(0xFF13200F), secondary = Mint,
                        background = Forest, surface = Panel, onSurface = Color.White
                    )
                ) {
                    KafiaNetApp(shizukuRevision.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuMonitor()
        if (ShizukuHelper.hasPermission()) ShizukuServiceConnection.bind()
        shizukuRevision.intValue++
    }

    override fun onDestroy() {
        ShizukuServiceConnection.setStateListener(null)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        runCatching { ShizukuServiceConnection.unbind() }
        super.onDestroy()
    }
}

@Composable
private fun KafiaNetApp(shizukuRevision: Int) {
    // Keep the unlock state in memory only: a fresh activity session starts at the password gate.
    var unlocked by remember { mutableStateOf(false) }
    if (unlocked) TransferDashboard(shizukuRevision) else PasswordGate { unlocked = true }
}

@Composable
private fun PasswordGate(onUnlock: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF07100B), Color(0xFF1B3020), Color(0xFF080D0A))))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(30.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(82.dp).background(Brush.linearGradient(listOf(Neon, Mint)), CircleShape), contentAlignment = Alignment.Center) {
                Text("KN", color = Color(0xFF102015), fontSize = 27.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(20.dp))
            Text("cn-PlayHub", fontSize = 31.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("هشام الريمي", fontSize = 15.sp, color = Neon, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(28.dp))
            Text("أدخل كلمة المرور", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("الوصول إلى أدوات النسخ والنقل محمي", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = false },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("كلمة المرور") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(16.dp), isError = error,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Neon, focusedLabelColor = Neon, cursorColor = Neon)
            )
            if (error) Text("كلمة المرور غير صحيحة", color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { if (password == APP_PASSWORD) onUnlock() else error = true },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("دخول آمن  →", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Text("كلمة المرور مطلوبة عند فتح واجهة جديدة.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

private val gameItems = GameCatalog.games

@Composable
private fun TransferDashboard(shizukuRevision: Int) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activity) {
        while (true) {
            activity?.refreshShizukuMonitor()
            delay(1_500L)
        }
    }

    var tab by rememberSaveable { mutableStateOf("USB") }
    var phoneRole by rememberSaveable { mutableStateOf("receiver") }
    var status by remember { mutableStateOf("جاهز — اختر طريقة النسخ أو النقل") }
    var transferBusy by remember { mutableStateOf(false) }
    var hosting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(TransferProgress()) }
    var transferJob by remember { mutableStateOf<Job?>(null) }
    var hostJob by remember { mutableStateOf<Job?>(null) }

    var usbUri by remember { mutableStateOf(prefs.getString(PREF_USB_URI, null)?.let(Uri::parse)) }

    var activeHostIp by rememberSaveable { mutableStateOf("") }
    var destinationIp by rememberSaveable { mutableStateOf("") }
    var localIps by remember { mutableStateOf(HotspotTransfer.localIpv4Addresses()) }
    var remoteGames by remember { mutableStateOf<List<HotspotTransfer.RemoteGame>>(emptyList()) }
    var pcGames by remember { mutableStateOf<List<HotspotTransfer.RemoteGame>>(emptyList()) }
    var pendingNetworkAction by remember { mutableStateOf("") }
    var grantedNetworkAction by remember { mutableStateOf("") }
    var pendingGame by remember { mutableStateOf<HotspotTransfer.RemoteGame?>(null) }
    var pendingInstallGame by remember { mutableStateOf<GameItem?>(null) }
    var installChoices by remember { mutableStateOf<List<ApkInstallChoice>>(emptyList()) }
    var installChooserTitle by remember { mutableStateOf("اختر ملف APK") }

    fun updateStatus(value: String) {
        activity?.runOnUiThread { status = value } ?: run { status = value }
    }
    fun updateProgress(value: TransferProgress) {
        activity?.runOnUiThread {
            progress = value
            val scanning = value.phase.startsWith("فحص الملفات قبل النسخ") ||
                value.phase.startsWith("فحص الملفات قبل بدء الكتابة")
            val notificationText = when {
                scanning -> value.phase + " • " + value.currentFile.take(90)
                value.totalBytes > 0L -> value.percent.toString() + "% • " +
                    CopyEngine.formatBytes(value.bytesDone) + " / " + CopyEngine.formatBytes(value.totalBytes)
                else -> value.phase + if (value.currentFile.isNotBlank()) " • " + value.currentFile.take(90) else ""
            }
            TransferForegroundService.update(context, notificationText)
        } ?: run { progress = value }
    }
    fun beginTransfer(notification: String) {
        transferBusy = true
        progress = TransferProgress(phase = "جارٍ التحضير")
        TransferForegroundService.start(context, notification)
    }
    fun endTransfer() {
        transferBusy = false
        TransferForegroundService.stop(context)
    }

    fun cancelActiveTransfer() {
        if (!transferBusy) return
        status = "جارٍ إيقاف النسخ فعليًا؛ انتظر تأكيد توقف الكتابة وإزالة الإشعار"
        TransferForegroundService.markCancellationRequested()
        // Cancels the current coroutine as well as the remote Shizuku copy and any hotspot socket.
        transferJob?.cancel()
    }

    fun installApkChoice(choice: ApkInstallChoice) {
        if (!ApkInstaller.requestInstallPermission(context)) {
            status = "فعّل إذن تثبيت التطبيقات لهذا التطبيق ثم اضغط تثبيت مرة أخرى"
            return
        }
        if (transferBusy) return
        beginTransfer("تجهيز APK: " + choice.name)
        transferJob = scope.launch {
            try {
                val progressCallback: (Long, Long, Long) -> Unit = { done, total, speed ->
                    updateProgress(TransferProgress(
                        bytesDone = if (total > 0L) done.coerceAtMost(total) else done,
                        totalBytes = total,
                        filesDone = if (total > 0L && done >= total) 1 else 0,
                        totalFiles = if (total > 0L) 1 else 0,
                        currentFile = choice.name,
                        bytesPerSecond = speed,
                        phase = "تنزيل ملف APK والتحقق من الحجم"
                    ))
                }
                val apkFile = when (choice.source) {
                    "USB" -> {
                        val uri = choice.contentUri?.let(Uri::parse)
                            ?: throw IOException("رابط ملف APK من USB غير متاح")
                        ApkInstaller.copyStreamToCache(context, choice.name, choice.sizeBytes, {
                            context.contentResolver.openInputStream(uri)
                                ?: throw IOException("تعذّر فتح ملف APK من USB")
                        }, progressCallback)
                    }
                    "SERVER" -> {
                        val game = GameCatalog.bySourceFolder(choice.gameFolder)
                            ?: throw IOException("مجلد اللعبة غير موجود في الكتالوج")
                        LanLibraryTransfer.downloadGameApk(context, game, choice.name, progressCallback, ::updateStatus)
                    }
                    "PHONE" -> {
                        val host = choice.host ?: throw IOException("عنوان الهاتف المصدر غير متاح")
                        HotspotTransfer.downloadGameApk(context, host, choice.gameFolder, choice.name, progressCallback, ::updateStatus)
                    }
                    else -> throw IOException("مصدر APK غير معروف")
                }
                ApkInstaller.launchInstaller(context, apkFile)
                status = "تم فتح مثبت Android للملف " + choice.name + "؛ أكّد التثبيت في نافذة النظام"
            } catch (cancelled: CancellationException) {
                status = "أُوقف تجهيز ملف APK"
                throw cancelled
            } catch (error: Exception) {
                status = "تعذّر تجهيز APK: " + (error.message ?: error.javaClass.simpleName)
            } finally {
                endTransfer()
            }
        }
    }

    fun showApkChoices(game: GameItem, choices: List<ApkInstallChoice>) {
        if (choices.isEmpty()) {
            status = "لم يوجد ملف APK مباشر في جذر مجلد اللعبة: " + game.sourceFolder
            return
        }
        installChooserTitle = "اختر APK — " + game.arabic
        installChoices = choices.sortedBy { it.name.lowercase() }
    }

    fun requestUsbInstall(game: GameItem) {
        if (transferBusy) return
        val uri = usbUri
        if (uri == null) { status = "اختر USB أولًا"; return }
        val root = DocumentFile.fromTreeUri(context, uri)?.findFile("العابي")?.findFile(game.sourceFolder)
        if (root == null || !root.isDirectory) {
            status = "لم يُعثر على مجلد اللعبة داخل USB: " + game.sourceFolder
            return
        }
        val choices = root.listFiles()
            .filter { it.isFile && (it.name ?: "").endsWith(".apk", ignoreCase = true) }
            .map { ApkInstallChoice(it.name ?: "game.apk", it.length().coerceAtLeast(0L), "USB", game.sourceFolder, contentUri = it.uri.toString()) }
        showApkChoices(game, choices)
    }

    fun requestServerInstall(game: GameItem) {
        if (transferBusy) return
        transferBusy = true
        status = "البحث عن APK في جذر " + game.sourceFolder
        progress = TransferProgress(phase = "البحث المباشر عن APK دون فهرسة ملفات اللعبة")
        scope.launch {
            try {
                val choices = LanLibraryTransfer.listGameApks(game, ::updateStatus).map {
                    ApkInstallChoice(it.name, it.sizeBytes, "SERVER", game.sourceFolder)
                }
                showApkChoices(game, choices)
            } catch (error: Exception) {
                status = "تعذّر البحث عن APK في السيرفر: " + (error.message ?: error.javaClass.simpleName)
            } finally {
                transferBusy = false
            }
        }
    }

    fun requestPhoneInstall(game: GameItem) {
        if (transferBusy) return
        val host = destinationIp.trim().ifBlank { HotspotTransfer.wifiGatewayIpv4Addresses(context).firstOrNull().orEmpty() }
        if (host.isBlank()) {
            status = "اتصل بهاتف مصدر الألعاب أولًا ثم اضغط تثبيت"
            return
        }
        destinationIp = host
        transferBusy = true
        status = "البحث عن APK في جذر " + game.sourceFolder + " على الهاتف المصدر"
        progress = TransferProgress(phase = "البحث المباشر عن APK")
        scope.launch {
            try {
                val choices = HotspotTransfer.listGameApks(host, game.sourceFolder, ::updateStatus).map {
                    ApkInstallChoice(it.name, it.sizeBytes, "PHONE", game.sourceFolder, host = host)
                }
                showApkChoices(game, choices)
            } catch (error: Exception) {
                status = "تعذّر البحث عن APK على الهاتف المصدر: " + (error.message ?: error.javaClass.simpleName)
            } finally {
                transferBusy = false
            }
        }
    }

    fun requestShizukuAccess() {
        when {
            !ShizukuHelper.isAvailable() ->
                updateStatus("افتح تطبيق Shizuku وابدأ الخدمة أولًا؛ ستتغير الحالة تلقائيًا عند اتصاله")
            !ShizukuHelper.hasPermission() -> {
                val request = runCatching { Shizuku.requestPermission(MainActivity.SHIZUKU_PERMISSION_CODE) }
                updateStatus(
                    if (request.isSuccess) "تم طلب إذن Shizuku؛ امنح الإذن في نافذته وستتحدّث الحالة تلقائيًا"
                    else "تعذّر طلب إذن Shizuku: " + (request.exceptionOrNull()?.message ?: "تحقق من اتصال الخدمة")
                )
            }
            else -> {
                ShizukuServiceConnection.retryBinding()
                updateStatus("إذن Shizuku ممنوح؛ جارٍ إعادة ربط خدمة الملفات واختبارها فعليًا")
            }
        }
        activity?.refreshShizukuMonitor()
    }

    val usbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            usbUri = uri
            prefs.edit().putString(PREF_USB_URI, uri.toString()).apply()
            status = "تم اختيار مصدر USB"
        }
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNetworkAction
        pendingNetworkAction = ""
        if (granted) grantedNetworkAction = action
        else status = "يلزم السماح بوصول الأجهزة المجاورة لاستخدام نقل الشبكة المحلية"
    }
    fun startUsbCopy(source: DocumentFile, game: GameItem) {
        if (transferBusy) return
        if (hosting) { status = "أوقف مشاركة الهاتف قبل بدء نسخ USB"; return }
        if (!ShizukuHelper.hasPermission()) {
            status = "شغّل Shizuku وامنح cn-PlayHub الإذن للوصول إلى مسارات الألعاب الثابتة"
            return
        }
        runCatching { ShizukuServiceConnection.bind() }
        beginTransfer("نسخ ${game.arabic} من USB")
        transferJob = scope.launch {
            var staged: File? = null
            try {
                status = "جارٍ التحقق من جاهزية خدمة Shizuku قبل نسخ USB"
                HotspotTransfer.ensureShizukuReady()
                val parent = File(context.getExternalFilesDir(null) ?: context.filesDir, "KafiaNet/TransferStaging/USB")
                val alreadyRouted = source.findFile("__kafianet_data") != null || source.findFile("__kafianet_obb") != null
                val expectedPhases = if (game.targetPaths.size > 1 && !alreadyRouted) 3 else 2
                var stagedPayloadBytes = 0L
                var stagedPayloadFiles = 0
                val stagedCopy = CopyEngine.copyTreeToAppStorage(context, source, parent) { stageProgress ->
                    if (stageProgress.totalBytes > 0L) {
                        stagedPayloadBytes = stageProgress.totalBytes
                        stagedPayloadFiles = stageProgress.totalFiles
                    }
                    val totalKnown = stageProgress.totalBytes > 0L
                    updateProgress(stageProgress.copy(
                        bytesDone = if (totalKnown) stageProgress.bytesDone / expectedPhases.toLong() else stageProgress.bytesDone,
                        totalBytes = if (totalKnown) stageProgress.totalBytes else 0L,
                        filesDone = if (totalKnown) (stageProgress.filesDone / expectedPhases).coerceAtMost(stageProgress.totalFiles) else stageProgress.filesDone,
                        totalFiles = if (totalKnown) stageProgress.totalFiles else 0,
                        phase = if (totalKnown) "المرحلة 1/$expectedPhases: تجهيز USB مكتمل جزئيًا" else stageProgress.phase
                    ))
                }
                staged = stagedCopy
                HotspotTransfer.copyStagedGameToTargets(stagedCopy, game, { targetProgress ->
                    if (game.targetPaths.size == 1 && stagedPayloadBytes > 0L) {
                        if (targetProgress.phase.startsWith("اكتمل النسخ والتحقق")) {
                            updateProgress(targetProgress.copy(
                                bytesDone = stagedPayloadBytes,
                                totalBytes = stagedPayloadBytes,
                                filesDone = stagedPayloadFiles,
                                totalFiles = stagedPayloadFiles
                            ))
                        } else {
                            val bytes = (stagedPayloadBytes / expectedPhases +
                                targetProgress.bytesDone.coerceAtMost(stagedPayloadBytes) / expectedPhases)
                                .coerceAtMost(stagedPayloadBytes)
                            val files = (stagedPayloadFiles / expectedPhases +
                                targetProgress.filesDone.coerceAtMost(stagedPayloadFiles) / expectedPhases)
                                .coerceAtMost(stagedPayloadFiles)
                            updateProgress(targetProgress.copy(
                                bytesDone = bytes,
                                totalBytes = stagedPayloadBytes,
                                filesDone = files,
                                totalFiles = stagedPayloadFiles,
                                phase = "المرحلة 2/2: كتابة الوجهة والتحقق من الملفات"
                            ))
                        }
                    } else {
                        updateProgress(targetProgress)
                    }
                }, ::updateStatus)
                status = "اكتمل نسخ ${game.arabic} إلى ${game.destinationLabel}"
            } catch (cancelled: CancellationException) {
                status = "أُوقف النسخ"
                throw cancelled
            } catch (error: Exception) {
                status = if (TransferForegroundService.isCancellationRequested()) "أُوقف النسخ" else
                    "فشل نسخ ${game.arabic}: " + (error.message ?: "تحقق من Shizuku والمساحة")
            } finally {
                staged?.let { runCatching { it.deleteRecursively() } }
                endTransfer()
            }
        }
    }

    fun copyUsbGame(game: GameItem) {
        val uri = usbUri
        if (uri == null) { status = "اختر USB أولًا"; return }
        val gameRoot = DocumentFile.fromTreeUri(context, uri)?.findFile("العابي")?.findFile(game.sourceFolder)
        if (gameRoot == null || !gameRoot.isDirectory) {
            status = "لم يُعثر على مجلد اللعبة داخل USB: " + game.sourceFolder
            return
        }
        startUsbCopy(gameRoot, game)
    }

    fun discoverPcGames() {
        if (transferBusy) return
        transferBusy = true
        pcGames = emptyList()
        progress = TransferProgress(phase = "اكتشاف المجلدات على السيرفر — لا يوجد تقدم نسخ حتى يبدأ النقل")
        status = "جارٍ الاتصال بمشاركة " + LanLibraryTransfer.UNC_PATH
        transferJob = scope.launch {
            try {
                pcGames = LanLibraryTransfer.fetchLibrary(::updateStatus)
                status = if (pcGames.isEmpty()) {
                    "اتصلنا بالسيرفر لكن لم نجد ألعابًا معروفة داخل " + LanLibraryTransfer.UNC_PATH
                } else {
                    "تم العثور على " + pcGames.size + " لعبة في مكتبة السيرفر"
                }
            } catch (cancelled: CancellationException) {
                status = "تم إيقاف اكتشاف مكتبة السيرفر"
                throw cancelled
            } catch (error: Exception) {
                status = if (TransferForegroundService.isCancellationRequested()) "تم إيقاف اكتشاف مكتبة السيرفر" else
                    "تعذّر الوصول إلى " + LanLibraryTransfer.UNC_PATH + ": " +
                    (error.message ?: "تحقق من شبكة المودم ومشاركة Guest")
            } finally {
                transferBusy = false
            }
        }
    }

    fun copyPcGame(remote: HotspotTransfer.RemoteGame) {
        if (transferBusy) return
        val game = GameCatalog.bySourceFolder(remote.name)
        if (game == null) {
            status = "هذه اللعبة ليست ضمن كتالوج النقل"
            return
        }
        if (!ShizukuHelper.hasPermission()) {
            status = "شغّل Shizuku وامنح cn-PlayHub الإذن للكتابة إلى مسار اللعبة"
            return
        }
        ShizukuServiceConnection.bind()
        beginTransfer("نسخ " + game.arabic + " من السيرفر")
        transferJob = scope.launch {
            var staged: File? = null
            try {
                status = "جارٍ التحقق من جاهزية خدمة Shizuku قبل تنزيل اللعبة من السيرفر"
                HotspotTransfer.ensureShizukuReady()
                val parent = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "KafiaNet/TransferStaging/NetworkShare"
                )
                val stagedGame = LanLibraryTransfer.copyGameToStaging(
                    game, parent, ::updateProgress, ::updateStatus
                )
                staged = stagedGame
                HotspotTransfer.copyStagedGameToTargets(stagedGame, game, ::updateProgress, ::updateStatus)
                status = "اكتمل نسخ " + game.arabic + " من السيرفر إلى " + game.destinationLabel
            } catch (cancelled: CancellationException) {
                status = "أُوقف النسخ من السيرفر"
                throw cancelled
            } catch (error: Exception) {
                status = if (TransferForegroundService.isCancellationRequested()) "أُوقف النسخ من السيرفر" else
                    "فشل نسخ " + game.arabic + " من السيرفر: " +
                    (error.message ?: "تحقق من المشاركة وShizuku والمساحة")
            } finally {
                staged?.let { runCatching { it.deleteRecursively() } }
                endTransfer()
            }
        }
    }

    fun startHosting() {
        if (hosting) return
        if (transferBusy) { status = "انتظر انتهاء النسخ الحالي"; return }
        if (!ShizukuHelper.hasPermission()) {
            status = "شغّل Shizuku وامنح cn-PlayHub الإذن لقراءة «العابي» تلقائيًا"
            return
        }
        runCatching { ShizukuServiceConnection.bind() }
        localIps = HotspotTransfer.localIpv4Addresses()
        val bindIp = localIps.firstOrNull()
        if (bindIp.isNullOrBlank()) {
            status = "شغّل نقطة الاتصال الشخصية ثم اضغط تحديث عنوان IP"
            return
        }
        activeHostIp = bindIp
        hosting = true
        TransferForegroundService.start(context, "مكتبة الهاتف متاحة للنقل")
        hostJob = scope.launch {
            try {
                HotspotTransfer.serveLibrary(context, GameCatalog.sourceLibraryPath, bindIp, ::updateStatus)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = "تعذرت مشاركة «العابي»: " + (error.message ?: "تحقق من Shizuku ونقطة الاتصال")
            } finally {
                hosting = false
                TransferForegroundService.stop(context)
            }
        }
    }

    fun stopHosting() {
        HotspotTransfer.cancel()
        hostJob?.cancel()
        hostJob = null
        hosting = false
        TransferForegroundService.stop(context)
        status = "تم إيقاف مشاركة مكتبة الألعاب"
    }

    fun connectAndListGames() {
        if (transferBusy) return
        val host = destinationIp.trim().ifBlank {
            HotspotTransfer.wifiGatewayIpv4Addresses(context).firstOrNull().orEmpty()
        }
        if (host.isBlank()) {
            status = "لم أستطع اكتشاف الهاتف المصدر. تأكد أنك متصل بنقطة الوصول نفسها، ثم حدّث العنوان أو أدخله يدويًا."
            return
        }
        destinationIp = host
        transferBusy = true
        status = "جارٍ اكتشاف مكتبة الألعاب على الهاتف المصدر…"
        transferJob = scope.launch {
            try {
                val games = HotspotTransfer.fetchLibrary(host, ::updateStatus)
                remoteGames = games
                status = if (games.isEmpty()) "اتصلنا بالمصدر، لكن مجلد «العابي» لا يحتوي مجلدات ألعاب" else "اتصال ناجح — تم العثور على " + games.size + " لعبة"
            } catch (cancelled: CancellationException) {
                status = "تم إيقاف اكتشاف الألعاب من الهاتف"
                throw cancelled
            } catch (error: Exception) {
                status = if (TransferForegroundService.isCancellationRequested()) "تم إيقاف اكتشاف الألعاب من الهاتف" else
                    "تعذّر الاتصال: " + (error.message ?: "تأكد من أن المصدر يشارك المكتبة وأنك متصل بنقطة الوصول")
            } finally { transferBusy = false }
        }
    }

    fun downloadRemoteGame(game: HotspotTransfer.RemoteGame) {
        if (transferBusy) return
        if (!ShizukuHelper.hasPermission()) {
            status = "شغّل Shizuku وامنح cn-PlayHub الإذن للنسخ إلى المسار المسجل للعبة"
            return
        }
        runCatching { ShizukuServiceConnection.bind() }
        beginTransfer("نسخ اللعبة من الهاتف")
        transferJob = scope.launch {
            try {
                HotspotTransfer.downloadGame(
                    context = context,
                    host = destinationIp.trim(),
                    gameSourceFolder = game.name,
                    onProgress = ::updateProgress,
                    onStatus = ::updateStatus
                )
                status = "اكتمل نسخ " + (GameCatalog.bySourceFolder(game.name)?.arabic ?: game.name)
            } catch (cancelled: CancellationException) {
                status = "أُوقف نسخ اللعبة"
                throw cancelled
            } catch (error: Exception) {
                status = if (TransferForegroundService.isCancellationRequested()) "أُوقف نسخ اللعبة" else
                    "فشل نسخ اللعبة: " + (error.message ?: "تحقق من الاتصال وShizuku والمساحة")
            } finally { endTransfer() }
        }
    }

    fun requireNetworkPermission(action: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNetworkAction = action
            nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else when (action) {
            "host" -> startHosting()
            "list" -> connectAndListGames()
            "download" -> pendingGame?.let { downloadRemoteGame(it) }
            "install" -> pendingInstallGame?.let { requestPhoneInstall(it) }
        }
    }

    LaunchedEffect(grantedNetworkAction) {
        val action = grantedNetworkAction
        if (action.isNotEmpty()) {
            grantedNetworkAction = ""
            when (action) {
                "host" -> startHosting()
                "list" -> connectAndListGames()
                "download" -> pendingGame?.let { downloadRemoteGame(it) }
                "install" -> pendingInstallGame?.let { requestPhoneInstall(it) }
            }
        }
    }

    LaunchedEffect(tab) {
        when {
            tab == "Wireless" -> tab = "الهاتف"
            tab !in setOf("USB", "الوايرلس", "الهاتف") -> tab = "USB"
        }
    }

    LaunchedEffect(tab, phoneRole) {
        if (tab == "الهاتف" && phoneRole == "receiver" && destinationIp.isBlank()) {
            val discoveredGateway = HotspotTransfer.wifiGatewayIpv4Addresses(context).firstOrNull()
            if (!discoveredGateway.isNullOrBlank()) {
                destinationIp = discoveredGateway
                status = "تم اكتشاف عنوان نقطة الوصول تلقائيًا"
            }
        }
    }

    DisposableEffect(transferBusy) {
        val window = activity?.window
        if (transferBusy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (transferBusy) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(Modifier.fillMaxSize().background(Forest)) {
        AppHeader(shizukuRevision, onRequestShizuku = { requestShizukuAccess() })
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("USB", "الوايرلس", "الهاتف").forEach { item ->
                val selected = tab == item
                Surface(Modifier.weight(1f).clickable { tab = item }, shape = RoundedCornerShape(13.dp), color = if (selected) Neon else Panel) {
                    Text(item, Modifier.padding(vertical = 11.dp, horizontal = 3.dp),
                        textAlign = TextAlign.Center, color = if (selected) Color(0xFF13200F) else Muted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 10.sp)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                "USB" -> UsbPage(
                    usbUri != null, transferBusy, status, progress,
                    { usbPicker.launch(null) }, ::copyUsbGame, ::requestUsbInstall,
                    {
                        cancelActiveTransfer()
                    }
                )
                "الوايرلس" -> LanLibraryPage(
                    busy = transferBusy,
                    status = status,
                    progress = progress,
                    remoteGames = pcGames,
                    onDiscover = ::discoverPcGames,
                    onCopy = ::copyPcGame,
                    onInstall = ::requestServerInstall,
                    onStopTransfer = {
                        cancelActiveTransfer()
                    }
                )
                "الهاتف" -> PhoneTransferPage(
                    phoneRole = phoneRole,
                    onRoleChange = { phoneRole = it; if (it == "receiver") remoteGames = emptyList() },
                    hosting = hosting,
                    busy = transferBusy,
                    status = status,
                    progress = progress,
                    localIps = if (hosting) listOf(activeHostIp) else localIps,
                    sourceIp = destinationIp,
                    remoteGames = remoteGames,
                    onSourceIpChange = { destinationIp = it },
                    onRefreshIps = {
                        if (hosting) {
                            localIps = HotspotTransfer.localIpv4Addresses()
                            status = "عنوان المشاركة: $activeHostIp:${HotspotTransfer.PORT}"
                        } else {
                            val discoveredGateway = HotspotTransfer.wifiGatewayIpv4Addresses(context).firstOrNull()
                            if (!discoveredGateway.isNullOrBlank()) {
                                destinationIp = discoveredGateway
                                status = "تم اكتشاف عنوان المصدر"
                            } else {
                                status = "لم يُكتشف العنوان تلقائيًا؛ أدخله يدويًا"
                            }
                        }
                    },
                    onStartHosting = { requireNetworkPermission("host") },
                    onStopHosting = ::stopHosting,
                    onConnect = { requireNetworkPermission("list") },
                    onDownload = { game -> pendingGame = game; requireNetworkPermission("download") },
                    onInstall = { game -> pendingInstallGame = game; requireNetworkPermission("install") },
                    onStopTransfer = {
                        cancelActiveTransfer()
                    }
                )
                else -> UsbPage(
                    usbUri != null, transferBusy, status, progress,
                    { usbPicker.launch(null) }, ::copyUsbGame, ::requestUsbInstall,
                    {
                        transferJob?.cancel()
                        TransferForegroundService.stop(context)
                        transferBusy = false
                        status = "تم طلب إيقاف النسخ"
                    }
                )
            }
        }
        StatusBar(status, transferBusy || hosting)
    }

    if (installChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { installChoices = emptyList() },
            title = { Text(installChooserTitle) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    installChoices.forEach { choice ->
                        OutlinedButton(
                            onClick = {
                                installChoices = emptyList()
                                installApkChoice(choice)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(choice.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (choice.sizeBytes > 0L) CopyEngine.formatBytes(choice.sizeBytes) else "حجم غير معروف") +
                                        " • " + when (choice.source) {
                                            "USB" -> "USB"
                                            "SERVER" -> "السيرفر"
                                            else -> "الهاتف المصدر"
                                        },
                                    color = Muted, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { installChoices = emptyList() }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun AppHeader(shizukuRevision: Int, onRequestShizuku: () -> Unit) {
    // Re-read service state on every monitor revision; do not let Compose keep stale header text.
    val available = remember(shizukuRevision) { ShizukuHelper.isAvailable() }
    val permission = remember(shizukuRevision) { ShizukuHelper.hasPermission() }
    val fileServiceReady = remember(shizukuRevision) { ShizukuServiceConnection.isReady() }
    val fileServiceBinding = remember(shizukuRevision) { ShizukuServiceConnection.isBinding() }
    val fileServiceFailure = remember(shizukuRevision) { ShizukuServiceConnection.bindFailureReason() }
    Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF1B3524), Color(0xFF102017), Color(0xFF0A110E)))).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(Brush.linearGradient(listOf(Neon, Mint)), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Text("KN", color = Color(0xFF102015), fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("cn-PlayHub", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("هشام الريمي  •  مركز نقل الألعاب", color = Neon, fontSize = 11.sp)
            }
            Column(
                modifier = Modifier.clickable(onClick = onRequestShizuku).padding(start = 6.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    when {
                        !available -> "Shizuku غير متصل • اضغط"
                        !permission -> "الإذن مطلوب • اضغط للمنح"
                        fileServiceReady -> "Shizuku والإذن فعالان ✓"
                        fileServiceBinding -> "جارٍ ربط خدمة الملفات..."
                        fileServiceFailure != null -> "فشل الربط • اضغط لإعادة المحاولة"
                        else -> "الإذن موجود • اضغط للتحقق"
                    },
                    color = if (fileServiceReady) Neon else Amber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        !available -> "المراقبة: Shizuku متوقف"
                        !permission -> "المراقبة: بانتظار منح الإذن"
                        fileServiceReady -> "مراقبة الاتصال والإذن مباشرة"
                        fileServiceFailure != null -> "الخدمة: " + fileServiceFailure.take(42)
                        fileServiceBinding -> "اختبار Binder وخدمة الملفات..."
                        else -> "اضغط لربط خدمة الملفات"
                    },
                    color = if (fileServiceReady) Neon else Amber, fontSize = 9.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickPill("نسخ USB", "⇄")
            QuickPill("الوايرلس", "⌁")
            QuickPill("تقدم إجمالي", "%")
        }
    }
}

@Composable
private fun QuickPill(label: String, icon: String) {
    Surface(shape = RoundedCornerShape(50), color = Color(0x332D593B)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = Neon, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(5.dp))
            Text(label, color = Color(0xFFDCE9DF), fontSize = 10.sp)
        }
    }
}

@Composable
private fun LanLibraryPage(
    busy: Boolean,
    status: String,
    progress: TransferProgress,
    remoteGames: List<HotspotTransfer.RemoteGame>,
    onDiscover: () -> Unit,
    onCopy: (HotspotTransfer.RemoteGame) -> Unit,
    onInstall: (GameItem) -> Unit,
    onStopTransfer: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Surface(color = CardGreen, shape = RoundedCornerShape(21.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("المصدر الثابت", color = Muted, fontSize = 10.sp)
                Text(LanLibraryTransfer.UNC_PATH, color = Neon, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Button(onClick = onDiscover, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Text(if (remoteGames.isEmpty()) "اكتشاف ألعاب السيرفر" else "تحديث ألعاب السيرفر")
                }
            }
        }
        if (busy) {
            AggregateProgressCard(progress)
            OutlinedButton(onClick = onStopTransfer, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                Text("إيقاف العملية", color = Danger)
            }
        }
        gameItems.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { game ->
                    val remote = remoteGames.firstOrNull { it.name == game.sourceFolder }
                    WirelessGameCard(
                        modifier = Modifier.weight(1f),
                        game = game,
                        remote = remote,
                        enabled = !busy && remote != null && ShizukuHelper.hasPermission(),
                        buttonText = "نسخ",
                        installEnabled = !busy && remote != null,
                        onDownload = { remote?.let(onCopy) },
                        onInstall = { onInstall(game) }
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(status, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PhoneTransferPage(
    phoneRole: String, onRoleChange: (String) -> Unit,
    hosting: Boolean, busy: Boolean, status: String, progress: TransferProgress,
    localIps: List<String>, sourceIp: String,
    remoteGames: List<HotspotTransfer.RemoteGame>, onSourceIpChange: (String) -> Unit,
    onRefreshIps: () -> Unit, onStartHosting: () -> Unit, onStopHosting: () -> Unit,
    onConnect: () -> Unit, onDownload: (HotspotTransfer.RemoteGame) -> Unit,
    onInstall: (GameItem) -> Unit, onStopTransfer: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoleButton("هذا الهاتف مصدر", phoneRole == "source", Modifier.weight(1f)) { onRoleChange("source") }
            RoleButton("نسخ إلى هذا الهاتف", phoneRole == "receiver", Modifier.weight(1f)) { onRoleChange("receiver") }
        }

        if (phoneRole == "source") {
            Surface(color = CardGreen, shape = RoundedCornerShape(21.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("مصدر الألعاب", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(GameCatalog.sourceLibraryPath, color = Neon, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("فعّل نقطة الاتصال الشخصية يدويًا قبل البدء.", color = Muted, fontSize = 10.sp)
                    Button(onClick = onStartHosting, enabled = !hosting && !busy,
                        modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(14.dp)) {
                        Text(if (hosting) "مشاركة الهاتف نشطة" else "بدء مشاركة الهاتف", fontWeight = FontWeight.Bold)
                    }
                    if (hosting) {
                        localIps.forEach { ip -> CopyAddressRow("$ip:${HotspotTransfer.PORT}") }
                        OutlinedButton(onClick = onRefreshIps, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("تحديث IP") }
                        OutlinedButton(onClick = onStopHosting, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("إيقاف مشاركة الهاتف", color = Danger) }
                    }
                }
            }
        } else {
            Surface(color = CardGreen, shape = RoundedCornerShape(21.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("اتصل بنقطة وصول الهاتف المصدر؛ ستظهر الألعاب المعروفة فقط.", color = Muted, fontSize = 10.sp)
                    OutlinedTextField(
                        value = sourceIp, onValueChange = onSourceIpChange, modifier = Modifier.fillMaxWidth(),
                        label = { Text("IP المصدر (تلقائي)") }, placeholder = { Text("اكتشاف تلقائي أو إدخال يدوي") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = RoundedCornerShape(13.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshIps, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("تحديث IP") }
                        Button(onClick = onConnect, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF102016))) {
                            Text("اكتشاف الألعاب", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SectionHeading("الألعاب")
            gameItems.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { catalogGame ->
                        val remoteGame = remoteGames.firstOrNull { it.name == catalogGame.sourceFolder }
                        WirelessGameCard(
                            modifier = Modifier.weight(1f),
                            game = catalogGame,
                            remote = remoteGame,
                            enabled = !busy && remoteGame != null && ShizukuHelper.hasPermission(),
                            buttonText = "نسخ إلى الهاتف",
                            installEnabled = !busy && remoteGame != null,
                            onDownload = { remoteGame?.let(onDownload) },
                            onInstall = { onInstall(catalogGame) }
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (busy) {
                AggregateProgressCard(progress)
                OutlinedButton(onClick = onStopTransfer, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Text("إيقاف النقل", color = Danger)
                }
            }
        }
        Text(status, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RoleButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), color = if (selected) Neon else Panel, shape = RoundedCornerShape(14.dp)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 13.dp), textAlign = TextAlign.Center,
            color = if (selected) Color(0xFF13200F) else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WirelessGameCard(
    modifier: Modifier, game: GameItem, remote: HotspotTransfer.RemoteGame?, enabled: Boolean,
    buttonText: String = "نسخ", installEnabled: Boolean = true,
    onDownload: () -> Unit, onInstall: () -> Unit
) {
    Surface(modifier, color = CardGreen, shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, Color(0xFF263E2E))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(105.dp).background(Color(0xFF0D130F), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Image(painter = painterResource(game.imageRes), contentDescription = game.arabic,
                    modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
            }
            Text(game.arabic, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(game.english, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (remote != null && remote.sizeBytes >= 0L && remote.fileCount >= 0) {
                Text(
                    "${CopyEngine.formatBytes(remote.sizeBytes)} • ${remote.fileCount} ملف",
                    color = Mint, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onDownload, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                Text(buttonText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onInstall, enabled = installEnabled, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp)
            ) { Text("تثبيت APK", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CopyAddressRow(address: String) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(address, color = Neon, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun AggregateProgressCard(progress: TransferProgress) {
    val hasKnownTotal = progress.totalBytes > 0L || progress.totalFiles > 0
    Surface(color = Color(0xFF192D20), shape = RoundedCornerShape(21.dp),
        border = BorderStroke(1.dp, Color(0xFF345A3E)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hasKnownTotal) "تقدم النقل الفعلي"
                    else if (progress.bytesDone > 0L || progress.currentFile.isNotBlank()) "جارٍ النقل — الحجم الإجمالي قيد الحساب"
                    else "جارٍ الفحص والتحضير",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                if (hasKnownTotal) {
                    Text(progress.percent.toString() + "%", color = Neon, fontWeight = FontWeight.Black, fontSize = 23.sp)
                } else {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Neon, strokeWidth = 2.dp)
                }
            }
            if (hasKnownTotal) {
                LinearProgressIndicator(
                    progress = progress.percent / 100f,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Neon, trackColor = Color(0xFF2B4332)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            CopyEngine.formatBytes(progress.bytesDone) + " / " + CopyEngine.formatBytes(progress.totalBytes),
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            progress.filesDone.toString() + " من " + progress.totalFiles + " ملف",
                            color = Muted, fontSize = 11.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(CopyEngine.formatBytes(progress.bytesPerSecond) + "/s", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Neon, trackColor = Color(0xFF2B4332)
                )
                if (progress.bytesDone > 0L) {
                    Text(
                        "المنقول فعليًا في هذه المرحلة: " + CopyEngine.formatBytes(progress.bytesDone) +
                            "؛ يجري اكتشاف الحجم الإجمالي أثناء النقل.",
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                } else {
                    Text(
                        "جارٍ الفحص أو الاتصال؛ لن تظهر نسبة وهمية قبل معرفة الحجم.",
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }
            if (progress.currentFile.isNotBlank()) {
                Text("العنصر الحالي: " + progress.currentFile, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(progress.phase, color = Neon, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun GameCard(game: GameItem, modifier: Modifier, enabled: Boolean, onClick: () -> Unit, onInstall: () -> Unit) {
    Surface(modifier = modifier, color = CardGreen, shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, Color(0xFF263E2E))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.fillMaxWidth().height(106.dp).background(Color(0xFF0D130F), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Image(painter = painterResource(game.imageRes), contentDescription = game.arabic,
                    modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
            }
            Text(game.arabic, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(game.english, fontSize = 9.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 4.dp)) {
                Text("نسخ", fontSize = 10.sp)
            }
            OutlinedButton(onClick = onInstall, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
                Text("تثبيت APK", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun UsbPage(
    usbSelected: Boolean, busy: Boolean, status: String, progress: TransferProgress,
    onChooseUsb: () -> Unit, onCopyGame: (GameItem) -> Unit, onInstallGame: (GameItem) -> Unit,
    onStopTransfer: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Button(onClick = onChooseUsb, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
            Text(if (usbSelected) "USB محدد — تغيير المصدر" else "اختيار USB")
        }
        if (busy) {
            AggregateProgressCard(progress)
            OutlinedButton(onClick = onStopTransfer, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                Text("إيقاف النسخ", color = Danger)
            }
        }
        gameItems.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { game ->
                    GameCard(game, Modifier.weight(1f), usbSelected && !busy, { onCopyGame(game) }, { onInstallGame(game) })
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(status, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusBar(status: String, active: Boolean) {
    Surface(color = Color(0xFF101A14), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (active) Neon else Mint, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(status, color = if (active) Color.White else Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (active) {
                Spacer(Modifier.width(5.dp))
                CircularProgressIndicator(Modifier.size(16.dp), color = Neon, strokeWidth = 2.dp)
            }
        }
    }
}
