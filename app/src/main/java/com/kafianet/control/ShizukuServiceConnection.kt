package com.kafianet.control

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import rikka.shizuku.Shizuku

object ShizukuServiceConnection {
    private const val BIND_RETRY_WINDOW_MS = 10_000L
    private const val SERVICE_VERIFY_TIMEOUT_MS = 5_000L
    private const val USER_SERVICE_VERSION = 7

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var service: IShizukuFileService? = null
    @Volatile private var serviceVerified = false
    @Volatile private var serviceVerifying = false
    @Volatile private var healthCheckInProgress = false
    @Volatile private var lastSuccessfulPingAt = 0L
    @Volatile private var bindRequestedAt = 0L
    @Volatile private var bindFailure: String? = null
    @Volatile private var stateListener: (() -> Unit)? = null

    private fun notifyStateChanged() {
        runCatching { stateListener?.invoke() }
    }

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName("com.kafianet.control", ShizukuFileService::class.java.name)
    ).processNameSuffix("files").daemon(true).debuggable(false).version(USER_SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connectedService = binder?.let { IShizukuFileService.Stub.asInterface(it) }
            service = connectedService
            serviceVerified = false
            serviceVerifying = connectedService != null
            healthCheckInProgress = false
            bindRequestedAt = 0L
            bindFailure = when {
                binder == null -> "Shizuku أعاد Binder فارغًا"
                connectedService == null -> "تعذّر إنشاء واجهة خدمة الملفات"
                !binder.isBinderAlive -> "انتهى Binder قبل اكتمال الربط"
                else -> null
            }
            notifyStateChanged()
            if (connectedService != null) verifyInBackground(connectedService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceVerified = false
            serviceVerifying = false
            bindRequestedAt = 0L
            bindFailure = "انقطع Binder الخاص بخدمة الملفات؛ أعد الربط"
            notifyStateChanged()
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            serviceVerified = false
            serviceVerifying = false
            bindRequestedAt = 0L
            bindFailure = "مات اتصال خدمة الملفات؛ أعد الربط"
            notifyStateChanged()
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            serviceVerified = false
            serviceVerifying = false
            bindRequestedAt = 0L
            bindFailure = "Shizuku أعاد اتصالًا بلا خدمة ملفات"
            notifyStateChanged()
        }
    }

    fun setStateListener(listener: (() -> Unit)?) {
        stateListener = listener
    }

    private fun hasLiveBinder(): Boolean {
        val current = service ?: return false
        return runCatching { current.asBinder().isBinderAlive }.getOrDefault(false)
    }

    private fun verifyInBackground(expected: IShizukuFileService) {
        mainHandler.postDelayed({
            if (service === expected && serviceVerifying && !serviceVerified) {
                service = null
                serviceVerified = false
                serviceVerifying = false
                bindRequestedAt = 0L
                bindFailure = "انتهت مهلة اختبار خدمة الملفات بعد 5 ثوانٍ؛ أعدنا اعتبار الاتصال غير جاهز"
                notifyStateChanged()
            }
        }, SERVICE_VERIFY_TIMEOUT_MS)

        Thread({
            if (service === expected) verifyReady()
        }, "KafiaNet-Shizuku-ping").apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleBindTimeout(requestedAt: Long) {
        mainHandler.postDelayed({
            if (bindRequestedAt == requestedAt && !isReady()) {
                bindRequestedAt = 0L
                bindFailure = "انتهت مهلة ربط خدمة الملفات بعد 10 ثوانٍ. اضغط إعادة الربط، وتأكد أن Shizuku يعمل."
                notifyStateChanged()
            }
        }, BIND_RETRY_WINDOW_MS)
    }

    fun bind() {
        if (!ShizukuHelper.isAvailable()) {
            bindRequestedAt = 0L
            bindFailure = "تطبيق Shizuku غير متصل بهذا التطبيق"
            notifyStateChanged()
            return
        }
        if (!ShizukuHelper.hasPermission()) {
            bindRequestedAt = 0L
            bindFailure = "إذن Shizuku غير ممنوح لتطبيق كافية نت"
            notifyStateChanged()
            return
        }
        if (isReady()) return

        // A Binder may be alive while the remote UserService is stale; verify it once
        // instead of creating duplicate binding requests during the verification window.
        if (hasLiveBinder()) {
            if (!serviceVerifying) {
                serviceVerifying = true
                bindFailure = null
                notifyStateChanged()
                service?.let { verifyInBackground(it) }
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (bindRequestedAt != 0L && now - bindRequestedAt in 0 until BIND_RETRY_WINDOW_MS) return

        bindRequestedAt = now
        bindFailure = null
        try {
            // Version bump forces Shizuku to recreate the old UserService after app updates.
            Shizuku.bindUserService(userServiceArgs(), connection)
            scheduleBindTimeout(now)
        } catch (error: Exception) {
            bindRequestedAt = 0L
            bindFailure = "فشل طلب ربط خدمة الملفات: ${error.message ?: error.javaClass.simpleName}"
        }
        notifyStateChanged()
    }

    /** Cleanly detaches a stale/old UserService, then performs a new bind. */
    fun retryBinding() {
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            bind()
            return
        }
        bindRequestedAt = 0L
        service = null
        serviceVerified = false
        serviceVerifying = false
        bindFailure = null
        notifyStateChanged()
        try {
            Shizuku.unbindUserService(userServiceArgs(), connection, true)
        } catch (error: Exception) {
            // A missing old service is harmless; the subsequent bind gives the real outcome.
            bindFailure = "تعذّر إيقاف خدمة الملفات القديمة: ${error.message ?: error.javaClass.simpleName}"
        }
        mainHandler.postDelayed({ bind() }, 250L)
    }

    fun unbind() {
        bindRequestedAt = 0L
        try {
            if (ShizukuHelper.isAvailable()) {
                Shizuku.unbindUserService(userServiceArgs(), connection, true)
            }
        } catch (error: Exception) {
            bindFailure = "تعذّر فصل خدمة الملفات: ${error.message ?: error.javaClass.simpleName}"
        }
        service = null
        serviceVerified = false
        serviceVerifying = false
        notifyStateChanged()
    }

    /**
     * Rechecks the remote UserService periodically. Binder.isBinderAlive alone does not
     * prove that the AIDL endpoint can still answer requests.
     */
    fun pollHealth() {
        val current = service ?: return
        if (!serviceVerified || serviceVerifying || healthCheckInProgress) return
        if (!runCatching { current.asBinder().isBinderAlive }.getOrDefault(false)) {
            service = null
            serviceVerified = false
            bindRequestedAt = 0L
            bindFailure = "انقطع Binder أثناء المراقبة الدورية"
            notifyStateChanged()
            return
        }

        if (SystemClock.elapsedRealtime() - lastSuccessfulPingAt < 5_000L) return
        healthCheckInProgress = true
        mainHandler.postDelayed({
            if (service === current && healthCheckInProgress) {
                healthCheckInProgress = false
                service = null
                serviceVerified = false
                serviceVerifying = false
                bindRequestedAt = 0L
                bindFailure = "انتهت مهلة المراقبة الفعلية لخدمة ملفات Shizuku"
                notifyStateChanged()
            }
        }, SERVICE_VERIFY_TIMEOUT_MS)

        Thread({
            val healthy = runCatching {
                current.ping() && current.asBinder().isBinderAlive
            }.getOrDefault(false)
            mainHandler.post {
                if (service !== current) {
                    healthCheckInProgress = false
                    return@post
                }
                healthCheckInProgress = false
                if (healthy) {
                    lastSuccessfulPingAt = SystemClock.elapsedRealtime()
                } else {
                    service = null
                    serviceVerified = false
                    serviceVerifying = false
                    bindRequestedAt = 0L
                    bindFailure = "فشل اختبار ping() الدوري؛ خدمة ملفات Shizuku لم تعد سليمة"
                    notifyStateChanged()
                }
            }
        }, "KafiaNet-Shizuku-health").apply {
            isDaemon = true
            start()
        }
    }

    /** True only after the real remote ping() transaction has succeeded. */
    fun isReady(): Boolean {
        if (!serviceVerified) return false
        return hasLiveBinder()
    }

    /** Performs a real Binder method call, not only a local Binder-alive check. */
    fun verifyReady(): Boolean {
        val current = service ?: return false
        if (!runCatching { current.asBinder().isBinderAlive }.getOrDefault(false)) {
            if (service === current) {
                service = null
                serviceVerified = false
                serviceVerifying = false
                bindRequestedAt = 0L
                bindFailure = "انقطع Binder أثناء اختبار خدمة الملفات"
                notifyStateChanged()
            }
            return false
        }
        serviceVerifying = true
        return try {
            val ready = current.ping()
            // Do not let a late reply from a timed-out/removed binder overwrite a newer bind.
            if (service !== current) return false
            if (ready && runCatching { current.asBinder().isBinderAlive }.getOrDefault(false)) {
                serviceVerified = true
                serviceVerifying = false
                lastSuccessfulPingAt = SystemClock.elapsedRealtime()
                healthCheckInProgress = false
                bindFailure = null
                notifyStateChanged()
                true
            } else {
                service = null
                serviceVerified = false
                serviceVerifying = false
                bindRequestedAt = 0L
                bindFailure = "خدمة الملفات استجابت لفحص الاتصال بنتيجة غير صحيحة؛ أعد الربط"
                notifyStateChanged()
                false
            }
        } catch (error: Exception) {
            if (service !== current) return false
            service = null
            serviceVerified = false
            serviceVerifying = false
            bindRequestedAt = 0L
            bindFailure = "فشل اختبار خدمة الملفات (قد تكون نسخة قديمة): ${error.message ?: error.javaClass.simpleName}"
            notifyStateChanged()
            false
        }
    }

    fun isBinding(): Boolean {
        val started = bindRequestedAt
        return (started != 0L && SystemClock.elapsedRealtime() - started in 0 until BIND_RETRY_WINDOW_MS) ||
            serviceVerifying
    }

    fun bindFailureReason(): String? = bindFailure

    /** 0 = missing/unreadable, 1 = file, 2 = directory. */
    fun pathType(path: String): Int = try {
        service?.pathType(path) ?: 0
    } catch (_: Exception) { 0 }

    fun listChildren(path: String): List<String> = try {
        service?.listChildren(path)?.toList().orEmpty()
    } catch (_: Exception) { emptyList() }

    fun fileLength(path: String): Long = try {
        service?.fileLength(path) ?: -1L
    } catch (_: Exception) { -1L }

    fun openFileForRead(path: String): ParcelFileDescriptor? = try {
        service?.openFileForRead(path)
    } catch (_: Exception) { null }

    /** Clears a previous cancellation before starting the next destination copy. */
    fun prepareCopy(): Boolean {
        return try {
            val current = service ?: return false
            if (!isReady()) return false
            current.prepareCopy()
            true
        } catch (error: Exception) {
            bindFailure = "تعذّر تهيئة خدمة النسخ: ${error.message ?: error.javaClass.simpleName}"
            notifyStateChanged()
            false
        }
    }

    /** Cooperative cancellation is a separate Binder transaction from the blocking copy call. */
    fun cancelCopy() {
        runCatching { service?.cancelCopy() }
    }

    fun copyDirectory(source: String, destination: String): Boolean = try {
        service?.copyDirectory(source, destination) ?: false
    } catch (_: Exception) { false }

    /**
     * Returns SUCCESS:bytes:files only after every destination file has been written and
     * verified. Other returned text is an error (including an explicit cancellation result).
     */
    /**
     * Moves already-staged files into the protected game directory when possible, avoiding
     * a second full read/write pass on the same storage volume. The remote service verifies
     * every destination and falls back to streaming copy if a rename cannot be used.
     */
    fun moveDirectoryWithProgressAndVerify(
        source: String,
        destination: String,
        onProgress: (bytesDone: Long, totalBytes: Long, filesDone: Int, totalFiles: Int, currentFile: String) -> Unit
    ): String {
        val current = service
            ?: return bindFailure ?: "خدمة ملفات Shizuku غير مربوطة"
        if (!isReady()) {
            return bindFailure ?: "خدمة الملفات لم تنجح في اختبار الاتصال الحقيقي"
        }
        return try {
            val listener = object : IShizukuProgressListener.Stub() {
                override fun onProgress(
                    bytesDone: Long,
                    totalBytes: Long,
                    filesDone: Int,
                    totalFiles: Int,
                    currentFile: String?
                ) {
                    runCatching {
                        onProgress(bytesDone, totalBytes, filesDone, totalFiles, currentFile.orEmpty())
                    }
                }
            }
            current.moveDirectoryWithProgressAndVerify(source, destination, listener).orEmpty()
        } catch (error: Exception) {
            val message = "فشل نقل الملفات الجاهزة إلى الوجهة $destination: ${error.message ?: error.javaClass.simpleName}"
            bindFailure = message
            notifyStateChanged()
            message
        }
    }

    fun copyDirectoryWithProgressAndVerify(
        source: String,
        destination: String,
        onProgress: (bytesDone: Long, totalBytes: Long, filesDone: Int, totalFiles: Int, currentFile: String) -> Unit
    ): String {
        val current = service
            ?: return bindFailure ?: "خدمة ملفات Shizuku غير مربوطة"
        if (!isReady()) {
            return bindFailure ?: "خدمة الملفات لم تنجح في اختبار الاتصال الحقيقي"
        }
        return try {
            val listener = object : IShizukuProgressListener.Stub() {
                override fun onProgress(
                    bytesDone: Long,
                    totalBytes: Long,
                    filesDone: Int,
                    totalFiles: Int,
                    currentFile: String?
                ) {
                    runCatching {
                        onProgress(bytesDone, totalBytes, filesDone, totalFiles, currentFile.orEmpty())
                    }
                }
            }
            current.copyDirectoryWithProgressAndVerify(source, destination, listener).orEmpty()
        } catch (error: Exception) {
            service = null
            serviceVerified = false
            serviceVerifying = false
            bindRequestedAt = 0L
            val message = "فشل استدعاء خدمة النسخ عند الوجهة $destination: ${error.message ?: error.javaClass.simpleName}"
            bindFailure = message
            notifyStateChanged()
            message
        }
    }
}
