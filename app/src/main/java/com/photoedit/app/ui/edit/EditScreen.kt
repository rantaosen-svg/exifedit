package com.photoedit.app.ui.edit

import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.decode.ExifOrientationPolicy
import coil.request.ImageRequest
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.PhotoMetadata
import com.photoedit.app.ui.location.LocationSheet
import com.photoedit.app.ui.location.rememberLocationRequester
import com.photoedit.app.ui.theme.IosDanger
import com.photoedit.app.ui.theme.IosSecondaryLabel
import com.photoedit.app.ui.theme.IosSeparator
import com.photoedit.app.ui.theme.iosCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private val TakenAtFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
private const val DEFAULT_PREVIEW_RATIO = 0.75f // 位图尺寸未知（加载中）时的占位宽高比

/**
 * 编辑页（spec §3.2）：预览 + 拍摄时间 + 地点 + 相机信息四卡，底部悬浮"保存"。
 *
 * - 状态全部来自 [EditViewModel]（页面本身无业务状态）；路由可达性由 AppNav 保证
 *   （state 非 null 才进来），Loading/Unsupported/Error 仍给兜底渲染。
 * - 预览旋正：Coil 解码显式 `ExifOrientationPolicy.IGNORE`（否则 API 28+ ImageDecoder
 *   会自动应用 EXIF，graphicsLayer 再转一次 = 双重旋转），再按 `edited.orientation`
 *   用 graphicsLayer 做 1..8 的旋正映射（见 [applyExifUpright]）。
 * - 各输入卡只改本地文本，解析合法才回写 ViewModel（spec §4：非法输入不污染状态）；
 *   remember 以 uri 为 key，切图后字段重新播种。
 * - 地点卡打开 [LocationSheet]（Task 14：搜索 / 当前定位 / 手动经纬度 / 清除）；
 *   有坐标但地名反查未回来时卡片显示"已设坐标·地名待获取"，不阻塞保存。
 * - 保存反馈（spec §3.5）：DoneSaved/Failed → snackbar；NeedsOverwritePermission →
 *   经 [onLaunchOverwritePermission] 发起系统授权（launcher 在 MainActivity 层），
 *   SaveSheet 内同时给"改为另存副本"一键路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    vm: EditViewModel,
    onBack: () -> Unit,
    onLaunchOverwritePermission: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val saveState by vm.saveState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showLocationSheet by remember { mutableStateOf(false) }
    // 定位的权限 launcher + 取位协程挂在本页作用域：系统授权弹窗打断地点面板时结果不丢
    val locationRequester = rememberLocationRequester(vm) { showLocationSheet = false }

    // 一次性事件（非法输入等，无 replay）→ snackbar
    LaunchedEffect(Unit) {
        vm.events.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(saveState) {
        when (val s = saveState) {
            is SaveState.DoneSaved -> {
                showSaveSheet = false
                // "查看"为占位（跳系统相册/大图页在 Task 15 视验收需要再加）
                snackbarHostState.showSnackbar("已保存", actionLabel = "查看")
            }

            is SaveState.NeedsOverwritePermission -> {
                showSaveSheet = true // 面板内展示"改为另存副本"一键路径
                onLaunchOverwritePermission() // 拉起系统写授权，OK 后 AppNav 重试 overwrite
            }

            is SaveState.Failed -> snackbarHostState.showSnackbar(s.reason)
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            if (state is EditState.Ready) {
                val working = saveState is SaveState.Working
                Button(
                    onClick = { showSaveSheet = true },
                    enabled = !working,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text("保存", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val current = state
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = current) {
                null, is EditState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                is EditState.Unsupported -> MessageWithBack(
                    "暂不支持该格式（仅 JPEG）",
                    onBack,
                    Modifier.align(Alignment.Center),
                )

                is EditState.Error -> MessageWithBack(
                    "读取失败：${s.msg}",
                    onBack,
                    Modifier.align(Alignment.Center),
                )

                is EditState.Ready -> {
                    val uri = vm.currentUri
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { PreviewCard(uri = uri, orientation = s.edited.orientation) }
                        item { TakenAtCard(takenAt = s.edited.takenAt, onPicked = { vm.setTakenAt(it) }) }
                        item {
                            PlaceCard(
                                placeName = s.edited.placeName,
                                gps = s.edited.gps,
                                onEdit = { showLocationSheet = true },
                            )
                        }
                        item { CameraInfoCard(sessionKey = uri, edited = s.edited, vm = vm) }
                    }
                }
            }
        }
    }

    if (showSaveSheet) {
        SaveSheet(vm = vm, onDismiss = { showSaveSheet = false })
    }

    if (showLocationSheet) {
        LocationSheet(
            vm = vm,
            location = locationRequester,
            onDismiss = { showLocationSheet = false },
        )
    }
}

@Composable
private fun MessageWithBack(msg: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.iosCard())
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("返回") }
    }
}

// ---- 预览卡 ----

@Composable
private fun PreviewCard(uri: Uri?, orientation: Int) {
    val context = LocalContext.current
    var intrinsic by remember(uri) { mutableStateOf(Size.Zero) }
    // Coil 默认 RESPECT_* 策略会自动旋正（ImageDecoder/BitmapFactory 双路径不可控），
    // 这里强制 IGNORE，旋正完全交给下面 graphicsLayer，保证只转一次。
    val model = remember(uri) {
        if (uri == null) null else ImageRequest.Builder(context)
            .data(uri)
            .decoderFactory(BitmapFactoryDecoder.Factory(exifOrientationPolicy = ExifOrientationPolicy.IGNORE))
            .build()
    }
    val swapped = orientation in 5..8 // 旋正后宽高互换（90°/270°）
    val storedRatio =
        if (intrinsic.width > 0f && intrinsic.height > 0f) intrinsic.width / intrinsic.height else DEFAULT_PREVIEW_RATIO
    val displayRatio = (if (swapped) 1f / storedRatio else storedRatio).coerceIn(0.3f, 3.3f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(displayRatio)
            .clip(RoundedCornerShape(16.dp))
            .background(IosSeparator),
    ) {
        AsyncImage(
            model = model,
            contentDescription = "照片预览",
            // 内容区尺寸按旋正后互换摆放，区域宽高比 == 位图宽高比，Fit 即精确铺满
            contentScale = ContentScale.Fit,
            error = ColorPainter(IosSeparator),
            modifier = Modifier
                .run { if (swapped) size(maxHeight, maxWidth) else size(maxWidth, maxHeight) }
                .align(Alignment.Center)
                .graphicsLayer { applyExifUpright(orientation) },
            onLoading = { st ->
                st.painter?.intrinsicSize?.let { if (it.width > 0f && it.height > 0f) intrinsic = it }
            },
            onSuccess = { st ->
                val s = st.painter.intrinsicSize
                if (s.width > 0f && s.height > 0f) intrinsic = s
            },
        )
    }
}

/**
 * EXIF orientation 1..8 → 屏幕旋正（graphicsLayer 变换序：先 scale 后 rotation，
 * 即矩阵 R(θ)·S；rotationZ 正值 = 视觉顺时针）：
 * 1 原样；2 水平镜像；3 转 180；4 垂直镜像；5 逆时针 90+水平镜像（转置）；
 * 6 顺时针 90；7 顺时针 90+水平镜像（反转置）；8 逆时针 90。0/缺省/越界按 1 处理。
 */
