# PhotoEdit 安卓照片元数据编辑器 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一个可安装的安卓 app：编辑相册 JPEG 的拍摄时间/地点/常用相机字段并写回系统相册（另存副本或覆盖原图），不破坏 Live 图。

**Architecture:** 单模块 Kotlin + Jetpack Compose + MVVM。三层：`domain`（纯 JVM 逻辑）、`data`（EXIF/MotionPhoto/Geocoder/MediaStore）、`ui`（Compose 屏幕 + ViewModel）。依赖方向 ui → domain ← data。

**Tech Stack:** Kotlin 2.1.0、AGP 8.7.x、Compose BOM 2024.12.01、androidx.exifinterface 1.3.7、OkHttp 4.12、kotlinx-serialization-json 1.7.3、Coil 2.7、JUnit4 + MockWebServer。

**Spec:** `docs/superpowers/specs/2026-09-26-photoedit-design.md`

## Global Constraints

- minSdk 26，compileSdk 35，targetSdk 35。
- 只处理 JPEG；非 JPEG 入口拦截。不做批量、不做地图选点、不改 Live 图 mp4 本身。
- 权限仅 `ACCESS_FINE_LOCATION`（懒申请）；不用 GMS 融合定位；网络全部 HTTPS；不新增第三方依赖（如需，先经用户批准）。
- UI 文案中文；iOS 风格 token：背景 `#F2F2F7`、卡片白、圆角 16dp、主色 `#007AFF`、危险色 `#FF3B30`。
- 副本命名 `原名_副本.jpg`，重名递增 `_副本2`、`_副本3`。
- 提交信息用 conventional commits（`feat:/test:/fix:/chore:`），英文描述。

## 文件结构

```
app/src/main/java/com/photoedit/app/
  MainActivity.kt                      # 入口 Activity：picker 结果、分享 intent、主题挂载
  domain/PhotoMetadata.kt              # 模型 + changedFields（Task 6）
  domain/GpsConvert.kt                 # 度分秒↔十进制 + 校验（Task 3）
  domain/ExifTime.kt                   # EXIF 时间字符串↔LocalDateTime（Task 4）
  domain/CopyNaming.kt                 # 副本命名递增（Task 5）
  data/MotionPhotoCodec.kt             # 内嵌型动态照片拆分/XMP偏移重建（Task 7）
  data/GeocoderService.kt              # 接口 + Photon/Nominatim 实现（Task 8）
  data/ExifRepository.kt               # EXIF 读/写字节流往返（Task 9）
  data/MediaStoreWriter.kt             # 另存副本/覆盖原图 + Live 配对（Task 10）
  ui/theme/Theme.kt                    # iOS 风格 Compose 主题（Task 11）
  ui/entry/EntryScreen.kt              # 大标题 + 选择照片（Task 11）
  ui/edit/EditViewModel.kt             # 状态机 + 保存编排（Task 12）
  ui/edit/EditScreen.kt                # 预览 + 三张卡片 + 保存悬浮钮（Task 13）
  ui/edit/SaveSheet.kt                 # 覆盖/另存选择 + 结果反馈（Task 13）
  ui/location/LocationSheet.kt         # 搜索/定位/手动经纬度（Task 14）
app/src/test/java/com/photoedit/app/…  # 镜像的 JVM 单元测试
app/src/androidTest/java/com/photoedit/app/…  # 仪器测试（Task 9/10/15）
```

---

### Task 1: 开发环境搭建（Windows）

**Files:** 无代码，产出可用命令行。

- [ ] **Step 1: 安装 Android Studio（按钮级）**
  1. 浏览器打开 `https://developer.android.com/studio` → 点 “Android Studio” 下载按钮 → 运行下载的 `android-studio-*.exe`。
  2. 安装向导全部 “Next”（默认即可），完成后勾选 “Finish”。
  3. 首次启动向导：选 “Standard” → 主题任意 → “Finish”，等待 SDK 组件下载完毕（右下角进度条走完）。
  4. 打开 “More Actions”（或菜单 Tools → SDK Manager）→ SDK Tools 标签 → 勾选 “Android SDK Platform-Tools”、“Android Emulator” → Apply。
- [ ] **Step 2: 验证 JDK 与 adb**
  在 Git Bash 运行：
  ```bash
  "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" version
  JAVA="$("/c/Program Files/Android/Android Studio/jbr/bin/java.exe" -version 2>&1)" && echo "$JAVA"
  ```
  预期：adb 版本 ≥ 35；java “version 17” 或 “21”。若路径不同（安装位置自定义），把实际路径记到本文件旁的 `ENV.md`。
- [ ] **Step 3: git 身份**（本仓库单独配置，不动全局）
  ```bash
  cd A:/project/project_all/photoedit && git config user.name "photoedit" && git config user.email "dev@photoedit.local"
  ```

