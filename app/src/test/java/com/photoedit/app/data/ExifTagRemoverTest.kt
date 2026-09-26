package com.photoedit.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExifTagRemover 纯字节逻辑的 JVM 单测（合成 TIFF 目录夹具，小端）。
 * 与仪器测试互补：这里精确验证目录平移/指针有效性，无需设备。
 */
class ExifTagRemoverTest {

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** [tag, type, count, value/offset] */
    private fun entry(tag: Int, type: Int, count: Int, value: Int): ByteArray =
        u16(tag) + u16(type) + u32(count) + u32(value)

    private fun ifd(entries: List<ByteArray>, next: Int = 0): ByteArray =
        u16(entries.size) + entries.fold(ByteArray(0)) { a, e -> a + e } + u32(next)

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    private class Dir(val count: Int, val entries: List<Pair<Int, Long>>)

    /** 从 TIFF 头（相对 tiff 基址）解析目录 */
    private fun parseDir(tiff: ByteArray, off: Int): Dir {
        val n = le16(tiff, off)
        val entries = (0 until n).map {
            val p = off + 2 + it * 12
            le16(tiff, p) to le32(tiff, p + 8)
        }
        return Dir(n, entries)
    }

    // 布局：header(8) + IFD0(2 条目) + ExifIFD(2) + GpsIFD(2) + 数据块
    private val ifd0Off = 8
    private val exifOff = ifd0Off + 2 + 24 + 4          // 38
    private val gpsOff = exifOff + 2 + 24 + 4           // 68
    private val dataOff = gpsOff + 2 + 24 + 4           // 98

    private val tiff: ByteArray = run {
        val ifd0 = ifd(listOf(
            entry(0x0110, 2, 8, dataOff),       // Model
            entry(0x8769, 4, 1, exifOff),       // ExifIFD pointer
        ))
        val exif = ifd(listOf(
            entry(0x9003, 2, 20, dataOff),      // DateTimeOriginal
            entry(0x8825, 4, 1, gpsOff),        // GPSIFD pointer
        ))
        val gps = ifd(listOf(
            entry(0x0002, 5, 3, dataOff),       // GPSLatitude
            entry(0x0006, 5, 1, dataOff),       // GPSAltitude
        ))
        u16(0x4949) + u16(42) + u32(ifd0Off) + ifd0 + exif + gps + ByteArray(64)
    }

    private val jpeg: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(tiff.size + 6 + 2) +
        "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** TIFF 段固定起始：SOI(2) + marker(2) + 段长(2) + "Exif\0\0"(6) */
    private fun extractTiff(j: ByteArray): ByteArray = j.copyOfRange(12, 12 + tiff.size)

    @Test fun clearGpsAndSelectedTagsKeepsOffsetsValid() {
        val out = ExifTagRemover.remove(jpeg, ifd0Tags = setOf(0x0110), exifTags = setOf(0x9003), clearGps = true)
        assertTrue(out != null)
        assertEquals(jpeg.size, out!!.size) // 长度不变
        val t = extractTiff(out)
        val ifd0 = parseDir(t, ifd0Off)
        assertEquals(1, ifd0.count)
        assertEquals(0x8769, ifd0.entries[0].first)
        assertEquals(exifOff.toLong(), ifd0.entries[0].second) // Exif 指针原样有效
        val exif = parseDir(t, exifOff)
        assertEquals(0, exif.count) // 日期与 GPS 指针均被摘除
        // 空槽已清零，不残留脏记录
        for (i in exifOff + 2 until exifOff + 2 + 24) assertEquals(0, t[i].toInt())
    }

    @Test fun removesOnlyWithinGpsDirWhenPointerStays() {
        val out = ExifTagRemover.remove(jpeg, gpsTags = setOf(0x0006))!!
        val t = extractTiff(out)
        assertEquals(2, parseDir(t, ifd0Off).count)
        val exif = parseDir(t, exifOff)
        assertEquals(2, exif.count)
        assertTrue(exif.entries.any { it.first == 0x8825 })
        val gps = parseDir(t, gpsOff)
        assertEquals(1, gps.count)
        assertEquals(0x0002, gps.entries[0].first)
    }

    @Test fun nothingToDeleteReturnsSameArray() {
        assertSame(jpeg, ExifTagRemover.remove(jpeg))
    }

    @Test fun missingExifApp1YieldsNull() {
        val noExif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertNull(ExifTagRemover.remove(noExif, clearGps = true))
        assertNull(ExifTagRemover.remove(ByteArray(0), ifd0Tags = setOf(0x0110)))
    }
}
