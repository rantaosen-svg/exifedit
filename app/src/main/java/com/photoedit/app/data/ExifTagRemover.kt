package com.photoedit.app.data

/**
 * 物理删除 JPEG EXIF APP1（TIFF）中的标签条目。
 *
 * 背景：androidx.exifinterface（1.3.7~1.4.2）没有公开 deleteAttribute，
 * 而"清除 GPS/时间"要求标签真正消失（spec §3.2「删除全部 GPS 标签」），
 * 因此在 saveAttributes 产物上直接编辑 TIFF 目录。
 *
 * 做法：IFD 条目是 12 字节定长记录；把保留记录平移进前部槽位、回写 count、
 * 清空多余槽位（不搬移任何字节区、文件长度不变），因此所有既有偏移
 * （Exif/GPS/IFD1 指针、值数据块偏移）全部保持有效。删除 GPS 标签集 =
 * 摘除 0x8825 GPS IFD 指针，整个 GPS 目录随之不可达。
 * 结构不可解析时返回 null，由调用方回退原字节，绝不产出损坏文件。
 */
internal object ExifTagRemover {

    // TIFF 标签号（非 ExifInterface 字符串常量）
    const val TIFF_TAG_MODEL = 0x0110
    const val TIFF_TAG_EXPOSURE_TIME = 0x829A
    const val TIFF_TAG_FNUMBER = 0x829D
    const val TIFF_TAG_EXIF_POINTER = 0x8769
    const val TIFF_TAG_GPS_POINTER = 0x8825
    const val TIFF_TAG_ISO = 0x8827
    const val TIFF_TAG_DATETIME_ORIGINAL = 0x9003
    const val TIFF_TAG_OFFSET_TIME_ORIGINAL = 0x9011
    const val TIFF_TAG_FOCAL_LENGTH = 0x920A
    const val TIFF_TAG_GPS_ALTITUDE = 0x0006
    const val TIFF_TAG_GPS_ALTITUDE_REF = 0x0007

    private const val ENTRY_SIZE = 12

    /**
     * @param ifd0Tags  从 IFD0 删除的标签（如 Model）
     * @param exifTags  从 Exif SubIFD 删除的标签（日期/光圈/快门/ISO/焦距）
     * @param gpsTags   从 GPS IFD 删除的标签（如海拔）
     * @param clearGps  摘除 GPS IFD 指针 → 全部 GPS 标签消失
     * @return 修改后的字节（长度与输入一致）；无需删除则原样返回；解析失败返回 null
     */
    fun remove(
        jpeg: ByteArray,
        ifd0Tags: Set<Int> = emptySet(),
        exifTags: Set<Int> = emptySet(),
        gpsTags: Set<Int> = emptySet(),
        clearGps: Boolean = false,
    ): ByteArray? {
        if (ifd0Tags.isEmpty() && exifTags.isEmpty() && gpsTags.isEmpty() && !clearGps) return jpeg
        return try {
            val data = jpeg.copyOf()
            val tiff = findTiffBase(data) ?: return null
            if (data.size - tiff < 8) return null
            val be = when {
                data[tiff] == 'M'.code.toByte() && data[tiff + 1] == 'M'.code.toByte() -> true
                data[tiff] == 'I'.code.toByte() && data[tiff + 1] == 'I'.code.toByte() -> false
                else -> return null
            }
            if (u16(data, tiff + 2, be) != 42) return null
            val ifd0 = u32(data, tiff + 4, be).toInt()
            // 先读全部目录指针再做删除：平移只在目录内部，不改变目录自身位置，指针保持有效
            val exifOff = pointerInDir(data, tiff, be, ifd0, TIFF_TAG_EXIF_POINTER)
            val gpsOff = pointerInDir(data, tiff, be, ifd0, TIFF_TAG_GPS_POINTER)
                ?: exifOff?.let { pointerInDir(data, tiff, be, it, TIFF_TAG_GPS_POINTER) }
            var touched = false
            if (clearGps) {
                touched = dropEntries(data, tiff, be, ifd0, setOf(TIFF_TAG_GPS_POINTER)) || touched
                if (exifOff != null) {
                    touched = dropEntries(data, tiff, be, exifOff, setOf(TIFF_TAG_GPS_POINTER)) || touched
                }
            }
            if (ifd0Tags.isNotEmpty()) {
                touched = dropEntries(data, tiff, be, ifd0, ifd0Tags) || touched
            }
            if (exifOff != null && exifTags.isNotEmpty()) {
                touched = dropEntries(data, tiff, be, exifOff, exifTags) || touched
            }
            if (gpsOff != null && gpsTags.isNotEmpty()) {
                touched = dropEntries(data, tiff, be, gpsOff, gpsTags) || touched
            }
            if (touched) data else jpeg
        } catch (e: Exception) {
            null
        }
    }

