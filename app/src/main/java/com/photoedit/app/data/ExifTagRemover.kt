package com.photoedit.app.data

import java.util.Arrays

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
 *
 * 隐私（字节残留清零）：仅摘指针/移条目会让不可达的原始字节留在文件里
 * （GPS 目录块本体、被删条目 count 超出 4 字节时的外置 value 数据块），
 * 十六进制扫描可恢复原坐标。因此删除时对**确定只有被删条目引用**的字节区间
 * 做原地覆写 0（长度不变原则：只覆写、不搬移）：
 * - clearGps：GPS 目录块（count+条目+next，含 next 链）与其全部外置值块；
 * - 普通删条目：该条目自身的外置值块。
 * 保护检查跨所有存活目录（IFD0/Exif/GPS/IFD1/Interop 链、同目录删前后条目）：
 * 候选区间与任一存活引用（目录块或存活条目的外置值块）重叠则不清。
 * 存在无法解析的可达子目录（opaque 指针）时整体放弃清零，绝不冒损坏风险。
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
    private const val MAX_DIRS = 64
    private const val TAG_INTEROP_POINTER = 0xA005

    /** TIFF 字段类型 → 单字节宽度；索引即 type 号，0=未知（未知一律不清零，保守）。 */
    private val TYPE_SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 4, 1, 8, 4, 8, 4, 8)

    /** [start, endExclusive) 的绝对下标区间 */
    private class Range(val start: Int, val end: Int) {
        fun overlaps(o: Range): Boolean = start < o.end && o.start < end
    }

    private class Entry(val pos: Int, val tag: Int, val type: Int, val count: Long, val value: Long) {
        /** 外置数据时的字节数（typeSize×count>4 → value 字段是偏移）；否则 0。-1=类型未知，不可判定。 */
        fun externalSize(): Long {
            if (type < 1 || type >= TYPE_SIZES.size || TYPE_SIZES[type] == 0) return if (count > 0) -1 else 0
            val total = TYPE_SIZES[type].toLong() * count
            return if (total > 4) total else 0
        }
    }

    private class Dir(val offset: Int, val entries: List<Entry>, val next: Int) {
        fun blockRange(tiff: Int): Range =
            Range(tiff + offset, tiff + offset + 2 + entries.size * ENTRY_SIZE + 4)
        fun tagValue(tag: Int): Long? = entries.firstOrNull { it.tag == tag }?.value
    }

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
            val ifd0Off = u32(data, tiff + 4, be).toInt()
            val tiffSize = data.size - tiff
            val ifd0 = parseDir(data, tiff, be, ifd0Off, tiffSize) ?: return null

            // 先读全部目录指针再做删除：平移只在目录内部，不改变目录自身位置，指针保持有效
            val exifOff = validPtr(ifd0.tagValue(TIFF_TAG_EXIF_POINTER), tiffSize)

            // 收集全部可达目录（IFD0/IFD1 链、Exif/GPS/Interop 子指针）用于存活引用判定
            val all = LinkedHashMap<Int, Dir>()
            all[ifd0Off] = ifd0
            var opaque = false // 存在可达但解析不了的子目录 → 放弃清零（保守）
            fun collect(off: Int?) {
                if (off == null || all.containsKey(off)) return
                if (all.size >= MAX_DIRS) { opaque = true; return }
                val d = parseDir(data, tiff, be, off, tiffSize)
                if (d == null) { opaque = true; return }
                all[off] = d
                collect(ifdNext(d, tiffSize))
                collect(validPtr(d.tagValue(TIFF_TAG_EXIF_POINTER), tiffSize))
                // GPS 指针：clearGps 时从 ifd0/exif 摘除（目标判死）；出现在其他目录则仍是存活可达目录
                collect(validPtr(d.tagValue(TIFF_TAG_GPS_POINTER), tiffSize))
                collect(validPtr(d.tagValue(TAG_INTEROP_POINTER), tiffSize))
            }
            collect(ifdNext(ifd0, tiffSize))
            exifOff?.let { collect(it) }
            val gpsOff = (validPtr(ifd0.tagValue(TIFF_TAG_GPS_POINTER), tiffSize)
                ?: exifOff?.let { off -> all[off]?.tagValue(TIFF_TAG_GPS_POINTER)?.let { validPtr(it, tiffSize) } })
                ?.takeIf { it != ifd0Off } // 病态自指，不判死 ifd0
            collect(gpsOff)
            collect(validPtr(ifd0.tagValue(TAG_INTEROP_POINTER), tiffSize))

            val drops = HashMap<Int, Set<Int>>()
            drops[ifd0Off] = if (clearGps) ifd0Tags + TIFF_TAG_GPS_POINTER else ifd0Tags
            if (exifOff != null && exifOff != ifd0Off) {
                drops[exifOff] = if (clearGps) exifTags + TIFF_TAG_GPS_POINTER else exifTags
            }
            if (gpsOff != null && !clearGps && gpsTags.isNotEmpty()) {
                drops[gpsOff] = gpsTags
            }
            // clearGps 后 GPS 目录整块不可达 → 判死（含 next 链）
            val deadDirs = HashSet<Int>()
            if (clearGps && gpsOff != null) {
                var o: Int? = gpsOff
                while (o != null && all.containsKey(o) && deadDirs.add(o)) {
                    o = ifdNext(all.getValue(o), tiffSize)
                }
            }

            // 存活引用：TIFF 头 + 存活目录块 + 存活条目（未被本次删除）的外置值块
            val live = ArrayList<Range>()
            live.add(Range(tiff, tiff + 8))
            val candidates = ArrayList<Range>()
            var undecided = false // 存活条目类型未知 → 其数据范围不可判定，放弃清零
            for ((off, d) in all) {
                if (deadDirs.contains(off)) {
                    candidates.add(d.blockRange(tiff))
                    for (e in d.entries) extRange(e, tiff, tiffSize)?.let { candidates.add(it) }
                    continue
                }
                live.add(d.blockRange(tiff))
                val dropTags = drops[off] ?: emptySet()
                for (e in d.entries) {
                    if (e.tag in dropTags) {
                        extRange(e, tiff, tiffSize)?.let { candidates.add(it) }
                    } else {
                        extRange(e, tiff, tiffSize)?.let { live.add(it) }
                        // 类型未知且值像外置偏移：数据范围不可判定 → 放弃清零（保守）
                        if (e.externalSize() < 0 && e.value in 8 until tiffSize.toLong()) undecided = true
                    }
                }
            }

            // 目录平移删除指定标签条目（死目录不重写——整块清零覆盖）
            var touched = false
            for ((off, tags) in drops) {
                if (tags.isEmpty() || deadDirs.contains(off)) continue
                // 目标目录解析失败时与旧实现一致返回 null（调用方回退原字节）
                val d = all[off] ?: return null
                touched = rewriteDir(data, tiff, be, d, tags) || touched
            }

            // 原地覆写 0：仅清"确定只被删除内容引用"的区间；与任何存活引用重叠则跳过
            if (candidates.isNotEmpty() && !opaque && !undecided) {
                for (c in candidates) {
                    if (live.any { it.overlaps(c) }) continue
                    Arrays.fill(data, c.start, minOf(c.end, data.size), 0)
                    touched = true
                }
            }
            if (touched) data else jpeg
        } catch (e: Exception) {
            null
        }
    }

    /** 目录 next 指针（有效值），否则 null。 */
    private fun ifdNext(d: Dir, tiffSize: Int): Int? =
        if (d.next in 8 until tiffSize) d.next else null

    private fun validPtr(raw: Long?, tiffSize: Int): Int? =
        raw?.takeIf { it in 8 until tiffSize.toLong() }?.toInt()

    /** 条目外置值块绝对区间；非外置或越界返回 null。 */
    private fun extRange(e: Entry, tiff: Int, tiffSize: Int): Range? {
        val size = e.externalSize()
        if (size <= 0 || e.value < 8 || e.value + size > tiffSize) return null
        return Range(tiff + e.value.toInt(), tiff + e.value.toInt() + size.toInt())
    }

    private fun parseDir(data: ByteArray, tiff: Int, be: Boolean, off: Int, tiffSize: Int): Dir? = try {
        require(off in 0 until tiffSize)
        val n = u16(data, tiff + off, be)
        require(n in 0..2000)
        require(tiff + off + 2L + n.toLong() * ENTRY_SIZE + 4 <= data.size)
        val entries = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val pos = tiff + off + 2 + i * ENTRY_SIZE
            entries.add(Entry(pos, u16(data, pos, be), u16(data, pos + 2, be), u32(data, pos + 4, be), u32(data, pos + 8, be)))
        }
        val next = u32(data, tiff + off + 2 + n * ENTRY_SIZE, be)
        Dir(off, entries, next.toInt())
    } catch (e: Exception) {
        null
    }

    /** 目录内平移删除指定标签条目；返回是否发生修改。 */
    private fun rewriteDir(data: ByteArray, tiff: Int, be: Boolean, dir: Dir, dropTags: Set<Int>): Boolean {
        val kept = dir.entries.filter { it.tag !in dropTags }
        if (kept.size == dir.entries.size) return false
        putU16(data, tiff + dir.offset, be, kept.size)
        var pos = tiff + dir.offset + 2
        for (e in kept) {
            System.arraycopy(data, e.pos, data, pos, ENTRY_SIZE)
            pos += ENTRY_SIZE
        }
        val end = tiff + dir.offset + 2 + dir.entries.size * ENTRY_SIZE
        while (pos < end) { data[pos] = 0; pos++ } // 空槽清零，避免脏记录被误读
        return true
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
