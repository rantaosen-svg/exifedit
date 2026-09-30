package com.photoedit.app.data

import android.content.Context

/**
 * 最近使用地点存储（改动 3）：只记**搜索选中**的地点（用户拍板——手动/定位路径不记），
 * LRU 上限 3。IO 介质经 [KeyValueStore] 抽象，JVM 单测注入 fake，不触 Android。
 */
interface KeyValueStore {
    fun read(): String?
    fun write(value: String?)
}

/** SharedPreferences 实现：单文件单 key，apply() 异步落盘（进程被杀不丢已写值）。 */
class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY) else putString(KEY, value)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "recent_places"
        const val KEY = "v1"
    }
}

/**
 * 记录一次选择后的列表：[place] 前插；去重（displayName+lat+lon 全等，data class 相等即此
 * 语义）——已存在时提到最前而非追加；取前 [max]（默认 3）。纯函数，JVM 可测。
 */
internal fun recentAfterRecord(existing: List<GeoPlace>, place: GeoPlace, max: Int = 3): List<GeoPlace> =
    (listOf(place) + existing.filter { it != place }).take(max)

/**
 * 序列化格式：每条 `"$lat,$lon,$displayName"`，条目间 `\n`。
 * 解析 split(",", limit=3)——地名可含逗号（Photon 展示名即 "外滩, 上海市" 风格）；
 * 坏行（段数不足 / 坐标非数值 / 空名）静默跳过，存储损坏只降级为丢历史不影响功能。
 */
internal fun serializeRecentPlaces(places: List<GeoPlace>): String =
    places.joinToString("\n") { "${it.latitude},${it.longitude},${it.displayName}" }

internal fun deserializeRecentPlaces(text: String?): List<GeoPlace> {
    if (text.isNullOrEmpty()) return emptyList()
    return text.split("\n").mapNotNull { line ->
        val parts = line.split(",", limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        val name = parts[2].takeIf { it.isNotBlank() } ?: return@mapNotNull null
        GeoPlace(name, lat, lon)
    }
}

class RecentPlaceStore(private val kv: KeyValueStore) {

    /** 读取历史；存储缺失/全坏行时为空列表。 */
    fun all(): List<GeoPlace> = deserializeRecentPlaces(kv.read())

    /** 记录一条并持久化新列表，返回记录后的列表（供 VM 直接更新 StateFlow）。 */
    fun record(place: GeoPlace): List<GeoPlace> {
        val next = recentAfterRecord(all(), place)
        kv.write(serializeRecentPlaces(next))
        return next
    }

    /** 清空：写 null（SharedPreferences 侧为 remove key）。 */
    fun clear() = kv.write(null)
}
