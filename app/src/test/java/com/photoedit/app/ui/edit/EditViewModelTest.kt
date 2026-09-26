package com.photoedit.app.ui.edit

import android.app.PendingIntent
import android.content.ContextWrapper
import android.net.FakeUri
import android.net.Uri
import com.photoedit.app.data.ExifRepository
import com.photoedit.app.data.GeocoderService
import com.photoedit.app.data.GeoPlace
import com.photoedit.app.data.MediaStoreWriter
import com.photoedit.app.data.SaveOutcome
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.PhotoMetadata
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * EditViewModel JVM 单测：全部依赖（repo/writer/geocoder/readBytes）用 fake，
 * 不触 Android 运行时（无 Robolectric）。Uri 用最小可实例化子类 [FakeUri]。
 */
class EditViewModelTest {

    // ---- fakes ----

    /**
     * read 分两相：写入过 → 返回 writtenMetadata（模拟"写成功且重读命中"）；
     * 未写入 → 返回 loadResult（load 用）。simulateSilentFailure 复刻真 repo
     * "写失败原样返回输入字节"：write 不更新 writtenMetadata，重读仍是旧值。
     */
    private open class FakeRepo(
        var loadResult: ExifRepository.Read = ExifRepository.Read.UnsupportedFormat,
    ) : ExifRepository() {
        var writtenMetadata: PhotoMetadata? = null
        var simulateSilentFailure = false
        var writeCalls = 0
        var lastWriteArgs: Triple<ByteArray, PhotoMetadata, PhotoMetadata>? = null

        override fun read(bytes: ByteArray): ExifRepository.Read =
            writtenMetadata?.let { ExifRepository.Read.Success(bytes, it, isMotionPhoto = false) }
                ?: loadResult

        override fun write(bytes: ByteArray, original: PhotoMetadata, edited: PhotoMetadata): ByteArray {
            writeCalls++
            lastWriteArgs = Triple(bytes, original, edited)
            if (!simulateSilentFailure) writtenMetadata = edited
            return bytes
        }
    }

    private class FakeWriter : MediaStoreWriter(ContextWrapper(null)) {
        var displayName: String? = "photo.jpg"
        var existing: Set<String> = emptySet()
        var saveCopyOutcome: (String) -> SaveOutcome = { SaveOutcome.Saved(savedUri) }
        var overwriteOutcome: SaveOutcome = SaveOutcome.Saved(savedUri)
        var lastCopyName: String? = null
        var lastCopyBytes: ByteArray? = null
        var lastCopyDateTaken: Long = -1
        var lastOverwriteUri: Uri? = null
        var overwriteCount = 0

        override suspend fun displayNameOf(uri: Uri): String? = displayName
        override suspend fun existingNames(): Set<String> = existing

        override suspend fun saveCopy(bytes: ByteArray, newName: String, dateTakenMillis: Long): SaveOutcome {
            lastCopyName = newName
            lastCopyBytes = bytes
            lastCopyDateTaken = dateTakenMillis
            return saveCopyOutcome(newName)
        }

        override suspend fun overwrite(uri: Uri, bytes: ByteArray): SaveOutcome {
            overwriteCount++
            lastOverwriteUri = uri
            return overwriteOutcome
        }

        override suspend fun createWriteIntentFor(uri: Uri): PendingIntent? = null

        companion object {
            val savedUri = FakeUri("content://media/external/images/media/42")
        }
    }

    private class FakeGeocoder(var place: GeoPlace? = null) : GeocoderService {
        var reverseCalls = 0
        override suspend fun search(query: String): List<GeoPlace> = emptyList()
        override suspend fun reverse(lat: Double, lon: Double): GeoPlace? {
            reverseCalls++
            return place
        }
    }

    // ---- helpers ----

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
    private val uri = FakeUri("content://media/external/images/media/7")

    private val baseMeta = PhotoMetadata(
        takenAt = LocalDateTime.of(2024, 5, 1, 12, 0, 0),
        model = "Pixel 8",
        iso = 100,
        orientation = 1,
    )

    private fun readyRepo(meta: PhotoMetadata = baseMeta) =
        FakeRepo(ExifRepository.Read.Success(jpegBytes, meta, isMotionPhoto = false))

    private fun viewModel(
        repo: ExifRepository = readyRepo(),
        writer: MediaStoreWriter = FakeWriter(),
        geocoder: GeocoderService = FakeGeocoder(),
        readBytes: (Uri) -> ByteArray = { jpegBytes },
    ) = EditViewModel(repo, writer, geocoder, readBytes)

    /** 收集 events（SharedFlow 无 replay，需先挂订阅）。 */
    private fun kotlinx.coroutines.test.TestScope.collectEvents(vm: EditViewModel): List<String> {
        val received = Collections.synchronizedList(mutableListOf<String>())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(received) }
        return received
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    // ---- load ----

