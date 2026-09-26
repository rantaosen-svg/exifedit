package com.photoedit.app.ui.edit

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** EditScreen 快门字段纯函数：1/x 展示与解析、数字去尾零。 */
class ShutterFormatTest {

    @Test
    fun `小于 1 秒优先展示为 1 除以整数`() {
        assertEquals("1/125", formatShutter(0.008))
        assertEquals("1/1000", formatShutter(1.0 / 1000))
        assertEquals("1/2", formatShutter(0.5001)) // 有理化损耗（±2%）内仍取整
    }

    @Test
    fun `大于等于 1 秒展示小数 且整数不带尾零`() {
        assertEquals("2", formatShutter(2.0))
        assertEquals("1.3", formatShutter(1.3))
    }

    @Test
    fun `非整分母回退小数倒数形式`() {
        assertTrue(formatShutter(0.625).startsWith("1/")) // 1/1.6
        assertEquals("1/1.6", formatShutter(0.625))
    }

    @Test
    fun `parseShutter 支持 1 斜杠 x 与十进制秒 拒绝非法`() {
        assertEquals(0.008, parseShutter("1/125")!!, 1e-9)
        assertEquals(0.008, parseShutter(" 1 / 125 ")!!, 1e-9)
        assertEquals(0.5, parseShutter("0.5")!!, 1e-9)
        assertEquals(2.0, parseShutter("2")!!, 1e-9)
        assertNull(parseShutter(""))
        assertNull(parseShutter("abc"))
        assertNull(parseShutter("1/0"))
        assertNull(parseShutter("-0.5"))
        assertNull(parseShutter("1/-3"))
    }

    @Test
    fun `trimNumber 去掉尾随零`() {
        assertEquals("2", trimNumber(2.0))
        assertEquals("2.8", trimNumber(2.8))
        assertEquals("0.008", trimNumber(0.008))
    }
}
