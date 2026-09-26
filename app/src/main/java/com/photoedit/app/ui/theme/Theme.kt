package com.photoedit.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iOS 系统色 token：主色系统蓝、分组背景浅灰、卡片纯白、危险红
val IosBlue = Color(0xFF007AFF)
val IosBackground = Color(0xFFF2F2F7)
val IosSurface = Color.White
val IosDanger = Color(0xFFFF3B30)
val IosLabel = Color(0xFF1C1C1E)
val IosSecondaryLabel = Color(0xFF8E8E93)
val IosSeparator = Color(0xFFE5E5EA)

private val IosColorScheme = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    secondary = IosBlue,
    onSecondary = Color.White,
    background = IosBackground,
    onBackground = IosLabel,
    surface = IosSurface,
    onSurface = IosLabel,
    surfaceVariant = Color(0xFFEDEDF0),
    onSurfaceVariant = IosSecondaryLabel,
    error = IosDanger,
    onError = Color.White,
    outline = IosSeparator,
)

// 字体走系统默认 sans，仅将大标题（headlineMedium）加粗
private val IosTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
)

/**
 * 应用主题：首版仅浅色、固定 iOS token，不启用 dynamicColor。
 */
@Composable
fun PhotoEditTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IosColorScheme,
        typography = IosTypography,
        content = content,
    )
}

/**
 * iOS 分组列表卡片：白底、16dp 圆角、无阴影（用 1dp 分隔描边）、内容内边距 16dp。
 * 通过 [modifier] 可叠加外部布局修饰（如 fillMaxWidth / padding）。
 */
fun Modifier.iosCard(modifier: Modifier = Modifier): Modifier =
    modifier
        .clip(RoundedCornerShape(16.dp))
        .background(IosSurface)
        .border(1.dp, IosSeparator, RoundedCornerShape(16.dp))
        .padding(16.dp)
