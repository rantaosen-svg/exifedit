package com.photoedit.app.ui

import android.content.Intent
import android.net.Uri

/**
 * 分享入口判定结果（改动 1）：入口只回答"能不能进编辑"，不回答"是不是 JPEG"——
 * 真实格式由 [com.photoedit.app.data.ExifRepository] 的 SOI 魔数判定产生
 * Unsupported 状态（编辑页已有"暂不支持该格式"UI），入口文案不再谎报格式。
 */
sealed interface ShareIntake {
    /** 单张图片内容可用：走 resolveCanonicalMediaUri + load（授权读/规范写分离不变）。 */
    data class LoadSingle(val uri: Uri) : ShareIntake

    /** SEND_MULTIPLE 选了多张：只支持单张。 */
    data object TooManyItems : ShareIntake

    /** action 是分享但取不到内容，或声明的 MIME 非图片类。 */
    data object NoContent : ShareIntake

    /** 非分享 action（含 null）：不处理。 */
    data object NotShare : ShareIntake
}

/**
 * 分享 intent 判定纯函数（JVM 可测，不触 Android 运行时；
 * ACTION_SEND* 常量编译期内联，可安全引用）。
 *
 * 根因取证：旧实现要求 type **恰好**等于 image/jpeg|image/jpg，而很多相册分享声明
 * image 通配、全通配、image/heic，且单图也可能走 SEND_MULTIPLE → 全被拒并弹误导文案。
 * 规则改为按内容判定：
 * - SEND 且（type==null / "image/" 前缀 / 全通配）且 uri 非空 → LoadSingle；
 * - SEND_MULTIPLE 且 size==1 → 同上一条判定；size>1 → TooManyItems；
 * - 分享但无内容或非图片类 type → NoContent；
 * - 其他 action → NotShare。
 *
 * 图片类声明只保证"是图片"，能否编辑仍由下游 SOI 魔数决定（heic 等非 JPEG 内容
 * 会进 Unsupported 状态，UI 已有对应展示）。
 */
fun classifyShareIntake(
    action: String?,
    type: String?,
    streamUri: Uri?,
    streamUris: List<Uri>?,
): ShareIntake {
    val imageLike = type == null || type == "*/*" || type.startsWith("image/")
    return when (action) {
        Intent.ACTION_SEND ->
            if (streamUri != null && imageLike) ShareIntake.LoadSingle(streamUri)
            else ShareIntake.NoContent

        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = streamUris.orEmpty()
            when {
                uris.size > 1 -> ShareIntake.TooManyItems
                uris.size == 1 && imageLike -> ShareIntake.LoadSingle(uris.single())
                else -> ShareIntake.NoContent
            }
        }

        else -> ShareIntake.NotShare
    }
}
