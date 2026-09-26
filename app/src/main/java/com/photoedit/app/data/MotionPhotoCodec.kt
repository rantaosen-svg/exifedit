package com.photoedit.app.data

object MotionPhotoCodec {
    data class Split(val photo: ByteArray, val video: ByteArray)
    private val MICRO = Regex("""MicroVideoOffset="(\d+)"""")
    private val ITEM = Regex("""Offset="(\d+)"\s+Length="(\d+)"\s+Id="MotionPhoto_Data"""")
    private const val XAP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val MAX_OFFSET_ITERATIONS = 16

    fun split(jpeg: ByteArray): Split? {
        val text = jpeg.toString(Charsets.ISO_8859_1)
        val videoLen = MICRO.find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?: ITEM.find(text)?.groupValues?.get(2)?.toLongOrNull()
            ?: return null
        if (videoLen <= 0 || videoLen > jpeg.size - 4) return null
        val start = jpeg.size - videoLen.toInt()
        val video = jpeg.copyOfRange(start, jpeg.size)
        if (!(video.size > 7 && video[4] == 'f'.code.toByte() && video[5] == 't'.code.toByte() &&
                video[6] == 'y'.code.toByte() && video[7] == 'p'.code.toByte())) return null
        return Split(jpeg.copyOfRange(0, start), video)
    }

    fun rebuild(photo: ByteArray, video: ByteArray): ByteArray {
        val text = String(photo, Charsets.ISO_8859_1)
        // 定点迭代：Item Offset 语义 = 视频在文件中的起始字节 = rebuiltPhoto 的最终长度；
        // 而 Offset 数字本身的位数又参与文本长度（XMP 增删字节），故迭代至自洽。
        // 位数变化单调收敛，MAX_OFFSET_ITERATIONS 仅为防死循环上限。
        var offset = photo.size
        var rebuiltPhoto = photo
        var iterations = 0
        while (true) {
            val fixed = text
                .replace(MICRO, "MicroVideoOffset=\"${video.size}\"")
                .replace(ITEM) { "Offset=\"$offset\" Length=\"${video.size}\" Id=\"MotionPhoto_Data\"" }
            val candidate = fixed.toByteArray(Charsets.ISO_8859_1)
            if (candidate.size == offset || ++iterations >= MAX_OFFSET_ITERATIONS) {
                rebuiltPhoto = candidate
                break
            }
            offset = candidate.size
        }
        val delta = rebuiltPhoto.size - photo.size
        if (delta == 0) return rebuiltPhoto + video
        // 只修正 XMP APP1（payload 以 xap 命名空间开头的那个 0xFFE1 段）的段长；
        // 定位失败或修正后长度越界 → 整体放弃，返回原 photo+video 拼接，不产损坏文件
        val lenPos = findXmpApp1LengthFieldPos(rebuiltPhoto) ?: return photo + video
        val oldLen = ((rebuiltPhoto[lenPos].toInt() and 0xFF) shl 8) or (rebuiltPhoto[lenPos + 1].toInt() and 0xFF)
        val newLen = oldLen + delta
        val segmentStart = lenPos - 2 // marker 0xFFE1 的起始
        if (newLen < 2 || newLen > 0xFFFF || segmentStart + 2 + newLen > rebuiltPhoto.size) return photo + video
        val header = rebuiltPhoto.copyOf()
        header[lenPos] = (newLen shr 8).toByte()
        header[lenPos + 1] = (newLen and 0xFF).toByte()
        return header + video
    }

    // 自 SOI 起按 JPEG marker 步进解析（非逐字节扫描，避免命中熵编码数据中的伪 FF E1），
    // 返回 XMP APP1 段长度字段（lenHi 所在下标）；越过 SOS/EOI 或结构非法返回 null
    private fun findXmpApp1LengthFieldPos(data: ByteArray): Int? {
        val xap = XAP_NAMESPACE.toByteArray(Charsets.ISO_8859_1)
        if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 3 < data.size) {
            if (data[i] != 0xFF.toByte()) return null
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xFF || marker == 0x00) { i++; continue } // 填充字节 / 熵段 stuffed 0x00
            if (marker == 0x01 || marker in 0xD0..0xD8 ||
                marker in 0x02..0x07 || marker in 0x09..0x0F
            ) { i += 2; continue } // 无长度字段的 marker
            if (marker == 0xD9 || marker == 0xDA) return null // EOI / SOS（其后为熵编码数据）
            val segLen = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (segLen < 2) return null
            if (marker == 0xE1) {
                val payloadStart = i + 4
                if (payloadStart + xap.size <= data.size &&
                    data.copyOfRange(payloadStart, payloadStart + xap.size).contentEquals(xap)
                ) return i + 2
            }
            i += 2 + segLen
        }
        return null
    }
}
