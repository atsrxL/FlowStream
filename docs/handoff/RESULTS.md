# FlowStream 0.3.0 实施与真机验收

日期：2026-09-11。在收到的0.2.0上继续开发。源码包缺少启动说明列出的AGENTS.md和handoff需求文件，按README、VALIDATION和用户追加需求执行。旧验证证据未覆盖。

## 交付

APK：dist/FlowStream-0.3.0-debug.apk。包名dev.flowstream.player.debug，已安装到192.168.123.140:5555，MI 9 / Android 15。校验值见dist/SHA256SUMS.txt。完整源码见dist/FlowStream-0.3.0-source.zip。
工具链为JDK17、SDK36、Build Tools35、Gradle8.13（官方校验）、AGP8.13.2。

## 已实现

- 补齐AndroidX annotation依赖，修复首次真实编译失败。刘海资源限定API28；返回回调保留API33+平台处理和旧系统兼容。
- 参考图黑底、居中标题、透明顶部工具栏和底部圆角控件。暂停无画面中心播放按钮或暂停提示。
- 上下滑跟手、取消回弹、新视频纵向移入动画。
- 下一条预读0–50MB，默认10MB；应用私有临时磁盘缓存总量50MiB，播放复用已缓存字节，未命中部分继续网络流式读取。
- 下区长按正播最低0.1×、最高5×，起点仍2×；中区回看最高4×。
- 更多菜单前后1帧、1/5/10秒；A–B区间循环；时间点书签；播放信息。
- 收藏和书签：首页收藏入口可播放或删除。来源保存速度与适屏/原始尺寸偏好。
- 手势锁，仅解锁控件继续响应应用触摸，系统导航仍有效。
- 拖动进度时显示缩略图；已播放区采样，未采样区防抖后通过SMB/WebDAV获取邻近关键帧。
- 修复暂停时原始尺寸状态正确但画面未更新的问题；纹理视图属性变换立即重绘，640×360实拍截图确认缩小居中。

## 实际验证

- 核心103组及随机序列通过：logs/core-tests.log、build-delivery.log，速度边界断言已更新。
- WebDAV Python夹具6项通过：logs/fixture-tests.log。
- 编译、lint通过：logs/build-delivery.log，0错误31警告：logs/lint-final.txt。
- 最终APK安装启动：logs/install-delivery.log、launch-delivery.log。
- WebDAV新版集成通过：logs/device-030-03.log。
- SMB2新版集成通过：logs/device-030-smb-final.log。
- 两协议实测：认证浏览、解码与时间推进、单击暂停、双击原始尺寸不改变播放、原始拖动不换片、双指缩放与剩余指拖动、短滑死区、上滑换片、长按2×及松手恢复、中区回看及CANCEL、单条循环、历史偏移恢复。
- 两协议实测新增：锁定阻止点击、+1秒控制、A–B循环、远程缩略图、预读缓存非空且不超50MiB、速度映射上限。
- 两协议实测：旋转、PiP进入/展开、后台释放、前台重建。
- 截图：logs/native-640-v030-portrait.png、landscape.png、pip.png。前两轮截图保留作为发现问题的证据，文件名未带v030者可能来自旧版。
- 本次崩溃缓冲记录：logs/crash-final.txt。

使用本机回环开发服务器，经ADB reverse 8089/1445转发；媒体为640×360/24秒与1920×1080/18秒MP4。独立androidTest测试APK不进入产品APK。测试配置在正常结束后清理。失败构建和测试驱动修正记录全部保留。

## 限制与待验证

- 回看是静音离散seek，不是真正逐帧倒播。
- ±1帧按视频轨帧率估算步长并EXACT seek，变帧率、B帧及反向不保证严格逐编码帧。未知帧率拒绝操作。
- 来源画面记忆为适屏/原始尺寸，尚不保存任意自定义比例和拖动偏移；旋转/PiP复位适屏。
- HLS/DASH暂不预读或远程预览。缩略图为邻近关键帧；不可提取时明确显示不可用。
- UI标MB，内部按MiB计算；0关闭新预读，不清除已有可回收缓存。小视频可能全部读入，长视频无需整片下载。
- 动画为单播放器纵向过渡，不包含相邻视频双解码实时预览。
- 收藏/删除、所有定位按钮与来源偏好已实现，但尚未逐按钮完整自动验收。
- 用户实际NAS、SMB3加密、HTTPS证书、4K/HDR、4GiB以上文件、Android8/16、大屏、弱网、耗电、精确播放倍率仍待验证。
- Debug签名用于本次安装调试，未配置生产签名或商店发布。

## 复现

设置JAVA_HOME为JDK17后执行bash tools/build.sh。设备测试构建:app:assembleDebugAndroidTest；启动WebDAV和SMB2本地服务，adb reverse端口并安装测试APK，运行am instrument -w（SMB加-e protocol SMB）dev.flowstream.player.debug.test/dev.flowstream.player.DeviceChecks。

## 后续0.4.0

最新结果与交付见RESULTS-0.4.0.md。旧版结果保留。

最新修复见RESULTS-0.4.1.md。

最新0.5.0见RESULTS-0.5.0.md，当前手势以docs/GESTURES.md为准。

当前0.7.0交付及USB设备记录见[RESULTS-0.7.0.md](RESULTS-0.7.0.md)。