internal fun GraphicsLayerScope.applyExifUpright(orientation: Int) {
    when (orientation) {
        2 -> scaleX = -1f
        3 -> rotationZ = 180f
        4 -> scaleY = -1f
        5 -> { rotationZ = -90f; scaleX = -1f }
        6 -> rotationZ = 90f
        7 -> { rotationZ = 90f; scaleX = -1f }
        8 -> rotationZ = -90f
        else -> Unit // 1 / 0（缺省）/ 非法值：不旋转
    }
}

// ---- 拍摄时间卡 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TakenAtCard(takenAt: LocalDateTime?, onPicked: (LocalDateTime) -> Unit) {
    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(Modifier.iosCard()) {
        CardRow(
            title = "拍摄时间",
            value = takenAt?.format(TakenAtFormatter) ?: "未设置·点击添加",
            onClick = { showDateDialog = true },
        )
    }

    if (showDateDialog) {
        val initialMillis = (takenAt ?: LocalDateTime.now())
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDateDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis
                    if (millis != null) {
                        // M3 DatePicker 返回值按 UTC 毫秒存日期，取 LocalDate 须用 UTC 时区
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        showTimeDialog = true
                    }
                    showDateDialog = false
                }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = { showDateDialog = false }) { Text("取消") } },
        ) {
            DatePicker(state = dateState, showModeToggle = false)
        }
    }

    if (showTimeDialog) {
        val seed = pickedDate?.let { d -> LocalDateTime.of(d, LocalTime.of((takenAt ?: LocalDateTime.now()).hour, (takenAt ?: LocalDateTime.now()).minute)) }
            ?: LocalDateTime.now()
        val context = LocalContext.current
        val timeState = rememberTimePickerState(
            initialHour = seed.hour,
            initialMinute = seed.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: takenAt?.toLocalDate() ?: LocalDate.now()
                    onPicked(LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute)))
                    showTimeDialog = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimeDialog = false }) { Text("取消") } },
            text = { TimePicker(state = timeState) },
        )
    }
}

// ---- 地点卡 ----

@Composable
private fun PlaceCard(placeName: String?, gps: GpsCoordinates?, onEdit: () -> Unit) {
    Column(Modifier.iosCard()) {
        CardRow(
            title = "拍摄地点",
            value = placeName ?: if (gps != null) "已设坐标·地名待获取" else "未设置",
            secondary = gps?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) },
            onClick = onEdit,
        )
    }
}

