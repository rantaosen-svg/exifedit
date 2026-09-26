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

    private fun ftypVideo(len: Int): ByteArray =
        ByteArray(len).also { it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte() }

    // 合法 APP1 段：长度字段 = 前缀+payload+2，用于双 APP1（EXIF 在前、XMP 在后）夹具
    private fun app1Segment(prefix: String, payload: String): ByteArray {
        val body = (prefix + payload).toByteArray(Charsets.ISO_8859_1)
        val len = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + body
    }

    private fun read16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

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

    // 缺陷 #1：delta≠0 时 Item Offset 必须等于视频真实起始位置（定点迭代收敛）
    @Test fun rebuildFixesItemOffsetWhenXmpTextGrew() {
        val xmp = "<Item:Item Offset=\"43\" Length=\"8\" Id=\"MotionPhoto_Data\"/>"
        val f = fixture(xmp, 8)
        val s = MotionPhotoCodec.split(f)!!
        val longerVideo = ftypVideo(1200) // Length 8→1200，XMP 文本增长 delta≠0
        val out = MotionPhotoCodec.rebuild(s.photo, longerVideo)
        val resplit = MotionPhotoCodec.split(out)!!
        assertContentEquals(longerVideo, resplit.video)
        val declared = Regex("""Offset="(\d+)"""")
            .find(out.toString(Charsets.ISO_8859_1))!!.groupValues[1].toInt()
        assertEquals(out.size - longerVideo.size, declared) // 声明 Offset == 真实视频起始
    }

    // 缺陷 #2：双 APP1（EXIF 前 / XMP 后）时只修正含 xap 命名空间的 XMP APP1 段长，EXIF 段长不得被改动
    @Test fun rebuildCorrectsXmpApp1AndLeavesExifApp1Untouched() {
        val xap = "http://ns.adobe.com/xap/1.0/\u0000"
        val xmpPayload = "<GPhoto:MicroVideoOffset=\"8\"/>"
        val exif = app1Segment("Exif\u0000\u0000", "fake-exif-thumbnail")
        val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exif + app1Segment(xap, xmpPayload)
        val s = MotionPhotoCodec.split(photo + ftypVideo(8))!!
        assertContentEquals(photo, s.photo)
        val longerVideo = ftypVideo(1200) // "8"→"1200"，delta = +3
        val out = MotionPhotoCodec.rebuild(s.photo, longerVideo)
        val resplit = MotionPhotoCodec.split(out)!!
        assertContentEquals(longerVideo, resplit.video)
        val text = out.toString(Charsets.ISO_8859_1)
        val exifLenPos = text.indexOf("Exif\u0000\u0000") - 2
        val xmpLenPos = text.indexOf(xap) - 2
        assertEquals(read16(exif, 2), read16(out, exifLenPos)) // EXIF APP1 段长原样
        assertEquals((xap + xmpPayload).toByteArray(Charsets.ISO_8859_1).size + 2 + 3, read16(out, xmpLenPos)) // XMP 段长 +delta
    }
}