### Task 2: 工程骨架 + 构建绿灯

**Files:**
- 生成: Android Studio New Project 向导产物（`app/`、`settings.gradle.kts`、`build.gradle.kts`、`gradle/`、`gradlew.bat`）
- Modify: `app/build.gradle.kts`（加依赖）、`app/src/main/AndroidManifest.xml`
- Create: `.gitignore`

**Interfaces:**
- Produces: 可 `gradlew.bat assembleDebug` 成功的空 Compose 工程；包名 `com.photoedit.app`。

- [ ] **Step 1: 用向导建工程**：Android Studio → New Project → “Empty Activity”（Compose 版）→ Name `PhotoEdit`、Package `com.photoedit.app`、Min SDK **API 26**、Build configuration language **Kotlin DSL** → Finish。工程目录选 `A:\project\project_all\photoedit`（若向导不允许直接在已有仓库目录创建，就建到子目录后把内容移到仓库根）。点 Sync，等待绿灯。
- [ ] **Step 2: `app/build.gradle.kts` 依赖块**（替换 dependencies，并加 `kotlin("plugin.serialization")`）

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    debugImplementation("androidx.test:core-ktx:1.6.1")
}
```
- [ ] **Step 3: Manifest 权限与分享入口**：`AndroidManifest.xml`  application 之前加 `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>` 与 `<uses-permission android:name="android.permission.INTERNET"/>`；`MainActivity` 的 `<activity>` 内加：

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="image/jpeg" />
    <data android:mimeType="image/jpg" />
</intent-filter>
```
- [ ] **Step 4: `.gitignore`**（Android Studio 模板即可，确认含 `.gradle/`、`build/`、`local.properties`、`.idea/`）
- [ ] **Step 5: 构建验证**

```bash
cd A:/project/project_all/photoedit && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug -q
```
预期：`BUILD SUCCESSFUL`。（若 JAVA_HOME 路径不同，用 Task 1 记录值。）
- [ ] **Step 6: Commit** `git add -A && git commit -m "chore: android compose scaffold with exif/okhttp/coil deps"`

### Task 3: domain — GpsConvert（度分秒↔十进制 + 校验）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/domain/GpsConvert.kt`
- Test: `app/src/test/java/com/photoedit/app/domain/GpsConvertTest.kt`

**Interfaces:**
- Produces: `GpsConvert.toDecimal(degrees:Int, minutes:Int, seconds:Double, ref:Char): Double?`；`GpsConvert.fromDecimalLatitude(v:Double): GpsConvert.Dms?`；`GpsConvert.fromDecimalLongitude(v:Double): GpsConvert.Dms?`；`data class Dms(degrees:Int, minutes:Int, seconds:Double, ref:Char)`。

- [ ] **Step 1: 写失败测试**

```kotlin
class GpsConvertTest {
    @Test fun shanghaiLatitudeRoundTrip() {
        val dms = GpsConvert.fromDecimalLatitude(31.2304)!!
        assertEquals('N', dms.ref)
        assertEquals(31.2304, GpsConvert.toDecimal(dms.degrees, dms.minutes, dms.seconds, dms.ref)!!, 1e-5)
    }
    @Test fun southLatitudeIsNegative() {
        val dms = GpsConvert.fromDecimalLatitude(-33.8688)!!
        assertEquals('S', dms.ref)
        assertEquals(33.8688, GpsConvert.toDecimal(dms.degrees, dms.minutes, dms.seconds, dms.ref)!!, 1e-5)
    }
    @Test fun invalidLatitudeRejected() { assertNull(GpsConvert.fromDecimalLatitude(91.0)) }
    @Test fun invalidLongitudeRejected() { assertNull(GpsConvert.fromDecimalLongitude(-200.0)) }
    @Test fun invalidRefReturnsNull() { assertNull(GpsConvert.toDecimal(1, 2, 3.0, 'X')) }
}
```
- [ ] **Step 2: 运行确认失败** `./gradlew.bat :app:testDebugUnitTest --tests "*GpsConvertTest" -q` → 编译失败（未定义）。
- [ ] **Step 3: 实现**

