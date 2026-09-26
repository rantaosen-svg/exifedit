package com.photoedit.app.data

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 仪器测试（真机会话）：MediaStoreWriter 的副本插入 / 覆盖 / 写授权 / Live 同名 mp4 配对。
 * 所有插入条目 @After 自行清理；外部所有权场景尽力构造，构造不出则 assume 跳过并在报告注明。
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
        val instr = InstrumentationRegistry.getInstrumentation()
        writer = MediaStoreWriter(instr.targetContext)
        // 跨属主 MediaStore 查询（existingNames/配对/外部条目）需相册读权限；
        // connected 渠道不会自动 grant manifest 运行时权限，这里显式授予（幂等）。
        val pkg = instr.targetContext.packageName
        for (p in listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")) {
            runCatching { instr.uiAutomation.grantRuntimePermission(pkg, p) }
        }
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

    private fun fakeMp4(): ByteArray =
        ByteArray(128).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte()
            it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
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

    private fun insertVideoDirect(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        inserted += uri
        resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

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
            val name = "PE_T10_names_$tag.jpg"
            val saved = writer.saveCopy(jpeg(), name, System.currentTimeMillis())
            assertTrue(saved is SaveOutcome.Saved)
            val uri = (saved as SaveOutcome.Saved).uri
            inserted += uri

            assertTrue("existingNames missing $name", writer.existingNames().contains(name))
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
            val seedUri = (writer.saveCopy(jpeg(1), seedName, System.currentTimeMillis()) as SaveOutcome.Saved).uri
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
            if (uri == null) {
                println("EXT-SCENARIO SKIPPED: cp 条目未入库") // 构造不出真实权限场景：跳过，见报告 concerns
                return@runBlocking
            }
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

    // ---- 4. Live 双文件配对 ----

    @Test fun findPairedVideoUriMatchesSameBaseAndRelativePath() {
        runBlocking {
            val base = "IMG_T10_pair_$tag"
            val imgUri = (writer.saveCopy(jpeg(), "$base.jpg", System.currentTimeMillis()) as SaveOutcome.Saved).uri
            inserted += imgUri
            val videoBytes = fakeMp4()
            val videoUri = insertVideoDirect("$base.mp4", videoBytes)

            val paired = writer.findPairedVideoUri(imgUri)
            assertNotNull("同名 $base.mp4 应配对成功", paired)
            assertEquals("$base.mp4", writer.displayNameOf(paired!!))
            assertArrayEquals(videoBytes, readBack(paired))

            // 副本改名后复制：新条目存在、字节一致
            val copy = writer.copyPairedVideo(paired, "${base}_副本")
            assertTrue("expected Saved, got $copy", copy is SaveOutcome.Saved)
            val copyUri = (copy as SaveOutcome.Saved).uri
            inserted += copyUri
            assertEquals("${base}_副本.mp4", writer.displayNameOf(copyUri))
            assertArrayEquals(videoBytes, readBack(copyUri))

            // 视频改名后与图片不再同名 → null
            resolver.update(videoUri, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_x.mp4")
            }, null, null)
            assertNull(writer.findPairedVideoUri(imgUri))
        }
    }

    @Test fun findPairedVideoUriReturnsNullWithoutVideo() {
        runBlocking {
            val saved = writer.saveCopy(jpeg(), "IMG_T10_alone_$tag.jpg", System.currentTimeMillis())
            val imgUri = (saved as SaveOutcome.Saved).uri
            inserted += imgUri
            assertNull(writer.findPairedVideoUri(imgUri))
        }
    }
}
