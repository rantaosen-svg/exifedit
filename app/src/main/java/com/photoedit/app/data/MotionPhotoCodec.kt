package com.photoedit.app.data

object MotionPhotoCodec {
    data class Split(val photo: ByteArray, val video: ByteArray)
    private val MICRO = Regex("""MicroVideoOffset="(\d+)"""")
    private val ITEM = Regex("""Offset="(\d+)"\s+Length="(\d+)"\s+Id="MotionPhoto_Data"""")

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
        val newOffset = (photo.size + video.size - video.size).toLong() // video 紧跟 photo
        val text = String(photo, Charsets.ISO_8859_1)
        val fixed = text
            .replace(MICRO, "MicroVideoOffset=\"${video.size}\"")
            .replace(ITEM) { m -> "Offset=\"$newOffset\" Length=\"${video.size}\" Id=\"MotionPhoto_Data\"" }
        val rebuiltPhoto = fixed.toByteArray(Charsets.ISO_8859_1)
        val delta = rebuiltPhoto.size - photo.size
        val header = rebuiltPhoto.copyOf() // 简报的 toMutableByteArray() 在 Kotlin stdlib 不存在；ByteArray 本就可变，copyOf() 语义等同
        if (delta != 0) { // 修正 APP1 段长度字段（测试夹具与真实 JPEG 均为 (0xFF,0xE1,lenHi,lenLo)）
            var i = 2
            while (i + 3 < header.size) {
                if (header[i] == 0xFF.toByte() && header[i + 1] == 0xE1.toByte()) {
                    val len = ((header[i + 2].toInt() and 0xFF) shl 8 or (header[i + 3].toInt() and 0xFF)) + delta
                    header[i + 2] = (len shr 8).toByte(); header[i + 3] = (len and 0xFF).toByte()
                    break
                }
                i++
            }
        }
        return header + video
    }
}
