package com.photoedit.app.data

import androidx.exifinterface.media.ExifInterface
import com.photoedit.app.domain.ExifTime
import com.photoedit.app.domain.GpsConvert
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.MetadataField
import com.photoedit.app.domain.PhotoMetadata
import com.photoedit.app.domain.changedFields
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * EXIF 字节流读/写核心（spec §3.2、§3.4）。
 * read：SOI 魔数校验 → ExifInterface 标签映射为 PhotoMetadata；解析失败不崩（IoError 或尽力映射）。
 * write：仅对 changedFields 命中标签 setAttribute，saveAttributes 后由 [ExifTagRemover]
 * 物理删除置空标签（含"清除地点"的整棵 GPS IFD）；内嵌型动态照片先拆尾附视频、
 * 写完由 MotionPhotoCodec.rebuild 修正 XMP 偏移拼回。
 *
 * 注：androidx.exifinterface 1.3.7 无 saveAttributes(OutputStream)/deleteAttribute 公开 API
 * （1.4.2 亦无），故落盘走临时文件 + 字节级删除器；Orientation/Make 等未改标签依赖
 * saveAttributes 的保留行为，由仪器测试断言。
 */
class ExifRepository {

    sealed interface Read {
        data class Success(val bytes: ByteArray, val metadata: PhotoMetadata, val isMotionPhoto: Boolean) : Read
        data object UnsupportedFormat : Read
        data class IoError(val message: String) : Read
    }

    fun read(bytes: ByteArray): Read {
        if (!isJpeg(bytes)) return Read.UnsupportedFormat
        val isMotion = safe { MotionPhotoCodec.split(bytes) != null } == true
        return try {
            val ei = ExifInterface(ByteArrayInputStream(bytes))
            Read.Success(bytes, readMetadata(ei), isMotion)
        } catch (e: Exception) {
            Read.IoError(e.message ?: "EXIF read failed")
        }
    }

    /**
     * 按 original→edited 差异重写 EXIF；未列入 changedFields 的字段一律不触碰。
     * 无法处理时（非 JPEG / 写失败 / 删除器解析失败）原样返回输入字节，绝不产出半损坏文件。
     */
    fun write(bytes: ByteArray, original: PhotoMetadata, edited: PhotoMetadata): ByteArray {
        if (!isJpeg(bytes)) return bytes
        val split = safe { MotionPhotoCodec.split(bytes) }
        val photo = split?.photo ?: bytes
        val deletions = Deletions()
        val saved = saveAttributesViaTempFile(photo, original, edited, deletions) ?: return bytes
        val finalPhoto = if (deletions.isEmpty) saved
        else ExifTagRemover.remove(saved, deletions.ifd0, deletions.exif, deletions.gps, deletions.clearGps)
            ?: return bytes
        return split?.let { MotionPhotoCodec.rebuild(finalPhoto, it.video) } ?: finalPhoto
    }

    // region read

