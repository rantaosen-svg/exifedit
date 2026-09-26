package com.photoedit.app.ui.location

import android.content.ContextWrapper
import android.net.FakeUri
import com.photoedit.app.data.ExifRepository
import com.photoedit.app.data.GeoPlace
import com.photoedit.app.data.GeocoderService
import com.photoedit.app.data.MediaStoreWriter
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.PhotoMetadata
import com.photoedit.app.ui.edit.EditState
import com.photoedit.app.ui.edit.EditViewModel
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 地点面板的纯逻辑 JVM 单测（不启 Compose）：
 * - [parseManualCoords]：手动经纬度的分段校验（与 EditViewModel.setGps 同一范围口径）；
 * - [freshestCachedFix] / [fallbackCachedFix]：当前定位缓存的新鲜度与兜底时限（评审 #2）；
 * - [applySearchPick] / [applyCoords]：面板三条应用路径对 placeName 的处理（评审 #1，
 *   真 EditViewModel + fake geocoder，锁"重选地点后地名不陈旧"）。
 */
class LocationSheetTest {

    // ---- 手动输入校验 ----

    @Test
    fun `两值均合法则给出坐标`() {
        val r = parseManualCoords("31.2397", "121.49")
        assertIs<ManualCoords.Valid>(r)
        assertEquals(31.2397, r.latitude)
        assertEquals(121.49, r.longitude)
    }

    @Test
    fun `负值与边界值合法`() {
        assertIs<ManualCoords.Valid>(parseManualCoords("-90", "-180"))
        assertIs<ManualCoords.Valid>(parseManualCoords("90", "180"))
        assertIs<ManualCoords.Valid>(parseManualCoords(" 31.5 ", " 121.5 "))
    }

    @Test
    fun `任一为空视为未填完 不报错但不可应用`() {
        assertIs<ManualCoords.Incomplete>(parseManualCoords("", "121.49"))
        assertIs<ManualCoords.Incomplete>(parseManualCoords("31.2", "  "))
    }

    @Test
    fun `纬度越界只标纬度错误`() {
        val r = parseManualCoords("91", "121.49")
        assertIs<ManualCoords.Invalid>(r)
        assertEquals("纬度超出范围（纬 ±90）", r.latError)
        assertNull(r.lonError)
    }

    @Test
    fun `经度越界只标经度错误`() {
        val r = parseManualCoords("31.2", "-181")
        assertIs<ManualCoords.Invalid>(r)
        assertNull(r.latError)
        assertEquals("经度超出范围（经 ±180）", r.lonError)
    }

    @Test
    fun `非数值给出数值错误`() {
        val r = parseManualCoords("abc", "121.49")
        assertIs<ManualCoords.Invalid>(r)
        assertEquals("纬度需为数值", r.latError)
    }

    @Test
    fun `NaN 与 Infinity 不被当作合法数值`() {
        // Kotlin 的 toDoubleOrNull 接受 "NaN"/"Infinity"，越界判定又对 NaN 恒假，必须显式挡掉
        assertIs<ManualCoords.Invalid>(parseManualCoords("NaN", "121.49"))
        assertIs<ManualCoords.Invalid>(parseManualCoords("31.2", "Infinity"))
    }

    // ---- 缓存定位新鲜度 ----

    private fun fix(time: Long) = LocationFix(31.2, 121.4, "gps", time)

    @Test
    fun `取时间最新的缓存`() {
        val best = freshestCachedFix(listOf(fix(1_000), fix(3_000), fix(2_000)), nowMillis = 3_000 + 10)
        assertEquals(3_000L, best?.timeMillis)
    }

    @Test
    fun `缓存超过新鲜度上限则返回空 转实时定位`() {
        assertNull(freshestCachedFix(listOf(fix(1_000)), nowMillis = 61_001, maxAgeMillis = 60_000))
        assertEquals(1_000L, freshestCachedFix(listOf(fix(1_000)), nowMillis = 61_000, maxAgeMillis = 60_000)?.timeMillis)
    }

    @Test
    fun `时间戳缺失或晚于当前时刻的缓存不采用`() {
        assertNull(freshestCachedFix(listOf(fix(-1), fix(0)), nowMillis = 10_000))
        assertNull(freshestCachedFix(listOf(fix(20_000)), nowMillis = 10_000))
        assertNull(freshestCachedFix(emptyList(), nowMillis = 10_000))
    }

    // ---- 过期缓存兜底（评审 #2：实时路径失败后，兜底必须有最大时限并明示年龄） ----

    @Test
    fun `兜底缓存取最新且在时限内可用并给出年龄`() {
        val r = fallbackCachedFix(listOf(fix(2_000), fix(5_000)), nowMillis = 6_000)
        assertIs<FallbackCache.Usable>(r)
        assertEquals(5_000L, r.fix.timeMillis)
        assertEquals(1_000L, r.ageMillis)
        // 恰好压在时限边界：可用（闭区间，与 freshestCachedFix 同风格）
        val edge = fallbackCachedFix(listOf(fix(1_000)), nowMillis = 1_000 + STALE_FALLBACK_MAX_AGE_MS)
        assertIs<FallbackCache.Usable>(edge)
        assertEquals(STALE_FALLBACK_MAX_AGE_MS, edge.ageMillis)
    }