// ---- 相机信息卡 ----

@Composable
private fun CameraInfoCard(sessionKey: Uri?, edited: PhotoMetadata, vm: EditViewModel) {
    var modelText by remember(sessionKey) { mutableStateOf(edited.model.orEmpty()) }

    Column(Modifier.iosCard()) {
        Text("相机信息", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = modelText,
            onValueChange = {
                modelText = it
                vm.setModel(it)
            },
            label = { Text("型号") },
            placeholder = { Text("未设置") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = IosSeparator, modifier = Modifier.padding(top = 8.dp))
        NumberFieldRow(
            label = "光圈",
            sessionKey = sessionKey,
            initial = edited.fNumber?.let(::trimNumber).orEmpty(),
            decimal = true,
            hint = "如 2.8",
            onCommit = { text ->
                val v = text.toDoubleOrNull()?.takeIf { it > 0 }
                if (v != null) { vm.setFNumber(v); null } else "请输入大于 0 的数值"
            },
        )
        NumberFieldRow(
            label = "快门",
            sessionKey = sessionKey,
            initial = edited.shutterSeconds?.let(::formatShutter).orEmpty(),
            decimal = true,
            hint = "如 1/125 或 0.5",
            onCommit = { text ->
                val v = parseShutter(text)
                if (v != null) { vm.setShutter(v); null } else "格式应为 1/125 或秒数（>0）"
            },
        )
        NumberFieldRow(
            label = "ISO",
            sessionKey = sessionKey,
            initial = edited.iso?.toString().orEmpty(),
            decimal = false,
            hint = "如 100",
            onCommit = { text ->
                val v = text.toIntOrNull()?.takeIf { it > 0 }
                if (v != null) { vm.setIso(v); null } else "请输入正整数"
            },
        )
        NumberFieldRow(
            label = "焦距",
            sessionKey = sessionKey,
            initial = edited.focalLengthMm?.let(::trimNumber).orEmpty(),
            decimal = true,
            hint = "mm，如 24",
            onCommit = { text ->
                val v = text.toDoubleOrNull()?.takeIf { it > 0 }
                if (v != null) { vm.setFocal(v); null } else "请输入大于 0 的数值"
            },
        )
    }
}

/**
 * 一行数字字段：留空 = 不改动（也不报错）；[onCommit] 返回 null 表示已合法回写 ViewModel，
 * 返回错误文案则就地红字提示（非法输入不回写，spec §4）。
 */
@Composable
private fun NumberFieldRow(
    label: String,
    sessionKey: Any?,
    initial: String,
    decimal: Boolean,
    hint: String,
    onCommit: (String) -> String?,
) {
    var text by remember(sessionKey) { mutableStateOf(initial) }
    var error by remember(sessionKey) { mutableStateOf<String?>(null) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = IosSecondaryLabel,
                modifier = Modifier.width(64.dp),
            )
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    error = if (it.isBlank()) null else onCommit(it)
                },
                singleLine = true,
                placeholder = {
                    Text("未设置（$hint）", color = IosSecondaryLabel, style = MaterialTheme.typography.bodyMedium)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodySmall,
                color = IosDanger,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
    }
}

// ---- 通用行 ----

@Composable
private fun CardRow(title: String, value: String, secondary: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == "未设置" || value == "未设置·点击添加") IosSecondaryLabel else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            if (secondary != null) {
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = IosSecondaryLabel)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = IosSecondaryLabel)
    }
}

// ---- 快门读写格式化（纯函数，JVM 可测） ----

/** 秒 → 展示文案：<1s 优先 "1/整数"（分母取整误差 ≤2% 或 ≤0.5 档），否则小数。 */
internal fun formatShutter(seconds: Double): String {
    if (seconds <= 0.0) return ""
    if (seconds >= 1.0) return trimNumber(seconds)
    val inv = 1.0 / seconds
    val rounded = inv.roundToLong()
    return if (rounded > 0 && abs(inv - rounded) <= maxOf(0.02, inv * 0.02)) {
        "1/$rounded"
    } else {
        "1/${trimNumber(inv)}"
    }
}

/** "1/x"、"a/b" 或十进制秒 → Double 秒；非法/非正返回 null。 */
internal fun parseShutter(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (t.contains('/')) {
        val num = t.substringBefore('/').trim().toDoubleOrNull() ?: return null
        val den = t.substringAfter('/').trim().toDoubleOrNull() ?: return null
        if (num <= 0.0 || den <= 0.0) return null
        return num / den
    }
    return t.toDoubleOrNull()?.takeIf { it > 0.0 }
}

/** 去掉尾随 0 的定点格式：2.0 → "2"，2.8 → "2.8"，0.008 → "0.008"。 */
internal fun trimNumber(v: Double): String =
    String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
