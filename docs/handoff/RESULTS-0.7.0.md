# FlowStream 0.7.0

调试改为USB 320237117371（F50，Android13，480×854），APK已安装。

- 顶部退出按钮移除，保留旋转/小窗/速度；系统返回仍可退出。
- 倍率提示移至顶部中央第二行。
- 上区长按默认1×倒放，左移1cm达到4×、右移1cm达到0.1×；下区长按默认2×正播，左移1cm达到0.1×、右移1cm达到5×。长度由屏幕报告DPI换算。
- 中区横拖定位进度，松手提交、取消不提交；横移一个屏宽对应整段时长。保持播放/暂停，纵滑切片保留，CUSTOM缩放仍优先平移。
- 进度条独立固定最下方，菜单隐藏和长按时保持可见；系统PiP内隐藏。

核心113组通过；构建、lint通过（见build-070-final.log）。USB实机通过三区手势、取消、1cm端点、倍率恢复、提示位置、菜单隐藏时进度条可见、双击适屏、纵向切片等检查，日志device-070-final.log。截图gesture-070.png和controls-070.png。

USB设备播放含音轨视频时报Cannot create AudioTrack。本轮使用无音轨合成视频副本验证手势与视频渲染，声音播放未通过。原有测试视频和用户视频均未修改。首次全屏系统引导会阻断/取消触摸，测试中通过系统“知道了”关闭后验证。

复测：启动tools/webdav_fixture.py --root .toolchain/usb-media，adb -s 320237117371 reverse tcp:8089 tcp:8089；构建测试APK加-PgestureChecks，运行dev.flowstream.player.GestureChecks。
