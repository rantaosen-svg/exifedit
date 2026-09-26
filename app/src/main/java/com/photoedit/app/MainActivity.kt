package com.photoedit.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.photoedit.app.ui.edit.EditState
import com.photoedit.app.ui.edit.EditViewModel
import com.photoedit.app.ui.entry.EntryScreen
import com.photoedit.app.ui.theme.PhotoEditTheme
import com.photoedit.app.ui.theme.iosCard

class MainActivity : ComponentActivity() {

    /**
     * 编辑期状态唯一归属在 EditViewModel（Task 11 评审 A）：
     * uri 不再存于 Activity，旋转/重建后由 ViewModel 恢复编辑页。
     */
    private val editViewModel: EditViewModel by viewModels { EditViewModel.factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intakeShareIntent(intent)
        setContent {
            PhotoEditTheme {
                AppNav(viewModel = editViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intakeShareIntent(intent)
    }

    /**
     * 分享进入：仅接受单张 image/jpeg|jpg 的 ACTION_SEND；
     * 多图或其他 mime 类型 Toast 提示并停留在入口页。
     *
     * 处理后清空 intent.action（Task 11 评审 B）：Activity 重建时 getIntent() 返回
     * 同一 Intent 对象，若不消费会重放 Toast（及无谓的 load）。新分享走 onNewIntent，
     * 携带的是新 Intent 对象，不受影响。
     */
    private fun intakeShareIntent(intent: Intent?) {
        val handled = when (intent?.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type
                val uri = if (type == "image/jpeg" || type == "image/jpg") {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    null
                }
                if (uri != null) editViewModel.load(uri) else toastUnsupportedShare()
                true
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                toastUnsupportedShare()
                true
            }

            else -> false
        }
        if (handled) intent?.action = null
    }

    private fun toastUnsupportedShare() {
        Toast.makeText(this, "暂支持单张 JPEG", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 无 Navigation 库的极简切换（评审 A）：是否处于编辑页由 ViewModel 状态决定——
 * state 非 null（Loading/Unsupported/Error/Ready）即在编辑页，重建后自动回到编辑页。
 */
@Composable
private fun AppNav(viewModel: EditViewModel) {
    val state by viewModel.state.collectAsState()
    val current = state
    if (current == null) {
        EntryScreen(onPickUri = { uri -> viewModel.load(uri) })
    } else {
        PlaceholderEditScreen(viewModel = viewModel, state = current)
    }
}

/** 占位编辑页：Task 13 替换为真实 EditScreen（字段卡片 + 保存面板）。 */
@Composable
private fun PlaceholderEditScreen(viewModel: EditViewModel, state: EditState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (state) {
                is EditState.Loading -> "加载中…"
                is EditState.Unsupported -> "暂不支持该格式（仅 JPEG）"
                is EditState.Error -> "读取失败：${state.msg}"
                is EditState.Ready -> "已载入（${state.edited.takenAt ?: "无拍摄时间"}）"
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 64.dp)
                .iosCard(),
        )
        if (state !is EditState.Loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                TextButton(onClick = { viewModel.reset() }) {
                    Text("返回选择照片")
                }
            }
        }
    }
}
