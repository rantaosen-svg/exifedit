package com.photoedit.app.data

import android.app.PendingIntent
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 仪器测试（真机会话）：MediaStoreWriter 的副本插入 / 覆盖 / 写授权。
 * 零存储权限（spec §1/§3.4 修订）：不声明也不授予 READ_MEDIA_*，
 * MediaStore 查询仅见本 app 自有条目；不测外部条目枚举与 Live 双文件配对（功能已删）。
 * 所有插入条目 @After 自行清理；外部所有权场景尽力构造，构造不出则以 Assume 显式
 * 跳过（运行器报告 skipped，而非假绿通过），NeedsPermission 路径转 Task 13 真机验收。
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreWriterTest {

    private lateinit var writer: MediaStoreWriter
    private val tag = UUID.randomUUID().toString().take(8)
    private val inserted = mutableListOf<Uri>()
    private var extPath: String? = null

    private val resolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Before fun setUp() {
        // 零权限运行：刻意不 grantRuntimePermission——验证默认（无 READ_MEDIA_*）行为。
        writer = MediaStoreWriter(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() {
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
        inserted.clear()
        extPath?.let { path -> runCatching { shell("rm -f $path") } }
        extPath = null
    }

    // ---- fixtures / helpers ----

    private fun jpeg(seed: Int = 1): ByteArray {
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(if (seed == 1) 0xFFFF0000.toInt() else 0xFF00FF00.toInt())
        val out = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 90, out))
        return out.toByteArray()
    }

    private fun readBack(uri: Uri): ByteArray =
        resolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun queryLong(uri: Uri, column: String): Long? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    private fun queryString(uri: Uri, column: String): String? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }

    private fun uriForImageName(name: String): Uri? =
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null,
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        // AutoCloseInputStream.close() 会一并关闭底层 ParcelFileDescriptor
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    // ---- 1. saveCopy ----

    @Test fun saveCopyWritesReadableEntryAndClearsPending() {
        runBlocking {
            val name = "PE_T10_copy_$tag.jpg"
            // datetaken 列由发布时的媒体扫描从 EXIF DateTimeOriginal 推导（update 不可回写，实测 rows=0），
            // 夹具走生产路径：ExifRepository 写入 DateTimeOriginal + OffsetTime，saveCopy 同步携带同一毫秒值。
            val taken = java.time.LocalDateTime.now().minusDays(1).withNano(0)
            val dateTaken = taken.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val bytes = com.photoedit.app.data.ExifRepository()
                .write(jpeg(), com.photoedit.app.domain.PhotoMetadata(), com.photoedit.app.domain.PhotoMetadata(takenAt = taken))
            val outcome = writer.saveCopy(bytes, name, dateTaken)
            assertTrue("expected Saved, got $outcome", outcome is SaveOutcome.Saved)
            val uri = (outcome as SaveOutcome.Saved).uri
            inserted += uri

            assertArrayEquals(bytes, readBack(uri))
            assertEquals(name, queryString(uri, MediaStore.Images.Media.DISPLAY_NAME))
            assertNotNull(uriForImageName(name)) // MediaStore 可查到该 DISPLAY_NAME

            // 发布扫描可能异步落库：轮询 datetaken 直至匹配（最长 ~5s）
            var dt: Long? = null
            repeat(25) {
                dt = queryLong(uri, MediaStore.Images.Media.DATE_TAKEN)
                if (dt == dateTaken) return@repeat
                if (dt != null) return@repeat
                kotlinx.coroutines.delay(200)
            }
            assertEquals(dateTaken, dt)
            assertNotNull(uriForImageName(name)) // MediaStore 可查到该 DISPLAY_NAME

            // 重复保存后不得残留 IS_PENDING=1 条目
            val second = writer.saveCopy(jpeg(2), "PE_T10_copy2_$tag.jpg", dateTaken)
            assertTrue("expected Saved, got $second", second is SaveOutcome.Saved)
            inserted += (second as SaveOutcome.Saved).uri

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.IS_PENDING} = 1", null, null,
            )?.use {
                while (it.moveToNext()) {
                    val n = it.getString(0) ?: ""
                    assertTrue("pending leftover: $n", !n.startsWith("PE_T10_copy_$tag") && !n.startsWith("PE_T10_copy2_$tag"))
                }
            }
        }
    }

    // ---- 2. existingNames / displayNameOf ----

    @Test fun existingNamesContainsSavedNameAndDisplayNameOfRoundTrips() {
        runBlocking {
            // 零权限语义：existingNames 只能枚举本 app 自有条目——
            // 断言"至少含刚 saveCopy 的名字"（自有可见），不断言能看到外部文件；
            // 外部重名的最终安全网是 MediaStore insert 自动追加 " (1)"。
            val name = "PE_T10_names_$tag.jpg"
            val saved = writer.saveCopy(jpeg(), name, System.currentTimeMillis())
            assertTrue(saved is SaveOutcome.Saved)
            val uri = (saved as SaveOutcome.Saved).uri
            inserted += uri

            assertTrue("existingNames missing own entry $name", writer.existingNames().contains(name))
            assertEquals(name, writer.displayNameOf(uri))
            assertEquals(name, uriForImageName(name)?.let { writer.displayNameOf(it) })
        }
    }

    // ---- 3. overwrite ----

    @Test fun overwriteOnOwnFileSucceedsAndUpdatesContent() {
        runBlocking {
            val name = "PE_T10_over_$tag.jpg"
            val saved = writer.saveCopy(jpeg(1), name, System.currentTimeMillis()) as SaveOutcome.Saved
            val uri = saved.uri
            inserted += uri

            val newBytes = jpeg(2)
            val outcome = writer.overwrite(uri, newBytes)
            assertTrue("expected Saved, got $outcome", outcome is SaveOutcome.Saved)
            assertEquals(uri, (outcome as SaveOutcome.Saved).uri)
            assertArrayEquals(newBytes, readBack(uri)) // 内容确实更新
            assertEquals(name, writer.displayNameOf(uri)) // 覆盖不改名，Live 配对天然保持

            // 自有 uri 上 createWriteIntentFor：非空（可发起系统授权）或合理 null 均可接受
            val intent: PendingIntent? = writer.createWriteIntentFor(uri)
            if (intent != null) assertNotNull(intent.intentSender)
        }
    }

    @Test fun overwriteOfForeignOwnedEntryReturnsNeedsPermission() {
        runBlocking {
            // 构造"非本 app 拥有"条目：先 saveCopy 一个 app 属主种子 JPEG，再用
            // executeShellCommand 的 cp（shell uid，不经 shell 解释器所以不能用重定向）复制成
            // 新文件——FUSE 自动入库后该条目 owner=shell，本 app 无写权限。
            val seedName = "PE_T10_seed_$tag.jpg"
            val seed = writer.saveCopy(jpeg(1), seedName, System.currentTimeMillis())
            assertTrue("seed saveCopy 应成功，实际: $seed", seed is SaveOutcome.Saved)
            val seedUri = (seed as SaveOutcome.Saved).uri
            inserted += seedUri
            val seedPath = "/storage/emulated/0/Pictures/$seedName"
            val name = "PE_T10_ext_$tag.jpg"
            val path = "/storage/emulated/0/Pictures/$name"
            shell("cp $seedPath $path")
            extPath = path // @After 用 shell rm 清理 cp 出的文件（本 app 无权删）
            // shell 创建的文件不会被 FUSE 自动入库，需显式扫描；由 shell 触发以保持"外部属主"语义
            // （若用 app 侧 MediaScannerConnection.scanFile，行属主可能被判给本 app，场景即失效）。
            shell("content call --uri content://media --method scan_file --arg $path")
            // FUSE 自动入库略有延迟：轮询 ~10s
            var uri: Uri? = null
            repeat(50) {
                uri = uriForImageName(name)
                if (uri != null) return@repeat
                kotlinx.coroutines.delay(200)
            }
            // 零权限下 shell 属主行对本 app 查询不可见，uri 大概率取不到：
            // 显式 Assume-skip（运行器报告 skipped），不再 println 早退假绿。
            Assume.assumeTrue("外部属主场景在零权限下不可构造，NeedsPermission 路径转 Task13 真机验收", uri != null)
            println("EXT-SCENARIO ACTIVE uri=$uri")
            inserted += uri!! // 若系统后续允许删除/row 属主变化时兜底清理（shell rm 已覆盖文件）

            val outcome = writer.overwrite(uri!!, jpeg(2))
            assertTrue("shell 外部条目应返回 NeedsPermission，实际: $outcome", outcome is SaveOutcome.NeedsPermission)
            // 授权路径：createWriteIntentFor 应能产出可 launch 的 PendingIntent（不实际 launch，避免系统弹窗挂起）
            val intent = writer.createWriteIntentFor(uri!!)
            assertNotNull("NeedsPermission 但 createWriteIntentFor 为 null", intent)
            assertNotNull(intent!!.intentSender)
        }
    }
}
