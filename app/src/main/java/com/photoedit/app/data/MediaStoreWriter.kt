package com.photoedit.app.data

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 保存结果：成功（携带条目 uri）/ 需要系统写授权 / 失败（携带原因）。 */
sealed interface SaveOutcome {
    data class Saved(val uri: Uri) : SaveOutcome
    data object NeedsPermission : SaveOutcome
    data class Failed(val reason: String) : SaveOutcome
}

/**
 * MediaStore 写回（spec §3.5）：
 * - saveCopy：IS_PENDING 事务式插入 `Pictures/`，写流失败即删条目，绝不留半成品；
 * - overwrite：先直写，app 不拥有条目时捕获 RecoverableSecurityException 返回 NeedsPermission，
 *   调用方经 [createWriteIntentFor] 发起系统授权后重试一次；
 * - Live 双文件（同名 jpg+mp4）：[findPairedVideoUri] 定位配对 mp4，[copyPairedVideo] 副本同名复制。
 */
class MediaStoreWriter(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** 按 uri 反查 DISPLAY_NAME；条目已不存在或不可读时 null。 */
    suspend fun displayNameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
        }.getOrNull()
    }

    /** 全量图片 DISPLAY_NAME 集合，供 CopyNaming.next 去重递增。 */
    suspend fun existingNames(): Set<String> = withContext(Dispatchers.IO) {
        val names = HashSet<String>()
        runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(names::add) }
        }
        names
    }

    /** 另存副本：插入 Pictures/ 下新 JPEG 条目并写入字节；失败清理半成品。 */
    suspend fun saveCopy(bytes: ByteArray, newName: String, dateTakenMillis: Long): SaveOutcome =
        insertEntry(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName = newName,
            mimeType = MIME_JPEG,
            bytes = bytes,
            relativePath = RELATIVE_PATH_PICTURES,
            dateTakenMillis = dateTakenMillis,
        )

    /**
     * 覆盖原图：uri 反查条目直写。app 拥有的文件直接 Saved；
     * 不拥有时 openOutputStream/write 抛 RecoverableSecurityException → NeedsPermission
     * （调用方 launch [createWriteIntentFor] 授权后重试一次）。文件名不变，Live 配对天然保持。
     */
    suspend fun overwrite(uri: Uri, bytes: ByteArray): SaveOutcome = withContext(Dispatchers.IO) {
        try {
            val stream = resolver.openOutputStream(uri, "w")
                ?: return@withContext SaveOutcome.Failed("无法打开输出流：条目可能已不存在")
            stream.use { it.write(bytes) }
            SaveOutcome.Saved(uri)
        } catch (e: SecurityException) {
            if (isRecoverable(e)) SaveOutcome.NeedsPermission
            else SaveOutcome.Failed(e.message ?: "无写入权限")
        } catch (e: Exception) {
            val cause = e.cause
            if (Build.VERSION.SDK_INT >= 29 && cause is RecoverableSecurityException) SaveOutcome.NeedsPermission
            // 部分 fuse 路径把权限错误压成裸 IOException：owner 非本 app 时按需授权处理
            else if (uriIsForeign(uri)) SaveOutcome.NeedsPermission
            else SaveOutcome.Failed(e.message ?: "写入失败")
        }
    }

    /**
     * MediaStore.createWriteRequest 返回系统授权用的 PendingIntent
     * （内部即 IntentSender 封装，调用方 startIntentSenderForResult 后重试 overwrite 一次）。
     */
    suspend fun createWriteIntentFor(uri: Uri): PendingIntent? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext null
        runCatching { MediaStore.createWriteRequest(resolver, listOf(uri)) }.getOrNull()
    }

    /**
     * Live 双文件配对：取图片 DISPLAY_NAME 去扩展名为 base，
     * 在同 RELATIVE_PATH 目录下查 `<base>.mp4` 视频条目；无则 null。
     */
    suspend fun findPairedVideoUri(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        val name = displayNameOf(uri) ?: return@withContext null
        val base = name.substringBeforeLast('.')
        val relPath = queryString(uri, MediaStore.MediaColumns.RELATIVE_PATH)
        val selection = StringBuilder("${MediaStore.Video.Media.DISPLAY_NAME} = ?")
        val args = mutableListOf("$base.mp4")
        if (relPath != null) {
            selection.append(" AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?")
            args += relPath
        }
        runCatching {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                selection.toString(), args.toTypedArray(), null,
            )?.use {
                if (it.moveToFirst())
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
                else null
            }
        }.getOrNull()
    }

    /** 另存副本时同步复制配对 mp4：同 RELATIVE_PATH，命名 `<newBaseName>.mp4`。 */
    suspend fun copyPairedVideo(videoUri: Uri, newBaseName: String): SaveOutcome = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            resolver.openInputStream(videoUri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext SaveOutcome.Failed("无法读取配对视频")
        insertEntry(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            displayName = "$newBaseName$VIDEO_EXT",
            mimeType = MIME_MP4,
            bytes = bytes,
            relativePath = queryString(videoUri, MediaStore.MediaColumns.RELATIVE_PATH) ?: RELATIVE_PATH_PICTURES,
            dateTakenMillis = queryLong(videoUri, DATE_TAKEN_COLUMN),
        )
    }

    // ---- internal ----

    private suspend fun insertEntry(
        collection: Uri,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        relativePath: String,
        dateTakenMillis: Long?,
    ): SaveOutcome = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            // images/videos 行的拍摄时间列是 legacy "datetaken"（epoch 毫秒）；
            // MediaColumns.DATE_TAKEN("date_taken") 在部分平台不可写/不可查，实测 API 35 被静默忽略。
            if (dateTakenMillis != null) put(DATE_TAKEN_COLUMN, dateTakenMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return@withContext SaveOutcome.Failed("MediaStore 插入失败：$displayName")
        return@withContext try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("openOutputStream 返回 null")
            stream.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // 注意：发布时 MediaProvider 会重扫文件，datetaken 以 EXIF DateTimeOriginal 为权威来源；
            // insert 时显式写入的 DATE_TAKEN 是对无 EXIF 字节（如原始截图流）的兜底，
            // 部分平台（实测 API 35 模拟器）发布后 update "datetaken" 返回 0 行、不可回写。
            // 生产路径字节均由 ExifRepository 写出、必带 DateTimeOriginal + OffsetTime*，扫描结果与 dateTakenMillis 一致。
            SaveOutcome.Saved(uri)
        } catch (e: SecurityException) {
            // 理论上 IS_PENDING 条目归本 app 独有，不应触发；防御性清理半成品。
            deleteQuietly(uri)
            if (isRecoverable(e)) SaveOutcome.NeedsPermission
            else SaveOutcome.Failed(e.message ?: "写入无权限：$displayName")
        } catch (e: Exception) {
            deleteQuietly(uri) // 不留半成品
            SaveOutcome.Failed(e.message ?: "写入失败：$displayName")
        }
    }

    private fun deleteQuietly(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun isRecoverable(e: SecurityException): Boolean =
        Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException

    private fun queryString(uri: Uri, column: String): String? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    }.getOrNull()

    private fun queryLong(uri: Uri, column: String): Long? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }.getOrNull()

    /** MediaProvider 在部分 fuse 路径把权限错误压成裸 IOException——查 owner 包名兜底判定。 */
    private fun uriIsForeign(uri: Uri): Boolean = runCatching {
        val owner = queryString(uri, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        owner != null && owner != context.packageName
    }.getOrDefault(false)

    companion object {
        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_MP4 = "video/mp4"
        private const val VIDEO_EXT = ".mp4"
        private const val RELATIVE_PATH_PICTURES = "Pictures/"
        private const val DATE_TAKEN_COLUMN = MediaStore.Images.ImageColumns.DATE_TAKEN // "datetaken"
    }
}
