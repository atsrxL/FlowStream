# 官方技术参考与版本选择

查阅日期：2026-09-11。以下为实现参考，不表示已经运行对应 Android 功能，也不宣称固定依赖是最新版本。

1. AndroidX Media3 发布记录：
   https://developer.android.com/jetpack/androidx/releases/media3
2. 固定版本 Media3 1.9.3 官方仓库常量：
   https://github.com/androidx/media/blob/1.9.3/constants.gradle
3. Media3 PlaybackParameters，speed 必须为正：
   https://developer.android.com/reference/androidx/media3/common/PlaybackParameters
4. Media3 DefaultAudioSink（1.9.3），播放倍率与音频时钟路径参考：
   https://github.com/androidx/media/blob/1.9.3/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java
5. Android Gradle Plugin 8.13 兼容说明；工程固定 AGP 8.13.2 与 Gradle 8.13 / JDK 17：
   https://developer.android.com/build/releases/agp-8-13-0-release-notes
6. Android 系统画中画指南：
   https://developer.android.com/develop/ui/views/picture-in-picture
7. Android 16 面向 targetSdk 36 的行为变化（方向和大屏）：
   https://developer.android.com/about/versions/16/behavior-changes-16
8. Android Keystore：
   https://developer.android.com/privacy-and-security/keystore
9. SMBJ 官方仓库、SMB2/3 和示例：
   https://github.com/hierynomus/smbj
10. SMBJ 文件随机读取和配置实现：
    https://github.com/hierynomus/smbj/blob/v0.14.0/src/main/java/com/hierynomus/smbj/share/File.java
    https://github.com/hierynomus/smbj/blob/v0.14.0/src/main/java/com/hierynomus/smbj/SmbConfig.java
11. OpenZFS `zfs-share`，服务端共享导出：
    https://openzfs.github.io/openzfs-docs/man/master/8/zfs-share.8.html
12. Gradle 官方发行包和同源校验文件（由构建脚本实际请求）：
    https://services.gradle.org/distributions/gradle-8.13-bin.zip
    https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256

工程采用原生 Android View 降低初版 UI 工具链复杂度，使用 Media3 解码与 Android MediaSession；播放器行为由本工程控制，不是把现有开源播放器界面套壳后宣称已实现手势需求。没有使用或分发 Telegram 源码或素材。

## 0.2.0 缩放／触摸参考（核对日期 2026-09-11）

- Android Media3 Surface 类型：TextureView 选择方式及与 SurfaceView 的功耗、帧时序、HDR、UI 分辨率差异。
  https://developer.android.com/media/media3/ui/surface
- Media3 VideoSize：解码输出宽高、非方形像素比，以及旋转已由播放器处理。
  https://developer.android.com/reference/androidx/media3/common/VideoSize
- Android OnDoubleTapListener：确认单击和第二次 DOWN 的区别。本工程特意延至第二次合格 UP 才提交双击。
  https://developer.android.com/reference/android/view/GestureDetector.OnDoubleTapListener
- Android ScaleGestureDetector：完整事件流、quick scale 和 stylus scale 开关。
  https://developer.android.com/reference/android/view/ScaleGestureDetector
- 已固定版本的 Media3 1.9.3 PlayerView 源码：texture_view 在 XML 构造时选定；FILL 保持视图尺寸；未额外设置 TextureView 矩阵。
  https://raw.githubusercontent.com/androidx/media/1.9.3/libraries/ui/src/main/java/androidx/media3/ui/PlayerView.java

这些来源只支持平台 API 与设计依据，不构成本工程通过 Android 编译或像素级真机测试的证据。
