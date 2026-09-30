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
import androidx.lifecycle.viewModelScope
import com.photoedit.app.ui.ShareIntake
import com.photoedit.app.ui.classifyShareIntake
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
     * 分享进入（改动 1）：按内容判定而非精确 MIME（根因：相册分享常声明 image 通配、
     * 全通配、image/heic，单图也可能走 SEND_MULTIPLE，旧版全被误拒）。判定逻辑抽为纯函数
     * [classifyShareIntake]（JVM 可测）；格式是否 JPEG 交给 ExifRepository.read 的
     * SOI 魔数判定 → Unsupported 状态（编辑页已有"暂不支持该格式"UI），入口不再谎报。
     *
     * 处理后清空 intent.action（Task 11 评审 B）：Activity 重建时 getIntent() 返回
     * 同一 Intent 对象，若不消费会重放 Toast（及无谓的 load）。新分享走 onNewIntent，
     * 携带的是新 Intent 对象，不受影响。
     */
    private fun intakeShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val type = intent.type
        // binder 查询仍留在主线程（与旧版一致，量级仅一次 parcel 解包）；异常兜底为空
        val streamUri = if (action == Intent.ACTION_SEND) {
            runCatching { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
        } else {
            null
        }
        val streamUris = if (action == Intent.ACTION_SEND_MULTIPLE) {
            runCatching { IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }
                .getOrNull()?.filterIsInstance<Uri>()
        } else {
            null
        }
        when (val intake = classifyShareIntake(action, type, streamUri, streamUris)) {
            is ShareIntake.LoadSingle -> {
                // Task14a 修复轮1（读写分离）：分享 EXTRA_STREAM 原样作为**授权读 uri**
                // 进入编辑/命名链路；另在 IO 协程内解析规范 writeUri（仅 media authority
                // 可信来源才有，Critical#2 门）供覆盖使用。file uri / 第三方 authority /
                // 解析失败 → writeUri=null，覆盖入口禁用（spec §3.5/§4），编辑与副本照常。
                // 归一的 binder 查询移出主线程（评审 #6）：挂在 VM 协程作用域内。
                editViewModel.viewModelScope.launch {
                    val canonical = resolveCanonicalMediaUri(this@MainActivity, intake.uri)
                    editViewModel.load(canonical.readUri, canonical.writeUri)
                }
            }

            ShareIntake.TooManyItems ->
                Toast.makeText(this, "暂支持单张图片，请减少选择数量", Toast.LENGTH_SHORT).show()

            ShareIntake.NoContent ->
                Toast.makeText(this, "暂不支持该内容类型", Toast.LENGTH_SHORT).show()

            ShareIntake.NotShare -> return // 非分享 action：不消费、不处理
        }
        intent.action = null
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
        EntryScreen(onPick = { canonical -> viewModel.load(canonical.readUri, canonical.writeUri) })
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
