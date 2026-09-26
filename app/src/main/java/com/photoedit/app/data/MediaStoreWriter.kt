package com.photoedit.app.data

import android.app.PendingIntent
import android.app.RecoverableSecurityException
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
 * 覆盖前 OWNER_PACKAGE_NAME 判定的三态（Important#3）。
 * [OwnerCheck.Foreign] 是唯一可"不打开流直接按需授权"的确定性信号。
 */
internal enum class OwnerCheck { Own, Foreign, Unknown }

/**
 * owner 判定纯函数（JVM 可测）：查询结果 null（零权限下外部行不可见 / 列缺失 / 查询失败）
 * 一律 [OwnerCheck.Unknown]——维持直写尝试路径，由异常分类兜底；
 * 已知且非本包 = [OwnerCheck.Foreign]。
 */
internal fun classifyOwnerCheck(owner: String?, packageName: String): OwnerCheck = when {
    owner == null -> OwnerCheck.Unknown
    owner == packageName -> OwnerCheck.Own
    else -> OwnerCheck.Foreign
}

/**
 * MediaStore 写回（spec §3.5）：
 * - saveCopy：IS_PENDING 事务式插入 `Pictures/`，写流失败即删条目，绝不留半成品；
 * - overwrite：先直写，app 不拥有条目时捕获 RecoverableSecurityException 返回 NeedsPermission，
 *   调用方经 [createWriteIntentFor] 发起系统授权后重试一次。
 *   Important#3：打开输出流即截断原文件，故写入前先查 OWNER_PACKAGE_NAME——已知外部属主
 *   的条目在**打开任何流之前**直接 NeedsPermission，消除对外部文件的截断窗口。
 * - 零存储权限（spec §1/§3.4 修订）：双文件型 Live 图副本为静态图，不复制配对 mp4。
 *
 * open：EditViewModel 构造注入以便 JVM 测试子类化 fake（公开方法均 open）。
 */
open class MediaStoreWriter(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** 按 uri 反查 DISPLAY_NAME；条目已不存在或不可读时 null。 */
    open suspend fun displayNameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
        }.getOrNull()
    }

    /**
     * 已占用 DISPLAY_NAME 集合，供 CopyNaming.next 去重递增。
     *
     * 零权限语义（spec §1/§3.4 修订）：API 33+ 无 READ_MEDIA_* 时 MediaStore 查询仅返回
     * 本 app 自有条目，看不到外部相册文件——去重因此是"尽力"而非完备。
     * 真正的重名安全网在系统层：insert 遇到同目录重复 DISPLAY_NAME 时 MediaProvider
     * 自动追加 " (1)" 后缀，不会产生冲突或覆盖。
     */
    open suspend fun existingNames(): Set<String> = withContext(Dispatchers.IO) {
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
    open suspend fun saveCopy(bytes: ByteArray, newName: String, dateTakenMillis: Long): SaveOutcome =
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
     * Important#3：openOutputStream("w") **打开即截断**——先查 OWNER_PACKAGE_NAME，
     * 已知且非本包的条目在打开流之前直接 NeedsPermission（外部文件零截断窗口）；
     * 查询失败 / owner 为 null（零权限下外部行本就查不到）维持现路径，不改变既有行为。
     */
    open suspend fun overwrite(uri: Uri, bytes: ByteArray): SaveOutcome = withContext(Dispatchers.IO) {
        if (ownerCheckOf(uri) == OwnerCheck.Foreign) return@withContext SaveOutcome.NeedsPermission
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
    open suspend fun createWriteIntentFor(uri: Uri): PendingIntent? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext null
        runCatching { MediaStore.createWriteRequest(resolver, listOf(uri)) }.getOrNull()
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

    /** OWNER_PACKAGE_NAME 查询 + 纯函数判定（Important#3：判定逻辑 JVM 可测，见 [classifyOwnerCheck]）。 */
    private fun ownerCheckOf(uri: Uri): OwnerCheck =
        classifyOwnerCheck(queryString(uri, MediaStore.MediaColumns.OWNER_PACKAGE_NAME), context.packageName)

    /** MediaProvider 在部分 fuse 路径把权限错误压成裸 IOException——查 owner 包名兜底判定。 */
    private fun uriIsForeign(uri: Uri): Boolean = ownerCheckOf(uri) == OwnerCheck.Foreign

    companion object {
        private const val MIME_JPEG = "image/jpeg"
        private const val RELATIVE_PATH_PICTURES = "Pictures/"
        private const val DATE_TAKEN_COLUMN = MediaStore.Images.ImageColumns.DATE_TAKEN // "datetaken"
    }
}