    @Test
    fun `load 非JPEG字节 read 返回 Unsupported 则状态为 Unsupported`() = runTest {
        val vm = viewModel(repo = FakeRepo(ExifRepository.Read.UnsupportedFormat))
        vm.load(uri)
        assertEquals(EditState.Unsupported, vm.state.value)
    }

    @Test
    fun `load IoError 转为 Error 并携带原因`() = runTest {
        val vm = viewModel(repo = FakeRepo(ExifRepository.Read.IoError("boom")))
        vm.load(uri)
        assertEquals(EditState.Error("boom"), vm.state.value)
    }

    @Test
    fun `load readBytes 抛异常转为 Error`() = runTest {
        val vm = viewModel(readBytes = { throw IllegalStateException("流打不开") })
        vm.load(uri)
        assertEquals(EditState.Error("流打不开"), vm.state.value)
    }

    @Test
    fun `load 成功时 Ready 且初始 edited 与 original 相同`() = runTest {
        val vm = viewModel()
        vm.load(uri)
        val ready = vm.state.value as EditState.Ready
        assertSame(jpegBytes, ready.bytes)
        assertEquals(baseMeta, ready.original)
        assertEquals(baseMeta, ready.edited)
        assertEquals(false, ready.isMotionPhoto)
        assertEquals(SaveState.Idle, vm.saveState.value)
    }

    // ---- 编辑不改 original ----

    @Test
    fun `字段编辑只改 edited 且 original 保持不变`() = runTest {
        val vm = viewModel()
        vm.load(uri)
        val newTime = LocalDateTime.of(2025, 1, 2, 3, 4, 5)
        vm.setTakenAt(newTime)
        vm.setModel("iPhone 16")
        vm.setFNumber(2.8)
        vm.setShutter(0.008)
        vm.setIso(400)
        vm.setFocal(24.0)
        vm.setPlaceName("外滩")
        val ready = vm.state.value as EditState.Ready
        assertEquals(baseMeta, ready.original) // original 一字未动
        assertEquals(newTime, ready.edited.takenAt)
        assertEquals("iPhone 16", ready.edited.model)
        assertEquals(2.8, ready.edited.fNumber)
        assertEquals(0.008, ready.edited.shutterSeconds)
        assertEquals(400, ready.edited.iso)
        assertEquals(24.0, ready.edited.focalLengthMm)
        assertEquals("外滩", ready.edited.placeName)
    }

    @Test
    fun `未 Ready 时编辑调用被忽略`() = runTest {
        val vm = viewModel()
        vm.setModel("x")
        vm.setIso(100)
        vm.clearGps()
        assertEquals(null, vm.state.value)
    }

    // ---- setGps 校验 ----

    @Test
    fun `setGps 非法坐标发错误事件且状态不变`() = runTest {
        val vm = viewModel()
        vm.load(uri)
        val events = collectEvents(vm)
        val before = vm.state.value
        vm.setGps(91.0, 0.0)
        vm.setGps(0.0, -181.0)
        assertSame(before, vm.state.value)
        assertEquals(2, events.size)
        assertContains(events[0], "超出范围")
    }

    @Test
    fun `setGps 合法时更新 edited 且反查地名回填 placeName`() = runTest {
        val geocoder = FakeGeocoder(GeoPlace("外滩, 上海市", 31.2, 121.4))
        val vm = viewModel(geocoder = geocoder)
        vm.load(uri)
        vm.setGps(31.2, 121.4)
        val ready = vm.state.value as EditState.Ready
        assertEquals(GpsCoordinates(31.2, 121.4, null), ready.edited.gps)
        assertEquals("外滩, 上海市", ready.edited.placeName)
        assertEquals(null, ready.original.gps) // original 不动
        assertEquals(1, geocoder.reverseCalls)
    }

    @Test
    fun `clearGps 清空坐标与地名`() = runTest {
        val vm = viewModel(repo = readyRepo(baseMeta.copy(gps = GpsCoordinates(1.0, 2.0), placeName = "somewhere")))
        vm.load(uri)
        vm.clearGps()
        val ready = vm.state.value as EditState.Ready
        assertEquals(null, ready.edited.gps)
        assertEquals(null, ready.edited.placeName)
    }

    // ---- saveAsCopy ----

