# PhotoEdit

一款单功能的安卓相册 EXIF 编辑器：修改照片的拍摄时间、拍摄地点与常用相机参数，保存回系统相册后，各厂商相册（realme / 荣耀 / 小米 / 三星等）能正确显示新的时间与地点。界面走苹果风格（分组圆角卡片、大标题、克制配色）。

## 功能

- **改拍摄时间**：`DateTimeOriginal` + `OffsetTimeOriginal`，日期 + 时间选择器。
- **改拍摄地点**：三选一 —— 搜索地名（Photon 主 / Nominatim 兜底）、用当前定位（系统 LocationManager，不依赖谷歌服务）、手动输入经纬度（纬 ±90 / 经 ±180 校验）；支持"清除地点"（整棵 GPS IFD 物理删除）。
- **改常用相机字段**：设备型号（显示 Make、可改 Model）、光圈 FNumber、快门 ExposureTime、ISO、焦距 FocalLength；未修改字段（含 Orientation）一律原样保留。
- **保存**：另存副本（默认，插入 `Pictures/`，命名 `原名_副本.jpg`，重名递增）或覆盖原图（`createWriteRequest` 系统授权后直写）。
- **动态照片（Live 图）**：内嵌型（Google / 三星 Motion Photo v2）EXIF 重写后自动修正 XMP 偏移，副本与覆盖都保持动效。
- **入口两个**：app 内系统 Photo Picker 选图；系统相册"分享 → PhotoEdit"。
- **仅 JPEG、仅单张**：非 JPEG 入口即提示，`ACTION_SEND_MULTIPLE` 提示只支持单张。

## 这版不做什么

- **不做批量编辑**：一次只处理一张。
- **不支持 HEIC / PNG / WebP**：非 JPEG 在入口即拦截提示。
- **不做地图交互选点**：搜索 / 定位 / 手动经纬度已满足"相册能看到地点"。
- **不申请任何存储 / 媒体读取权限**：零权限的直接后果——双文件型 Live 图（小米 / 旧 ColorOS / 旧荣耀的同名 jpg+mp4）"另存副本"只产生**静态图**（无法复制配对 mp4，UI 会明示）；个别来源的原图无法覆盖时降级提示"改为另存副本"。
- **不做完整 EXIF 编辑器**：只开放上面列出的字段，不修改任何非管理字段。
- 不做 iOS 版、不做网页版。

## 构建

**Android Studio**：直接 `Open` 本目录，等 Gradle 同步完成后 Run 即可。

**命令行**（Windows，JAVA_HOME 指向 Android Studio 自带 JBR）：

```bat
set JAVA_HOME=<Android Studio 安装目录>\jbr
./gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，安装到设备：

```bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bat
:: JVM 单元测试（domain 纯逻辑 + ViewModel 等，无需设备）
./gradlew.bat :app:testDebugUnitTest

:: 仪器测试（需连接设备 / 模拟器：EXIF 往返、MediaStore 写回、端到端链路）
./gradlew.bat :app:connectedDebugAndroidTest
```

注：`MediaStoreWriterTest` 中"外部属主条目覆盖"一条在零权限模拟器上以
`Assume` 显式跳过（NeedsPermission 真链路转下面的真机验收清单验证）。

## 真机验收清单（spec 附录 A + Task 14a 追加项）

- [ ] 改时间 → 系统相册时间线/详情按新时间显示与排序
- [ ] 改地点（搜索方式）→ 相册地图视图/详情出现该地点
- [ ] 改地点（定位方式）→ 同上，且首次仅弹定位权限
- [ ] 手动经纬度 → 无网络亦可保存
- [ ] Live 图（本机拍摄）→ 覆盖原图后仍可播放；另存副本：内嵌型保持动效，双文件型为静态图且保存前有明确告知
- [ ] 覆盖原图 → 系统授权弹窗；拒绝后可一键转另存副本
- [ ] 分享入口 → 从系统相册分享一张 JPEG 可进入编辑
- [ ] 非 JPEG → 入口提示不支持
- [ ] （Task 14a）Photo Picker 选图后另存副本 → 副本名为**真实原文件名**加 `_副本`（非数字 id / FALLBACK 名）
- [ ] （Task 14a）覆盖相册中来历不明的外部原图 → 系统"允许 PhotoEdit 编辑此文件?"授权弹窗外观与流程正常；拒绝后可转另存副本
- [ ] （Task 14a）从系统相册发起真实"分享 → PhotoEdit" → 编辑 → 覆盖/副本全链路成功
- [ ] （Task 14a）同一张照片二次分享进入 → UI 状态正确重建，不残留上一次的编辑内容或弹窗循环

任何一项不过 → 记录 issue 回对应任务修复。

## 隐私

- **照片永远不上传**：所有 EXIF 读写、保存均在本地完成，无云端。
- **定位权限**：`ACCESS_FINE_LOCATION` 仅在点"用当前定位"时申请，拒绝不影响其他功能。
- **网络请求只有一类**：地点搜索 / 地名回显走 Photon（故障切 Nominatim）公共地理编码服务——你输入的**查询词**与选中的**坐标**会发给该服务；除此之外 app 不联网。无网络时仅"手动经纬度"可用，不阻塞保存。