```kotlin
package com.photoedit.app.domain
import kotlin.math.abs
import kotlin.math.round

object GpsConvert {
    data class Dms(val degrees: Int, val minutes: Int, val seconds: Double, val ref: Char)

    fun toDecimal(degrees: Int, minutes: Int, seconds: Double, ref: Char): Double? {
        if (ref != 'N' && ref != 'S' && ref != 'E' && ref != 'W') return null
        if (degrees < 0 || minutes !in 0..59 || seconds !in 0.0..60.0) return null
        val abs = degrees + minutes / 60.0 + seconds / 3600.0
        return if (ref == 'S' || ref == 'W') -abs else abs
    }

    fun fromDecimalLatitude(value: Double): Dms? =
        if (value < -90.0 || value > 90.0) null else dms(value, 'N', 'S')

    fun fromDecimalLongitude(value: Double): Dms? =
        if (value < -180.0 || value > 180.0) null else dms(value, 'E', 'W')

    private fun dms(value: Double, pos: Char, neg: Char): Dms {
        val ref = if (value >= 0) pos else neg
        var a = abs(value)
        val d = a.toInt(); a = (a - d) * 60
        val m = a.toInt(); a = (a - m) * 60
        val s = round(a * 10000) / 10000.0
        return Dms(d, m, s, ref)
    }
}
```
- [ ] **Step 4: 运行确认通过**（同上命令）→ PASS。
- [ ] **Step 5: Commit** `git add app/src && git commit -m "feat: gps dms/decimal conversion with validation"`

### Task 4: domain — ExifTime（EXIF 时间字符串）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/domain/ExifTime.kt`
- Test: `app/src/test/java/com/photoedit/app/domain/ExifTimeTest.kt`

**Interfaces:**
- Produces: `ExifTime.format(dt: LocalDateTime): String`（`yyyy:MM:dd HH:mm:ss`）、`ExifTime.parse(s: String?): LocalDateTime?`、`ExifTime.offsetOf(zone: ZoneId): String`（`+08:00` 样式）。

- [ ] **Step 1: 失败测试**

```kotlin
class ExifTimeTest {
    @Test fun formatsWithExifColonYear() {
        assertEquals("2026:09:26 14:03:05",
            ExifTime.format(LocalDateTime.of(2026, 9, 26, 14, 3, 5)))
    }
    @Test fun parsesValidAndRejectsGarbage() {
        assertEquals(LocalDateTime.of(2026, 9, 26, 14, 3, 5),
            ExifTime.parse("2026:09:26 14:03:05"))
        assertNull(ExifTime.parse("2026-09-26T14:03:05")); assertNull(ExifTime.parse(null))
    }
    @Test fun eastOffset() {
        assertEquals("+08:00", ExifTime.offsetOf(ZoneId.of("Asia/Shanghai")))
    }
    @Test fun westOffset() {
        assertEquals("-05:00", ExifTime.offsetOf(ZoneId.of("America/New_York")))
    }
}
```
- [ ] **Step 2: 运行失败** `--tests "*ExifTimeTest"`。
- [ ] **Step 3: 实现**

```kotlin
package com.photoedit.app.domain
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object ExifTime {
    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    fun format(dt: LocalDateTime): String = dt.format(FMT)
    fun parse(s: String?): LocalDateTime? {
        if (s.isNullOrBlank()) return null
        return try { LocalDateTime.parse(s.trim(), FMT) } catch (e: Exception) { null }
    }
    fun offsetOf(zone: ZoneId = ZoneId.systemDefault()): String {
        val total = zone.rules.getOffset(Instant.now()).totalSeconds
        val sign = if (total < 0) "-" else "+"
        return "%s%02d:%02d".format(sign, abs(total) / 3600, abs(total) % 3600 / 60)
    }
}
```
- [ ] **Step 4: 运行通过** →  **Step 5: Commit** `feat: exif datetime format/parse and tz offset`

### Task 5: domain — CopyNaming（副本命名）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/domain/CopyNaming.kt`
- Test: `app/src/test/java/com/photoedit/app/domain/CopyNamingTest.kt`

**Interfaces:**
- Produces: `CopyNaming.next(originalName: String, takenNames: Set<String>): String`。

- [ ] **Step 1: 失败测试**

```kotlin
class CopyNamingTest {
    @Test fun firstCopy() {
        assertEquals("IMG_1234_副本.jpg", CopyNaming.next("IMG_1234.jpg", emptySet()))
    }
    @Test fun incrementsOnConflict() {
        val taken = setOf("IMG_1234_副本.jpg", "IMG_1234_副本2.jpg")
        assertEquals("IMG_1234_副本3.jpg", CopyNaming.next("IMG_1234.jpg", taken))
    }
    @Test fun noExtensionAppendsJpg() {
        assertEquals("IMG_9_副本.jpg", CopyNaming.next("IMG_9", emptySet()))
    }
}
```
- [ ] **Step 2: 失败** →  **Step 3: 实现**

```kotlin
package com.photoedit.app.domain

object CopyNaming {
    fun next(originalName: String, takenNames: Set<String>): String {
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ".jpg"
        var i = 1
        while (true) {
            val candidate = if (i == 1) "${base}_副本$ext" else "${base}_副本$i$ext"
            if (candidate !in takenNames) return candidate
            i++
        }
    }
}
```
- [ ] **Step 4: 通过** →  **Step 5: Commit** `feat: copy naming with conflict increment`

### Task 6: domain — PhotoMetadata 模型 + changedFields

