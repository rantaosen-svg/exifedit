package com.photoedit.app.ui.location

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

/**
 * 地点面板的纯逻辑 JVM 单测（不启 Compose）：
 * - [parseManualCoords]：手动经纬度的分段校验（与 EditViewModel.setGps 同一范围口径）；
 * - [freshestCachedFix]：当前定位取缓存的新鲜度判定。
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
}
