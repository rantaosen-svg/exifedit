package com.photoedit.app.ui.edit

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.photoedit.app.data.ExifRepository
import com.photoedit.app.data.GeoPlace
import com.photoedit.app.data.GeocoderService
import com.photoedit.app.data.MediaStoreWriter
import com.photoedit.app.data.OkHttpFetcher
import com.photoedit.app.data.PhotonGeocoder
import com.photoedit.app.data.RecentPlaceStore
import com.photoedit.app.data.SaveOutcome
import com.photoedit.app.data.SharedPreferencesStore
import com.photoedit.app.domain.CopyNaming
import com.photoedit.app.domain.GpsConvert
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.MetadataField
import com.photoedit.app.domain.PhotoMetadata
import com.photoedit.app.domain.changedFields
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑页加载/编辑状态。placeName 随 [EditState.Ready.edited] 一起演进，不再单列。 */
sealed interface EditState {
    data object Loading : EditState
    data object Unsupported : EditState
    data class Error(val msg: String) : EditState
    data class Ready(
        val bytes: ByteArray,
        val original: PhotoMetadata,
        val edited: PhotoMetadata,
        val isMotionPhoto: Boolean,
    ) : EditState
}

/** 保存编排状态（spec §3.5）。NeedsOverwritePermission 时 UI 发起系统授权后重试 overwrite。 */
sealed interface SaveState {
    data object Idle : SaveState
    data object Working : SaveState
    data class DoneSaved(val uri: Uri) : SaveState
    data object NeedsOverwritePermission : SaveState
    data class Failed(val reason: String) : SaveState
}

/**
 * 编辑页状态机与保存编排（Task 12）。
 *
 * - 构造注入 repo/writer/geocoder + readBytes 闭包，JVM 单测可全部 fake，不触 Android 运行时；
 *   生产组装见 [EditViewModel.factory]。
 * - 编辑期唯一状态归属（配置变更存活，Task 11 评审 A）：Activity 只负责在
 *   picker/分享时调 [load]，页面路由由 [state] 决定。
 * - [load] 不做伪幂等守卫：分享重放已由 Task 11 评审 B 的 intent 消费处理，重放同 uri
 *   最坏只是一次冗余读取；读取完成前核对 [loadedUri] 会话标识，迟到的旧结果直接丢弃。
 * - 非法输入（如越界经纬度）不回写状态，只经 [events] 发一条错误提示（选型：
 *   MutableSharedFlow(extraBufferCapacity=8, DROP_OLDEST)，无 replay，避免重建后重放旧提示）。
 * - 保存发起统一前置校验（Important#1）：repo.write 返回输入同一引用（静默失败契约）
 *   且本轮确有字段变更时，直接 Failed，**不触 writer**——另存副本不会先插入相册再
 *   事后校验留下未编辑的垃圾 `原名_副本.jpg`，覆盖不会打开输出流造成截断窗口。
 * - 保存成功统一回写（[onSaved]）：先做重读校验，通过后基于**当前** state 做 copy——
 *   bytes 前移到 write 产物、original 前移到本轮快照的 edited，保存窗口内的在途编辑
 *   保留在 edited 中。bytes 必须与 original 同步前移：repo.write 是 diff-only（只把
 *   changedFields(original, edited) 打到输入字节上），bytes 停留在 load 快照会让下一轮
 *   写入用旧值覆盖上一轮已保存字段（spec §4 静默丢数据）。
 * - 会话守卫：保存发起时记录 loadedUri，完成回写前校验会话未被 [load]/[reset] 切走，
 *   旧会话的迟到结果（含 Failed/NeedsOverwritePermission）整体丢弃。
 * - 全文件字节 IO（readBytes/repo.read/repo.write/重读校验）经 [ioDispatcher] 离开 Main；
 *   生产默认 IO，JVM 测试注入 UnconfinedTestDispatcher 保持同步断言。
 */