**Files:**
- Create: `app/src/main/java/com/photoedit/app/domain/PhotoMetadata.kt`
- Test: `app/src/test/java/com/photoedit/app/domain/PhotoMetadataTest.kt`

**Interfaces:**
- Produces:
  - `data class GpsCoordinates(latitude: Double, longitude: Double, altitudeMeters: Double? = null)`
  - `data class PhotoMetadata(takenAt: LocalDateTime? = null, gps: GpsCoordinates? = null, placeName: String? = null, make: String? = null, model: String? = null, fNumber: Double? = null, shutterSeconds: Double? = null, iso: Int? = null, focalLengthMm: Double? = null, orientation: Int = 1)`
  - `enum class MetadataField { TAKEN_AT, GPS, MODEL, F_NUMBER, SHUTTER, ISO, FOCAL }`
  - `fun changedFields(old: PhotoMetadata, new: PhotoMetadata): Set<MetadataField>`
- Task 9/12 依赖这些精确名字。

- [ ] **Step 1: 失败测试**

```kotlin
class PhotoMetadataTest {
    @Test fun unchangedYieldsEmptySet() {
        val a = PhotoMetadata(takenAt = LocalDateTime.now(), gps = GpsCoordinates(1.0, 2.0))
        assertEquals(emptySet<MetadataField>(), changedFields(a, a.copy()))
    }
    @Test fun detectsTimeAndGpsChange() {
        val a = PhotoMetadata(takenAt = LocalDateTime.of(2020, 1, 1, 0, 0), gps = null)
        val b = a.copy(takenAt = LocalDateTime.of(2021, 1, 1, 0, 0), gps = GpsCoordinates(31.0, 121.0))
        assertEquals(setOf(MetadataField.TAKEN_AT, MetadataField.GPS), changedFields(a, b))
    }
    @Test fun gpsClearedIsAChange() {
        val a = PhotoMetadata(gps = GpsCoordinates(1.0, 2.0)); val b = a.copy(gps = null)
        assertEquals(setOf(MetadataField.GPS), changedFields(a, b))
    }
    @Test fun placeNameIsNotPersistedField() {
        val a = PhotoMetadata(placeName = "外滩"); assertEquals(emptySet<MetadataField>(), changedFields(a, a.copy(placeName = "陆家嘴")))
    }
}
```
- [ ] **Step 2: 失败** →  **Step 3: 实现**

```kotlin
package com.photoedit.app.domain
import java.time.LocalDateTime

data class GpsCoordinates(val latitude: Double, val longitude: Double, val altitudeMeters: Double? = null)

data class PhotoMetadata(
    val takenAt: LocalDateTime? = null,
    val gps: GpsCoordinates? = null,
    val placeName: String? = null,
    val make: String? = null,
    val model: String? = null,
    val fNumber: Double? = null,
    val shutterSeconds: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
    val orientation: Int = 1,
)

enum class MetadataField { TAKEN_AT, GPS, MODEL, F_NUMBER, SHUTTER, ISO, FOCAL }

fun changedFields(old: PhotoMetadata, new: PhotoMetadata): Set<MetadataField> = buildSet {
    if (old.takenAt != new.takenAt) add(MetadataField.TAKEN_AT)
    if (old.gps != new.gps) add(MetadataField.GPS)
    if (old.model != new.model) add(MetadataField.MODEL)
    if (old.fNumber != new.fNumber) add(MetadataField.F_NUMBER)
    if (old.shutterSeconds != new.shutterSeconds) add(MetadataField.SHUTTER)
    if (old.iso != new.iso) add(MetadataField.ISO)
    if (old.focalLengthMm != new.focalLengthMm) add(MetadataField.FOCAL)
}
```
- [ ] **Step 4: 通过** →  **Step 5: Commit** `feat: photo metadata domain model and field diff`

### Task 7: data — MotionPhotoCodec（内嵌动态照片）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/data/MotionPhotoCodec.kt`
- Test: `app/src/test/java/com/photoedit/app/data/MotionPhotoCodecTest.kt`

**Interfaces:**
- Produces:
  - `MotionPhotoCodec.split(jpeg: ByteArray): MotionPhotoCodec.Split?`，`data class Split(val photo: ByteArray, val video: ByteArray)`
  - `MotionPhotoCodec.rebuild(photo: ByteArray, video: ByteArray): ByteArray`（XMP 偏移量随新 photo 长度修正）
- 支持的声明方式：旧版 `GPhoto:MicroVideoOffset="<字节数>"`；新版 `<Item:Item ... Offset=".." Length=".." Id="MotionPhoto_Data"/>`。测试夹具用合成文件（假 JPEG 头 FFD8 + XMP APP1 文本块 + 尾部 `ftyp` 假视频）。

