package com.photoedit.app.ui.entry

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.photoedit.app.ui.CanonicalMediaUri
import com.photoedit.app.ui.resolveCanonicalMediaUri
import kotlinx.coroutines.launch

/**
 * 入口页：大标题 + 副标题 + “选择照片”胶囊主按钮（系统 Photo Picker，无需存储权限）。
 *
 * - 格式（仅 JPEG）不在此校验，由后续 EditViewModel.load 判定并给出 UnsupportedFormat 提示。
 * - Task14a 修复轮1（读写分离）：Photo Picker 返回的 uri 带**临时读授权**，读取/命名必须
 *   继续用它；这里把它原样作为 readUri，另经 [resolveCanonicalMediaUri] 解析出仅用于
 *   “覆盖原图”的规范 writeUri（picker uri 自身查 `_ID` 重建 media images uri；解析不到
 *   则 writeUri=null，覆盖入口禁用、编辑与另存副本照常）。
 * - 归一的 binder 查询在 Dispatchers.IO 协程内执行（评审 #6），不阻塞 picker 回调线程。
 */
@Composable
fun EntryScreen(onPick: (CanonicalMediaUri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) scope.launch {
            onPick(resolveCanonicalMediaUri(context, uri))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "ExifEdit",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "编辑照片的拍摄时间与地点",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = {
                    pickLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = "选择照片",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