    @Test
    fun `saveAsCopy 成功走 CopyNaming 递增并用 existingNames 去重`() = runTest {
        val repo = readyRepo()
        val writer = FakeWriter().apply {
            displayName = "photo.jpg"
            existing = setOf("photo.jpg", "photo_副本.jpg")
        }
        val vm = viewModel(repo = repo, writer = writer)
        vm.load(uri)
        vm.setIso(200)
        vm.saveAsCopy()

        assertEquals("photo_副本2.jpg", writer.lastCopyName)
        assertEquals(SaveState.DoneSaved(FakeWriter.savedUri), vm.saveState.value)
        assertTrue(writer.lastCopyBytes === jpegBytes) // 传给 saveCopy 的正是 write 的产物
        val expectedMillis = baseMeta.takenAt!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedMillis, writer.lastCopyDateTaken) // datetaken 列同步写编辑后时间
    }

    @Test
    fun `saveAsCopy 成功后 original 基线前移到 edited 供连续编辑`() = runTest {
        val repo = readyRepo()
        val vm = viewModel(repo = repo, writer = FakeWriter())
        vm.load(uri)
        vm.setModel("A")
        vm.saveAsCopy()
        val afterFirst = vm.state.value as EditState.Ready
        assertEquals("A", afterFirst.original.model)
        assertEquals("A", afterFirst.edited.model)

        vm.setModel("B")
        vm.saveAsCopy()
        val (_, originalArg, editedArg) = repo.lastWriteArgs!!
        assertEquals("A", originalArg.model) // 第二次写以"已保存"为基线
        assertEquals("B", editedArg.model)
        assertEquals(SaveState.DoneSaved(FakeWriter.savedUri), vm.saveState.value)
    }

    @Test
    fun `writer 返回 Failed 则 SaveState Failed 且状态页保留`() = runTest {
        val writer = FakeWriter().apply { saveCopyOutcome = { SaveOutcome.Failed("MediaStore 插入失败") } }
        val vm = viewModel(writer = writer)
        vm.load(uri)
        vm.setIso(300)
        vm.saveAsCopy()
        assertEquals(SaveState.Failed("MediaStore 插入失败"), vm.saveState.value)
        val ready = vm.state.value as EditState.Ready // 编辑内容完整保留可重试
        assertEquals(300, ready.edited.iso)
        assertEquals(baseMeta, ready.original)
    }

    @Test
    fun `write 静默失败被重读校验兜底为 Failed`() = runTest {
        val repo = readyRepo().apply { simulateSilentFailure = true }
        val vm = viewModel(repo = repo, writer = FakeWriter())
        vm.load(uri)
        vm.setTakenAt(LocalDateTime.of(2030, 1, 1, 0, 0, 0))
        vm.saveAsCopy()
        val failed = vm.saveState.value as SaveState.Failed
        assertContains(failed.reason, "拍摄时间未写入")
        // original 不前移（校验失败 = 未保存成功）
        assertEquals(baseMeta, (vm.state.value as EditState.Ready).original)
    }

    // ---- overwriteOriginal ----

    @Test
    fun `overwrite NeedsPermission 映射为 NeedsOverwritePermission`() = runTest {
        val writer = FakeWriter().apply { overwriteOutcome = SaveOutcome.NeedsPermission }
        val vm = viewModel(writer = writer)
        vm.load(uri)
        vm.setIso(500)
        vm.overwriteOriginal()
        assertEquals(SaveState.NeedsOverwritePermission, vm.saveState.value)
        assertEquals(uri, writer.lastOverwriteUri)
    }

    @Test
    fun `overwrite 成功 DoneSaved 且授权重试可再次发起`() = runTest {
        val writer = FakeWriter()
        val vm = viewModel(writer = writer)
        vm.load(uri)
        vm.setModel("C")

        writer.overwriteOutcome = SaveOutcome.NeedsPermission
        vm.overwriteOriginal()
        assertEquals(SaveState.NeedsOverwritePermission, vm.saveState.value)

        // Task 13 拿到 createWriteIntentFor 授权后重试一次
        writer.overwriteOutcome = SaveOutcome.Saved(FakeWriter.savedUri)
        vm.overwriteOriginal()
        assertEquals(SaveState.DoneSaved(FakeWriter.savedUri), vm.saveState.value)
        assertEquals("C", (vm.state.value as EditState.Ready).original.model)
        assertEquals(2, writer.overwriteCount)
    }

    @Test
    fun `未 Ready 时保存调用被忽略`() = runTest {
        val writer = FakeWriter()
        val vm = viewModel(writer = writer)
        vm.saveAsCopy()
        vm.overwriteOriginal()
        assertEquals(SaveState.Idle, vm.saveState.value)
        assertEquals(0, writer.overwriteCount)
        assertEquals(null, writer.lastCopyName)
    }

    // ---- reset（Task 11 评审 A：路由由 state 决定）----

    @Test
    fun `reset 回到未选图状态`() = runTest {
        val vm = viewModel()
        vm.load(uri)
        vm.reset()
        assertEquals(null, vm.state.value)
        assertEquals(SaveState.Idle, vm.saveState.value)
    }
}