- [ ] **Step 1: 失败测试**（合成夹具函数 `fixture(xmp: String, videoLen: Int)` 生成合法 APP1）

```kotlin
class MotionPhotoCodecTest {
    private fun app1(payload: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xE1, 0, (payload.length + 3).toByte()) +
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + payload.toByteArray()

    private fun fixture(xmp: String, videoLen: Int): ByteArray {
        val video = ByteArray(videoLen).also { it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte() }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1(xmp) + video
    }

    @Test fun detectsViaMicroVideoOffset() {
        val f = fixture("<x:xmpmeta><GPhoto:MicroVideoOffset=\"8\"/></x:xmpmeta>", 8)
        val s = MotionPhotoCodec.split(f)!!
        assertEquals(8, s.video.size)
    }
    @Test fun detectsViaItemOffsetLength() {
        val xmp = "<Item:Item Offset=\"43\" Length=\"8\" Id=\"MotionPhoto_Data\"/>"
        val f = fixture(xmp, 8); val s = MotionPhotoCodec.split(f)!!
        assertEquals(8, s.video.size)
    }
    @Test fun plainJpegReturnsNull() {
        assertNull(MotionPhotoCodec.split(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)))
    }
    @Test fun rebuildFixesOffsetsAfterExifGrew() {
        val f = fixture("<GPhoto:MicroVideoOffset=\"8\"/>", 8)
        val s = MotionPhotoCodec.split(f)!!
        val grownPhoto = s.photo + byteArrayOf(0, 0, 0, 0) // 模拟 EXIF 变长 4 字节
        val out = MotionPhotoCodec.rebuild(grownPhoto, s.video)
        val resplit = MotionPhotoCodec.split(out)!!   // 修正后仍可识别
        assertContentEquals(s.video, resplit.video)
    }
}
```
- [ ] **Step 2: 失败** →  **Step 3: 实现**

```kotlin
package com.photoedit.app.data
import kotlin.text.RegexOption

object MotionPhotoCodec {
    data class Split(val photo: ByteArray, val video: ByteArray)
    private val MICRO = Regex("""MicroVideoOffset="(\d+)"""")
    private val ITEM = Regex("""Offset="(\d+)"\s+Length="(\d+)"\s+Id="MotionPhoto_Data"""")

    fun split(jpeg: ByteArray): Split? {
        val text = jpeg.toString(Charsets.ISO_8859_1)
        val videoLen = MICRO.find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?: ITEM.find(text)?.groupValues?.get(2)?.toLongOrNull()
            ?: return null
        if (videoLen <= 0 || videoLen > jpeg.size - 4) return null
        val start = jpeg.size - videoLen.toInt()
        val video = jpeg.copyOfRange(start, jpeg.size)
        if (!(video.size > 7 && video[4] == 'f'.code.toByte() && video[5] == 't'.code.toByte() &&
                video[6] == 'y'.code.toByte() && video[7] == 'p'.code.toByte())) return null
        return Split(jpeg.copyOfRange(0, start), video)
    }

    fun rebuild(photo: ByteArray, video: ByteArray): ByteArray {
        val newOffset = (photo.size + video.size - video.size).toLong() // video 紧跟 photo
        val text = String(photo, Charsets.ISO_8859_1)
        val fixed = text
            .replace(MICRO, "MicroVideoOffset=\"${video.size}\"")
            .replace(ITEM) { m -> "Offset=\"$newOffset\" Length=\"${video.size}\" Id=\"MotionPhoto_Data\"" }
        val rebuiltPhoto = fixed.toByteArray(Charsets.ISO_8859_1)
        val delta = rebuiltPhoto.size - photo.size
        val header = rebuiltPhoto.toMutableByteArray()
        if (delta != 0) { // 修正 APP1 段长度字段（测试夹具与真实 JPEG 均为 (0xFF,0xE1,lenHi,lenLo)）
            var i = 2
            while (i + 3 < header.size) {
                if (header[i] == 0xFF.toByte() && header[i + 1] == 0xE1.toByte()) {
                    val len = ((header[i + 2].toInt() and 0xFF) shl 8 or (header[i + 3].toInt() and 0xFF)) + delta
                    header[i + 2] = (len shr 8).toByte(); header[i + 3] = (len and 0xFF).toByte()
                    break
                }
                i++
            }
        }
        return header + video
    }
}
```
- [ ] **Step 4: 通过** →  **Step 5: Commit** `feat: motion photo split/rebuild with xmp offset fix`

### Task 8: data — GeocoderService（Photon 主 / Nominatim 兜底）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/data/GeocoderService.kt`
- Test: `app/src/test/java/com/photoedit/app/data/GeocoderServiceTest.kt`

