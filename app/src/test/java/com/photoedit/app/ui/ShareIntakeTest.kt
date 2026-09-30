package com.photoedit.app.ui

import android.content.Intent
import android.net.FakeUri
import android.net.Uri
import kotlin.test.assertIs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分享入口判定的纯函数测（改动 1，TDD）：
 * 根因取证——旧 intakeShareIntent 只接受 type 恰为 image/jpeg|jpg，相册分享常见的
 * image 通配、全通配、image/heic 全被拒并弹误导文案。新规则按"是否图片类声明 +
 * 是否有内容"判定，真实格式交给下游 ExifRepository 的 SOI 魔数判定。
 *
 * 注：android.content.Intent.ACTION_SEND* 是 String 常量，编译期内联，JVM 可安全引用。
 */
class ShareIntakeTest {

    private val uri = FakeUri("content://media/external/images/media/1000000116")
    private val uri2 = FakeUri("content://media/external/images/media/2")
    private val uri3 = FakeUri("content://media/external/images/media/3")

    private fun send(type: String?, stream: Uri?) =
        classifyShareIntake(Intent.ACTION_SEND, type, stream, null)

    private fun sendMultiple(type: String?, streams: List<Uri>?) =
        classifyShareIntake(Intent.ACTION_SEND_MULTIPLE, type, null, streams)

    @Test
    fun `SEND image_jpeg 带 uri → LoadSingle`() {
        val r = classifyShareIntake(Intent.ACTION_SEND, "image/jpeg", uri, null)
        assertIs<ShareIntake.LoadSingle>(r)
        assertEquals(uri, r.uri)
    }

    @Test
    fun `SEND image 通配声明带 uri → LoadSingle（旧版误拒）`() {
        assertIs<ShareIntake.LoadSingle>(send("image/*", uri))
    }

    @Test
    fun `SEND 全通配声明带 uri → LoadSingle`() {
        assertIs<ShareIntake.LoadSingle>(send("*/*", uri))
    }

    @Test
    fun `SEND image_heic 带 uri → LoadSingle（格式判定交下游 SOI 魔数）`() {
        assertIs<ShareIntake.LoadSingle>(send("image/heic", uri))
    }

    @Test
    fun `SEND type 为 null 带 uri → LoadSingle`() {
        assertIs<ShareIntake.LoadSingle>(send(null, uri))
    }

    @Test
    fun `SEND 无 uri → NoContent`() {
        assertEquals(ShareIntake.NoContent, send("image/jpeg", null))
    }

    @Test
    fun `SEND_MULTIPLE 单张 → LoadSingle`() {
        val r = sendMultiple("image/*", listOf(uri))
        assertIs<ShareIntake.LoadSingle>(r)
        assertEquals(uri, r.uri)
    }

    @Test
    fun `SEND_MULTIPLE 三张 → TooManyItems`() {
        assertEquals(ShareIntake.TooManyItems, sendMultiple("image/jpeg", listOf(uri, uri2, uri3)))
    }

    @Test
    fun `SEND_MULTIPLE 空列表 → NoContent`() {
        assertEquals(ShareIntake.NoContent, sendMultiple("image/jpeg", emptyList()))
    }

    @Test
    fun `SEND_MULTIPLE 单张但非图片类 type → NoContent`() {
        assertEquals(ShareIntake.NoContent, sendMultiple("text/plain", listOf(uri)))
    }

    @Test
    fun `SEND text_plain → NoContent（非图片类声明）`() {
        assertEquals(ShareIntake.NoContent, send("text/plain", uri))
    }

    @Test
    fun `非分享 action → NotShare`() {
        assertEquals(ShareIntake.NotShare, classifyShareIntake(Intent.ACTION_MAIN, null, uri, null))
        assertEquals(ShareIntake.NotShare, classifyShareIntake(null, "image/jpeg", uri, null))
    }
}
