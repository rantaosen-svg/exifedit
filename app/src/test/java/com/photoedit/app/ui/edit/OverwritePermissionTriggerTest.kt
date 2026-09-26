package com.photoedit.app.ui.edit

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Task14a 缺陷 2：系统授权弹窗只在 Working→NOP 的一次跃变触发，不重入、不因重组重发。 */
class OverwritePermissionTriggerTest {

    private val nop = SaveState.NeedsOverwritePermission

    @Test
    fun `Working 跃变到 NOP 且未消费过时自动发起一次`() {
        assertTrue(shouldRelaunchOverwritePermission(SaveState.Working, nop, autoLaunchConsumed = false))
    }

    @Test
    fun `已消费过则同一会话不再自动弹`() {
        assertFalse(shouldRelaunchOverwritePermission(SaveState.Working, nop, autoLaunchConsumed = true))
    }

    @Test
    fun `非从 Working 跃变不触发`() {
        assertFalse(shouldRelaunchOverwritePermission(SaveState.Idle, nop, autoLaunchConsumed = false))
        assertFalse(shouldRelaunchOverwritePermission(nop, nop, autoLaunchConsumed = false))
    }

    @Test
    fun `NOP 到 Working 到 NOP 的重入循环第二次不再自动弹`() {
        // 第一次：Working→NOP 触发，消费置真
        val first = shouldRelaunchOverwritePermission(SaveState.Working, nop, autoLaunchConsumed = false)
        assertTrue(first)
        // 授权后重试：NOP→Working→NOP，此时 previous 是 Working 但已消费 → 不再弹
        val second = shouldRelaunchOverwritePermission(SaveState.Working, nop, autoLaunchConsumed = true)
        assertFalse(second)
    }

    @Test
    fun `当前态非 NOP 一律不触发`() {
        assertFalse(shouldRelaunchOverwritePermission(SaveState.Working, SaveState.Working, false))
        assertFalse(shouldRelaunchOverwritePermission(SaveState.Working, SaveState.Idle, false))
    }
}