class EditViewModel(
    private val repo: ExifRepository,
    private val writer: MediaStoreWriter,
    private val geocoder: GeocoderService,
    private val readBytes: (Uri) -> ByteArray,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // 改动 3：尾加默认参（null = 不记最近地点，既有 42 条 VM 测构造不变）
    private val recentStore: RecentPlaceStore? = null,
) : ViewModel() {

    /** null = 尚未选图（入口页）；Loading/Unsupported/Error/Ready = 编辑页（Task 11 评审 A）。 */
    private val _state = MutableStateFlow<EditState?>(null)
    val state: StateFlow<EditState?> = _state.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    /**
     * 当前来源能否走"覆盖原图"（Task14a 修复轮1）：等价于 [loadedWriteUri] 非 null——
     * picker/分享来源只有解析出**规范 MediaStore writeUri**（见 ui.MediaUriCanonical 的
     * 权威来源门）才可覆盖；解析不到时 false——编辑与另存副本照常，覆盖入口禁用
     * （spec §3.5/§4：不静默失败）。
     */
    private val _overwriteSupported = MutableStateFlow(true)
    val overwriteSupported: StateFlow<Boolean> = _overwriteSupported.asStateFlow()

    /**
     * 一次性错误提示事件（非法经纬度等）；无重放，晚订阅者不会收到陈旧提示。
     */
    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<String> = _events.asSharedFlow()

    /**
     * "该请求系统写授权"一次性事件（Important#4）：overwriteOriginal 落入
     * NeedsOverwritePermission 时**由 VM 确定性发出**，EditScreen 订阅后拉起系统弹窗——
     * 不再依赖 UI 恰好观测到 Working→NOP 的 StateFlow 跃变（conflation 可能吞掉首个
     * NOP 导致 false-negative 不弹）。每个会话至多发一次（[autoOverwritePermissionRequested]
     * 卡重入，防 NOP→授权→NOP 自动弹循环），[load]/[reset] 复位。
     */
    private val _overwritePermissionRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val overwritePermissionRequests: SharedFlow<Unit> = _overwritePermissionRequests.asSharedFlow()

    private var loadedUri: Uri? = null

    /**
     * 覆盖链路专用规范 uri（Task14a 修复轮1，Critical#1 读写分离）：
     * 读取/命名永远走 [loadedUri]（原始授权 uri，读权限绑定在它本身）；
     * overwriteOriginal / requestOverwritePermissionIntent 只走这里（规范 media images uri）。
     * null = 来源解析不出可信规范 uri（或分享/file uri）→ 仅另存副本。
     */
    private var loadedWriteUri: Uri? = null

    /** 本会话是否已自动发过系统写授权事件（[load]/[reset] 复位，卡重入循环）。 */
    private var autoOverwritePermissionRequested = false

    /**
     * 会话自增令牌（评审 #8）：同一 uri 二次分享/选择时 [currentUri] 相等，UI 侧
     * `remember(vm.currentUri, vm.sessionToken)` 靠令牌区分新会话，重置自动弹窗消费位。
     */
    private var sessionCounter = 0L
    val sessionToken: Long get() = sessionCounter

    /** 当前会话的图片 uri（reset/未选图时 null）：编辑页预览/读取路径用，即"授权读 uri"。 */
    val currentUri: Uri? get() = loadedUri

    // region load

    /**
     * @param uri 原始授权读 uri（picker/分享带来的 uri）：预览、readBytes、displayNameOf 全走它。
     * @param writeUri 覆盖链路规范 MediaStore uri（[uri] 自身即规范 uri 时默认同上）；
     *   传 null 表示来源不可信覆盖 → 覆盖入口禁用（仅另存副本）。
     */
    fun load(uri: Uri, writeUri: Uri? = uri) {
        // 无重放守卫（评审 B 的 intent 消费已覆盖）；迟到结果靠 loadedUri 会话核对丢弃
        loadedUri = uri
        loadedWriteUri = writeUri
        _overwriteSupported.value = writeUri != null
        autoOverwritePermissionRequested = false
        sessionCounter++
        _state.value = EditState.Loading
        _saveState.value = SaveState.Idle
        viewModelScope.launch {
            try {
                // 全文件读取 + EXIF 解析是重 IO，离开 Main（评审 #4）；读的是授权 uri（[uri]），
                // 绝不切到 writeUri——getMediaUri/重建出的规范 uri 不带任何新读权限（Critical#1）
                val read = withContext(ioDispatcher) { repo.read(readBytes(uri)) }
                if (loadedUri != uri) return@launch // 已被更新的 load/reset 取代
                when (read) {
                    is ExifRepository.Read.Success -> _state.value = EditState.Ready(
                        bytes = read.bytes,
                        original = read.metadata,
                        edited = read.metadata,
                        isMotionPhoto = read.isMotionPhoto,
                    )

                    ExifRepository.Read.UnsupportedFormat -> _state.value = EditState.Unsupported
                    is ExifRepository.Read.IoError -> _state.value = EditState.Error(read.message)
                }
            } catch (e: Exception) {
                if (loadedUri == uri) _state.value = EditState.Error(e.message ?: "读取图片失败")
            }
        }
    }

    /** 回到入口页（放弃当前编辑会话）。 */
    fun reset() {
        loadedUri = null
        loadedWriteUri = null
        autoOverwritePermissionRequested = false
        sessionCounter++
        _overwriteSupported.value = true
        _state.value = null
        _saveState.value = SaveState.Idle
    }

    // endregion

    // region field setters（仅 Ready 时改 edited；original 永不动）

    private inline fun edit(transform: (PhotoMetadata) -> PhotoMetadata) {
        val ready = _state.value as? EditState.Ready ?: return
        _state.value = ready.copy(edited = transform(ready.edited))
    }

    fun setTakenAt(dt: LocalDateTime) = edit { it.copy(takenAt = dt) }

    fun setModel(v: String) = edit { it.copy(model = v.ifBlank { null }) }

    fun setFNumber(v: Double) = edit { it.copy(fNumber = v) }

    fun setShutter(v: Double) = edit { it.copy(shutterSeconds = v) }

    fun setIso(v: Int) = edit { it.copy(iso = v) }

    fun setFocal(v: Double) = edit { it.copy(focalLengthMm = v) }

    fun setPlaceName(v: String) = edit { it.copy(placeName = v) }

    /** 只清地名不动坐标：地点面板在"坐标实际变化"时用它解除 [setGps] 的反查守卫（评审 #1）。 */
    fun clearPlaceName() = edit { it.copy(placeName = null) }

    fun clearGps() = edit { it.copy(gps = null, placeName = null) }

    fun setGps(lat: Double, lon: Double) {
        val ready = _state.value as? EditState.Ready ?: return
        if (GpsConvert.fromDecimalLatitude(lat) == null || GpsConvert.fromDecimalLongitude(lon) == null) {
            _events.tryEmit("经纬度超出范围（纬 ±90，经 ±180）")
            return
        }
        edit { it.copy(gps = GpsCoordinates(lat, lon, it.gps?.altitudeMeters)) }
        // spec §3.3：坐标选定后反查地名回显供确认；仅在用户尚未填写地名时覆盖，失败静默（离线不阻塞）
        if (ready.edited.placeName.isNullOrEmpty()) {
            viewModelScope.launch {
                val place = try {
                    geocoder.reverse(lat, lon)
                } catch (e: Exception) {
                    null
                } ?: return@launch
                // 反查在途期间坐标可能已被用户改动/清空：仅当 edited.gps 仍等于发起时坐标才回填
                edit {
                    if (it.gps?.latitude == lat && it.gps?.longitude == lon && it.placeName.isNullOrEmpty()) {
                        it.copy(placeName = place.displayName)
                    } else it
                }
            }
        }
    }

    // endregion

    // region 地点搜索（spec §3.3）

    /**
     * 地点搜索：委托 [GeocoderService.search]，UI 不直接持有 geocoder。
     * 空/全空白 query 不发请求；任何异常（离线、超时、解析失败）吞掉后返回空列表——
     * 由地点面板把"无结果"就地降级为"可改用手动经纬度"，不阻塞保存。
     *
     * 反查地名不在此列：[setGps] 已内建带会话守卫的反查回填，面板经
     * applySearchPick / applyCoords（ui.location）走坐标回写，不重复发 reverse。
     */
    suspend fun searchPlaces(query: String): List<GeoPlace> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            geocoder.search(trimmed)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * UI 侧一次性提示通道：只有 UI 才知道的失败（定位超时 / 权限被拒）走与非法输入
     * 相同的 [events] snackbar，避免为一条文案再开一条状态流。
     */
    fun reportIssue(msg: String) {
        _events.tryEmit(msg)
    }

    // endregion

    // region 最近地点（改动 3：只记搜索选中，手动/定位不记——用户拍板）

    /** init 时从存储读存量；无 store（测试/降级）恒空。 */
    private val _recentPlaces = MutableStateFlow(recentStore?.all() ?: emptyList())
    val recentPlaces: StateFlow<List<GeoPlace>> = _recentPlaces.asStateFlow()

    /** 记录一条搜索选中的地点并持久化；null store 时 no-op。 */
    fun recordRecentPlace(place: GeoPlace) {
        val store = recentStore ?: return
        _recentPlaces.value = store.record(place)
    }

    /** 清空最近地点（存储 + flow）；null store 时仅复位 flow（恒空，无副作用）。 */
    fun clearRecentPlaces() {
        recentStore?.clear()
        _recentPlaces.value = emptyList()
    }

    // endregion

    // region save

    fun saveAsCopy() {
        val ready = readyOrNull() ?: return
        val uri = loadedUri ?: return
        if (!startWorking()) return
        viewModelScope.launch {
            try {
                val written = withContext(ioDispatcher) { repo.write(ready.bytes, ready.original, ready.edited) }
                // Important#1：静默失败（write 原样返回输入字节）在**触达 writer 之前**拦截——
                // 绝不先 saveCopy 插入相册再事后校验报失败（那会留下未编辑的垃圾 `原名_副本.jpg`）。
                silentWriteFailure(ready, written)?.let {
                    setResultInSession(uri, SaveState.Failed(it))
                    return@launch
                }
                // Important#3：命名走**授权读 uri**（loadedUri）——它带 picker/分享读授权，
                // DISPLAY_NAME 查得到真名；writeUri 无读权限、file/第三方来源更查不到，
                // 故 canOverwrite=false 时副本名仍为 `原名_副本`（不回归数字 id）。
                val baseName = writer.displayNameOf(uri) ?: FALLBACK_NAME
                val newName = CopyNaming.next(baseName, writer.existingNames())
                // Live 内嵌型字节已含尾附视频（repo.write 已保动效）；双文件型按 spec 只产静态图
                when (val outcome = writer.saveCopy(written, newName, dateTakenMillisOf(ready.edited))) {
                    is SaveOutcome.Saved -> onSaved(uri, ready, written, outcome.uri)
                    SaveOutcome.NeedsPermission ->
                        setResultInSession(uri, SaveState.Failed("另存副本需要写入权限"))

                    is SaveOutcome.Failed -> setResultInSession(uri, SaveState.Failed(outcome.reason))
                }
            } catch (e: Exception) {
                setResultInSession(uri, SaveState.Failed(e.message ?: "保存副本失败"))
            }
        }
    }

    fun overwriteOriginal() {
        val ready = readyOrNull() ?: return
        val session = loadedUri ?: return
        // Task14a 缺陷 1：来源无可信规范 writeUri 时不发起，就地给文案
        if (!_overwriteSupported.value) {
            setResultInSession(session, SaveState.Failed(OVERWRITE_UNSUPPORTED_MSG))
            return
        }
        if (!startWorking()) return
        viewModelScope.launch {
            try {
                val written = withContext(ioDispatcher) { repo.write(ready.bytes, ready.original, ready.edited) }
                // Important#1（同一防线）：静默失败时在 openOutputStream 截断之前中止，
                // 不用原始字节做无意义的截断重写，也不给事后校验留马后炮。
                silentWriteFailure(ready, written)?.let {
                    setResultInSession(session, SaveState.Failed(it))
                    return@launch
                }
                // Critical#1：写只走规范 writeUri（原始 picker/分享授权 uri 覆盖必失败）
                when (val outcome = writer.overwrite(loadedWriteUri ?: session, written)) {
                    is SaveOutcome.Saved -> onSaved(session, ready, written, outcome.uri)
                    SaveOutcome.NeedsPermission -> onNeedsOverwritePermission(session)

                    is SaveOutcome.Failed -> setResultInSession(session, SaveState.Failed(outcome.reason))
                }
            } catch (e: Exception) {
                setResultInSession(session, SaveState.Failed(e.message ?: "覆盖原图失败"))
            }
        }
    }

    /**
     * 进入 NeedsOverwritePermission 的唯一入口（Important#4）：置态 + **确定性**发一次性
     * 授权请求事件（每会话至多一次，[autoOverwritePermissionRequested] 卡重入）。
     * UI 侧不再靠观测 Working→NOP 跃变（StateFlow conflation 可能漏弹）。
     */
    private fun onNeedsOverwritePermission(session: Uri) {
        setResultInSession(session, SaveState.NeedsOverwritePermission)
        if (loadedUri == session && _state.value is EditState.Ready && !autoOverwritePermissionRequested) {
            autoOverwritePermissionRequested = true
            _overwritePermissionRequests.tryEmit(Unit)
        }
    }

    /**
     * 覆盖原图的系统授权 PendingIntent（spec §3.5，委托 [MediaStoreWriter.createWriteIntentFor]）：
     * UI 层拿它发起 IntentSender 授权，结果 OK 后重试一次 [overwriteOriginal]。
     * 无会话（未 load / 已 reset）或无可信 writeUri（不可覆盖来源）时 null——
     * createWriteRequest 也必须用规范 media uri（Critical#1：授权对象与写入对象一致）。
     */
    suspend fun requestOverwritePermissionIntent(): PendingIntent? {
        if (loadedUri == null) return null
        val writeUri = loadedWriteUri ?: return null
        return writer.createWriteIntentFor(writeUri)
    }

    private fun readyOrNull(): EditState.Ready? = _state.value as? EditState.Ready

    /** Working 重入保护：已在保存中返回 false，调用方直接放弃本次触发。 */
    private fun startWorking(): Boolean {
        if (_saveState.value is SaveState.Working) return false
        _saveState.value = SaveState.Working
        return true
    }

    /** 会话是否仍是发起保存时的那个（未被 load/reset 切走且处于 Ready）。 */
    private fun sessionAlive(session: Uri): Boolean =
        loadedUri == session && _state.value is EditState.Ready

    /** 保存终态（Failed/NeedsOverwritePermission）只回写给仍在世的会话，迟到的旧结果丢弃。 */
    private fun setResultInSession(session: Uri, next: SaveState) {
        if (loadedUri == session && _state.value is EditState.Ready) _saveState.value = next
    }

    /**
     * 保存完成统一回写（评审 #1/#2/#3）：
     * - 校验通过 → 基于**当前** state copy：bytes = write 产物、original = 本轮快照的 edited
     *   （真正落到磁盘的基线），当前 edited 原样保留（保存窗口内的在途编辑不被吞掉）；
     * - 会话已切换 → 整体丢弃，不复活旧会话、不污染新会话 saveState。
     */
    private suspend fun onSaved(session: Uri, snapshot: EditState.Ready, written: ByteArray, savedUri: Uri) {
        if (!sessionAlive(session)) return
        val mismatch = withContext(ioDispatcher) { verifyFailure(snapshot, written) }
        if (mismatch != null) {
            setResultInSession(session, SaveState.Failed(mismatch))
            return
        }
        val current = _state.value as? EditState.Ready ?: return
        if (!sessionAlive(session)) return
        _state.value = current.copy(bytes = written, original = snapshot.edited)
        _saveState.value = SaveState.DoneSaved(savedUri)
    }

    /**
     * Important#1：write 静默失败的前置判定（在触达 writer 之前）。
     * 真 repo.write 的失败契约是"原样返回输入字节（同一引用）"——本轮确有字段变更而
     * written === 输入 bytes 即判失败：另存副本不 saveCopy（相册不留垃圾 `原名_副本`），
     * 覆盖不开输出流（不产生截断窗口）。onSaved 的重读校验仍保留同一判定作双保险。
     */
    private fun silentWriteFailure(snapshot: EditState.Ready, written: ByteArray): String? =
        if (written === snapshot.bytes &&
            changedFields(snapshot.original, snapshot.edited).isNotEmpty()
        ) {
            WRITE_SILENT_FAILURE_MSG
        } else {
            null
        }

    /**
     * 重读校验（评审 #3），两层：
     * 1) 廉价精确信号：write 静默失败（同一引用 + 本轮确有变更）——正常已被
     *    [silentWriteFailure] 前置拦截，这里是双保险；
     * 2) 字段级：按本轮 changedFields(original, edited) **逐项**验证 written 重读值命中
     *    edited（时间截秒、GPS 千分度容差、有理数字段 1e-3 容差），而非固定三字段子集——
     *    只改 ISO 时同样能发现 ISO 没落盘。
     */
    private fun verifyFailure(snapshot: EditState.Ready, written: ByteArray): String? {
        val expected = snapshot.edited
        val changed = changedFields(snapshot.original, expected)
        if (changed.isEmpty()) return null
        silentWriteFailure(snapshot, written)?.let { return it }
        return when (val read = repo.read(written)) {
            is ExifRepository.Read.Success -> mismatchOf(read.metadata, expected, changed)
            else -> "保存后校验失败：无法重读已保存文件"
        }
    }

    private fun mismatchOf(m: PhotoMetadata, expected: PhotoMetadata, changed: Set<MetadataField>): String? = when {
        MetadataField.TAKEN_AT in changed && trimToSeconds(m.takenAt) != trimToSeconds(expected.takenAt) ->
            mismatchMsg("拍摄时间")

        MetadataField.MODEL in changed && m.model != expected.model -> mismatchMsg("相机型号")
        MetadataField.GPS in changed && gpsMismatch(m.gps, expected.gps) -> mismatchMsg("GPS")
        MetadataField.F_NUMBER in changed && doubleMismatch(m.fNumber, expected.fNumber) -> mismatchMsg("光圈值")
        MetadataField.SHUTTER in changed && doubleMismatch(m.shutterSeconds, expected.shutterSeconds) -> mismatchMsg("快门时间")
        MetadataField.ISO in changed && m.iso != expected.iso -> mismatchMsg("ISO")
        MetadataField.FOCAL in changed && doubleMismatch(m.focalLengthMm, expected.focalLengthMm) -> mismatchMsg("焦距")
        else -> null
    }

    private fun mismatchMsg(field: String): String = "保存后校验失败：${field}未写入"

    /** GPS：有无必须一致，都有则千分度容差比对（Float + DMS 有理化逐位相等不可能）。 */
    private fun gpsMismatch(actual: GpsCoordinates?, expected: GpsCoordinates?): Boolean =
        (actual == null) != (expected == null) ||
            (actual != null && expected != null &&
                (abs(actual.latitude - expected.latitude) > GPS_TOLERANCE ||
                    abs(actual.longitude - expected.longitude) > GPS_TOLERANCE))

    /** 有理数派生字段（光圈/快门/焦距）：EXIF 有理化往返有损耗，用绝对容差比对。 */
    private fun doubleMismatch(actual: Double?, expected: Double?): Boolean =
        (actual == null) != (expected == null) ||
            (actual != null && expected != null && abs(actual - expected) > RATIONAL_TOLERANCE)

    // endregion

    companion object {
        private const val FALLBACK_NAME = "photo.jpg"

        /**
         * 不可覆盖来源的统一文案（评审 #7：唯一定义处，SaveSheet/VM 共用，不再两处字面量）。
         */
        internal const val OVERWRITE_UNSUPPORTED_MSG = "该来源无法覆盖原图，请用另存为副本"

        /** write 静默失败（返回输入同一引用）的前置拦截文案（Important#1）。 */
        internal const val WRITE_SILENT_FAILURE_MSG = "保存失败：写入返回原始字节（EXIF 未被修改），未改动相册"

        /** 有坐标但反查地名未回来时的统一展示文案（EditScreen 地点卡 / LocationSheet 头部共用，Minor④）。 */
        internal const val PENDING_PLACE_LABEL = "已设坐标·地名待获取"
        private const val GPS_TOLERANCE = 0.001 // ≈111 m，覆盖 Float 精度 + DMS 有理化损耗
        private const val RATIONAL_TOLERANCE = 0.001 // 覆盖 /10000 有理化与十进制往返损耗

        /** EXIF 秒字段无亚秒精度：比对前截断到秒 */
        private fun trimToSeconds(dt: LocalDateTime?): LocalDateTime? = dt?.truncatedTo(ChronoUnit.SECONDS)

        /** 生产组装：真 repo/writer/geocoder + 基于 ContentResolver 的 readBytes。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                EditViewModel(
                    repo = ExifRepository(),
                    writer = MediaStoreWriter(app),
                    // spec §3.3：搜索/反查 Photon 为主、Nominatim 兜底——fallback 必须装配，
                    // 否则 Photon 挂了地点功能双双空转（Task 14 评审 #3）。
                    // Nominatim 礼貌限流的 mutex 为 PhotonGeocoder 实例级：每台设备单用户单 VM 实例，
                    // 串行已足够，无需进程级全局闸门。
                    geocoder = PhotonGeocoder(OkHttpFetcher(), fallback = OkHttpFetcher()),
                    readBytes = { uri ->
                        app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IOException("无法打开图片输入流")
                    },
                    // 改动 3：最近地点持久化（SharedPreferences，见 data.RecentPlaceStore）
                    recentStore = RecentPlaceStore(SharedPreferencesStore(app)),
                )
            }
        }
    }
}

/** 拍摄时间 → MediaStore datetaken 毫秒；编辑后无时间时回退当前时刻（写列仅兜底，EXIF 才是权威）。 */
internal fun dateTakenMillisOf(metadata: PhotoMetadata): Long =
    (metadata.takenAt ?: LocalDateTime.now())
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