    @Test
    fun `兜底缓存超过时限不可用`() {
        assertIs<FallbackCache.Unusable>(
            fallbackCachedFix(listOf(fix(1_000)), nowMillis = 1_000 + STALE_FALLBACK_MAX_AGE_MS + 1),
        )
        // 昨天的缓存：绝不允许冒充"当前位置"（评审 #2 的原始 bug）
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60_000L
        assertIs<FallbackCache.Unusable>(fallbackCachedFix(listOf(fix(yesterday)), nowMillis = yesterday + 24 * 60 * 60_000L))
    }

    @Test
    fun `兜底缓存拒绝无效时间戳与未来时间戳 时钟回拨不冒充新鲜`() {
        assertIs<FallbackCache.Unusable>(fallbackCachedFix(listOf(fix(0), fix(-3)), nowMillis = 10_000))
        assertIs<FallbackCache.Unusable>(fallbackCachedFix(emptyList(), nowMillis = 10_000))
        assertIs<FallbackCache.Unusable>(fallbackCachedFix(listOf(fix(20_000)), nowMillis = 10_000))
    }

    @Test
    fun `陈旧缓存提示按分钟向上取整`() {
        assertEquals("使用了 2 分钟前的缓存位置", staleCacheNotice(61_000))
        assertEquals("使用了 3 分钟前的缓存位置", staleCacheNotice(121_000))
        assertEquals("使用了 1 分钟前的缓存位置", staleCacheNotice(1_000)) // 亚分钟至少报 1
        assertEquals("使用了 30 分钟前的缓存位置", staleCacheNotice(STALE_FALLBACK_MAX_AGE_MS))
    }

    // ---- 选点路径与 placeName（评审 #1：重选地点后地名必须跟随新点） ----

    @Before fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDownMain() = Dispatchers.resetMain()

    private class RecordingGeocoder(var place: GeoPlace? = null) : GeocoderService {
        var reverseCalls = 0
        override suspend fun search(query: String): List<GeoPlace> = emptyList()
        override suspend fun reverse(lat: Double, lon: Double): GeoPlace? {
            reverseCalls++
            return place
        }
    }

    private class ReadyRepo : ExifRepository() {
        override fun read(bytes: ByteArray): ExifRepository.Read =
            ExifRepository.Read.Success(bytes, PhotoMetadata(), isMotionPhoto = false)
    }

    private fun readyVm(geocoder: GeocoderService): EditViewModel =
        EditViewModel(
            repo = ReadyRepo(),
            writer = MediaStoreWriter(ContextWrapper(null)), // 本组测试只走 setGps 路径，不触 IO
            geocoder = geocoder,
            readBytes = { byteArrayOf(1) },
            ioDispatcher = UnconfinedTestDispatcher(), // load 同步完成，保持断言时序
        ).apply { load(FakeUri("content://media/external/images/media/1")) }

    @Test
    fun `先搜外滩再搜东京 连续搜索选中后地名坐标一起更新 不留陈旧名`() {
        val geocoder = RecordingGeocoder(GeoPlace("外滩, 上海市", 31.2397, 121.4998))
        val vm = readyVm(geocoder)
        applySearchPick(vm, GeoPlace("外滩, 上海市", 31.2397, 121.4998))
        // 第二次搜索返回完全不同的地点（东京）：旧实现只调 setGps，
        // 守卫因"外滩"名仍在而跳过反查 → 卡片显示"外滩, 上海市"+东京坐标（评审 #1 原始 bug）
        applySearchPick(vm, GeoPlace("东京, 关东地方", 35.68, 139.69))
        val ready = vm.state.value as EditState.Ready
        assertEquals(GpsCoordinates(35.68, 139.69, null), ready.edited.gps)
        assertEquals("东京, 关东地方", ready.edited.placeName)
    }

    @Test
    fun `手动或定位应用坐标实际变化时清旧名并重新反查回填`() {
        val geocoder = RecordingGeocoder(GeoPlace("外滩, 上海市", 31.2397, 121.4998))
        val vm = readyVm(geocoder)
        applySearchPick(vm, GeoPlace("外滩, 上海市", 31.2397, 121.4998))
        geocoder.place = GeoPlace("东京, 关东地方", 35.68, 139.69)
        applyCoords(vm, 35.68, 139.69)
        val ready = vm.state.value as EditState.Ready
        assertEquals(GpsCoordinates(35.68, 139.69, null), ready.edited.gps)
        // 旧实现：坐标变了但"外滩"名未清 → setGps 跳过反查，地名停留在上一个地点
        assertEquals("东京, 关东地方", ready.edited.placeName)
        assertEquals(1, geocoder.reverseCalls) // 搜索选中自带名字不反查；只有这次变坐标反查一次
    }

    @Test
    fun `应用坐标与当前一致时不清名不重复反查`() {
        val geocoder = RecordingGeocoder(GeoPlace("不该被用上", 0.0, 0.0))
        val vm = readyVm(geocoder)
        vm.setPlaceName("用户自定义名")
        vm.setGps(35.68, 139.69) // 名非空 → 无反查
        applyCoords(vm, 35.68, 139.69) // 同一坐标重放（如手动段原值直接点应用）
        val ready = vm.state.value as EditState.Ready
        assertEquals("用户自定义名", ready.edited.placeName)
        assertEquals(0, geocoder.reverseCalls)
    }
}