    private fun readMetadata(ei: ExifInterface): PhotoMetadata {
        val latLng = FloatArray(2)
        val coordinates: GpsCoordinates? = if (safe { ei.getLatLong(latLng) } == true) {
            val alt = rationalAttribute(ei, ExifInterface.TAG_GPS_ALTITUDE)
            val altRef = rationalAttribute(ei, ExifInterface.TAG_GPS_ALTITUDE_REF)?.roundToInt()
            GpsCoordinates(latLng[0].toDouble(), latLng[1].toDouble(), alt?.let { if (altRef == 1) -it else it })
        } else null
        val orientation = safe { ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }
            ?.takeIf { it in 1..8 } ?: 1
        return PhotoMetadata(
            takenAt = ExifTime.parse(stringAttribute(ei, ExifInterface.TAG_DATETIME_ORIGINAL)),
            gps = coordinates,
            placeName = null,
            make = stringAttribute(ei, ExifInterface.TAG_MAKE),
            model = stringAttribute(ei, ExifInterface.TAG_MODEL),
            fNumber = rationalAttribute(ei, ExifInterface.TAG_F_NUMBER),
            shutterSeconds = rationalAttribute(ei, ExifInterface.TAG_EXPOSURE_TIME),
            iso = rationalAttribute(ei, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.roundToInt(),
            focalLengthMm = rationalAttribute(ei, ExifInterface.TAG_FOCAL_LENGTH),
            orientation = orientation,
        )
    }

    private fun stringAttribute(ei: ExifInterface, tag: String): String? =
        safe { ei.getAttribute(tag)?.trim() }?.takeIf { it.isNotEmpty() }

    private fun rationalAttribute(ei: ExifInterface, tag: String): Double? =
        parseRational(stringAttribute(ei, tag))

    /** "1/125"、"28/10"、"2.8" 等形式 → Double；分母为 0 或非法返回 null。 */
    internal fun parseRational(s: String?): Double? = safe {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return@safe null
        if (t.contains('/')) {
            val parts = t.split('/')
            val n = parts[0].trim().toDouble()
            val d = parts[1].trim().toDouble()
            if (d == 0.0) null else n / d
        } else {
            t.toDouble()
        }
    }

    // endregion

    // region write

    /** 置空需物理删除的 TIFF 标签集合（androidx 无 deleteAttribute）。 */
    private class Deletions {
        val ifd0 = linkedSetOf<Int>()
        val exif = linkedSetOf<Int>()
        val gps = linkedSetOf<Int>()
        var clearGps = false
        val isEmpty: Boolean get() = ifd0.isEmpty() && exif.isEmpty() && gps.isEmpty() && !clearGps
    }

    private fun saveAttributesViaTempFile(
        photo: ByteArray,
        original: PhotoMetadata,
        edited: PhotoMetadata,
        deletions: Deletions,
    ): ByteArray? = try {
        val tmp = File.createTempFile("photoedit-exif-", ".jpg")
        try {
            tmp.outputStream().use { it.write(photo) }
            val ei = ExifInterface(tmp)
            applyChanges(ei, original, edited, deletions)
            ei.saveAttributes()
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    } catch (e: Exception) {
        null
    }

    private fun applyChanges(
        ei: ExifInterface,
        original: PhotoMetadata,
        edited: PhotoMetadata,
        deletions: Deletions,
    ) {
        val changed = changedFields(original, edited)
        if (MetadataField.TAKEN_AT in changed) {
            val dt = edited.takenAt
            if (dt != null) {
                ei.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ExifTime.format(dt))
                ei.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifTime.offsetOf())
            } else {
                deletions.exif += ExifTagRemover.TIFF_TAG_DATETIME_ORIGINAL
                deletions.exif += ExifTagRemover.TIFF_TAG_OFFSET_TIME_ORIGINAL
            }
        }
        if (MetadataField.GPS in changed) {
            val gps = edited.gps
            if (gps != null) setGps(ei, gps, deletions) else deletions.clearGps = true
        }
        if (MetadataField.MODEL in changed) {
            val v = edited.model
            if (v != null) ei.setAttribute(ExifInterface.TAG_MODEL, v) else deletions.ifd0 += ExifTagRemover.TIFF_TAG_MODEL
        }
        // FNumber/ExposureTime 属 androidx sTagSetForCompatibility 集合：setAttribute 对它们
        // 整串走 Double.parseDouble（内部再经 Rational(double) 转有理数），传 "N/D" 分数字符串
        // 会被判 "Invalid value" 静默丢弃（1.3.7 字节码实证）；必须写十进制字符串。
        if (MetadataField.F_NUMBER in changed) {
            val v = edited.fNumber
            if (v != null) ei.setAttribute(ExifInterface.TAG_F_NUMBER, decimalString(v))
            else deletions.exif += ExifTagRemover.TIFF_TAG_FNUMBER
        }
        if (MetadataField.SHUTTER in changed) {
            val v = edited.shutterSeconds
            if (v != null) ei.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, decimalString(v))
            else deletions.exif += ExifTagRemover.TIFF_TAG_EXPOSURE_TIME
        }
        if (MetadataField.ISO in changed) {
            val v = edited.iso
            if (v != null) ei.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, v.toString())
            else deletions.exif += ExifTagRemover.TIFF_TAG_ISO
        }
        if (MetadataField.FOCAL in changed) {
            val v = edited.focalLengthMm
            if (v != null) ei.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, rationalString(v))
            else deletions.exif += ExifTagRemover.TIFF_TAG_FOCAL_LENGTH
        }
    }

    private fun setGps(ei: ExifInterface, gps: GpsCoordinates, deletions: Deletions) {
        GpsConvert.fromDecimalLatitude(gps.latitude)?.let {
            ei.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, it.ref.toString())
            ei.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dmsToExifString(it))
        }
        GpsConvert.fromDecimalLongitude(gps.longitude)?.let {
            ei.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, it.ref.toString())
            ei.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dmsToExifString(it))
        }
        val alt = gps.altitudeMeters
        if (alt != null) {
            ei.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (alt < 0) "1" else "0")
            ei.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, rationalString(abs(alt)))
        } else {
            // 原有海拔标签需物理移除
            deletions.gps += ExifTagRemover.TIFF_TAG_GPS_ALTITUDE
            deletions.gps += ExifTagRemover.TIFF_TAG_GPS_ALTITUDE_REF
        }
        ei.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
    }

    /** DMS → EXIF 度分秒字符串；各分母恒为 1/10000（非零）。 */
    private fun dmsToExifString(dms: GpsConvert.Dms): String {
        val secNumerator = (dms.seconds * 10_000).roundToLong()
        return "${dms.degrees}/1,${dms.minutes}/1,$secNumerator/10000"
    }

    /** double → 十进制字符串（FNumber/ExposureTime 兼容集标签专用；Kotlin toString 不随 locale 变化）。 */
    internal fun decimalString(v: Double): String = v.toString()

    /** double → 有理数字符串（仅用于非兼容集标签，如 FocalLength/GPS 海拔）；小于 1 优先 "1/n" 表示，否则放大为 /10000。 */
    internal fun rationalString(v: Double): String {
        if (v <= 0.0) return "0/1"
        if (v < 1.0) {
            val inv = (1.0 / v).roundToLong()
            if (inv > 0 && abs(1.0 / inv - v) < 1e-6) return "1/$inv"
        }
        return "${(v * 10_000).roundToLong()}/10000"
    }

    // endregion

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    private inline fun <T : Any> safe(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}
