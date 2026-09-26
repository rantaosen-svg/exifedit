package com.photoedit.app.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 归一结果：[uri] 是交给编辑/保存链路的 uri；[canOverwrite] 表示该来源能否走"覆盖原图"。
 *
 * Photo Picker（PickVisualMedia）与部分分享返回的是只读、非规范 MediaStore uri
 * （`content://media/picker/...` 等），直接 openOutputStream 覆盖必失败，且 displayNameOf
 * 只能取到 id 段名（副本名回归成 `1000000116_副本`）。故在"拿到 uri → load"之前归一回
 * 规范 `content://media/{volume}/images/media/{id}`；拿不到规范 uri 时优雅降级：编辑与
 * 另存副本照常，覆盖入口禁用（spec §3.5/§4：不静默失败）。
 */
data class CanonicalMediaUri(val uri: Uri, val canOverwrite: Boolean)

/** 规范 media images 引用（纯数据，供 JVM 测）。 */
internal data class MediaImagesRef(val id: Long, val volume: String)

/** 纯字符串归一结果。 */
internal data class CanonicalStringOutcome(val uriString: String, val canOverwrite: Boolean)

/**
 * 归一核心（纯字符串，无 Android 运行时依赖，JVM 可测）：
 * 1. [canonicalUriString]（`MediaStore.getMediaUri` 解析出的规范 media uri）命中
 *    media images → 采用之，canOverwrite=true；
 * 2. 否则 [pickedUriString] 已是规范 media images uri → 规范化（去 query/fragment）后采用，true；
 * 3. 否则非 file 且查到 `_ID`（[mediaIdFromQuery]）→ 重建 `external/images/media/{id}`，true；
 * 4. 都拿不到（file uri / 归一失败 / 异常导致上两步为 null）→ 回退原 uri，false。
 */
internal fun decideCanonicalUri(
    pickedUriString: String,
    canonicalUriString: String?,
    mediaIdFromQuery: Long?,
): CanonicalStringOutcome {
    canonicalUriString?.let {
        mediaImagesRefOf(it)?.let { ref ->
            return CanonicalStringOutcome(canonicalImagesUri(ref), true)
        }
    }
    mediaImagesRefOf(pickedUriString)?.let { ref ->
        return CanonicalStringOutcome(canonicalImagesUri(ref), true)
    }
    val isFile = pickedUriString.startsWith("file://")
    if (!isFile && mediaIdFromQuery != null) {
        return CanonicalStringOutcome(
            "content://media/${VOLUME_EXTERNAL}/${PATH_IMAGES_MEDIA}/$mediaIdFromQuery",
            true,
        )
    }
    return CanonicalStringOutcome(pickedUriString, false)
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
 * Android 边界：把 picked uri 归一为 [CanonicalMediaUri]。纯逻辑在 [decideCanonicalUri]，
 * 本函数只负责用真实 Context/resolver 取候选（[MediaStore.getMediaUri]、`_ID` 查询）
 * 再交给纯函数判定。
 */
internal fun resolveCanonicalMediaUri(context: Context, pickedUri: Uri): CanonicalMediaUri {
    val pickedString = pickedUri.toString()
    val canonicalString = if (Build.VERSION.SDK_INT >= 29 && !pickedString.startsWith("file://")) {
        runCatching { MediaStore.getMediaUri(context, pickedUri)?.toString() }.getOrNull()
    } else {
        null
    }
    val idFromQuery = if (canonicalString == null && !pickedString.startsWith("file://")) {
        queryMediaId(context, pickedUri)
    } else {
        null
    }
    val outcome = decideCanonicalUri(pickedString, canonicalString, idFromQuery)
    val resultUri = if (outcome.uriString == pickedString) pickedUri else Uri.parse(outcome.uriString)
    return CanonicalMediaUri(resultUri, outcome.canOverwrite)
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
