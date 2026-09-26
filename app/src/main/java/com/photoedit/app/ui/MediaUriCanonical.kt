package com.photoedit.app.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 归一结果（Task14a 修复轮1，读写 uri 分离）：
 * - [readUri] **始终是原始授权 uri**（picker/分享带来的 uri，读权限绑在它本身）：
 *   编辑期所有读取（openInputStream）与命名（DISPLAY_NAME 查询）都走它。
 *   `MediaStore.getMediaUri` 的 platform javadoc 明确不授予任何新权限，此前实现把
 *   读取路径也切到规范 uri 会让本 app（清单零存储权限）load 直接读失败，是反的。
 * - [writeUri] 仅用于"覆盖原图"链路的规范 MediaStore images uri
 *   （createWriteRequest 授权 + openOutputStream 写入）；只有能**可靠**解析出
 *   media authority 的 images 引用时才有（见 [decideWriteUri] 的来源门），否则 null。
 * - [canOverwrite] = writeUri != null。
 */
data class CanonicalMediaUri(val readUri: Uri, val writeUri: Uri?) {
    val canOverwrite: Boolean get() = writeUri != null
}

/** 规范 media images 引用（纯数据，供 JVM 测）。 */
internal data class MediaImagesRef(val id: Long, val volume: String)

/**
 * 归一核心（纯字符串，无 Android 运行时依赖，JVM 可测）：决定 [writeUri] 候选，
 * **绝不替换读取 uri**（读取永远走原始授权 uri，由调用方保证）。
 *
 * 1. [pickedUriString] 已是规范 media images uri → 规范化（去 query/fragment）即 writeUri；
 * 2. 否则 [canonicalUriString]（仅对 documents uri 调用 `MediaStore.getMediaUri` 得到，
 *    见 [supportsGetMediaUri]）命中 media images → 采用之；
 * 3. 否则，当且仅当 picked 的 authority 属于 MediaStore 可信来源
 *    （`media` picker uri / `com.android.providers.media.documents`，其 `_ID` 就是
 *    MediaStore 行号，见 [supportsMediaIdQuery]）时取 id：优先 [mediaIdFromQuery]，
 *    查不到再回退 [mediaStoreIdOfPickerUri]（实测 API 35：photopicker 形态 picker uri
 *    的 `_ID` 列返回 null，但末段路径就是 MediaStore 行号）→
 *    重建 `content://media/{volume}/images/media/{id}`；
 * 4. 其余（file uri / 第三方 authority / 拿不到 id）→ null：只降级为"仅另存副本"。
 *
 * Critical#2 的来源门是关键：第三方 share provider 应答的 `_ID` 与 MediaStore 行号
 * 无关，拿它猜行可能映射到不相干的另一张照片，授权后经 openOutputStream 覆盖错文件
 * = 数据丢失，故非可信 authority 的 `_ID` 一律不产生 writeUri。
 */
internal fun decideWriteUri(
    pickedUriString: String,
    canonicalUriString: String?,
    mediaIdFromQuery: Long?,
): String? {
    mediaImagesRefOf(pickedUriString)?.let { ref ->
        return canonicalImagesUri(ref)
    }
    canonicalUriString?.let { s ->
        mediaImagesRefOf(s)?.let { ref -> return canonicalImagesUri(ref) }
    }
    if (supportsMediaIdQuery(pickedUriString)) {
        val id = mediaIdFromQuery ?: mediaStoreIdOfPickerUri(pickedUriString)
        if (id != null) {
            return "content://media/${volumeHintOf(pickedUriString)}/$PATH_IMAGES_MEDIA/$id"
        }
    }
    return null
}

/** content uri 的 authority（非 content:// 或无法解析返回 null）。 */
internal fun authorityOf(uriString: String): String? {
    if (!uriString.startsWith("content://")) return null
    return uriString.removePrefix("content://").substringBefore('/').ifEmpty { null }
}

/** `MediaStore.getMediaUri` 只接受 DocumentsProvider uri（且要求调用方已有读权限）。 */
internal fun supportsGetMediaUri(uriString: String): Boolean =
    authorityOf(uriString) in DOCUMENTS_AUTHORITIES

/**
 * 仅当 authority 是 MediaStore 本身（picker uri `content://media/...`）或
 * MediaStore 的 documents provider 时，`_ID` 才等价 MediaStore 行号、可用于重建 images uri。
 * 第三方 authority 一律 false（Critical#2）；file uri false。
 */
internal fun supportsMediaIdQuery(uriString: String): Boolean =
    authorityOf(uriString) in MEDIA_ID_AUTHORITIES

/**
 * MediaStore 自己签发的 picker uri（`content://media/picker/{userId}/…/{id}`，AOSP
 * PhotoPickerContract：末段路径即 MediaStore 行号；修复轮 1 设备冒烟实测 photopicker
 * 形态 uri 查询 `_ID` 列返回 null，故按路径尾段兜底）。非 media authority 或
 * 末段非数字 → null（Critical#2：第三方 provider 的 id 一律不猜）。
 */