**Interfaces:**
- Produces:
  - `data class GeoPlace(displayName: String, latitude: Double, longitude: Double)`
  - `interface GeocoderService { suspend fun search(query: String): List<GeoPlace>; suspend fun reverse(lat: Double, lon: Double): GeoPlace? }`
  - `interface HttpFetcher { fun get(url: String): String }`；`class OkHttpFetcher : HttpFetcher`
  - `class PhotonGeocoder(private val http: HttpFetcher, private val fallback: HttpFetcher? = null) : GeocoderService`

- [ ] **Step 1: 失败测试**（用 fake fetcher 喂 GeoJSON，不起真网络）

```kotlin
class GeocoderServiceTest {
    private val photonResult = """{"features":[{"properties":{"name":"外滩","city":"上海市","country":"中国"},"geometry":{"coordinates":[121.49,31.24]}}]}"""
    private val emptyResult = """{"features":[]}"""

    @Test fun parsesPhotonSearch() {
        val svc = PhotonGeocoder(fake(photonResult))
        val p = svc.search("外滩").single()
        assertEquals("外滩, 上海市", p.displayName); assertEquals(31.24, p.latitude, 1e-9); assertEquals(121.49, p.longitude, 1e-9)
    }
    @Test fun fallsBackToNominatimWhenPhotonEmpty() {
        val nominati = """[{"display_name":"外滩, 黄浦区, 上海市","lat":"31.24","lon":"121.49"}]"""
        val svc = PhotonGeocoder(fake(emptyResult), fake(nominati))
        assertEquals("外滩, 黄浦区, 上海市", svc.search("外滩").single().displayName)
    }
    @Test fun reverseReturnsNullOnEmpty() {
        assertNull(PhotonGeocoder(fake(emptyResult)).reverse(31.2, 121.5))
    }
    @Test fun httpErrorFallsBack() {
        val nominati = """[{"display_name":"X","lat":"1","lon":"2"}]"""
        val svc = PhotonGeocoder(throwing(), fake(nominati))
        assertEquals(1, svc.search("x").size)
    }
    private fun fake(body: String) = object : HttpFetcher { override fun get(url: String) = body }
    private fun throwing() = object : HttpFetcher { override fun get(url: String): String = throw java.io.IOException("net") }
}
```
- [ ] **Step 2: 失败** →  **Step 3: 实现**（Photon 与 Nominatim URL、GeoJSON/JSON 解析用 kotlinx.serialization；displayName 规则：`name, city` 回退 `display_name`；Nominatim 请求必须带 `User-Agent: PhotoEdit/1.0`，串行、每请求间隔 ≥1.1s 的 `Mutex + delay` 限流）
- [ ] **Step 4: 通过** →  **Step 5: Commit** `feat: photon geocoder with nominatim fallback`

### Task 9: data — ExifRepository（读写往返，仪器测试）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/data/ExifRepository.kt`
- Test: `app/src/androidTest/java/com/photoedit/app/data/ExifRepositoryTest.kt`

**Interfaces:**
- Consumes: `PhotoMetadata`、`changedFields`、`GpsConvert`、`ExifTime`、`MotionPhotoCodec`（Task 3-7 全部）。
- Produces:
  - `class ExifRepository { sealed interface Read { data class Success(val bytes: ByteArray, val metadata: PhotoMetadata, val isMotionPhoto: Boolean) : Read; data object UnsupportedFormat : Read; data class IoError(val message: String) : Read }; fun read(bytes: ByteArray): Read; fun write(bytes: ByteArray, original: PhotoMetadata, edited: PhotoMetadata): ByteArray }`

- [ ] **Step 1: 仪器测试（读→改→写→重读断言）**：用运行时生成的 JPEG（`Bitmap.compress`）为夹具；断言：改时间后 `DateTimeOriginal` 更新；`Orientation` 不变；GPS 设置后 `getLatLong` 一致；GPS 清除后 `getLatLong` 返回 false；未改字段原样。
- [ ] **Step 2: 运行失败**（设备/模拟器上 `./gradlew.bat connectedDebugAndroidTest`）
- [ ] **Step 3: 实现**：`read` 先查 SOI 魔数 `FF D8`，非 JPEG → UnsupportedFormat；`ExifInterface(ByteArrayInputStream)` 读标签映射到 PhotoMetadata（GPS 用 `getLatLong(FloatArray)`，时间 `ExifTime.parse`）；`write` 复制字节、按 `changedFields` 精确 setAttribute/deleteAttribute（GPS 清除删除全部 8 个 GPS 标签），`saveAttributes(OutputStream)`；末尾若 `MotionPhotoCodec.split` 非空则先拆视频、写 EXIF、`rebuild`。
- [ ] **Step 4: connectedDebugAndroidTest 全绿** →  **Step 5: Commit** `feat: exif repository read/write round-trip`

