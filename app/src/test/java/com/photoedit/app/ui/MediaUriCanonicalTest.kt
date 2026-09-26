package com.photoedit.app.ui

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Task14a 缺陷 1：canonical MediaStore uri 归一的纯字符串决策（JVM 可测，无 Android 运行时）。 */
class MediaUriCanonicalTest {

    @Test
    fun `已是规范 media images uri 原样归一并可覆盖`() {
        val out = decideCanonicalUri("content://media/external/images/media/1000000116", null, null)
        assertTrue(out.canOverwrite)
        assertEquals("content://media/external/images/media/1000000116", out.uriString)
    }

    @Test
    fun `规范 uri 去 query fragment 后仍归一`() {
        val out = decideCanonicalUri("content://media/external/images/media/42?foo=bar", null, null)
        assertTrue(out.canOverwrite)
        assertEquals("content://media/external/images/media/42", out.uriString)
    }

    @Test
    fun `getMediaUri 归一结果优先于 picked picker uri`() {
        val out = decideCanonicalUri(
            "content://media/picker/0/external/images/media/77",
            "content://media/external/images/media/77",
            null,
        )
        assertTrue(out.canOverwrite)
        assertEquals("content://media/external/images/media/77", out.uriString)
    }

    @Test
    fun `非规范 content uri 但查到 _ID 时重建规范 images uri 且可覆盖`() {
        val out = decideCanonicalUri(
            "content://com.android.providers.media.documents/document/image%3A1234",
            null,
            1234L,
        )
        assertTrue(out.canOverwrite)
        assertEquals("content://media/external/images/media/1234", out.uriString)
    }

    @Test
    fun `file uri 回退原 uri 且不可覆盖`() {
        val out = decideCanonicalUri("file:///sdcard/DCIM/smoke.jpg", null, null)
        assertFalse(out.canOverwrite)
        assertEquals("file:///sdcard/DCIM/smoke.jpg", out.uriString)
    }

    @Test
    fun `拿不到规范 uri 时回退原 uri 且不可覆盖`() {
        val out = decideCanonicalUri("content://media/picker/0/external/images/media/9", null, null)
        assertFalse(out.canOverwrite)
        assertEquals("content://media/picker/0/external/images/media/9", out.uriString)
    }

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
