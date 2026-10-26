package com.kafianet.control

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class CopyProgress(
    val copiedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long
)

object LegacyCopyEngine {

    suspend fun copyFolder(
        context: Context,
        source: DocumentFile,
        destination: File,
        onProgress: (CopyProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {

        try {

            val totalBytes = calculateSize(source)

            if (!destination.exists()) {
                destination.mkdirs()
            }

            var copiedBytes = 0L
            val startTime = System.currentTimeMillis()

            fun copyRecursive(
                item: DocumentFile,
                target: File
            ) {

                if (item.isDirectory) {

                    val dir = File(
                        target,
                        item.name ?: "folder"
                    )

                    if (!dir.exists()) {
                        dir.mkdirs()
                    }

                    item.listFiles().forEach { child ->
                        copyRecursive(child, dir)
                    }

                } else if (item.isFile) {

                    val outputFile = File(
                        target,
                        item.name ?: "file"
                    )

                    val inputStream =
                        context.contentResolver
                            .openInputStream(item.uri)
                            ?: return

                    inputStream.use { input ->

                        FileOutputStream(outputFile).use { output ->

                            val buffer =
                                ByteArray(1024 * 1024)

                            while (true) {

                                val read =
                                    input.read(buffer)

                                if (read == -1) {
                                    break
                                }

                                output.write(
                                    buffer,
                                    0,
                                    read
                                )

                                copiedBytes += read

                                val elapsed =
                                    System.currentTimeMillis() -
                                            startTime

                                val speed =
                                    if (elapsed > 0) {
                                        copiedBytes * 1000L /
                                                elapsed
                                    } else {
                                        0L
                                    }

                                onProgress(
                                    CopyProgress(
                                        copiedBytes =
                                            copiedBytes,
                                        totalBytes =
                                            totalBytes,
                                        speedBytesPerSecond =
                                            speed
                                    )
                                )
                            }
                        }
                    }
                }
            }

            copyRecursive(
                source,
                destination
            )

            true

        } catch (e: Exception) {

            false
        }
    }

    private fun calculateSize(
        item: DocumentFile
    ): Long {

        if (item.isFile) {
            return item.length()
        }

        var total = 0L

        if (item.isDirectory) {

            item.listFiles().forEach { child ->
                total += calculateSize(child)
            }
        }

        return total
    }
}
