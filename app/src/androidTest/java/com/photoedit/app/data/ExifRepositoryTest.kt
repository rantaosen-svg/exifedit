package com.photoedit.app.data

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.PhotoMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime

/**
 * 仪器测试：ExifInterface 完整"读→改→写→重读"往返（spec §5.2）。
 * 夹具为运行时 Bitmap.compress 生成的 JPEG，避免仓库二进制文件。
 */
class ExifRepositoryTest {

    private val repo = ExifRepository()

    private fun jpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 92, out))
        return out.toByteArray()
    }

    /** 直接经 ExifInterface（临时文件路径，1.3.7 仅支持文件 saveAttributes）打标签，模拟厂商预置。 */
    private fun setTags(bytes: ByteArray, vararg pairs: Pair<String, String>): ByteArray {
        val tmp = File.createTempFile("fixture-", ".jpg")
        try {
            tmp.writeBytes(bytes)
            val ei = ExifInterface(tmp)
            pairs.forEach { (tag, value) -> ei.setAttribute(tag, value) }
            ei.saveAttributes()
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    private fun exifOf(bytes: ByteArray): ExifInterface =
        ExifInterface(ByteArrayInputStream(bytes))

    private fun metadataOf(bytes: ByteArray): PhotoMetadata {
        val read = repo.read(bytes)
        assertTrue("read failed: $read", read is ExifRepository.Read.Success)
        return (read as ExifRepository.Read.Success).metadata
    }

    private fun ftypVideo(len: Int): ByteArray =
        ByteArray(len).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte()
            it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }

    private fun xmpApp1(payload: String): ByteArray {
        val body = ("http://ns.adobe.com/xap/1.0/\u0000" + payload).toByteArray(Charsets.ISO_8859_1)
        val len = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + body
    }

    /** 内嵌型动态照片夹具：真实 JPEG + EOI 前插入 XMP APP1 + 尾部假 ftyp 视频。 */
    private fun motionJpeg(videoLen: Int = 600): Pair<ByteArray, ByteArray> {
        val video = ftypVideo(videoLen)
        val jpeg = jpegBytes()
        val eoi = jpeg.size - 2
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), jpeg.copyOfRange(eoi, jpeg.size))
        val photo = jpeg.copyOfRange(0, eoi) +
            xmpApp1("<x:xmpmeta><GPhoto:MicroVideoOffset=\"${video.size}\"/></x:xmpmeta>") +
            jpeg.copyOfRange(eoi, jpeg.size)
        return (photo + video) to video
    }

    private val taken = LocalDateTime.of(2024, 3, 14, 18, 30, 45)
    private val wuhan = GpsCoordinates(30.540281, 114.310824, 23.7)
    private val fullMd = PhotoMetadata(
        takenAt = taken,
        gps = wuhan,
        make = null,
        model = "Z6III",
        fNumber = 2.8,
        shutterSeconds = 0.008, // 1/125
        iso = 200,
        focalLengthMm = 50.0,
    )

    // ---- read 基础 ----

    @Test fun readRejectsNonJpeg() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2)
        assertEquals(ExifRepository.Read.UnsupportedFormat, repo.read(png))
        assertEquals(ExifRepository.Read.UnsupportedFormat, repo.read(ByteArray(0)))
        assertEquals(ExifRepository.Read.UnsupportedFormat, repo.read(byteArrayOf(0xFF.toByte())))
    }

    @Test fun readPlainJpegMapsDefaults() {
        val bytes = jpegBytes()
        val read = repo.read(bytes)
        assertTrue(read is ExifRepository.Read.Success)
        read as ExifRepository.Read.Success
        assertArrayEquals(bytes, read.bytes)
        assertFalse(read.isMotionPhoto)
        val md = read.metadata
        assertEquals(1, md.orientation)
        assertNull(md.takenAt)
        assertNull(md.gps)
        assertNull(md.model)
        assertNull(md.iso)
    }

    @Test fun readNeverCrashesOnGarbageJpeg() {
        val garbage = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { (it * 7).toByte() }
        val read = repo.read(garbage)
        assertTrue(
            "expected Success or IoError, got $read",
            read is ExifRepository.Read.Success || read is ExifRepository.Read.IoError
        )
    }

    // ---- 写→读往返 ----

    @Test fun writeThenReadRoundTripsAllFields() {
        val seeded = setTags(jpegBytes(), ExifInterface.TAG_MAKE to "Nikon")
        val out = repo.write(seeded, PhotoMetadata(make = "Nikon"), fullMd)
        val md = metadataOf(out)
        assertEquals(taken, md.takenAt)
        assertEquals("Nikon", md.make)
        assertEquals("Z6III", md.model)
        assertEquals(2.8, md.fNumber!!, 1e-3)
        assertEquals(0.008, md.shutterSeconds!!, 1e-4)
        assertEquals(200, md.iso)
        assertEquals(50.0, md.focalLengthMm!!, 1e-3)
        assertNotNull(md.gps)
        assertEquals(30.540281, md.gps!!.latitude, 1e-4)
        assertEquals(114.310824, md.gps.longitude, 1e-4)
        assertEquals(23.7, md.gps.altitudeMeters!!, 1e-2)
    }

    @Test fun dateTimeOriginalUpdatedWithOffsetTimeOriginal() {
        val seeded = repo.write(jpegBytes(), PhotoMetadata(), fullMd)
        val newTaken = LocalDateTime.of(2021, 12, 25, 9, 5, 1)
        val out = repo.write(seeded, fullMd, fullMd.copy(takenAt = newTaken))
        val ei = exifOf(out)
        assertEquals("2021:12:25 09:05:01", ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertTrue(
            "OffsetTimeOriginal missing/invalid: ${ei.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)}",
            Regex("""[+-]\d{2}:\d{2}""").matches(ei.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: "")
        )
        assertEquals(newTaken, metadataOf(out).takenAt)
    }

    @Test fun orientationAndUntouchedFieldsSurviveRewrite() {
        // 相机预置 Orientation=6、Make；只改时间后这些必须原样
        val seeded = setTags(
            jpegBytes(),
            ExifInterface.TAG_ORIENTATION to "6",
            ExifInterface.TAG_MAKE to "Nikon",
        )
        val withAll = repo.write(seeded, metadataOf(seeded), fullMd)
        assertEquals(6, exifOf(withAll).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
        val edited = fullMd.copy(takenAt = LocalDateTime.of(2023, 1, 2, 3, 4, 5))
        val out = repo.write(withAll, metadataOf(withAll), edited)
        assertEquals(6, metadataOf(out).orientation)
        assertEquals(6, exifOf(out).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
        assertEquals("Nikon", metadataOf(out).make)
        assertEquals("Z6III", metadataOf(out).model)
        assertEquals(200, metadataOf(out).iso)
        assertEquals(2.8, metadataOf(out).fNumber!!, 1e-3)
        assertEquals(0.008, metadataOf(out).shutterSeconds!!, 1e-4)
        assertEquals(50.0, metadataOf(out).focalLengthMm!!, 1e-3)
        assertEquals(edited.takenAt, metadataOf(out).takenAt)
    }

    @Test fun noChangesKeepsMetadataIdentical() {
        val seeded = repo.write(setTags(jpegBytes(), ExifInterface.TAG_MAKE to "Nikon"), PhotoMetadata(), fullMd)
        val before = metadataOf(seeded)
        val out = repo.write(seeded, before, before)
        assertEquals(before, metadataOf(out))
    }

    // ---- GPS ----

    @Test fun gpsWrittenIsReadableViaGetLatLong() {
        val out = repo.write(jpegBytes(), PhotoMetadata(), fullMd)
        val ei = exifOf(out)
        val latLng = FloatArray(2)
        assertTrue(ei.getLatLong(latLng))
        assertEquals(30.540281, latLng[0].toDouble(), 1e-4)
        assertEquals(114.310824, latLng[1].toDouble(), 1e-4)
        assertEquals("N", ei.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)?.trim())
        assertEquals("E", ei.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)?.trim())
        assertNotNull(ei.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        assertTrue(ei.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD)?.contains("GPS") == true)
    }

    @Test fun gpsSouthernHemisphere() {
        val sydney = GpsCoordinates(-33.865143, 151.209900, null)
        val out = repo.write(jpegBytes(), PhotoMetadata(), fullMd.copy(gps = sydney))
        val md = metadataOf(out)
        assertEquals("S", exifOf(out).getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)?.trim())
        assertEquals(-33.865143, md.gps!!.latitude, 1e-4)
        assertEquals(151.2099, md.gps.longitude, 1e-4)
        assertNull(md.gps.altitudeMeters)
    }

    @Test fun gpsClearedRemovesAllGpsTags() {
        val seeded = repo.write(jpegBytes(), PhotoMetadata(), fullMd)
        assertNotNull(exifOf(seeded).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val out = repo.write(seeded, fullMd, fullMd.copy(gps = null))
        val ei = exifOf(out)
        assertFalse(ei.getLatLong(FloatArray(2)))
        listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_VERSION_ID,
        ).forEach { assertNull("tag $it not removed", ei.getAttribute(it)) }
        assertNull(metadataOf(out).gps)
    }

    // ---- 动态照片（spec §3.4） ----

    @Test fun readDetectsMotionPhoto() {
        val (bytes, _) = motionJpeg()
        val read = repo.read(bytes)
        assertTrue(read is ExifRepository.Read.Success)
        assertTrue((read as ExifRepository.Read.Success).isMotionPhoto)
    }

    @Test fun writePreservesEmbeddedVideoAndXmpOffsets() {
        val (bytes, video) = motionJpeg()
        val original = metadataOf(bytes)
        val edited = original.copy(iso = 400)
        val out = repo.write(bytes, original, edited)
        val split = MotionPhotoCodec.split(out)
        assertNotNull("motion photo lost after write", split)
        assertArrayEquals(video, split!!.video)
        val md = metadataOf(out)
        assertEquals(400, md.iso)
        assertEquals(original.takenAt, md.takenAt)
    }
}
