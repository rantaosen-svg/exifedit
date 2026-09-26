# PhotoEdit — 安卓照片元数据编辑器 设计文档（spec）

日期：2026-09-26
状态：待用户审阅
技术栈：Kotlin + Jetpack Compose + MVVM，minSdk 26 / target 34+

## 1. 目标与定位

一款单功能的安卓 app：修改相册照片的 EXIF 元数据（拍摄时间、拍摄地点、常用相机参数），
保存回系统相册后，各厂商相册（realme/荣耀/小米/三星等）能正确显示新的时间与地点。
界面走苹果风格：分组圆角卡片、大标题、克制配色、磨砂悬浮按钮。

### 首版明确不做什么

- 不做批量编辑（一次一张）。
- 不做完整 EXIF 编辑器（只开放第 3 节列出的字段）。
- 不支持 HEIC / PNG / WebP，非 JPEG 在入口即提示。
- 不做地图交互选点（搜索/定位/手动经纬度已满足"相册能看到地点"）。
- 不修改 Live 图配对的 mp4 本身；且不申请任何存储/媒体读取权限——双文件型 Live 图的
  "另存副本"只产生静态图（2026-09-26 用户裁定，见 §3.4）。
- 不做 iOS 版、不做网页版。

## 2. 架构

单模块工程，包结构三层，依赖方向 ui → domain ← data：

- `ui/` — Compose 界面与主题（iOS 风格 token）、ViewModel。
- `domain/` — `PhotoMetadata` 不可变模型与字段级"已修改"标记；纯逻辑（度分秒转换、
  副本命名、格式校验），全部 JVM 可测。
- `data/` — `ExifRepository`（androidx.exifinterface 读写）、`MotionPhotoCodec`
  （内嵌型动态照片探测与 XMP 偏移修正）、`GeocoderService`（Photon 主 / Nominatim
  兜底）、`MediaStoreWriter`（另存副本 / 覆盖原图 + 系统授权）。

## 3. 功能设计

### 3.1 入口（两个，汇入同一编辑流程）

1. app 内"选择照片"→ 系统 Photo Picker（无需存储权限）。
2. 系统相册/其他 app 中"分享 → PhotoEdit"：`ACTION_SEND` + `image/*` intent-filter；
   `ACTION_SEND_MULTIPLE` 提示仅支持单张。

### 3.2 可编辑字段

- 拍摄时间：`DateTimeOriginal` + `OffsetTimeOriginal`（日期选择器 + 时间选择器）。
- 拍摄地点：GPS 经纬度（度分秒有理化写入，含 N/S、E/W 参考）、可选海拔、
  `GPSProcessingMethod`；"清除地点"删除全部 GPS 标签。
- 常用相机信息：设备型号（显示 Make/Model，可改 Model）、光圈 `FNumber`、
  快门 `ExposureTime`、`ISO`、焦距 `FocalLength`。
- 未修改的字段一律原样保留；`Orientation` 必须原样写回。

### 3.3 地点获取（三选一）

1. 搜索地点名 → Photon API（免 key），故障切 Nominatim（限速 1 req/s，带 UA）。
2. 用当前定位 → 系统 `LocationManager`（不用 GMS 融合定位，兼容国行机）；
   `ACCESS_FINE_LOCATION` 仅在此操作时申请。
3. 手动输入经纬度 → 范围校验（纬 ±90，经 ±180）。

坐标选定后反向地理编码出地名在卡片上回显，仅供确认；相册内地点由各相册根据
坐标自行显示，app 只需保证坐标正确。无网络时仅手动经纬度可用，不阻塞保存。

### 3.4 Live 图（动态照片）

- 内嵌型（Google/三星/Motion Photo v2：视频追加在 JPEG 尾部、XMP 记录偏移）：
  EXIF 重写后由 `MotionPhotoCodec` 重算并修正 XMP 偏移，单文件自包含，
  **覆盖与另存副本都保持动效**。
- 双文件型（小米/旧 ColorOS/旧荣耀：同名 jpg+mp4）：**覆盖原图**文件名不动、
  配对保持、动效不受影响；**另存副本只产生静态图**——连带复制配对 mp4 需要
  READ_MEDIA 运行时权限，用户裁定首版不引入任何存储权限（2026-09-26 修订）。
  UI 必须在此情形明示"副本将为静态图"。
- 无法识别的厂商变体：降级按普通 JPEG 处理，保存前明示"动效可能丢失"，用户确认才继续。

### 3.5 保存（每次询问）

- **另存副本（默认高亮）**：字节流插入 `MediaStore.Images`，目录 `Pictures/`，
  命名 `原名_副本.jpg`（重名递增序号），同步写 `DATE_TAKEN` 列。
- **覆盖原图**：Uri 反查 MediaStore 条目 → `createWriteRequest()` 系统授权 →
  写回原文件并 `notifyChange`。授权被拒或原文件已不存在时，提示并给"改为另存副本"。

## 4. 错误处理

总原则：失败必须明确告知，绝不静默丢编辑内容；保存失败时页面状态完整保留可重试。
各场景策略见 §3.3/§3.4/§3.5 及：非 JPEG 入口拦截；无 EXIF 照片（截图等）按"添加元数据"正常处理。

## 5. 测试

1. JVM 单元测试：度分秒↔十进制、字段修改标记、Motion Photo 尾部探测与偏移重算
   （合成 JPEG+假视频字节夹具）、副本命名冲突递增、时间格式化、经纬度校验。
2. 仪器测试：ExifInterface 完整"读→改→写→重读"往返断言；Orientation 保留；
   GPS 标签集合增删完整。
3. 人工验收清单（附录 A，用户真机执行）：见下。

## 附录 A · 真机验收清单

- [ ] 改时间 → 系统相册时间线/详情按新时间显示与排序
- [ ] 改地点（搜索方式）→ 相册地图视图/详情出现该地点
- [ ] 改地点（定位方式）→ 同上，且首次仅弹定位权限
- [ ] 手动经纬度 → 无网络亦可保存
- [ ] Live 图（本机拍摄）→ 覆盖原图后仍可播放；另存副本：内嵌型保持动效，
      双文件型为静态图且保存前有明确告知
- [ ] 覆盖原图 → 系统授权弹窗；拒绝后可一键转另存副本
- [ ] 分享入口 → 从系统相册分享一张 JPEG 可进入编辑
- [ ] 非 JPEG → 入口提示不支持
