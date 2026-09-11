# FlowStream 0.6.1

底部第一行右侧增加菜单，当前只有删除。确认弹窗展示视频文件名；确认后调用SMB文件删除或WebDAV DELETE删除原文件。拒绝删除目录与空路径，WebDAV删除禁用重定向和自动连接重试。成功移除进程内播放队列当前项并打开剩余视频，最后一项删除后退出。失败保留队列并显示错误。第三行按钮均匀分布，按钮间留出至少8dp空隙。

编译、核心自检和lint通过，lint 0错误36警告。已通过ADB安装192.168.123.140:5555。

真机在独立的本地WebDAV测试目录验证11项：布局、取消、认证失败不删除、失败保留队列、真实磁盘删除、播放队列衔接、内存队列同步、旋转后队列、最后一项删除及退出。两个合成测试视频副本均已从宿主机磁盘消失。没有删除任何用户原视频。SMB删除代码已编译，未对用户SMB文件进行破坏性验证。

日志：logs/build-061-final.log、logs/device-061-final.log。截图：logs/controls-061.png。

删除测试：单独启动tools/webdav_fixture.py，指定可丢弃目录并加--allow-delete。默认fixture仍不允许删除。构建测试APK使用-PdeleteChecks，运行dev.flowstream.player.DeletionChecks。测试会真正删除目录中的两段测试视频。