### Task 10: data — MediaStoreWriter（副本/覆盖/Live 配对）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/data/MediaStoreWriter.kt`
- Test: `app/src/androidTest/java/com/photoedit/app/data/MediaStoreWriterTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface SaveOutcome { data class Saved(val uri: Uri) : SaveOutcome; data object NeedsPermission : SaveOutcome; data class Failed(val reason: String) : SaveOutcome }`
  - `class MediaStoreWriter(private val context: Context) { suspend fun displayNameOf(uri: Uri): String?; suspend fun existingNames(): Set<String>; suspend fun saveCopy(bytes: ByteArray, newName: String, dateTakenMillis: Long): SaveOutcome; suspend fun overwrite(uri: Uri, bytes: ByteArray): SaveOutcome; suspend fun createWriteIntentFor(uri: Uri): android.app.PendingIntent?; suspend fun findPairedVideoUri(uri: Uri): Uri?; suspend fun copyPairedVideo(videoUri: Uri, newBaseName: String): SaveOutcome }`
- `overwrite` 在 app 不拥有该条目时返回 NeedsPermission（调用方 launch `createWriteIntentFor` 后重试一次）。

- [ ] **Step 1: 仪器测试**：`saveCopy` 插入后 MediaStore 可查到、`DATE_TAKEN` 正确、`IS_PENDING` 归零；`overwrite` 对自建文件直接成功、对外部文件返回 NeedsPermission；`findPairedVideoUri` 对同名 mp4 夹具返回配对。
- [ ] **Step 2: 失败** →  **Step 3: 实现**（`ContentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues{DISPLAY_NAME, RELATIVE_PATH="Pictures/", MIME_TYPE, DATE_TAKEN})` + 写流；`existingNames` 用 `query(DISPLAY_NAME)`；`createWriteRequest` 返回 `IntentSender`→PendingIntent）
- [ ] **Step 4: 通过** →  **Step 5: Commit** `feat: mediastore copy/overwrite with write-request flow`

### Task 11: ui — 主题 + 入口页 + Photo Picker + 分享接收

**Files:**
- Create: `app/src/main/java/com/photoedit/app/ui/theme/Theme.kt`、`ui/entry/EntryScreen.kt`
- Modify: `MainActivity.kt`
- Test: 手动（无 UI 单测框架；JVM 不测 Composable）

**Interfaces:**
- Produces: `MainActivity` 暴露 `currentUri: Uri`（picker 结果或 `EXTRA_STREAM`），导航到 `EditScreen`（Task 13 前暂显示占位文本）。

- [ ] **Step 1: Theme**：iOS token（见 Global Constraints）——`MaterialTheme` 包装：colorScheme(primary=0xFF007AFF, background=0xFFF2F2F7, surface=白)，字体 scale 加粗大标题，提供 `Modifier.iosCard()`（白底、16dp 圆角、elevation 0、1dp 分隔）。
- [ ] **Step 2: EntryScreen**：居中 `Text("PhotoEdit", largeTitle)` + “选择照片”主按钮（胶囊、primary 底白字）→ `ActivityResultContracts.PickVisualMedia()`。
- [ ] **Step 3: 分享接收**：`MainActivity.onCreate` 检查 `ACTION_SEND` + `EXTRA_STREAM`，非空则直接进编辑；`SEND_MULTIPLE` 弹 Toast “暂支持单张”。
- [ ] **Step 4: 真机/模拟器冒烟**：从相册分享一张 JPEG 能进占位页；picker 能选图。
- [ ] **Step 5: Commit** `feat: ios-style theme, entry screen, picker and share intake`

### Task 12: ui — EditViewModel（状态机与保存编排，JVM 测试）

**Files:**
- Create: `app/src/main/java/com/photoedit/app/ui/edit/EditViewModel.kt`
- Test: `app/src/test/java/com/photoedit/app/ui/edit/EditViewModelTest.kt`

**Interfaces:**
- Consumes: `ExifRepository.Read/Write`、`MediaStoreWriter.SaveOutcome`、`GeocoderService`（构造注入，测试用 fake）。
- Produces:
  - `sealed interface EditState { data object Loading; data object Unsupported; data class Error(msg:String); data class Ready(bytes, original, edited, isMotionPhoto) }`
  - `sealed interface SaveState { data object Idle; data object Working; data class DoneSaved(uri); data class NeedsOverwritePermission; data class Failed(reason) }`
  - `class EditViewModel(repo, writer, geocoder) { val state: StateFlow<EditState>; val saveState: StateFlow<SaveState>; fun load(uri: Uri); fun setTakenAt(dt: LocalDateTime); fun setGps(lat: Double, lon: Double); fun clearGps(); fun setModel(v: String); fun setFNumber/Shutter/Iso/Focal(v); fun setPlaceName(v: String); fun saveAsCopy(); fun overwriteOriginal() }`

