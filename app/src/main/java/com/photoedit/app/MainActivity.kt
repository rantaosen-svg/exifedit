package com.photoedit.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.photoedit.app.ui.entry.EntryScreen
import com.photoedit.app.ui.theme.PhotoEditTheme
import com.photoedit.app.ui.theme.iosCard

class MainActivity : ComponentActivity() {

    /** 当前待编辑的图片 uri：来自 Photo Picker 或分享 ACTION_SEND；null 时显示入口页 */
    private var currentUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intakeShareIntent(intent)
        setContent {
            PhotoEditTheme {
                AppNav(currentUri = currentUri, onPickUri = { currentUri = it })
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
     */
    private fun intakeShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type
                if (type != "image/jpeg" && type != "image/jpg") {
                    toastUnsupportedShare()
                    return
                }
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) currentUri = uri else toastUnsupportedShare()
            }

            Intent.ACTION_SEND_MULTIPLE -> toastUnsupportedShare()
        }
    }

    private fun toastUnsupportedShare() {
        Toast.makeText(this, "暂支持单张 JPEG", Toast.LENGTH_SHORT).show()
    }
}

/** 无 Navigation 库的极简切换：uri 为空显示入口页，否则显示占位编辑页（Task 13 替换为真实 EditScreen）。 */
@Composable
private fun AppNav(currentUri: Uri?, onPickUri: (Uri) -> Unit) {
    if (currentUri == null) {
        EntryScreen(onPickUri = onPickUri)
    } else {
        PlaceholderEditScreen(uri = currentUri)
    }
}

@Composable
private fun PlaceholderEditScreen(uri: Uri) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "编辑 $uri",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .iosCard(),
        )
    }
}
