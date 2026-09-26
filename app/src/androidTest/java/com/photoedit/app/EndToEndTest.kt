package com.photoedit.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoedit.app.data.ExifRepository
import com.photoedit.app.data.MediaStoreWriter
import com.photoedit.app.data.SaveOutcome
import com.photoedit.app.domain.CopyNaming
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.PhotoMetadata
import com.photoedit.app.ui.edit.dateTakenMillisOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Task 15 端到端仪器测试（spec §5）：走生产全链路
 * 真生成 JPEG → ExifRepository.read → 改时间/GPS/Model → write →
 * MediaStoreWriter.saveCopy（Pictures/ 插入）→ 从返回 uri 读回字节 → read 断言
 * 新元数据一致且未管理的 Orientation 存活；第二用例覆盖 saveCopy 产物
 * （app 自有条目）的 overwrite 直写路径（Task 10 已证自有文件直写成功）。
 * 零存储权限下与生产一致：所有插入条目 @After 删除清理。
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private val repo = ExifRepository()
    private val writer = MediaStoreWriter(InstrumentationRegistry.getInstrumentation().targetContext)
    private val tag = UUID.randomUUID().toString().take(8)
    private val inserted = mutableListOf<Uri>()

    private val resolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @After fun tearDown() {
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
        inserted.clear()
    }

    // ---- fixtures ----

    /** 可辨识像素：红色底 + 蓝色实心圆 + 绿色横条，避免纯色/空白位图。 */
    private fun recognizableJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFFF0000.toInt())
        val blue = Paint().apply { color = 0xFF0000FF.toInt() }
        canvas.drawCircle(32f, 32f, 18f, blue)
        val green = Paint().apply { color = 0xFF00FF00.toInt() }
        canvas.drawRect(0f, 4f, 64f, 12f, green)
        val out = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 92, out))
        return out.toByteArray()
    }

    /** 厂商预置式打标签（androidx 1.3.7 仅文件路径 saveAttributes）。 */
    private fun setTags(bytes: ByteArray, vararg pairs: Pair<String, String>): ByteArray {
        val tmp = File.createTempFile("e2e-fixture-", ".jpg")
        try {
            tmp.writeBytes(bytes)
            val ei = ExifInterface(tmp)
            pairs.forEach { (t, v) -> ei.setAttribute(t, v) }
            ei.saveAttributes()
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    private fun readSuccess(bytes: ByteArray, stage: String): Pair<ByteArray, PhotoMetadata> {
        val read = repo.read(bytes)
        assertTrue("$stage read 失败: $read", read is ExifRepository.Read.Success)
        read as ExifRepository.Read.Success
        return read.bytes to read.metadata
    }

    private fun bytesOf(uri: Uri): ByteArray =
        resolver.openInputStream(uri)!!.use { it.readBytes() }

    // ---- 1. 全链路：生成→读→改→写→saveCopy→回读断言 ----

    @Test fun generateEditSaveCopyReadBackRoundTrips() {
        // 预置 Orientation=6（模拟相机竖拍），后续全链路必须存活
        val seeded = setTags(recognizableJpeg(), ExifInterface.TAG_ORIENTATION to "6")
        val (bytes, original) = readSuccess(seeded, "初始")
        assertEquals("夹具 Orientation=6 应被 read 映射", 6, original.orientation)

        val newTaken = LocalDateTime.of(2001, 2, 3, 4, 5, 6)
        val newGps = GpsCoordinates(31.2304, 121.4737, null)
        val edited = original.copy(takenAt = newTaken, gps = newGps, model = "Z6III")
        val written = repo.write(bytes, original, edited)
        // 写回 MediaStore 前先验内存态往返（隔离 EXIF 层与 MediaStore 层故障）
        val (_, memMd) = readSuccess(written, "write 产物")
        assertEquals(newTaken, memMd.takenAt)

        // 生产同款命名与 dateTaken
        val outcome = kotlinx.coroutines.runBlocking {
            val newName = CopyNaming.next("PE_e2e_$tag.jpg", writer.existingNames())
            writer.saveCopy(written, newName, dateTakenMillisOf(edited))
        }
        assertTrue("saveCopy 应 Saved，实际: $outcome", outcome is SaveOutcome.Saved)
        val uri = (outcome as SaveOutcome.Saved).uri
        inserted += uri

        val (_, md) = readSuccess(bytesOf(uri), "副本回读")
        assertEquals(newTaken, md.takenAt)
        assertNotNull("GPS 丢失", md.gps)
        assertEquals(31.2304, md.gps!!.latitude, 1e-4)
        assertEquals(121.4737, md.gps.longitude, 1e-4)
        assertEquals("Z6III", md.model)
        assertEquals("Orientation 必须原样存活（spec §3.2）", 6, md.orientation)
    }

    // ---- 2. 自有文件覆盖直写：saveCopy 产物二次 overwrite ----

    @Test fun overwriteOwnSavedCopyAppliesSecondEditInPlace() {
        val seeded = setTags(recognizableJpeg(), ExifInterface.TAG_ORIENTATION to "6")
        val (bytes, original) = readSuccess(seeded, "初始")
        val firstTaken = LocalDateTime.of(2001, 2, 3, 4, 5, 6)
        val firstEdited = original.copy(takenAt = firstTaken, gps = GpsCoordinates(31.2304, 121.4737, null), model = "Z6III")
        val firstBytes = repo.write(bytes, original, firstEdited)
        val saved = kotlinx.coroutines.runBlocking {
            val newName = CopyNaming.next("PE_e2e_ow_$tag.jpg", writer.existingNames())
            writer.saveCopy(firstBytes, newName, dateTakenMillisOf(firstEdited))
        }
        assertTrue("saveCopy 应 Saved: $saved", saved is SaveOutcome.Saved)
        val uri = (saved as SaveOutcome.Saved).uri
        inserted += uri

        // 第二次编辑（改时间+改 Model）→ 自有 uri 直写覆盖（app 为条目属主，无系统授权弹窗）
        val secondTaken = LocalDateTime.of(2019, 7, 8, 9, 10, 11)
        val secondEdited = firstEdited.copy(takenAt = secondTaken, model = "X-T30")
        val secondBytes = repo.write(firstBytes, firstEdited, secondEdited)
        val ow = kotlinx.coroutines.runBlocking { writer.overwrite(uri, secondBytes) }
        assertTrue("自有条目 overwrite 应 Saved，实际: $ow", ow is SaveOutcome.Saved)
        assertEquals(uri, (ow as SaveOutcome.Saved).uri)

        val (_, md) = readSuccess(bytesOf(uri), "覆盖后回读")
        assertEquals(secondTaken, md.takenAt)
        assertEquals("X-T30", md.model)
        // 未触碰字段存活：GPS/时间之外的编辑与 Orientation
        assertEquals(31.2304, md.gps!!.latitude, 1e-4)
        assertEquals(6, md.orientation)
    }
}
