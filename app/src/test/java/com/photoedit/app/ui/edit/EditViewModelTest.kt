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
import com.photoedit.app.domain.MetadataField
import com.photoedit.app.domain.PhotoMetadata
import com.photoedit.app.domain.changedFields
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    /** 字节内容作 map key（contentEquals/contentHashCode）。 */
    private class BytesKey(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is BytesKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * 字节感知 fake（复刻真 ExifRepository 的关键契约）：
     * - read 只返回字节流"实际携带"的元数据（registry 按内容查），与哪次 write 无关；
     * - write 只把 changedFields(original, edited) 打到输入 bytes 上：以输入字节流的现有
     *   元数据为底、覆盖 changed 命中的字段，产出一条新字节流（尾部加盖修订号字节以示重写）；
     * - simulateSilentFailure 复刻真 repo"写失败原样返回输入字节（同一引用）"的静默路径。
     */
    private open class FakeRepo(
        var loadResult: ExifRepository.Read = ExifRepository.Read.UnsupportedFormat,
    ) : ExifRepository() {
        private val registry = HashMap<BytesKey, PhotoMetadata>()
        var simulateSilentFailure = false
        var writeCalls = 0
        var lastWriteArgs: Triple<ByteArray, PhotoMetadata, PhotoMetadata>? = null
        var lastWritten: ByteArray? = null

        init {
            (loadResult as? ExifRepository.Read.Success)?.let { registry[BytesKey(it.bytes)] = it.metadata }
        }

        override fun read(bytes: ByteArray): ExifRepository.Read =
            registry[BytesKey(bytes)]?.let { ExifRepository.Read.Success(bytes, it, isMotionPhoto = false) }
                ?: loadResult

        override fun write(bytes: ByteArray, original: PhotoMetadata, edited: PhotoMetadata): ByteArray {
            writeCalls++
            lastWriteArgs = Triple(bytes, original, edited)
            if (simulateSilentFailure) return bytes // 真契约：失败返回同一引用，字节内容不变
            val base = registry[BytesKey(bytes)] ?: original
            val merged = mergeChanged(base, edited, changedFields(original, edited))
            val out = bytes + writeCalls.toByte()
            registry[BytesKey(out)] = merged
            lastWritten = out
            return out
        }

        /** 只覆盖 changed 命中的 EXIF 字段，其余保留底流原值（placeName 非 EXIF，不参与）。 */
        private fun mergeChanged(base: PhotoMetadata, edited: PhotoMetadata, changed: Set<MetadataField>): PhotoMetadata {
            var m = base
            if (MetadataField.TAKEN_AT in changed) m = m.copy(takenAt = edited.takenAt)
            if (MetadataField.GPS in changed) m = m.copy(gps = edited.gps)
            if (MetadataField.MODEL in changed) m = m.copy(model = edited.model)
            if (MetadataField.F_NUMBER in changed) m = m.copy(fNumber = edited.fNumber)
            if (MetadataField.SHUTTER in changed) m = m.copy(shutterSeconds = edited.shutterSeconds)
            if (MetadataField.ISO in changed) m = m.copy(iso = edited.iso)
            if (MetadataField.FOCAL in changed) m = m.copy(focalLengthMm = edited.focalLengthMm)
            return m
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
        var onReverse: () -> Unit = {}
        override suspend fun search(query: String): List<GeoPlace> = emptyList()
        override suspend fun reverse(lat: Double, lon: Double): GeoPlace? {
            reverseCalls++
            onReverse() // 供"反查在途时用户继续编辑"的测试注入并发操作
            return place
        }
    }

    // ---- helpers ----

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
    private val uri = FakeUri("content://media/external/images/media/7")
    private val uri2 = FakeUri("content://media/external/images/media/8")

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
        io: CoroutineDispatcher = UnconfinedTestDispatcher(), // 同步执行 IO，保持既有断言时序
    ) = EditViewModel(repo, writer, geocoder, readBytes, io)

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

    @Test
    fun `load 的全文件 IO 跑在注入的 io 调度器上 未完成前保持 Loading`() = runTest {
        val io = StandardTestDispatcher(testScheduler) // 与 Unconfined 的 Main 相对：需推进调度器才执行 IO 段
        val vm = viewModel(io = io)
        vm.load(uri)
        assertEquals(EditState.Loading, vm.state.value) // IO 尚未被调度 → 还没悄悄在 Main 上跑完
        advanceUntilIdle()
        assertTrue(vm.state.value is EditState.Ready)
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
    fun `反查地名返回时坐标已被改动 陈旧回填被丢弃`() = runTest {
        lateinit var vm: EditViewModel
        val geocoder = FakeGeocoder(GeoPlace("外滩", 31.2, 121.4)).apply {
            onReverse = { vm.clearGps() } // 反查在途时用户清空坐标
        }
        vm = viewModel(geocoder = geocoder)
        vm.load(uri)
        vm.setGps(31.2, 121.4)
        val ready = vm.state.value as EditState.Ready
        assertEquals(null, ready.edited.gps)
        assertEquals(null, ready.edited.placeName) // 陈旧地名不复活
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
        assertSame(repo.lastWritten, writer.lastCopyBytes) // 传给 saveCopy 的正是 write 的产物
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
        assertEquals("A", originalArg.model) // 第二次写以"已存"为基线
        assertEquals("B", editedArg.model)
        assertEquals(SaveState.DoneSaved(FakeWriter.savedUri), vm.saveState.value)
    }

    @Test
    fun `连续两次保存 第二轮写入不得回退第一轮已保存字段`() = runTest {
        val repo = readyRepo()
        val writer = FakeWriter()
        val vm = viewModel(repo = repo, writer = writer)
        vm.load(uri)

        vm.setModel("iPhone 16")
        vm.saveAsCopy()
        val first = repo.lastWritten!!
        assertSame(first, (vm.state.value as EditState.Ready).bytes) // #1: bytes 随保存前移

        vm.setIso(640)
        vm.saveAsCopy()
        assertSame(first, repo.lastWriteArgs!!.first) // 第二轮 write 的输入是第一轮产物
        val second = repo.lastWritten!!
        assertNotSame(first, second)
        // 最终落盘字节流必须同时含新 model 与新 iso（旧实现红：stale bytes + diff-only write
        // 用第一轮旧 model 覆盖回去 → spec §4 静默丢数据）
        val finalMeta = (repo.read(second) as ExifRepository.Read.Success).metadata
        assertEquals("iPhone 16", finalMeta.model)
        assertEquals(640, finalMeta.iso)
    }

    @Test
    fun `保存窗口内的继续编辑不被完成回写吞掉`() = runTest {
        val repo = readyRepo()
        lateinit var vm: EditViewModel
        val writer = FakeWriter().apply {
            saveCopyOutcome = {
                vm.setModel("在途编辑") // MediaStore 往返期间用户继续编辑
                SaveOutcome.Saved(FakeWriter.savedUri)
            }
        }
        vm = viewModel(repo = repo, writer = writer)
        vm.load(uri)
        vm.setModel("A")
        vm.saveAsCopy()
        val ready = vm.state.value as EditState.Ready
        assertEquals("在途编辑", ready.edited.model) // 在途编辑保留（旧实现红：陈旧快照回写吞掉）
        assertEquals("A", ready.original.model) // 基线只前移到本轮真正写入的内容
        assertSame(repo.lastWritten, ready.bytes)
    }

    @Test
    fun `保存中途切换到新图片 旧会话完成不复活旧状态`() = runTest {
        val repo = readyRepo()
        lateinit var vm: EditViewModel
        val writer = FakeWriter().apply {
            saveCopyOutcome = {
                vm.load(uri2) // 保存往返期间用户切图（load 同步完成为新会话 Ready）
                SaveOutcome.Saved(FakeWriter.savedUri)
            }
        }
        vm = viewModel(repo = repo, writer = writer)
        vm.load(uri)
        vm.setModel("A")
        vm.saveAsCopy()
        val ready = vm.state.value as EditState.Ready
        assertEquals(baseMeta, ready.original) // 新会话基线 = 新读取结果（旧实现红：旧会话状态复活）
        assertEquals("Pixel 8", ready.edited.model) // "A" 属于已放弃的旧会话
        assertSame(jpegBytes, ready.bytes)
        assertEquals(SaveState.Idle, vm.saveState.value) // 旧会话的 DoneSaved 不污染新会话
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
    fun `write 静默失败（同引用）被识别为 Failed 且基线与 bytes 不前移`() = runTest {
        val repo = readyRepo().apply { simulateSilentFailure = true }
        val vm = viewModel(repo = repo, writer = FakeWriter())
        vm.load(uri)
        vm.setTakenAt(LocalDateTime.of(2030, 1, 1, 0, 0, 0))
        vm.saveAsCopy()
        val failed = vm.saveState.value as SaveState.Failed
        assertContains(failed.reason, "原始字节") // write 失败契约：返回输入同一引用
        val ready = vm.state.value as EditState.Ready
        assertEquals(baseMeta, ready.original) // 校验失败 = 未保存成功，基线不前移
        assertSame(jpegBytes, ready.bytes)
    }

    @Test
    fun `只改 ISO 且 write 静默失败 判 Failed 且基线不前移`() = runTest {
        val repo = readyRepo().apply { simulateSilentFailure = true }
        val vm = viewModel(repo = repo, writer = FakeWriter())
        vm.load(uri)
        vm.setIso(800)
        vm.saveAsCopy()
        // 旧实现红：固定三字段子集校验对"只改 ISO"恒过 → 假 DoneSaved + 基线前移
        assertTrue(vm.saveState.value is SaveState.Failed)
        val ready = vm.state.value as EditState.Ready
        assertEquals(baseMeta, ready.original)
        assertSame(jpegBytes, ready.bytes)
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
    fun `overwrite 成功后 bytes 前移 连续保存不回退字段`() = runTest {
        val repo = readyRepo()
        val writer = FakeWriter()
        val vm = viewModel(repo = repo, writer = writer)
        vm.load(uri)
        vm.setModel("Z")
        vm.overwriteOriginal()
        assertSame(repo.lastWritten, (vm.state.value as EditState.Ready).bytes) // saveAsCopy 与 overwrite 两条路径都要前移
        vm.setIso(320)
        vm.overwriteOriginal()
        val finalMeta = (repo.read(repo.lastWritten!!) as ExifRepository.Read.Success).metadata
        assertEquals("Z", finalMeta.model)
        assertEquals(320, finalMeta.iso)
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