internal fun mediaStoreIdOfPickerUri(uriString: String): Long? {
    if (authorityOf(uriString) != AUTHORITY_MEDIA) return null
    val segments = uriString.removePrefix("content://").substringAfter('/', "").split('/')
    if (segments.firstOrNull() != "picker") return null
    return segments.lastOrNull()?.substringBefore('?')?.substringBefore('#')?.toLongOrNull()?.takeIf { it > 0 }
}

/** 重建 uri 的卷名：从 picked 路径里找 external/internal 段（picker uri 带卷提示），缺省 external。 */
internal fun volumeHintOf(uriString: String): String {
    val afterAuthority = uriString.removePrefix("content://").substringAfter('/', "")
    val segments = afterAuthority.split('/')
    return if (VOLUME_INTERNAL in segments) VOLUME_INTERNAL else VOLUME_EXTERNAL
}

/**
 * 解析规范 media images uri：`content://media/{volume}/images/media/{id}[?..][#..]`。
 * authority 必须是 `media` 且第 1 段为 external/internal、第 2/3 段为 `images`/`media`；
 * picker 形如 `content://media/picker/...` 不匹配（正是要归一的对象）。非法/无 id → null。
 */
internal fun mediaImagesRefOf(uriString: String): MediaImagesRef? {
    if (!uriString.startsWith("content://")) return null
    val afterScheme = uriString.removePrefix("content://")
    val authority = afterScheme.substringBefore('/')
    if (authority != AUTHORITY_MEDIA) return null
    val segments = afterScheme.substringAfter('/', "").split('/')
    if (segments.size < 4) return null
    val volume = segments[0]
    if (volume != VOLUME_EXTERNAL && volume != VOLUME_INTERNAL) return null
    if (segments[1] != "images" || segments[2] != "media") return null
    val id = segments[3].substringBefore('?').substringBefore('#').toLongOrNull() ?: return null
    return MediaImagesRef(id, volume)
}

private fun canonicalImagesUri(ref: MediaImagesRef): String =
    "content://media/${ref.volume}/$PATH_IMAGES_MEDIA/${ref.id}"

/**
 * Android 边界：为 picked uri 解析 writeUri 候选（[readUri] 恒为 picked 原 uri）。
 * suspend + [Dispatchers.IO]：`getMediaUri` / `_ID` 查询都是 binder 调用，
 * 不得在主线程跑（评审 #6）——picker 回调与分享 intake 都在协程里调本函数。
 *
 * `getMediaUri` 自 **API 30 (R)** 才有（评审 #5：此前守卫误写 29），且只对
 * DocumentsProvider uri 有效（[supportsGetMediaUri]），media/picker uri 不走它，
 * 改为直接查询 picker uri 自身的 `_ID`（其带读授权，query 可用）重建 images uri；
 * 实测 API 35 photopicker uri 的 `_ID` 列为 null，再由 [mediaStoreIdOfPickerUri]
 * 按末段路径兜底（仍限 media authority，Critical#2 门不放宽）。
 */
internal suspend fun resolveCanonicalMediaUri(context: Context, pickedUri: Uri): CanonicalMediaUri =
    withContext(Dispatchers.IO) {
        val pickedString = pickedUri.toString()
        val canonicalString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !pickedString.startsWith("file://") && supportsGetMediaUri(pickedString)
        ) {
            runCatching { MediaStore.getMediaUri(context, pickedUri)?.toString() }.getOrNull()
        } else {
            null
        }
        val alreadyCanonical = mediaImagesRefOf(pickedString) != null
        val idFromQuery = if (!alreadyCanonical && supportsMediaIdQuery(pickedString)) {
            queryMediaId(context, pickedUri)
        } else {
            null
        }
        val writeString = decideWriteUri(pickedString, canonicalString, idFromQuery)
        val writeUri = when {
            writeString == null -> null
            writeString == pickedString -> pickedUri
            else -> runCatching { Uri.parse(writeString) }.getOrNull()
        }
        CanonicalMediaUri(readUri = pickedUri, writeUri = writeUri)
    }

private fun queryMediaId(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }
}.getOrNull()

private const val AUTHORITY_MEDIA = "media"
private const val VOLUME_EXTERNAL = "external"
private const val VOLUME_INTERNAL = "internal"
private const val PATH_IMAGES_MEDIA = "images/media"

/** getMediaUri 合法的来源 authority（只接受 DocumentsProvider）。 */
private val DOCUMENTS_AUTHORITIES = setOf(
    "com.android.externalstorage.documents",
    "com.android.providers.media.documents",
)

/** `_ID` 可信（= MediaStore 行号）的来源 authority（Critical#2 门）。 */
private val MEDIA_ID_AUTHORITIES = setOf(
    AUTHORITY_MEDIA,
    "com.android.providers.media.documents",
)
