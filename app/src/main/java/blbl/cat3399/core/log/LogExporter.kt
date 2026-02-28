package blbl.cat3399.core.log

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogExporter {
    private const val ZIP_MIME = "application/zip"

    data class ZipExtra(
        /**
         * Path inside the zip, e.g. `meta.json` or `logs/extra.txt`.
         */
        val path: String,
        val bytes: ByteArray,
    )

    data class ExportResult(
        val fileName: String,
        val uri: Uri,
        val includedFiles: Int,
    )

    data class LocalExportResult(
        val fileName: String,
        val file: File,
        val includedFiles: Int,
    )

    fun exportToTreeUri(
        context: Context,
        treeUri: Uri,
        nowMs: Long = System.currentTimeMillis(),
        fileNameOverride: String? = null,
        extras: List<ZipExtra> = emptyList(),
    ): ExportResult {
        val appContext = context.applicationContext
        val logDir = AppLog.logDir(appContext)
        val logFiles =
            logDir.listFiles()?.asSequence()
                ?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedBy { it.lastModified() }
                ?.toList()
                ?: emptyList()

        val crashFile = CrashTracker.crashFile(appContext).takeIf { it.exists() && it.isFile }

        if (logFiles.isEmpty() && crashFile == null) {
            throw IOException("没有可导出的日志文件")
        }

        val fileName = resolveExportFileName(nowMs = nowMs, fileNameOverride = fileNameOverride)
        val outUri = createZipDocument(appContext, treeUri, fileName)
        val included: Int =
            appContext.contentResolver.openOutputStream(outUri, "w")?.use { rawOut ->
                ZipOutputStream(BufferedOutputStream(rawOut, 32 * 1024)).use { zip ->
                    writeZip(zip = zip, extras = extras, logFiles = logFiles, crashFile = crashFile)
                }
            } ?: throw IOException("无法写入导出文件")

        return ExportResult(fileName = fileName, uri = outUri, includedFiles = included)
    }

    fun exportToLocalFile(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        fileNameOverride: String? = null,
        extras: List<ZipExtra> = emptyList(),
    ): LocalExportResult {
        val appContext = context.applicationContext
        val logDir = AppLog.logDir(appContext)
        val logFiles =
            logDir.listFiles()?.asSequence()
                ?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedBy { it.lastModified() }
                ?.toList()
                ?: emptyList()

        val crashFile = CrashTracker.crashFile(appContext).takeIf { it.exists() && it.isFile }

        if (logFiles.isEmpty() && crashFile == null) {
            throw IOException("没有可导出的日志文件")
        }

        val exportDir =
            appContext.getExternalFilesDir("exports")
                ?: File(appContext.filesDir, "exports")
        runCatching { exportDir.mkdirs() }

        val fileName = resolveExportFileName(nowMs = nowMs, fileNameOverride = fileNameOverride)
        val outFile = createLocalZipFile(exportDir, fileName)
        val included: Int =
            FileOutputStream(outFile).use { rawOut ->
                ZipOutputStream(BufferedOutputStream(rawOut, 32 * 1024)).use { zip ->
                    writeZip(zip = zip, extras = extras, logFiles = logFiles, crashFile = crashFile)
                }
            }

        return LocalExportResult(fileName = fileName, file = outFile, includedFiles = included)
    }

    private fun buildExportFileName(nowMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val ts = runCatching { sdf.format(Date(nowMs)) }.getOrNull() ?: nowMs.toString()
        return "blbl_logs_${ts}.zip"
    }

    private fun writeZip(
        zip: ZipOutputStream,
        extras: List<ZipExtra>,
        logFiles: List<File>,
        crashFile: File?,
    ): Int {
        var included = 0
        for (extra in extras) {
            val path = sanitizeZipPath(extra.path) ?: continue
            val bytes = extra.bytes
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
            included++
        }
        for (f in logFiles) {
            if (!f.exists() || !f.isFile) continue
            zip.putNextEntry(ZipEntry("logs/${f.name}"))
            FileInputStream(f).use { input ->
                input.copyTo(zip, bufferSize = 32 * 1024)
            }
            zip.closeEntry()
            included++
        }
        if (crashFile != null) {
            zip.putNextEntry(ZipEntry("logs/${crashFile.name}"))
            FileInputStream(crashFile).use { input ->
                input.copyTo(zip, bufferSize = 32 * 1024)
            }
            zip.closeEntry()
            included++
        }
        return included
    }

    private fun resolveExportFileName(
        nowMs: Long,
        fileNameOverride: String?,
    ): String {
        val fallback = buildExportFileName(nowMs)
        val raw = fileNameOverride?.trim().takeIf { !it.isNullOrBlank() } ?: fallback
        val withExt = if (raw.endsWith(".zip", ignoreCase = true)) raw else "$raw.zip"
        val safe = sanitizeFileName(withExt)
        return safe.ifBlank { fallback }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return ""
        val noSeparators = trimmed.replace(Regex("[\\\\/\\r\\n\\t]"), "_")
        // Keep it reasonably short; some providers reject very long file names.
        return noSeparators.take(96)
    }

    private fun sanitizeZipPath(path: String): String? {
        val p = path.trim().replace('\\', '/')
        if (p.isBlank()) return null
        if (p.startsWith('/')) return null
        if (p.startsWith("../")) return null
        if (p.contains("/../")) return null
        if (p.contains('\u0000')) return null
        return p.take(256)
    }

    private fun createZipDocument(context: Context, treeUri: Uri, baseName: String): Uri {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

        var attempt = 0
        while (attempt <= 20) {
            val name = if (attempt == 0) baseName else baseName.removeSuffix(".zip") + "_$attempt.zip"
            try {
                val created = DocumentsContract.createDocument(resolver, dirUri, ZIP_MIME, name)
                if (created != null) return created
            } catch (_: Throwable) {
                // Try a different name.
            }
            attempt++
        }
        throw IOException("创建导出文件失败")
    }

    private fun createLocalZipFile(dir: File, baseName: String): File {
        var attempt = 0
        while (attempt <= 20) {
            val name = if (attempt == 0) baseName else baseName.removeSuffix(".zip") + "_$attempt.zip"
            val file = File(dir, name)
            try {
                if (file.createNewFile()) return file
            } catch (_: Throwable) {
                // Try a different name.
            }
            attempt++
        }
        throw IOException("创建导出文件失败")
    }
}
