package com.photoedit.app.data

import kotlin.test.assertContentEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPhotoCodecTest {
    private fun app1(payload: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (payload.length + 3).toByte()) +
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + payload.toByteArray()

    private fun fixture(xmp: String, videoLen: Int): ByteArray {
        val video = ByteArray(videoLen).also { it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte() }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1(xmp) + video
    }

    @Test fun detectsViaMicroVideoOffset() {
        val f = fixture("<x:xmpmeta><GPhoto:MicroVideoOffset=\"8\"/></x:xmpmeta>", 8)
        val s = MotionPhotoCodec.split(f)!!
        assertEquals(8, s.video.size)
    }
    @Test fun detectsViaItemOffsetLength() {
        val xmp = "<Item:Item Offset=\"43\" Length=\"8\" Id=\"MotionPhoto_Data\"/>"
        val f = fixture(xmp, 8); val s = MotionPhotoCodec.split(f)!!
        assertEquals(8, s.video.size)
    }
    @Test fun plainJpegReturnsNull() {
        assertNull(MotionPhotoCodec.split(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)))
    }
    @Test fun rebuildFixesOffsetsAfterExifGrew() {
        val f = fixture("<GPhoto:MicroVideoOffset=\"8\"/>", 8)
        val s = MotionPhotoCodec.split(f)!!
        val grownPhoto = s.photo + byteArrayOf(0, 0, 0, 0) // 模拟 EXIF 变长 4 字节
        val out = MotionPhotoCodec.rebuild(grownPhoto, s.video)
        val resplit = MotionPhotoCodec.split(out)!!   // 修正后仍可识别
        assertContentEquals(s.video, resplit.video)
    }
}
