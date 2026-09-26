package com.photoedit.app.data

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一个地理候选点：展示名 + WGS84 坐标。 */
data class GeoPlace(val displayName: String, val latitude: Double, val longitude: Double)

interface GeocoderService {
    suspend fun search(query: String): List<GeoPlace>
    suspend fun reverse(lat: Double, lon: Double): GeoPlace?
}

/** 同步 HTTP GET，返回响应体文本；失败（非 2xx / 网络异常）抛任意异常，由上层兜底。 */
interface HttpFetcher {
    fun get(url: String): String
}

/** 真实现：同步 GET + 10s 超时。Nominatim 礼貌策略要求的 User-Agent 对全部请求统一携带。 */
class OkHttpFetcher : HttpFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    companion object {
        const val USER_AGENT = "PhotoEdit/1.0"
    }
}

/** 可注入的睡眠抽象：测试里注入 no-op，避免 Nominatim 限流真的睡 1.1s。 */
fun interface Sleeper {
    suspend fun sleep(millis: Long)
}

/**
 * Photon (komoot) 为主的地理编码，Nominatim 兜底。
 * - 搜索：Photon `/api/?lang=zh&q=..`，GeoJSON FeatureCollection；空结果或任何异常 → Nominatim `search`（jsonv2）。
 * - 反查：Photon `/reverse`，同样空/异常 → Nominatim `reverse`；仍失败返回 null。
 * - Nominatim 礼貌策略：Mutex 串行 + 相邻两次请求间隔 ≥1.1s（首次立即发）；UA 由 [OkHttpFetcher] 携带。
 * - [fallback] 为 null 时不发 Nominatim 请求。
 */
class PhotonGeocoder(
    private val http: HttpFetcher,
    private val fallback: HttpFetcher? = null,
    private val nominatimSleeper: Sleeper = Sleeper { delay(it) },
) : GeocoderService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nominatimMutex = Mutex()
    private var nominatimCallsIssued = 0

    override suspend fun search(query: String): List<GeoPlace> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val photon = runCatching { parsePhotonFeatureCollection(http.get(photonSearchUrl(q))) }.getOrDefault(emptyList())
        if (photon.isNotEmpty()) return@withContext photon
        val body = politeNominatimGet(nominatimSearchUrl(q)) ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<NominatimHit>>(body).mapNotNull { it.toPlace() }
        }.getOrDefault(emptyList())
    }

    override suspend fun reverse(lat: Double, lon: Double): GeoPlace? = withContext(Dispatchers.IO) {
        val photon = runCatching {
            parsePhotonFeatureCollection(http.get(photonReverseUrl(lat, lon))).firstOrNull()
        }.getOrNull()
        if (photon != null) return@withContext photon
        val body = politeNominatimGet(nominatimReverseUrl(lat, lon)) ?: return@withContext null
        runCatching { json.decodeFromString<NominatimHit>(body).toPlace() }.getOrNull()
    }

    /** 串行 + 限流地访问 Nominatim；未配置 fallback 或请求失败返回 null。 */
    private suspend fun politeNominatimGet(url: String): String? = nominatimMutex.withLock {
        val fetcher = fallback ?: return@withLock null
        if (nominatimCallsIssued++ > 0) nominatimSleeper.sleep(NOMINATIM_MIN_INTERVAL_MS)
        runCatching { fetcher.get(url) }.getOrNull()
    }

    // ---- Photon GeoJSON ----

    @Serializable
    internal data class PhotonCollection(val features: List<PhotonFeature> = emptyList())

    @Serializable
    internal data class PhotonFeature(
        val properties: PhotonProperties? = null,
        val geometry: PhotonGeometry? = null,
    )

    @Serializable
    internal data class PhotonProperties(
        val name: String? = null,
        val street: String? = null,
        val housenumber: String? = null,
        val city: String? = null,
        val state: String? = null,
        val country: String? = null,
    )

    @Serializable
    internal data class PhotonGeometry(val coordinates: List<Double> = emptyList())

    private fun parsePhotonFeatureCollection(body: String): List<GeoPlace> =
        json.decodeFromString<PhotonCollection>(body).features.mapNotNull { feature ->
            val coords = feature.geometry?.coordinates
            val display = feature.properties?.displayName()
            if (coords == null || coords.size < 2 || display == null) return@mapNotNull null
            GeoPlace(display, coords[1], coords[0]) // GeoJSON: [lon, lat]
        }

    /**
     * displayName 规则：Photon 取 `name`，拼接存在的 `city`/`state`（country 不进展示名，
     * 见测试断言 "外滩, 上海市"）；`name` 缺失时回退 `housenumber street`；再缺失则丢弃该 feature。
     */
    private fun PhotonProperties.displayName(): String? {
        val streetLine = listOfNotNull(housenumber?.trim()?.takeIf { it.isNotEmpty() }, street?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ")
        val base = name?.trim()?.takeIf { it.isNotEmpty() } ?: streetLine.takeIf { it.isNotEmpty() } ?: return null
        val context = listOfNotNull(city?.trim()?.takeIf { it.isNotEmpty() }, state?.trim()?.takeIf { it.isNotEmpty() })
            .filter { it != base }
        return (listOf(base) + context).distinct().joinToString(", ")
    }

    // ---- Nominatim (jsonv2) ----

    @Serializable
    internal data class NominatimHit(
        @SerialName("display_name") val displayName: String = "",
        val lat: String = "",
        val lon: String = "",
    ) {
        fun toPlace(): GeoPlace? {
            val latitude = lat.toDoubleOrNull() ?: return null
            val longitude = lon.toDoubleOrNull() ?: return null
            val name = displayName.trim().takeIf { it.isNotEmpty() } ?: return null
            return GeoPlace(name, latitude, longitude)
        }
    }

    companion object {
        internal fun photonSearchUrl(q: String) = "https://photon.komoot.io/api/?lang=zh&q=$q"
        internal fun photonReverseUrl(lat: Double, lon: Double) =
            "https://photon.komoot.io/reverse?lat=$lat&lon=$lon&lang=zh"
        internal fun nominatimSearchUrl(q: String) =
            "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&accept-language=zh&limit=8"
        internal fun nominatimReverseUrl(lat: Double, lon: Double) =
            "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=jsonv2&accept-language=zh"
        const val NOMINATIM_MIN_INTERVAL_MS = 1100L
    }
}
