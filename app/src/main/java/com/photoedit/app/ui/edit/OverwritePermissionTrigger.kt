package com.photoedit.app.ui.edit

/**
 * "覆盖原图"系统授权弹窗的自动发起判定（Task14a 缺陷 2）。
 *
 * 只在 **Working → NeedsOverwritePermission 的一次跃变**且本会话**尚未自动发起过**时
 * 触发一次；其余情况（重开面板后仍 NOP、配置变更重组成 first-composition 的 NOP、
 * 授权后重试再次回到 NOP）都不再自动弹，改由面板内联"另存副本"一键兜底。
 *
 * 用 [previous] 必须是 Working 来卡"跃变"，天然挡住"重组后 saveState 仍是 NOP 的
 * first-composition（previous 被重置为 Idle）"这一重复触发路径；用 [autoLaunchConsumed]
 * 挡住同一份 composition 生命周期内 NOP→Working→NOP 的重入循环。
 */
internal fun shouldRelaunchOverwritePermission(
    previous: SaveState,
    current: SaveState,
    autoLaunchConsumed: Boolean,
): Boolean =
    current === SaveState.NeedsOverwritePermission &&
        !autoLaunchConsumed &&
        previous is SaveState.Working
