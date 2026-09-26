package com.photoedit.app.ui

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Task14a 修复轮1：writeUri 候选决策的纯字符串测（JVM 可测，无 Android 运行时）。
 *
 * 语义变化（相对被评审否定的旧版）：归一**不再替换读取 uri**——decideWriteUri 只回答
 * "覆盖链路能不能用、用哪个规范 media uri"，返回 null 即降级为仅另存副本。
 * Critical#2 的来源门：只有 media authority 自身（picker uri）或 MediaStore 的
 * documents provider 应答的 `_ID` 才等价 MediaStore 行号；第三方 provider 的 `_ID`
 * 与 MediaStore 行号无关，绝不允许拿它猜行重建（否则授权后可能覆盖不相干的照片）。
 */
class MediaUriCanonicalTest {

    // ---- 可信来源：规范 media images uri ----

    @Test
    fun `已是规范 media images uri 时 writeUri 为去 query fragment 的规范形`() {
        assertEquals(
            "content://media/external/images/media/1000000116",
            decideWriteUri("content://media/external/images/media/1000000116", null, null),
        )
        assertEquals(
            "content://media/external/images/media/42",
            decideWriteUri("content://media/external/images/media/42?foo=bar", null, null),
        )
    }

    @Test
    fun `picker uri 经 getMediaUri 解析出规范 uri 时采用之`() {
        // documents uri 场景：getMediaUri 结果本身即 media images 引用
        assertEquals(
            "content://media/external/images/media/77",
            decideWriteUri(
                "content://com.android.externalstorage.documents/document/primary%3ADCIM%2Fsmoke.jpg",
                "content://media/external/images/media/77",
                null,
            ),
        )
    }

    // ---- picker media uri：直接查自身 _ID 重建（读授权在 picker uri 上，query 可用）----

    @Test
    fun `picker media uri 查到 _ID 时重建规范 images uri 作为 writeUri`() {
        assertEquals(
            "content://media/external/images/media/77",
            decideWriteUri("content://media/picker/0/external/images/media/77", null, 77L),
        )
    }

    @Test
    fun `picker uri 的 _ID 列查不到时按末段路径兜底（API35 实测 _ID 返回 null）`() {
        // 修复轮 1 设备冒烟实录：Android 35 PhotoPicker 返回
        // content://media/picker/0/com.android.providers.media.photopicker/media/1000000118，
        // query(_ID) 有行但值为 null——末段即 MediaStore 行号，authority 是 media，可信。
        assertEquals(
            "content://media/external/images/media/1000000118",
            decideWriteUri(
                "content://media/picker/0/com.android.providers.media.photopicker/media/1000000118",
                null,
                null,
            ),
        )
    }

    @Test
    fun `picker uri 路径带 internal 卷提示时重建到 internal 卷`() {
        assertEquals(
            "content://media/internal/images/media/9",
            decideWriteUri("content://media/picker/0/internal/images/media/9", null, 9L),
        )
        // _ID 查询为 null 时同走末段兜底，卷提示一致
        assertEquals(
            "content://media/internal/images/media/9",
            decideWriteUri("content://media/picker/0/internal/images/media/9", null, null),
        )
    }

    @Test
    fun `picker uri 末段非数字且查不到 _ID 时无 writeUri（仅另存副本）`() {
        assertNull(decideWriteUri("content://media/picker/0/external/images/media/abc", null, null))
        assertNull(decideWriteUri("content://media/picker/0/cloud/remoteitem", null, null))
    }

    // ---- Critical#2：权威来源门 ----

    @Test
    fun `media documents provider 的 _ID 可信 重建规范 images uri`() {
        // 旧版不安全用例的修正版：这条之所以允许，是因为 authority 是
        // com.android.providers.media.documents——其 _ID 就是 MediaStore 行号。
        assertEquals(
            "content://media/external/images/media/1234",
            decideWriteUri(
                "content://com.android.providers.media.documents/document/image%3A1234",
                null,
                1234L,
            ),
        )
    }

    @Test
    fun `第三方 authority 的 _ID 不得重建 writeUri（不得猜 MediaStore 行）`() {
        // 评审 Critical#2 固化的反例：第三方 share provider 也能应答 _ID 查询，
        // 但其值与 MediaStore 行号无关——旧实现会重建 images uri 并开启覆盖 = 数据丢失风险。
        assertNull(
            decideWriteUri("content://com.example.provider/files/5", null, 1234L)
        )
        // 即便第三方 uri 路径长得像 picker（末段数字），authority 不是 media 一律拒绝
        assertNull(decideWriteUri("content://com.example.provider/picker/0/media/5", null, null))
        assertFalse(supportsMediaIdQuery("content://com.example.provider/files/5"))
        assertFalse(supportsMediaIdQuery("content://com.android.externalstorage.documents/document/primary%3Aa.jpg"))
    }

    @Test
    fun `externalstorage documents 只走 getMediaUri 不走 _ID 重建`() {
        assertFalse(supportsMediaIdQuery("content://com.android.externalstorage.documents/document/primary%3Aa.jpg"))
        assertTrue(supportsGetMediaUri("content://com.android.externalstorage.documents/document/primary%3Aa.jpg"))
        assertTrue(supportsGetMediaUri("content://com.android.providers.media.documents/document/image%3A1"))
        assertFalse(supportsGetMediaUri("content://media/external/images/media/1")) // 非 media authority 根本不调 getMediaUri（评审 #5）
        assertFalse(supportsGetMediaUri("file:///sdcard/a.jpg"))
    }

    // ---- 降级路径 ----

    @Test
    fun `file uri 无 writeUri 且各类查询门都拒绝`() {
        val file = "file:///sdcard/DCIM/smoke.jpg"
        assertNull(decideWriteUri(file, null, 1234L))
        assertFalse(supportsGetMediaUri(file))
        assertFalse(supportsMediaIdQuery(file))
        assertNull(authorityOf(file))
    }

    @Test
    fun `getMediaUri 结果非规范 images 引用时忽略`() {
        assertNull(
            decideWriteUri(
                "content://com.android.externalstorage.documents/document/primary%3Aa.jpg",
                "content://media/external/video/media/5",
                null,
            )
        )
    }

    // ---- mediaImagesRefOf 解析 ----

    @Test
    fun `picker uri 不属于规范 images ref`() {
        assertNull(mediaImagesRefOf("content://media/picker/0/external/images/media/9"))
    }

    @Test
    fun `mediaImagesRefOf 解析内外置卷与 id`() {
        assertEquals(MediaImagesRef(42L, "external"), mediaImagesRefOf("content://media/external/images/media/42"))
        assertEquals(MediaImagesRef(7L, "internal"), mediaImagesRefOf("content://media/internal/images/media/7"))
        assertNull(mediaImagesRefOf("content://media/external/video/media/42")) // 非 images
    }
}
