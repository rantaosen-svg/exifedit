package com.photoedit.app.ui.edit

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.photoedit.app.data.ExifRepository
import com.photoedit.app.data.GeocoderService
import com.photoedit.app.data.MediaStoreWriter
import com.photoedit.app.data.OkHttpFetcher
import com.photoedit.app.data.PhotonGeocoder
import com.photoedit.app.data.SaveOutcome
import com.photoedit.app.domain.CopyNaming
import com.photoedit.app.domain.GpsConvert
import com.photoedit.app.domain.GpsCoordinates
import com.photoedit.app.domain.PhotoMetadata
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * - [load] 幂等：同一 uri 处于 Loading 时重复调用直接忽略（防重建期重放）。
 * - 非法输入（如越界经纬度）不回写状态，只经 [events] 发一条错误提示（选型：
 *   MutableSharedFlow(extraBufferCapacity=8, DROP_OLDEST)，无 replay，避免重建后重放旧提示）。
 * - 保存成功后 repo.write 的产物经重读校验（repo.read 断言关键字段命中 edited），
 *   兜底 ExifRepository"静默返回原字节"的失败路径；校验通过后 original 基线前移到 edited，
 *   支持连续编辑再存。
 */
class EditViewModel(
    private val repo: ExifRepository,
    private val writer: MediaStoreWriter,
    private val geocoder: GeocoderService,
    private val readBytes: (Uri) -> ByteArray,
) : ViewModel() {

    /** null = 尚未选图（入口页）；Loading/Unsupported/Error/Ready = 编辑页（Task 11 评审 A）。 */
    private val _state = MutableStateFlow<EditState?>(null)
    val state: StateFlow<EditState?> = _state.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    /** 一次性错误提示事件（非法经纬度等）；无重放，晚订阅者不会收到陈旧提示。 */
    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var loadedUri: Uri? = null

    // region load

    fun load(uri: Uri) {
        // 幂等：同一 uri 正在加载时忽略（onCreate/onNewIntent 重放、旋转双触发均安全）
        if (loadedUri == uri && _state.value is EditState.Loading) return
        loadedUri = uri
        _state.value = EditState.Loading
        _saveState.value = SaveState.Idle
        try {
            val bytes = readBytes(uri)
            when (val read = repo.read(bytes)) {
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
            _state.value = EditState.Error(e.message ?: "读取图片失败")
        }
    }

    /** 回到入口页（放弃当前编辑会话）。 */
    fun reset() {
        loadedUri = null
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

    fun setModel(v: String) = edit { it.copy(model = v) }

    fun setFNumber(v: Double) = edit { it.copy(fNumber = v) }

    fun setShutter(v: Double) = edit { it.copy(shutterSeconds = v) }

    fun setIso(v: Int) = edit { it.copy(iso = v) }

    fun setFocal(v: Double) = edit { it.copy(focalLengthMm = v) }

    fun setPlaceName(v: String) = edit { it.copy(placeName = v) }

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
                edit { if (it.placeName.isNullOrEmpty()) it.copy(placeName = place.displayName) else it }
            }
        }
    }

    // endregion

    // region save

    fun saveAsCopy() {
        val ready = readyOrNull() ?: return
        val uri = loadedUri ?: return
        if (!startWorking()) return
        viewModelScope.launch {
            try {
                val written = repo.write(ready.bytes, ready.original, ready.edited)
                val baseName = writer.displayNameOf(uri) ?: FALLBACK_NAME
                val newName = CopyNaming.next(baseName, writer.existingNames())
                // Live 内嵌型字节已含尾附视频（repo.write 已保动效）；双文件型按 spec 只产静态图
                when (val outcome = writer.saveCopy(written, newName, dateTakenMillisOf(ready.edited))) {
                    is SaveOutcome.Saved -> onSaved(ready, written, outcome.uri)
                    SaveOutcome.NeedsPermission -> fail("另存副本需要写入权限")
                    is SaveOutcome.Failed -> fail(outcome.reason)
                }
            } catch (e: Exception) {
                fail(e.message ?: "保存副本失败")
            }
        }
    }

    fun overwriteOriginal() {
        val ready = readyOrNull() ?: return
        val uri = loadedUri ?: return
        if (!startWorking()) return
        viewModelScope.launch {
            try {
                val written = repo.write(ready.bytes, ready.original, ready.edited)
                when (val outcome = writer.overwrite(uri, written)) {
                    is SaveOutcome.Saved -> onSaved(ready, written, outcome.uri)
                    SaveOutcome.NeedsPermission -> _saveState.value = SaveState.NeedsOverwritePermission
                    is SaveOutcome.Failed -> fail(outcome.reason)
                }
            } catch (e: Exception) {
                fail(e.message ?: "覆盖原图失败")
            }
        }
    }

    private fun readyOrNull(): EditState.Ready? = _state.value as? EditState.Ready

    /** Working 重入保护：已在保存中返回 false，调用方直接放弃本次触发。 */
    private fun startWorking(): Boolean {
        if (_saveState.value is SaveState.Working) return false
        _saveState.value = SaveState.Working
        return true
    }

    /** 重读校验通过 → DoneSaved 并把 original 基线前移到 edited（连续编辑）；不通过 → Failed。 */
    private fun onSaved(ready: EditState.Ready, written: ByteArray, savedUri: Uri) {
        verifyFailure(written, ready.edited)?.let { fail(it); return }
        _state.value = ready.copy(original = ready.edited)
        _saveState.value = SaveState.DoneSaved(savedUri)
    }

    private fun fail(reason: String) {
        _saveState.value = SaveState.Failed(reason)
    }

    /**
     * 兜底 repo.write 的"写失败原样返回输入字节"静默路径（Task 9/10 报告建议）。
     * 只校验对精度不敏感的权威字段：时间/型号精确比对，GPS 比"有无 + 千分度容差"
     * （ExifInterface 读回经纬度走 Float + DMS 有理化，逐位相等不可能）。
     */
    private fun verifyFailure(written: ByteArray, expected: PhotoMetadata): String? =
        when (val read = repo.read(written)) {
            is ExifRepository.Read.Success -> {
                val m = read.metadata
                val gpsMismatch = (m.gps == null) != (expected.gps == null) ||
                    (m.gps != null && expected.gps != null &&
                        (abs(m.gps.latitude - expected.gps.latitude) > GPS_TOLERANCE ||
                            abs(m.gps.longitude - expected.gps.longitude) > GPS_TOLERANCE))
                when {
                    trimToSeconds(m.takenAt) != trimToSeconds(expected.takenAt) ->
                        "保存后校验失败：拍摄时间未写入"

                    m.model != expected.model -> "保存后校验失败：相机型号未写入"
                    gpsMismatch -> "保存后校验失败：GPS 未写入"
                    else -> null
                }
            }

            else -> "保存后校验失败：无法重读已保存文件"
        }

    // endregion

    companion object {
        private const val FALLBACK_NAME = "photo.jpg"
        private const val GPS_TOLERANCE = 0.001 // ≈111 m，覆盖 Float 精度 + DMS 有理化损耗

        /** EXIF 秒字段无亚秒精度：比对前截断到秒 */
        private fun trimToSeconds(dt: LocalDateTime?): LocalDateTime? = dt?.truncatedTo(ChronoUnit.SECONDS)

        /** 生产组装：真 repo/writer/geocoder + 基于 ContentResolver 的 readBytes。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                EditViewModel(
                    repo = ExifRepository(),
                    writer = MediaStoreWriter(app),
                    geocoder = PhotonGeocoder(OkHttpFetcher()),
                    readBytes = { uri ->
                        app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IOException("无法打开图片输入流")
                    },
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
