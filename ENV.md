# 本机环境路径（Windows / Git Bash）

- Android Studio: `A:\winget\android_studio`（JBR: `A:\winget\android_studio\jbr`，OpenJDK 25）
- Android SDK: `C:\Users\82328\AppData\Local\Android\Sdk`
  - platform-tools(adb 37.0.1) / build-tools 36.0.0 / platforms android-37.0，licenses 已接受
- 系统 PATH 无 java。跑 gradlew 前设：
  `export JAVA_HOME="A:/winget/android_studio/jbr"`
- 注意：JDK 25 较新，若 AGP/Gradle 报不支持，以 Studio 新建工程向导生成的 Gradle/AGP 版本为准（不要手动降级 wrapper）。