    /** 自 SOI 步进 JPEG 段，返回首个 "Exif\0\0" APP1 段的 TIFF 头起始下标。 */
    private fun findTiffBase(jpeg: ByteArray): Int? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF || marker == 0x00) { i++; continue }
            if (marker == 0x01 || marker in 0xD0..0xD9 ||
                marker in 0x02..0x07 || marker in 0x09..0x0F
            ) { i += 2; continue }
            if (marker == 0xDA) return null // 熵编码数据开始
            val segLen = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (segLen < 2) return null
            if (marker == 0xE1) {
                val p = i + 4
                val exif = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
                if (p + 6 <= jpeg.size && jpeg.copyOfRange(p, p + 6).contentEquals(exif)) return p + 6
            }
            i += 2 + segLen
        }
        return null
    }

    private fun dirEntryCount(data: ByteArray, tiff: Int, be: Boolean, dirOff: Int): Int {
        require(dirOff in 0 until data.size - tiff)
        val n = u16(data, tiff + dirOff, be)
        require(n in 0..2000)
        require(tiff + dirOff + 2L + n.toLong() * ENTRY_SIZE + 4 <= data.size)
        return n
    }

    private fun pointerInDir(data: ByteArray, tiff: Int, be: Boolean, dirOff: Int, tag: Int): Int? = try {
        val n = dirEntryCount(data, tiff, be, dirOff)
        var result: Int? = null
        for (i in 0 until n) {
            val pos = tiff + dirOff + 2 + i * ENTRY_SIZE
            if (u16(data, pos, be) == tag) {
                val off = u32(data, pos + 8, be)
                if (off in 8 until (data.size - tiff).toLong()) result = off.toInt()
                break
            }
        }
        result
    } catch (e: Exception) {
        null
    }

    /** 目录内平移删除指定标签条目；返回是否发生修改。 */
    private fun dropEntries(data: ByteArray, tiff: Int, be: Boolean, dirOff: Int, tags: Set<Int>): Boolean {
        val n = dirEntryCount(data, tiff, be, dirOff)
        val kept = ArrayList<ByteArray>()
        for (i in 0 until n) {
            val pos = tiff + dirOff + 2 + i * ENTRY_SIZE
            val tag = u16(data, pos, be)
            if (tags.none { it == tag }) kept.add(data.copyOfRange(pos, pos + ENTRY_SIZE))
        }
        if (kept.size == n) return false
        putU16(data, tiff + dirOff, be, kept.size)
        var pos = tiff + dirOff + 2
        kept.forEach { it.copyInto(data, pos); pos += ENTRY_SIZE }
        val end = tiff + dirOff + 2 + n * ENTRY_SIZE
        while (pos < end) { data[pos] = 0; pos++ } // 空槽清零，避免脏记录被误读
        return true
    }

    private fun u16(b: ByteArray, i: Int, be: Boolean): Int {
        val x = b[i].toInt() and 0xFF
        val y = b[i + 1].toInt() and 0xFF
        return if (be) (x shl 8) or y else (y shl 8) or x
    }

    private fun u32(b: ByteArray, i: Int, be: Boolean): Long {
        val x = (b[i].toLong() and 0xFF) shl 24 or
            (b[i + 1].toLong() and 0xFF) shl 16 or
            (b[i + 2].toLong() and 0xFF) shl 8 or
            (b[i + 3].toLong() and 0xFF)
        return if (be) x else ((b[i + 3].toLong() and 0xFF) shl 24 or
            (b[i + 2].toLong() and 0xFF) shl 16 or
            (b[i + 1].toLong() and 0xFF) shl 8 or
            (b[i].toLong() and 0xFF))
    }

    private fun putU16(b: ByteArray, i: Int, be: Boolean, v: Int) {
        if (be) {
            b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte()
        } else {
            b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte()
        }
    }
}
