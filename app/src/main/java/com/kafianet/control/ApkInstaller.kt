package com.kafianet.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

data class AvailableGameApk(val name: String, val sizeBytes: Long)

data class ApkInstallChoice(
    val name: String,
    val sizeBytes: Long,
    val source: String,
    val gameFolder: String,
    val contentUri: String? = null,
    val host: String? = null
)

object ApkInstaller {
    private const val MAX_APK_BYTES = 10L * 1024L * 1024L * 1024L
    private const val BUFFER_SIZE = 256 * 1024

    fun requestInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        ) return true

        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return false
    }

    suspend fun copyStreamToCache(
        context: Context,
        sourceName: String,
        expectedSize: Long,
        openSource: () -> InputStream,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        if (!sourceName.endsWith(".apk", ignoreCase = true)) {
            throw IOException("الملف المحدد ليس ملف APK: " + sourceName)
        }
        if (expectedSize < 0L || expectedSize > MAX_APK_BYTES) {
            throw IOException("حجم ملف APK غير صالح أو يتجاوز الحد الآمن")
        }

        val directory = File(context.cacheDir, "install-apks")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("تعذّر إنشاء مجلد تجهيز التثبيت")
        }
        val safeName = sourceName.substringAfterLast('/').substringAfterLast('\\')
            .replace('/', '_').replace('\\', '_').take(180)
        val target = File(directory, System.currentTimeMillis().toString() + "_" + safeName)
        val started = android.os.SystemClock.elapsedRealtime()
        var copied = 0L
        var lastUpdate = started

        try {
            openSource().use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied = Math.addExact(copied, count.toLong())
                        if (copied > MAX_APK_BYTES) throw IOException("ملف APK أكبر من الحد الآمن")
                        output.write(buffer, 0, count)

                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 150L) {
                            val elapsed = (now - started).coerceAtLeast(1L)
                            val shownBytes = if (expectedSize > 0L) {
                                copied.coerceAtMost((expectedSize - 1L).coerceAtLeast(0L))
                            } else copied
                            onProgress(shownBytes, expectedSize, copied * 1000L / elapsed)
                            lastUpdate = now
                        }
                    }
                    output.fd.sync()
                }
            }

            if (!target.isFile || target.length() <= 0L) {
                throw IOException("ملف APK المستلَم فارغ")
            }
            if (expectedSize > 0L && target.length() != expectedSize) {
                throw IOException(
                    "فشل التحقق من ملف APK: المتوقع " + expectedSize +
                        " بايت، والمستلَم " + target.length() + " بايت"
                )
            }
            onProgress(target.length(), target.length(), 0L)
            target
        } catch (error: Exception) {
            runCatching { target.delete() }
            throw error
        }
    }

    fun launchInstaller(context: Context, apkFile: File) {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            throw IOException("ملف APK غير موجود أو فارغ")
        }
        if (!requestInstallPermission(context)) {
            throw IOException("فعّل إذن تثبيت التطبيقات من هذا التطبيق ثم اضغط التثبيت مرة أخرى")
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
