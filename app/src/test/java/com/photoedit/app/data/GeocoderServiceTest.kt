package com.photoedit.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 简报测试块逐字落地；仅在调用 suspend 方法处包 runBlocking（JUnit4 测试方法不能是 suspend）。
class GeocoderServiceTest {
    private val photonResult = """{"features":[{"properties":{"name":"外滩","city":"上海市","country":"中国"},"geometry":{"coordinates":[121.49,31.24]}}]}"""
    private val emptyResult = """{"features":[]}"""

    @Test fun parsesPhotonSearch() = runBlocking {
        val svc = PhotonGeocoder(fake(photonResult))
        val p = svc.search("外滩").single()
        assertEquals("外滩, 上海市", p.displayName); assertEquals(31.24, p.latitude, 1e-9); assertEquals(121.49, p.longitude, 1e-9)
    }
    @Test fun fallsBackToNominatimWhenPhotonEmpty() = runBlocking {
        val nominati = """[{"display_name":"外滩, 黄浦区, 上海市","lat":"31.24","lon":"121.49"}]"""
        val svc = PhotonGeocoder(fake(emptyResult), fake(nominati))
        assertEquals("外滩, 黄浦区, 上海市", svc.search("外滩").single().displayName)
    }
    @Test fun reverseReturnsNullOnEmpty() = runBlocking {
        assertNull(PhotonGeocoder(fake(emptyResult)).reverse(31.2, 121.5))
    }
    @Test fun httpErrorFallsBack() = runBlocking {
        val nominati = """[{"display_name":"X","lat":"1","lon":"2"}]"""
        val svc = PhotonGeocoder(throwing(), fake(nominati))
        assertEquals(1, svc.search("x").size)
    }

    /**
     * Task 14 冒烟回归：Photon 只认 default/de/en/fr，`lang=zh` 直接 HTTP 400
     * （真实请求实测），搜索与反查会双双失效 → 断言 URL 不带 zh。
     */
    @Test fun photonUrlsDoNotUseUnsupportedZhLang() = runBlocking {
        val urls = mutableListOf<String>()
        val svc = PhotonGeocoder(recording(emptyResult, urls))
        svc.search("外滩")
        svc.reverse(31.2, 121.5)
        assertEquals(2, urls.size)
        urls.forEach { url ->
            org.junit.Assert.assertTrue("Photon URL 不应带 lang=zh: $url", !url.contains("lang=zh"))
            org.junit.Assert.assertTrue("Photon URL 应带受支持的 lang: $url", url.contains("lang=default"))
        }
        org.junit.Assert.assertTrue(urls[0].startsWith("https://photon.komoot.io/api/"))
        org.junit.Assert.assertTrue(urls[1].startsWith("https://photon.komoot.io/reverse?"))
    }

    private fun fake(body: String) = object : HttpFetcher { override fun get(url: String) = body }
    private fun throwing() = object : HttpFetcher { override fun get(url: String): String = throw java.io.IOException("net") }
    private fun recording(body: String, sink: MutableList<String>) = object : HttpFetcher {
        override fun get(url: String): String {
            sink += url
            return body
        }
    }
}
