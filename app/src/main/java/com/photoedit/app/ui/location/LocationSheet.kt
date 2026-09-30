package com.photoedit.app.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.photoedit.app.data.GeoPlace
import com.photoedit.app.domain.GpsConvert
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.ui.edit.EditState
import com.photoedit.app.ui.edit.EditViewModel
import com.photoedit.app.ui.edit.trimNumber
import com.photoedit.app.ui.theme.IosDanger
import com.photoedit.app.ui.theme.IosSecondaryLabel
import com.photoedit.app.ui.theme.IosSeparator
import com.photoedit.app.ui.theme.IosSurface
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 地点编辑面板（spec §3.3）：搜索 / 当前定位 / 手动经纬度 三段 + 清除地点。
 *
 * - 网络请求一律经 [EditViewModel.searchPlaces]（UI 不持有 Geocoder）；坐标回写经
 *   [applySearchPick]（搜索结果：自带地名）/ [applyCoords]（手动、当前定位：坐标变化先清旧名
 *   让 [EditViewModel.setGps] 的内建反查为新坐标回填匹配地名，评审 #1），面板不自行 reverse。
 * - 当前定位只用系统 [LocationManager]（不引入 GMS/融合定位，国行机无谷歌）。
 *   `ACCESS_FINE_LOCATION` 只在此操作时申请；权限 launcher 与取位协程挂在**调用方**
 *   （[rememberLocationRequester]，编辑页作用域）——系统授权弹窗会打断面板窗口，
 *   挂在面板内的协程会随之取消、结果丢失（Task 14 冒烟实测），挂在外层则面板即便被
 *   关掉，定位结果照样回写坐标。
 * - 离线降级：搜索无结果时就地提示改用手动经纬度（手动段完全本地，不依赖网络）；
 *   定位失败（无权限/超时/无 provider）就地红字 + 一次性事件，不崩不阻塞保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(
    vm: EditViewModel,
    location: LocationRequester,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val ready = state as? EditState.Ready ?: return
    val recentPlaces by vm.recentPlaces.collectAsState()
    var tab by remember { mutableStateOf(LocationTab.Search) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = IosSurface) {
        Column(Modifier.fillMaxWidth()) {
            CurrentPlaceHeader(ready.edited.placeName, ready.edited.gps)
            // 改动 3：最近三次搜索选中，胶囊 chip 一键复用
            if (recentPlaces.isNotEmpty()) {
                RecentPlacesSection(
                    places = recentPlaces,
                    onPick = { place ->
                        applySearchPick(vm, place)
                        onDismiss()
                    },
                    onClear = { vm.clearRecentPlaces() },
                )
            }
            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = IosSurface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                LocationTab.entries.forEach { item ->
                    Tab(
                        selected = tab == item,
                        onClick = { tab = item },
                        text = { Text(item.label, style = MaterialTheme.typography.bodyMedium) },
                    )
                }
            }
            when (tab) {
                LocationTab.Search -> SearchSection(
                    search = { query -> vm.searchPlaces(query) },
                    onPick = { place ->
                        applySearchPick(vm, place)
                        onDismiss()
                    },
                )

                LocationTab.Current -> CurrentLocationSection(
                    locating = location.locating,
                    error = location.error,
                    notice = location.notice,
                    onLocate = location.request,
                )

                LocationTab.Manual -> ManualSection(
                    gps = ready.edited.gps,
                    onApply = { lat, lon ->
                        applyCoords(vm, lat, lon)
                        onDismiss()
                    },
                )
            }
            HorizontalDivider(color = IosSeparator)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.clearGps()
                        onDismiss()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("清除地点", style = MaterialTheme.typography.bodyMedium, color = IosDanger)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 三段 Tab（顺序即 [TabRow] 的 index）。 */
private enum class LocationTab(val label: String) {
    Search("搜索"),
    Current("当前定位"),
    Manual("手动输入"),
}

// ---- 当前定位请求控制器 ----

/** 取位结果：[staleCacheAgeMillis] 非 null = 实时路径失败后回落的有时限过期缓存，UI 必须明示年龄。 */
internal data class LocationResult(val fix: LocationFix, val staleCacheAgeMillis: Long?)

/**
 * 定位请求的状态出口：[locating] 取位中（按钮禁用 + 进度），[error] 就地失败文案，
 * [notice] 就地中性提示（目前唯一来源：使用了过期缓存位置，评审 #2），
 * [request] 发起（必要时先申请权限）。
 */
class LocationRequester(
    val locating: Boolean,
    val error: String?,
    val notice: String?,
    val request: () -> Unit,
)

/**
 * 在**调用方**作用域注册权限 launcher 与取位协程（见 [LocationSheet] 注释），
 * 成功后经 [applyCoords] 回写坐标并回调 [onLocated]（面板用它关闭自己）。
 * 失败（无权限 / 超时 / 无可用 provider / 缓存超时限）只写 [LocationRequester.error] +
 * 一条 [EditViewModel.reportIssue] 事件，不抛不崩；走了过期缓存则经 [LocationRequester.notice]
 * + events 双通道明示"使用了 N 分钟前的缓存位置"。
 */
@Composable
internal fun rememberLocationRequester(vm: EditViewModel, onLocated: () -> Unit): LocationRequester {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val start: () -> Unit = {
        if (!locating) {
            locating = true
            error = null
            notice = null
            scope.launch {
                val result = try {
                    fetchCurrentLocation(context)
                } catch (e: CancellationException) {
                    // 用户离开界面导致作用域取消：这不是定位失败，静默结束，不报误导性错误（评审 #5）
                    return@launch
                } catch (e: Exception) {
                    null
                }
                locating = false
                if (result == null) {
                    error = "无法获取当前定位，可改用手动经纬度"
                    vm.reportIssue("无法获取当前定位")
                } else {
                    applyCoords(vm, result.fix.latitude, result.fix.longitude) // 地名由 VM 反查回填
                    result.staleCacheAgeMillis?.let { age ->
                        val text = staleCacheNotice(age)
                        notice = text // 就地中性提示 + events（面板已关时编辑页 snackbar 接住）
                        vm.reportIssue(text)
                    }
                    onLocated()
                }
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            start()
        } else {
            error = "未授予定位权限，可改用手动经纬度"
            vm.reportIssue("未授予定位权限")
        }
    }
    return LocationRequester(
        locating = locating,
        error = error,
        notice = notice,
        request = {
            if (hasLocationPermission(context)) start()
            else launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        },
    )
}

// ---- 顶部：当前地点回显 ----

@Composable
private fun CurrentPlaceHeader(placeName: String?, gps: GpsCoordinates?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            placeName ?: if (gps != null) EditViewModel.PENDING_PLACE_LABEL else "未设置地点",
            style = MaterialTheme.typography.titleMedium,
        )
        if (gps != null) {
            Text(
                formatCoords(gps.latitude, gps.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = IosSecondaryLabel,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---- 最近使用 chips（改动 3） ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentPlacesSection(
    places: List<GeoPlace>,
    onPick: (GeoPlace) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近使用",
                style = MaterialTheme.typography.bodySmall,
                color = IosSecondaryLabel,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text("清空", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            places.forEach { place ->
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onPick(place) },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 240.dp)) {
                        Text(
                            place.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatCoords(place.latitude, place.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = IosSecondaryLabel,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatCoords(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

// ---- 搜索 ----

@Composable
private fun SearchSection(
    search: suspend (String) -> List<GeoPlace>,
    onPick: (GeoPlace) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<GeoPlace>?>(null) } // null = 尚未搜索过
    val scope = rememberCoroutineScope()

    val runSearch: () -> Unit = {
        val q = query.trim()
        if (q.isNotEmpty() && !searching) {
            searching = true
            results = null
            scope.launch {
                val found = runCatching { search(q) }.getOrDefault(emptyList())
                searching = false
                results = found
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                results = null // 关键词已变，旧结果与"未找到"提示一并作废
            },
            label = { Text("地点名") },
            placeholder = { Text("如 外滩", color = IosSecondaryLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searching) {
                LinearProgressIndicator(Modifier.width(120.dp))
                Spacer(Modifier.width(12.dp))
            }
            TextButton(onClick = runSearch, enabled = !searching && query.isNotBlank()) {
                Text(if (searching) "搜索中…" else "搜索", color = MaterialTheme.colorScheme.primary)
            }
        }
        // 先捕获成稳定局部 val：LazyColumn 的内容 lambda 是延迟执行的（快照应用阶段重算派生状态时
        // 会重跑），若直接在里面读可空的 `results` 状态，用户改输入把 results 置 null 后 `!!` 会 NPE
        // 崩主线程（真机复现：搜索出结果后编辑输入框 → LocationSheet.kt:330 NPE）。捕获后 lambda 锁
        // 定非空快照，置 null 只影响下一次组合（走 results==null 分支显示提示）。
        val currentResults = results
        when {
            searching -> HintLine("正在请求地点列表…")
            currentResults == null -> HintLine("Photon 地理编码（免 key）。无网络时可切到“手动输入”")
            currentResults.isEmpty() -> Text(
                "未找到地点。网络不可用或无结果时，请改用“手动输入”经纬度",
                style = MaterialTheme.typography.bodySmall,
                color = IosDanger,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                // key 追加 index（评审 #8）：接口返回两条同名同坐标的候选时纯内容 key 会重复并崩溃
                itemsIndexed(
                    currentResults,
                    key = { index, place -> "${place.displayName}@${place.latitude},${place.longitude}#$index" },
                ) { _, place ->
                    PlaceResultRow(place, onClick = { onPick(place) })
                }
            }
        }
    }
}

@Composable
private fun PlaceResultRow(place: GeoPlace, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            place.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatCoords(place.latitude, place.longitude),
            style = MaterialTheme.typography.bodySmall,
            color = IosSecondaryLabel,
        )
    }
}

@Composable
private fun HintLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = IosSecondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

// ---- 当前定位 ----

@Composable
private fun CurrentLocationSection(locating: Boolean, error: String?, notice: String?, onLocate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Button(onClick = onLocate, enabled = !locating, modifier = Modifier.fillMaxWidth()) {
            Text(if (locating) "定位中…" else "获取当前定位")
        }
        Spacer(Modifier.height(8.dp))
        HintLine("使用系统定位（LocationManager），不依赖谷歌服务")
        // 评审 #2：走了有时限的过期缓存时必须就地明示"使用了 N 分钟前的缓存位置"
        if (notice != null) HintLine(notice)
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = IosDanger,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

// ---- 手动经纬度 ----

@Composable
private fun ManualSection(gps: GpsCoordinates?, onApply: (Double, Double) -> Unit) {
    var latText by remember { mutableStateOf(gps?.latitude?.let(::trimNumber).orEmpty()) }
    var lonText by remember { mutableStateOf(gps?.longitude?.let(::trimNumber).orEmpty()) }
    val parsed = parseManualCoords(latText, lonText)

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = latText,
            onValueChange = { latText = it },
            label = { Text("纬度") },
            placeholder = { Text("-90 ~ 90", color = IosSecondaryLabel) },
            singleLine = true,
            isError = (parsed as? ManualCoords.Invalid)?.latError != null,
            supportingText = { (parsed as? ManualCoords.Invalid)?.latError?.let { ErrorLine(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = lonText,
            onValueChange = { lonText = it },
            label = { Text("经度") },
            placeholder = { Text("-180 ~ 180", color = IosSecondaryLabel) },
            singleLine = true,
            isError = (parsed as? ManualCoords.Invalid)?.lonError != null,
            supportingText = { (parsed as? ManualCoords.Invalid)?.lonError?.let { ErrorLine(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { (parsed as? ManualCoords.Valid)?.let { onApply(it.latitude, it.longitude) } },
            enabled = parsed is ManualCoords.Valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("应用") }
        Spacer(Modifier.height(8.dp))
        HintLine("十进制度，西经/南纬为负；此段完全本地校验，不依赖网络")
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = IosDanger,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- 选点应用路径（评审 #1：重选地点不得残留旧地名） ----

/**
 * 搜索结果选中：直接把结果展示名写入 placeName 再设坐标。
 * 搜索结果的displayName就是该坐标的地名，[EditViewModel.setGps] 的"名为空才反查"守卫
 * 因此天然跳过——既省一次 reverse，也保证旧地名不存活（评审 #1：只调 setGps 时
 * 守卫会因旧名非空而跳过反查，卡片显示"上一个地点名 + 新坐标"）。
 */
internal fun applySearchPick(vm: EditViewModel, place: GeoPlace) {
    vm.setPlaceName(place.displayName)
    vm.setGps(place.latitude, place.longitude)
    // 改动 3：只有搜索选中路径记最近地点（chips 点击同样经此路径，视为再次搜索选中）
    vm.recordRecentPlace(place)
}

/**
 * 手动 / 当前定位应用：坐标相对当前 edited.gps **实际变化**时先清地名，
 * 让 [EditViewModel.setGps] 的反查守卫为新高地坐标回填匹配地名（评审 #1）；
 * 坐标未变则不清（重复应用同一坐标不丢用户自定义名）。
 * 十进制字面比较够用：edited.gps 只由本会话三条路径原样写入，无 EXIF 往返。
 */
internal fun applyCoords(vm: EditViewModel, lat: Double, lon: Double) {
    val current = (vm.state.value as? EditState.Ready)?.edited?.gps
    if (current == null || current.latitude != lat || current.longitude != lon) vm.clearPlaceName()
    vm.setGps(lat, lon)
}

// ---- 手动输入校验（纯逻辑，JVM 可测） ----

/** 手动经纬度的解析结果：合法 / 非法（按字段给就地文案）/ 未填完。 */
internal sealed interface ManualCoords {
    data class Valid(val latitude: Double, val longitude: Double) : ManualCoords
    data class Invalid(val latError: String?, val lonError: String?) : ManualCoords
    data object Incomplete : ManualCoords
}

/**
 * 两字段一起解析（范围判定复用 [GpsConvert]，与 [EditViewModel.setGps] 同一口径）：
 * 任一空白 = [ManualCoords.Incomplete]（不报错，但应用禁用）；
 * 非数值/NaN/越界 = [ManualCoords.Invalid]，只给首个出错字段的文案对应位置。
 */
internal fun parseManualCoords(latText: String, lonText: String): ManualCoords {
    val latError = coordinateError(latText, latitude = true)
    val lonError = coordinateError(lonText, latitude = false)
    if (latError != null || lonError != null) return ManualCoords.Invalid(latError, lonError)
    val lat = latText.trim().toDoubleOrNull()
    val lon = lonText.trim().toDoubleOrNull()
    return if (lat == null || lon == null) ManualCoords.Incomplete else ManualCoords.Valid(lat, lon)
}

private fun coordinateError(text: String, latitude: Boolean): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val v = t.toDoubleOrNull()?.takeUnless { it.isNaN() || it.isInfinite() }
        ?: return if (latitude) "纬度需为数值" else "经度需为数值"
    val inRange = if (latitude) GpsConvert.fromDecimalLatitude(v) != null else GpsConvert.fromDecimalLongitude(v) != null
    return if (inRange) null else if (latitude) "纬度超出范围（纬 ±90）" else "经度超出范围（经 ±180）"
}

// ---- 系统定位（LocationManager，禁 GMS/融合定位） ----

/** 单次实时定位的等待上限。 */
private const val LOCATE_TIMEOUT_MS = 5_000L

/** 缓存定位视为"够新"直接采用的年龄上限。 */
private const val CACHED_MAX_AGE_MS = 60_000L

/** 参与取位的 provider，按精度优先（GPS > network > passive），只用系统 provider。 */
private val LOCATION_PROVIDERS = listOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.PASSIVE_PROVIDER,
)

/** [Location] 的纯数据投影（坐标非法或时间戳缺失的候选直接丢弃，便于 JVM 单测选择逻辑）。 */
internal data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val provider: String,
    val timeMillis: Long,
)

private fun Location.toFixOrNull(): LocationFix? {
    val lat = latitude
    val lon = longitude
    if (GpsConvert.fromDecimalLatitude(lat) == null || GpsConvert.fromDecimalLongitude(lon) == null) return null
    return LocationFix(lat, lon, provider.orEmpty(), time)
}

/**
 * 取最新缓存：全为无效时间戳或年龄超过 [maxAgeMillis] 返回 null（此时应发起实时定位）。
 * `nowMillis - timeMillis < 0`（时钟回拨/未来时间戳）按"不新鲜"处理，交给实时路径。
 */
internal fun freshestCachedFix(
    candidates: List<LocationFix>,
    nowMillis: Long,
    maxAgeMillis: Long = CACHED_MAX_AGE_MS,
): LocationFix? {
    val best = candidates.filter { it.timeMillis > 0 }.maxByOrNull { it.timeMillis } ?: return null
    val age = nowMillis - best.timeMillis
    return if (age in 0..maxAgeMillis) best else null
}

/** 实时定位全失败后，过期缓存可容忍的最大年龄（评审 #2：昨天的缓存不能冒充"当前位置"）。 */
internal const val STALE_FALLBACK_MAX_AGE_MS = 30 * 60_000L

/** 过期缓存兜底判定结果：[Usable.ageMillis] 供 UI 明示陈旧度；超时限/无效则 [Unusable]。 */
internal sealed interface FallbackCache {
    data class Usable(val fix: LocationFix, val ageMillis: Long) : FallbackCache
    data object Unusable : FallbackCache
}

/**
 * 实时路径失败后的缓存兜底：取时间最新者，仅接受年龄 ∈ [0, maxAgeMillis]。
 * 与 [freshestCachedFix] 同一口径——时间戳缺失（≤0）或未来时间戳（时钟回拨，age<0）
 * 一律 [FallbackCache.Unusable]，由调用方按"无法获取当前定位"提示。
 */
internal fun fallbackCachedFix(
    candidates: List<LocationFix>,
    nowMillis: Long,
    maxAgeMillis: Long = STALE_FALLBACK_MAX_AGE_MS,
): FallbackCache {
    val best = candidates.filter { it.timeMillis > 0 }.maxByOrNull { it.timeMillis }
        ?: return FallbackCache.Unusable
    val age = nowMillis - best.timeMillis
    return if (age in 0..maxAgeMillis) FallbackCache.Usable(best, age) else FallbackCache.Unusable
}

/** 使用过期缓存时的明示文案：年龄向上取整到分钟、至少 1 分钟（评审 #2）。 */
internal fun staleCacheNotice(ageMillis: Long): String {
    val minutes = ((ageMillis + 59_999) / 60_000).coerceAtLeast(1)
    return "使用了 $minutes 分钟前的缓存位置"
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 系统 [LocationManager] 取当前定位：
 * 1. 各 provider 的 `getLastKnownLocation` 快速返回（[CACHED_MAX_AGE_MS] 内直接采用）；
 * 2. 否则对第一个已开启的 provider 发 `requestSingleUpdate`（GPS > network > passive），
 *    [LOCATE_TIMEOUT_MS] 超时/失败后回落到过期缓存；
 * 3. 兜底缓存只接受 [STALE_FALLBACK_MAX_AGE_MS] 内的（评审 #2），且经
 *    [LocationResult.staleCacheAgeMillis] 要求 UI 明示"使用了 N 分钟前的缓存位置"；
 *    超时限或都没有则 null，由 UI 就地提示并给出手动兜底。
 *
 * 调用方必须已拿到 `ACCESS_FINE_LOCATION`（[hasLocationPermission] + 运行时申请在
 * [rememberLocationRequester] 里，即编辑页作用域）。
 */
@SuppressLint("MissingPermission")
private suspend fun fetchCurrentLocation(context: Context): LocationResult? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = LOCATION_PROVIDERS.filter { runCatching { lm.getProvider(it) != null }.getOrDefault(false) }
    if (providers.isEmpty()) return null
    val cached = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull()?.toFixOrNull() }
    freshestCachedFix(cached, System.currentTimeMillis())?.let { return LocationResult(it, null) }
    val live = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val direct = live?.let { awaitSingleLocation(lm, it) }
    if (direct != null) return LocationResult(direct, null)
    // 实时定位失败/超时 → 回落有时限的过期缓存（评审 #2）；超时限按"无法获取当前定位"处理
    val fallback = fallbackCachedFix(cached, System.currentTimeMillis())
    return (fallback as? FallbackCache.Usable)?.let { LocationResult(it.fix, it.ageMillis) }
}

@SuppressLint("MissingPermission")
private suspend fun awaitSingleLocation(lm: LocationManager, provider: String): LocationFix? =
    withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location.toFixOrNull())
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            val requested = runCatching { lm.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.isSuccess
            if (!requested && cont.isActive) cont.resume(null)
        }
    }
