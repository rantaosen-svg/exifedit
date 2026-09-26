package com.photoedit.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.IntentCompat
import com.photoedit.app.ui.edit.EditScreen
import com.photoedit.app.ui.edit.EditViewModel
import com.photoedit.app.ui.entry.EntryScreen
import com.photoedit.app.ui.resolveCanonicalMediaUri
import com.photoedit.app.ui.theme.PhotoEditTheme
import kotlinx.coroutines.launch

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
                if (uri != null) {
                    // Task14a 缺陷 1：分享 EXTRA_STREAM 若已是 images/media/<id> 归一后走覆盖；
                    // file uri / 归一失败则只读降级（覆盖入口禁用，spec §3.5/§4）
                    val canonical = resolveCanonicalMediaUri(this, uri)
                    editViewModel.load(canonical.uri, canonical.canOverwrite)
                } else {
                    toastUnsupportedShare()
                }
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
 *
 * 覆盖原图的系统授权 launcher 放在这一层（Task 13）：EditScreen 观察到
 * NeedsOverwritePermission 时经 [EditScreen] 回调触发——用 ViewModel 的
 * requestOverwritePermissionIntent 拿 PendingIntent 起 IntentSender，
 * 用户在系统弹窗授权成功后自动重试一次 overwriteOriginal。
 */
@Composable
private fun AppNav(viewModel: EditViewModel) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val overwritePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.overwriteOriginal()
    }
    if (state == null) {
        EntryScreen(onPick = { canonical -> viewModel.load(canonical.uri, canonical.canOverwrite) })
    } else {
        EditScreen(
            vm = viewModel,
            onBack = { viewModel.reset() }, // 回入口可再选图（session 守卫已防在途保存回写）
            onLaunchOverwritePermission = {
                scope.launch {
                    val intent = viewModel.requestOverwritePermissionIntent() ?: return@launch
                    overwritePermissionLauncher.launch(IntentSenderRequest.Builder(intent).build())
                }
            },
        )
    }
}
