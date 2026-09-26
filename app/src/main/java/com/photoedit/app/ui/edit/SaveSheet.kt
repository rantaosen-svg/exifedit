package com.photoedit.app.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.photoedit.app.ui.theme.IosBlue
import com.photoedit.app.ui.theme.IosSecondaryLabel
import com.photoedit.app.ui.theme.IosSeparator
import com.photoedit.app.ui.theme.IosSurface

// Theme.kt 无警告色 token，iOS 系统橙就近定义（黄条专用于动态照片提示）
private val IosWarning = Color(0xFFFF9500)

/**
 * 保存面板（spec §3.5）：另存副本（默认高亮）/ 覆盖原图 两行 + SaveState 反馈。
 *
 * - Working：两行禁用 + 线性进度条；
 * - DoneSaved / Failed 的 snackbar 与 dismiss 由 EditScreen 的 LaunchedEffect 统一处理；
 * - NeedsOverwritePermission：EditScreen 拉起系统授权对话框的同时，面板保持打开并
 *   就地给出"授权被拒绝，可改为另存副本" + 一键 [EditViewModel.saveAsCopy]
 *   （覆盖路径的完整语义 = 面板内联兜底，不依赖用户是否注意到 snackbar）。
 * - 动态照片黄条仅对**检测到的内嵌型**（isMotionPhoto）显示；双文件型无法检测、
 *   副本为静态图属 spec §3.4 已知限制，不在此文案范围。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSheet(vm: EditViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val saveState by vm.saveState.collectAsState()
    val overwriteSupported by vm.overwriteSupported.collectAsState()
    val ready = state as? EditState.Ready
    val working = saveState is SaveState.Working

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = IosSurface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            if (ready?.isMotionPhoto == true) {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(IosWarning)
                        .padding(12.dp),
                ) {
                    Text(
                        "动态照片：覆盖与另存副本都将保留动效",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF3A2A00),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            SaveOptionRow(
                title = "另存为副本",
                subtitle = "原图保持不变",
                highlighted = true,
                enabled = !working,
                onClick = { vm.saveAsCopy() },
            )
            HorizontalDivider(color = IosSeparator)
            SaveOptionRow(
                title = "覆盖原图",
                subtitle = if (overwriteSupported) "需要系统确认" else "该来源不支持覆盖",
                highlighted = false,
                enabled = !working && overwriteSupported,
                onClick = { vm.overwriteOriginal() },
            )

            // Task14a 缺陷 1：来源无可信规范 writeUri（picker/分享只读 uri 解析失败）时
            // 优雅降级——就地给文案，引导"另存为副本"，不静默失败（spec §3.5/§4）。
            // 评审 #7：文案常量化到 EditViewModel.OVERWRITE_UNSUPPORTED_MSG 一处，不再两处字面量。
            if (!overwriteSupported) {
                Text(
                    EditViewModel.OVERWRITE_UNSUPPORTED_MSG,
                    style = MaterialTheme.typography.bodySmall,
                    color = IosSecondaryLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (working) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            if (saveState is SaveState.NeedsOverwritePermission) {
                HorizontalDivider(color = IosSeparator)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "授权被拒绝，可改为另存副本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = IosSecondaryLabel,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.saveAsCopy() }) {
                        Text("另存副本", color = IosBlue)
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveOptionRow(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlighted) IosBlue else MaterialTheme.colorScheme.onSurface,
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = IosSecondaryLabel)
    }
}