- [ ] **Step 1: 失败测试**：fake repo/writer；断言：load Unsupported bytes → Unsupported；setGps 非法坐标 → state 不变且 error 事件；saveAsCopy 成功后 saveState=DoneSaved 且调用了 `CopyNaming.next(existingNames)`；NeedsPermission 结果 → `SaveState.NeedsOverwritePermission`；编辑不影响 original。
- [ ] **Step 2: 失败** →  **Step 3: 实现**（`Ready.bytes` 只在 load 时填充；保存走 `viewModelScope`，Live 图配对复制在 saveCopy 内联；错误路径遵循 spec §4）
- [ ] **Step 4: 通过** →  **Step 5: Commit** `feat: edit viewmodel state machine and save orchestration`

### Task 13: ui — 编辑页三卡片 + 保存面板

**Files:**
- Create: `ui/edit/EditScreen.kt`、`ui/edit/SaveSheet.kt`
- Test: 手动冒烟 + Task 15 验收清单

- [ ] **Step 1: EditScreen**：LazyColumn：预览卡（Coil 加载 uri，`orientation` 正确旋转）、时间卡（点击弹 `DatePickerDialog`/`TimePickerDialog`，Material3 组件套 iOS 卡样式）、地点卡（显示 `edited.placeName ?: "未设置"`，点击弹 LocationSheet — Task 14 前占位）、相机信息卡（Model 文本框；光圈/快门/ISO/焦距 数字框，快门以 `1/x` 显示）、底部悬浮“保存”（磨砂 `graphicsLayer` blur 背景）。
- [ ] **Step 2: SaveSheet**：`ModalBottomSheet` 两行：“另存为副本”（默认，primary 色 + 副文案说明原图不变）、“覆盖原图”（副文案“需要系统确认”）；isMotionPhoto 时顶部显示黄条“动态照片：覆盖与另存均保留动效”；SaveState 反馈（snackbar：成功“已保存”/ 被拒“授权被拒绝，可改为另存副本”按钮 / 失败原因）。
- [ ] **Step 3: 冒烟**：改时间→另存副本 → 相册新图按新时间归位；覆盖路径走完系统弹窗。
- [ ] **Step 4: Commit** `feat: edit screen cards and save bottom sheet`

### Task 14: ui — 地点面板（搜索/定位/手动）

**Files:**
- Create: `ui/location/LocationSheet.kt`
- Modify: `MainActivity.kt`（定位权限 launcher）

- [ ] **Step 1: LocationSheet**：Tab 三段——搜索（输入框 + Photon 结果列表，点击即 `setGps` + 异步反查 placeName 回显）、当前定位（点击申请 `ACCESS_FINE_LOCATION`，`LocationManager.getLastKnownLocation + requestSingleUpdate` GPS/network 双 provider，5s 超时提示）、手动（两个数字输入 + 校验错误内联提示 `纬 ±90 / 经 ±180`）；“清除地点”按钮。
- [ ] **Step 2: 无网络时**：搜索/反查区置灰 + 说明文案，手动仍可用（对应 spec §3.3）。
- [ ] **Step 3: 冒烟**：搜索“外滩”保存 → 相册详情显示地点；定位按钮首次弹权限。
- [ ] **Step 4: Commit** `feat: location sheet with search, current-location and manual input`

### Task 15: 集成验收 + 文档

**Files:**
- Modify: `README.md`（创建：功能、构建、验收清单）
- Test: `app/src/androidTest/java/com/photoedit/app/EndToEndTest.kt`

- [ ] **Step 1: 端到端仪器测试**：生成 JPEG → `ExifRepository.read` → 改时间+GPS → `write` → `MediaStoreWriter.saveCopy` → 从写回 Uri `read` 断言元数据一致（含 Orientation）。
- [ ] **Step 2: 跑全量** `./gradlew.bat testDebugUnitTest connectedDebugAndroidTest -q` 全绿。
- [ ] **Step 3: 用户真机验收**：按 spec 附录 A 清单逐项过（真机 adb 安装 `adb install app/build/outputs/apk/debug/app-debug.apk`，每步给出按钮级说明）。任何一项不过 → 记 issue 回相应 Task 修复。
- [ ] **Step 4: README + commit** `docs: readme and end-to-end test`

---

## 自检记录（写计划后）

- Spec 覆盖：§3.1→T2/T11，§3.2→T3-T6/T9，§3.3→T8/T14，§3.4→T7/T9/T10，§3.5→T5/T10/T12/T13，§4→T12/T13，§5→T3-T10 各自 TDD + T15；附录 A→T15 Step 3。无遗漏。
- 类型一致性：`PhotoMetadata/changedFields/SaveOutcome/GeoPlace` 签名跨任务一致。
- 风险标注：T7 真实厂商文件兼容、T10 覆盖授权细节是仅有的两处需真机验证点，均落在 T15 验收。
