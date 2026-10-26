package com.kafianet.control

import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read-only HTTP browser for /storage/emulated/0/العابي/تطبيقات.
 * File metadata and file descriptors are resolved by Shizuku; clients cannot write or delete.
 * The random token in the URL prevents casual browsing of the listening port.
 */
class BrowserShareServer(
    private val rootPath: String,
    private val bindAddress: String,
    port: Int,
    private val accessToken: String
) : NanoHTTPD(bindAddress, port) {

    companion object {
        const val PORT = 8766
    }

    private val root = File(rootPath).canonicalFile
    val shareUrl: String
        get() = "http://$bindAddress:$PORT/$accessToken/"

    fun startServer() {
        if (ShizukuServiceConnection.pathType(root.path) != 2) {
            throw IOException("مجلد المشاركة غير متاح أو إذن Shizuku غير جاهز: $rootPath")
        }
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return textResponse(Response.Status.METHOD_NOT_ALLOWED, "هذه المشاركة للعرض والتنزيل فقط")
        }

        val decodedPath = try {
            URLDecoder.decode(session.uri.substringBefore('?'), StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            return textResponse(Response.Status.BAD_REQUEST, "مسار غير صالح")
        }

        val segments = decodedPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.first() != accessToken) {
            return textResponse(Response.Status.UNAUTHORIZED, "رابط المشاركة غير صالح")
        }

        val relativeSegments = segments.drop(1)
        if (relativeSegments.any { it == "." || it == ".." || it.contains('\\') || it.indexOf('\u0000') >= 0 }) {
            return textResponse(Response.Status.FORBIDDEN, "المسار مرفوض")
        }

        val requestedPath = try {
            safePath(relativeSegments)
        } catch (_: SecurityException) {
            return textResponse(Response.Status.FORBIDDEN, "المسار خارج مجلد المشاركة")
        } catch (_: Exception) {
            return textResponse(Response.Status.BAD_REQUEST, "المسار غير صالح")
        }

        return try {
            when (ShizukuServiceConnection.pathType(requestedPath)) {
                2 -> directoryResponse(requestedPath, relativeSegments)
                1 -> fileResponse(requestedPath)
                else -> textResponse(Response.Status.NOT_FOUND, "الملف أو المجلد غير موجود")
            }
        } catch (error: Exception) {
            textResponse(Response.Status.INTERNAL_ERROR, "تعذر قراءة العنصر: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun safePath(segments: List<String>): String {
        val child = if (segments.isEmpty()) root else File(root, segments.joinToString(File.separator))
        val canonical = child.canonicalFile
        val basePath = root.path.trimEnd(File.separatorChar)
        if (canonical.path != basePath && !canonical.path.startsWith(basePath + File.separator)) {
            throw SecurityException("path traversal")
        }
        return canonical.path
    }

    private fun directoryResponse(path: String, segments: List<String>): Response {
        val entries = ShizukuServiceConnection.listChildren(path)
            .mapNotNull { childPath ->
                val child = File(childPath)
                val name = child.name
                if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
                val childSegments = segments + name
                val safeChildPath = runCatching { safePath(childSegments) }.getOrNull() ?: return@mapNotNull null
                val kind = ShizukuServiceConnection.pathType(safeChildPath)
                if (kind !in 1..2) return@mapNotNull null
                val isDirectory = kind == 2
                val size = if (isDirectory) "" else formatSize(ShizukuServiceConnection.fileLength(safeChildPath))
                BrowserEntry(name, childSegments.joinToString("/"), isDirectory, size)
            }
            .sortedWith(compareBy<BrowserEntry>({ !it.directory }, { it.name.lowercase() }))

        val html = buildString {
            append("<!doctype html><html lang='ar' dir='rtl'><head><meta charset='utf-8'>")
            append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
            append("<title>cn-PlayHub | مشاركة التطبيقات</title>")
            append("<style>")
            append("body{font-family:system-ui,sans-serif;background:#08110b;color:#eef8ee;margin:0;padding:20px}")
            append("main{max-width:900px;margin:auto}h1{font-size:24px;color:#a5f36b}")
            append(".path{color:#b2c2b3;margin-bottom:18px;overflow-wrap:anywhere}")
            append("ul{list-style:none;padding:0}li{padding:13px 15px;margin:8px 0;background:#15251a;border:1px solid #274632;border-radius:12px}")
            append("a{color:#e8f8e4;text-decoration:none;display:block}small{color:#91aa98;display:block;margin-top:5px}")
            append(".folder{color:#a5f36b}footer{color:#91aa98;font-size:12px;margin-top:24px}")
            append("</style></head><body><main>")
            append("<h1>cn-PlayHub — مجلد العابي/تطبيقات</h1>")
            append("<div class='path'>المسار الحالي: /")
            append(escapeHtml((listOf("العابي", "تطبيقات") + segments).joinToString("/")))
            append("</div><ul>")
            if (segments.isNotEmpty()) {
                val parent = segments.dropLast(1)
                append("<li><a href=\"").append(href(parent.joinToString("/"), true))
                    .append("\">↩ الرجوع للمجلد السابق</a></li>")
            }
            entries.forEach { entry ->
                val href = href(entry.relativePath, entry.directory)
                val icon = if (entry.directory) "📁" else "📄"
                append("<li><a class='").append(if (entry.directory) "folder" else "file")
                    .append("' href=\"").append(href).append("\">")
                    .append(icon).append(" ").append(escapeHtml(entry.name))
                    .append("</a><small>")
                    .append(if (entry.directory) "مجلد" else "ملف · " + entry.size)
                    .append("</small></li>")
            }
            if (entries.isEmpty()) append("<li>المجلد فارغ أو لا توجد عناصر يمكن قراءتها</li>")
            append("</ul><footer>المشاركة للقراءة والتنزيل فقط. أوقف المشاركة من cn-PlayHub عند الانتهاء.</footer>")
            append("</main></body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun fileResponse(path: String): Response {
        val length = ShizukuServiceConnection.fileLength(path)
        if (length < 0L) return textResponse(Response.Status.NOT_FOUND, "تعذر قراءة حجم الملف")
        val descriptor = ShizukuServiceConnection.openFileForRead(path)
            ?: return textResponse(Response.Status.NOT_FOUND, "تعذر فتح الملف للتنزيل")
        val fileName = File(path).name
        val mime = getMimeTypeForFile(fileName) ?: "application/octet-stream"
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mime,
            ParcelFileDescriptor.AutoCloseInputStream(descriptor),
            length
        )
        response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodeURIComponent(fileName))
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun href(relative: String, directory: Boolean): String {
        val encoded = if (relative.isBlank()) "" else relative.split('/').joinToString("/") { encodeURIComponent(it) }
        return "/$accessToken/" + encoded + if (directory && encoded.isNotEmpty()) "/" else ""
    }

    private fun encodeURIComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun formatSize(bytes: Long): String {
        if (bytes < 0L) return "غير معروف"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun textResponse(status: Response.IStatus, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)
    
    private data class BrowserEntry(
        val name: String,
        val relativePath: String,
        val directory: Boolean,
        val size: String
    )
}
