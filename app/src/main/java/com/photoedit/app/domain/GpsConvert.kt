package com.photoedit.app.domain
import kotlin.math.abs
import kotlin.math.round

object GpsConvert {
    data class Dms(val degrees: Int, val minutes: Int, val seconds: Double, val ref: Char)

    fun toDecimal(degrees: Int, minutes: Int, seconds: Double, ref: Char): Double? {
        if (ref != 'N' && ref != 'S' && ref != 'E' && ref != 'W') return null
        if (degrees < 0 || minutes !in 0..59 || seconds !in 0.0..60.0) return null
        val abs = degrees + minutes / 60.0 + seconds / 3600.0
        return if (ref == 'S' || ref == 'W') -abs else abs
    }

    // Minor①：NaN 与任何区间比较恒为 false，会漏过 < / > 双端界检查、把 NaN 喂进 dms()
    // 产出度/分/秒全为垃圾值的 Dms（0/负数截断）——isFinite 显式拒 NaN 与 ±Infinity。
    fun fromDecimalLatitude(value: Double): Dms? =
        if (!value.isFinite() || value < -90.0 || value > 90.0) null else dms(value, 'N', 'S')

    fun fromDecimalLongitude(value: Double): Dms? =
        if (!value.isFinite() || value < -180.0 || value > 180.0) null else dms(value, 'E', 'W')

    private fun dms(value: Double, pos: Char, neg: Char): Dms {
        val ref = if (value >= 0) pos else neg
        var a = abs(value)
        val d = a.toInt(); a = (a - d) * 60
        val m = a.toInt(); a = (a - m) * 60
        val s = round(a * 10000) / 10000.0
        return Dms(d, m, s, ref)
    }
}
