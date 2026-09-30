package com.photoedit.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最近地点存储（改动 3，TDD）：
 * - [recentAfterRecord] 纯函数：LRU 前插 + 去重提前 + 上限 3；
 * - 序列化往返：每条 "lat,lon,displayName"，条目间换行；地名可含逗号（split limit=3），
 *   坏行（段数不足/坐标非数值/空名）静默跳过；
 * - [RecentPlaceStore] 经注入的 [KeyValueStore] fake 验证持久化与清空，不触 Android。
 */
class RecentPlaceStoreTest {

    private class FakeKv(var value: String? = null) : KeyValueStore {
        var writeCalls = 0
        override fun read(): String? = value
        override fun write(value: String?) {
            this.value = value
            writeCalls++
        }
    }

    private fun place(name: String, lat: Double = 31.2, lon: Double = 121.5) = GeoPlace(name, lat, lon)

    // ---- recentAfterRecord 纯函数 ----

    @Test
    fun `空列表记第一条`() {
        assertEquals(listOf(place("外滩")), recentAfterRecord(emptyList(), place("外滩")))
    }

    @Test
    fun `按时间倒序最多三条 第四条挤掉最旧`() {
        val a = place("A")
        val b = place("B", 1.0)
        val c = place("C", 2.0)
        val d = place("D", 3.0)
        val after3 = recentAfterRecord(recentAfterRecord(recentAfterRecord(emptyList(), a), b), c)
        assertEquals(listOf(c, b, a), after3)
        assertEquals(listOf(d, c, b), recentAfterRecord(after3, d)) // a 被淘汰
    }

    @Test
    fun `重复记录提到最前且不增数量`() {
        val a = place("A")
        val b = place("B", 1.0)
        val c = place("C", 2.0)
        val list = listOf(c, b, a)
        assertEquals(listOf(a, c, b), recentAfterRecord(list, a))
        assertEquals(listOf(b, c, a), recentAfterRecord(list, b))
    }

    @Test
    fun `去重键是 name+lat+lon 全等 同名不同坐标算两条`() {
        val p1 = place("外滩", 31.2, 121.5)
        val p2 = place("外滩", 31.3, 121.5)
        assertEquals(listOf(p2, p1), recentAfterRecord(listOf(p1), p2))
    }

    @Test
    fun `max 边界可配`() {
        val a = place("A")
        val b = place("B", 1.0)
        assertEquals(listOf(b, a), recentAfterRecord(listOf(a), b, max = 2))
    }

    // ---- 序列化往返（经 store 走 fake kv） ----

    @Test
    fun `地名含逗号的条目序列化后可完整还原`() {
        val kv = FakeKv()
        val store = RecentPlaceStore(kv)
        val p = place("外滩, 上海市, 中国", 31.2397, 121.4998)
        store.record(p)
        assertEquals(listOf(p), RecentPlaceStore(kv).all()) // 新实例 = 从存储介质重读
    }

    @Test
    fun `多条记录换行分隔`() {
        val kv = FakeKv()
        val store = RecentPlaceStore(kv)
        store.record(place("A", 1.5, 2.5))
        store.record(place("B", 3.5, 4.5))
        val raw = kv.value!!
        assertEquals(2, raw.split("\n").size)
        assertTrue(raw.contains("1.5,2.5,A"))
        assertTrue(raw.contains("3.5,4.5,B"))
    }

    @Test
    fun `坏行跳过 好行保留`() {
        val kv = FakeKv(
            value = "垃圾行\n31.2,121.5\nabc,def,坏坐标\n31.2,121.5,好点\n,," + "\n31.2,121.5,",
        )
        val all = RecentPlaceStore(kv).all()
        assertEquals(listOf(GeoPlace("好点", 31.2, 121.5)), all)
    }

    @Test
    fun `read 为 null 时 all 为空`() {
        assertEquals(emptyList<GeoPlace>(), RecentPlaceStore(FakeKv(null)).all())
    }

    @Test
    fun `record 持久化并可被新实例读回`() {
        val kv = FakeKv()
        val store = RecentPlaceStore(kv)
        val returned = store.record(place("外滩"))
        assertEquals(listOf(place("外滩")), returned)
        assertEquals(1, kv.writeCalls)
        assertEquals(listOf(place("外滩")), store.all())
    }

    @Test
    fun `clear 清空存储与内存视图`() {
        val kv = FakeKv()
        val store = RecentPlaceStore(kv)
        store.record(place("外滩"))
        store.clear()
        assertNull(kv.value)
        assertEquals(emptyList<GeoPlace>(), store.all())
        assertEquals(emptyList<GeoPlace>(), RecentPlaceStore(kv).all())
    }
}
