# FlowStream · iOS / iPadOS

SwiftUI + UIKit 原生版，功能对应 Android 0.9.0：SMB2/3（AMSMB2 / libsmb2）与 WebDAV 来源、九区手势、双指缩放平移、三种适屏、快进／快退长按调速、进度条长按拖动与缩略图预览、上下滑切换、随机、标记、真实删除、下一条预读（磁盘／内存）、画中画、触屏锁。iPhone 与 iPad 通用，最低 iOS 16。

## 构建 IPA（无签名）

```bash
brew install xcodegen        # 仅首次
bash ios/build-ipa.sh        # 输出 dist/FlowStream-<版本>-ios-unsigned.ipa
```

产物未签名，用 AltStore / Sideloadly / TrollStore 或自己的证书重签后安装。工程用 `ios/project.yml` 生成，`ios/FlowStream.xcodeproj` 不入库；也可 `cd ios && xcodegen && open FlowStream.xcodeproj`。

## 与 Android 版的差异

- 播放器是系统 AVPlayer：**只支持 MP4 / MOV / M4V / 3GP**（及 HLS）。MKV、WebM、AVI、裸 TS 会提示不支持。
- SMB / WebDAV 经 `AVAssetResourceLoader` 自定义 scheme（`flowx://`）按 Range 读取，1 MiB 分块缓存；下一条预读前 N MB 并带上文件尾部 4 MiB（非 faststart MP4 的索引）。
- 小窗是系统画中画；进入播放器锁定当前朝向，用旋转钮切换。
- 服务器密码存 Keychain；无 Keychain 授权的重签包自动回退到应用私有 UserDefaults。
- 会话与历史仍只在内存中，标记持久化。

## 测试

- 逻辑 / 网络：`/tmp` 下的 macOS 命令行验证过 WebDAV（`tools/webdav_fixture.py`）与 Samba 容器的列目录、Range 读取、AVPlayer 起播与 seek、缩略图、删除。
- 模拟器：`ios/UITests/PlayerGestureUITests.swift` 需要本机 SMB（127.0.0.1:4445，共享 `videos`，账号 demo/secret）或 WebDAV（`FS_TEST_PROTO=dav`，端口 8765）上有 `test.mp4`、`del.mp4`，Debug 构建会按环境变量自动配置服务器并打开视频。
