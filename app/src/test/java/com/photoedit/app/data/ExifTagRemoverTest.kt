package com.photoedit.app.data

import org.junit.Assert.assertArrayEquals
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

    // ---- 字节清零（隐私残留）：夹具 B，各外置值块互不重叠 ----

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun be32Bytes(v: Int): ByteArray =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** count 个 [num/den] RATIONAL（小端 u32 对），模拟 GPS 度分秒数据块 */
    private fun rationalsLe(vararg pairs: Pair<Int, Int>): ByteArray =
        pairs.fold(ByteArray(0)) { a, p -> a + u32(p.first) + u32(p.second) }

    private fun ascii(s: String, pad: Int): ByteArray =
        ByteArray(pad).also { s.toByteArray(Charsets.ISO_8859_1).copyInto(it) }

    // 布局 B：header(8) + IFD0(Artist/ExifPtr/GpsPtr) + Exif(DateTime) + GPS(Lat/Lon/Alt) + 独立外置块
    private val bIfd0 = 8
    private val bExif = bIfd0 + 2 + 3 * 12 + 4          // 52
    private val bGps = bExif + 2 + 12 + 4               // 70
    private val bArtist = bGps + 2 + 3 * 12 + 4         // 112，10 字节
    private val bDate = bArtist + 10                    // 122，20 字节
    private val bLat = bDate + 20                       // 142，24 字节
    private val bLon = bLat + 24                        // 166，24 字节
    private val bAlt = bLon + 24                        // 190，8 字节
    private val bSize = bAlt + 8                        // 198

    private val artistBlock = ascii("Artist0123", 10)
    private val dateBlock = ascii("2026:09:26 12:00:00", 20)
    private val latBlock = rationalsLe(31 to 1, 14 to 1, 44441 to 10000)
    private val lonBlock = rationalsLe(121 to 1, 59 to 1, 155555 to 10000)
    private val altBlock = rationalsLe(237 to 10)

    private val tiffB: ByteArray = run {
        val ifd0 = ifd(listOf(
            entry(0x013B, 2, 10, bArtist),      // Artist
            entry(0x8769, 4, 1, bExif),         // ExifIFD 指针
            entry(0x8825, 4, 1, bGps),          // GPSIFD 指针
        ))
        val exif = ifd(listOf(
            entry(0x9003, 2, 20, bDate),        // DateTimeOriginal
        ))
        val gps = ifd(listOf(
            entry(0x0002, 5, 3, bLat),          // GPSLatitude
            entry(0x0004, 5, 3, bLon),          // GPSLongitude
            entry(0x0006, 5, 1, bAlt),          // GPSAltitude
        ))
        u16(0x4949) + u16(42) + u32(bIfd0) + ifd0 + exif + gps +
            artistBlock + dateBlock + latBlock + lonBlock + altBlock
    }

    private val jpegB: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(tiffB.size + 6 + 2) +
        "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiffB +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun extractTiffB(j: ByteArray): ByteArray = j.copyOfRange(12, 12 + tiffB.size)

    @Test fun clearGpsZeroesGpsDirAndItsExternalValues() {
        // 前提：扫描器能捕获原坐标编码（防假绿）
        assertTrue(indexOf(jpegB, u32(44441)) >= 0)
        assertTrue(indexOf(jpegB, u32(155555)) >= 0)
        val out = ExifTagRemover.remove(jpegB, clearGps = true)!!
        assertEquals(jpegB.size, out.size) // 长度不变（原地覆写）
        val t = extractTiffB(out)
        // GPS 目录区间（count+条目+next）整体清零
        for (i in bGps until bArtist) assertEquals("gps dir $i", 0, t[i].toInt())
        // GPS 引用的外置值块清零：lat+lon+alt 连续覆盖到 tiff 末尾
        for (i in bLat until bSize) assertEquals("gps data $i", 0, t[i].toInt())
        // 坐标数值不再以任何 u32 小端/大端形式出现
        for (v in intArrayOf(44441, 155555, 10000, 237)) {
            assertTrue("le32 $v residue", indexOf(out, u32(v)) < 0)
            assertTrue("be32 $v residue", indexOf(out, be32Bytes(v)) < 0)
        }
        // 存活内容不受损：Artist/DateTime 外置块原样
        assertArrayEquals(artistBlock, t.copyOfRange(bArtist, bArtist + 10))
        assertArrayEquals(dateBlock, t.copyOfRange(bDate, bDate + 20))
        // IFD0 摘除 GPS 指针后剩 Artist + Exif 指针，指针有效
        val ifd0 = parseDir(t, bIfd0)
        assertEquals(2, ifd0.count)
        assertEquals(0x013B, ifd0.entries[0].first)
        assertEquals(bArtist.toLong(), ifd0.entries[0].second)
        assertEquals(0x8769, ifd0.entries[1].first)
        assertEquals(bExif.toLong(), ifd0.entries[1].second)
        val exif = parseDir(t, bExif)
        assertEquals(1, exif.count)
        assertEquals(0x9003, exif.entries[0].first)
        assertEquals(bDate.toLong(), exif.entries[0].second)
    }

    @Test fun droppedEntryScrubsWithItsOwnExternalValue() {
        val out = ExifTagRemover.remove(jpegB, ifd0Tags = setOf(0x013B))!!
        val t = extractTiffB(out)
        // Artist 外置块被清零
        for (i in bArtist until bDate) assertEquals("artist $i", 0, t[i].toInt())
        // 其余块原样（GPS/Exif 未触碰）
        assertArrayEquals(dateBlock, t.copyOfRange(bDate, bDate + 20))
        assertArrayEquals(latBlock, t.copyOfRange(bLat, bLat + 24))
        assertArrayEquals(lonBlock, t.copyOfRange(bLon, bLon + 24))
        val ifd0 = parseDir(t, bIfd0)
        assertEquals(2, ifd0.count)
        assertEquals(0x8769, ifd0.entries[0].first)
        assertEquals(bExif.toLong(), ifd0.entries[0].second)
    }

    @Test fun droppedExifSubIfdEntryScrubsWithItsExternalValue() {
        val out = ExifTagRemover.remove(jpegB, exifTags = setOf(0x9003))!!
        val t = extractTiffB(out)
        for (i in bDate until bLat) assertEquals("date $i", 0, t[i].toInt())
        assertArrayEquals(artistBlock, t.copyOfRange(bArtist, bArtist + 10))
        assertArrayEquals(latBlock, t.copyOfRange(bLat, bLat + 24))
    }

    @Test fun singleGpsTagDropScrubsWithItsValueOnly() {
        val out = ExifTagRemover.remove(jpegB, gpsTags = setOf(0x0006))!!
        val t = extractTiffB(out)
        // 海拔外置块清零；lat/lon 块与目录本体保持
        for (i in bAlt until bSize) assertEquals("alt $i", 0, t[i].toInt())
        assertArrayEquals(latBlock, t.copyOfRange(bLat, bLat + 24))
        assertArrayEquals(lonBlock, t.copyOfRange(bLon, bLon + 24))
        val gps = parseDir(t, bGps)
        assertEquals(2, gps.count)
        assertEquals(0x0002, gps.entries[0].first)
        assertEquals(bLat.toLong(), gps.entries[0].second)
    }

    @Test fun externalSharedWithLiveEntryIsNotScrubbed() {
        // 布局 C：Model 与 Copyright 共享同一外置块；只删 Model → 块必须保留（保守）
        val dataOff = 8 + 2 + 2 * 12 + 4 // 38
        val tiffC = u16(0x4949) + u16(42) + u32(8) +
            ifd(listOf(
                entry(0x0110, 2, 16, dataOff),  // Model
                entry(0x8298, 7, 16, dataOff),  // Copyright（同一块）
            )) + ByteArray(64) { 'C'.code.toByte() }
        val jpegC = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(tiffC.size + 6 + 2) +
            "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiffC +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val out = ExifTagRemover.remove(jpegC, ifd0Tags = setOf(0x0110))!!
        val t = out.copyOfRange(12, 12 + tiffC.size)
        val ifd0 = parseDir(t, 8)
        assertEquals(1, ifd0.count)
        assertEquals(0x8298, ifd0.entries[0].first)
        // 存活 Copyright 引用的块未被清零
        for (i in dataOff until dataOff + 64) assertEquals("byte $i", 'C'.code.toByte(), t[i])
    }

    // ---- Minor#3：TYPE_SIZES 表 SBYTE=1、SSHORT=2（旧表 4/8 把外置块算大） ----

    @Test fun sbyteAndSshortEntriesUseCorrectFieldWidthsForScrubbing() {
        // 布局 D：紧邻排布 SBYTE(type 6, count 8 → 8 字节) 与 SSHORT(type 8, count 5 → 10 字节)
        // 两个外置块。删前者 → 必须精确清零 8 字节且不动后者。
        // 旧表（SBYTE=4→32 字节、SSHORT=8→40 字节）下：被删块区间假性覆盖存活块 →
        // 重叠保护放弃清零（隐私残留），存活块区间也虚胖——本用例在旧实现下必红。
        val dataA = 8 + 2 + 2 * 12 + 4 // 38：SBYTE 块起点
        val dataB = dataA + 8 // 46：SSHORT 块起点（与 A 紧邻）
        val blockA = ByteArray(8) { 'A'.code.toByte() }
        val blockB = ByteArray(10) { 'B'.code.toByte() }
        val tiffD = u16(0x4949) + u16(42) + u32(8) +
            ifd(listOf(
                entry(0x0148, 6, 8, dataA), // BYTE(-ish) 私有标签位：SBYTE×8
                entry(0x0149, 8, 5, dataB), // SSHORT×5
            )) + blockA + blockB
        val jpegD = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(tiffD.size + 6 + 2) +
            "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiffD +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val out = ExifTagRemover.remove(jpegD, ifd0Tags = setOf(0x0148))!!
        assertEquals(jpegD.size, out.size) // 长度不变原则
        val t = out.copyOfRange(12, 12 + tiffD.size)
        // 被删条目外置块按真实宽度（1×8=8 字节）精确清零
        for (i in dataA until dataB) assertEquals("scrub $i", 0, t[i].toInt())
        // 存活 SSHORT 块一分不动（旧表算成 40 字节 → 重叠 → 连 A 块都不敢清）
        assertArrayEquals(blockB, t.copyOfRange(dataB, dataB + 10))
        // 目录条目删除正确：只剩 0x0149
        val ifd0 = parseDir(t, 8)
        assertEquals(1, ifd0.count)
        assertEquals(0x0149, ifd0.entries[0].first)
        assertEquals(dataB.toLong(), ifd0.entries[0].second)
    }
}
