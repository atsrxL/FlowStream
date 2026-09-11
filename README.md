# FlowStream 0.6.0 · 精简控制与主题

[APK](dist/FlowStream-0.6.0-debug.apk) · [完整手势](docs/GESTURES.md) · [当前验收](docs/handoff/RESULTS-0.6.0.md)。以下内容为历史版本记录，当前功能以0.6.0为准。

# FlowStream 0.5.0 · 新手势与参考界面

[完整手势](docs/GESTURES.md) · [当前验收](docs/handoff/RESULTS-0.5.0.md)。下方保留历史版本说明。

# FlowStream 0.4.1 · 修复缩放状态下的切片入口

新版结果见[RESULTS-0.4.1](docs/handoff/RESULTS-0.4.1.md)。原始尺寸下画面内拖动、黑边上下切片；使用底部“适屏”可恢复全画面上下切片。

# FlowStream 0.4.0 · 缩略图与随机播放

最新交付见dist/FlowStream-0.4.0-debug.apk；[本次变更与验证](docs/handoff/RESULTS-0.4.0.md)。以下保留历史说明。

# FlowStream 0.3.0 · 已构建真机调试版

APK位于dist/FlowStream-0.3.0-debug.apk，已安装MI 9 Android 15。最新实施与验收见[RESULTS](docs/handoff/RESULTS.md)。下方0.2.0内容保留为原始交接记录。

# 流映 FlowStream · Android 网络媒体播放器

版本：0.2.0 手势与画面缩放更新 · 整理日期：2026-09-11

**交付状态：在 0.1.0 上更新的完整源码工程、离线核心测试、构建脚本和 CI 配置。未完成 Android 编译、APK 生成或真机验证。** 当前执行环境有 JDK，但没有 Android SDK，下载主机的 DNS 解析也失败。这里没有把 Java 语法检查冒充 Android 构建；详细记录见 `docs/VALIDATION.md`。

## 1. 已写入代码的功能

| 需求 | 本版实现及边界 |
| --- | --- |
| 可配置来源 | SMB2/3（Samba）、WebDAV；设置内新增、编辑、删除、测试和选择服务器。 |
| 主界面 | 原生 Android 深色、紧凑文件列表，参考 Telegram 媒体列表的层级和控制布局；文件／会话历史两个入口。没有复制 Telegram 源码、商标或图标，不是像素级复刻；当前没有远端视频缩略图。 |
| 会话历史 | 最近 100 个会话，保存服务器、目录、播放队列、当前条目、进度、时长和常用速度；每 5 秒及暂停、切换、退出时保存。可续播、删除单条或清空。 |
| 全屏与朝向 | 进入播放器锁定当时朝向，不依据视频宽高自动转屏；底部旋转按钮切换横／竖屏。Android 16 大屏的临时兼容选项已写入清单，仍需设备验证。 |
| 上下滑换片 | 上滑下一条，下滑上一条；当前目录的视频构成队列。轴向锁定、死区、边缘保护、距离／速度阈值、松手提交、450ms 冷却。点对点／自定义缩放时拖动优先，适屏时启用纵滑；第二指转入画面缩放。队列首尾提示，不循环跳到另一端。 |
| 结束重播 | `REPEAT_MODE_ONE`，播完本条从头播放，留在全屏播放器，不自动跳下一条。 |
| 上区 | 没有专属长按功能；仍可单击暂停／继续并显示菜单、双击切换尺寸；适屏可纵向换片。 |
| 中区 | 长按进入临时 1×；按住向左移动超过 24dp 后，以 0.1× 起步反向回看，每继续 12dp 增加 0.1×，最高 2×。回到死区恢复临时 1×。**反向部分是静音、连续反向定位，不是真正逐帧倒播。** |
| 下区 | 长按进入临时 2×；横向 ±12dp 内保持 2×。向左约 120dp 降到 1×，向右约 180dp 升到 5×，中间以 0.05× 调整并限制范围。 |
| 松手恢复 | 长按结束／多指／触摸取消／焦点变化／旋转／画中画切换时，结束临时模式，恢复常用速度及长按前的播放或暂停状态。 |
| 菜单 | 单击播放器内容区切换暂停／继续，同时显示菜单；暂停时菜单不自动隐藏，播放时 3.5 秒后隐藏；顶部严格依次为“返回、小窗、播放速度”。底部包含进度、上一条、播放／暂停、下一条、强制旋转。菜单内按钮和进度条优先接收触摸；系统边缘保留给返回／主页手势。 |
| 双击 | 适屏 → 原始尺寸；点对点或双指自定义缩放 → 适屏。以双击位置为焦点，受画面边界限制。双击不改变播放／暂停状态。 |
| 点对点 | 对常见方形像素视频按当前解码尺寸 1 视频像素对 1 应用渲染像素，原始模式取整平移坐标。不是固定放大 2×；低分辨率视频可能缩小。非方形像素校正显示比例并另行标注。 |
| 双指缩放 | 围绕双指中心缩放，可缩回适屏。上限为“8×适屏”和“原始尺寸”中较大值，下限为适屏和原始尺寸中较小值。 |
| 拖动画面 | 原始尺寸／自定义缩放模式下，未建立长按的单指拖动平移画面；每个轴独立夹紧，画面小于窗口的轴固定居中。拖到边界不会继续变成切片。 |
| 手势仲裁 | 单击等待系统双击窗口确认；第二次接触必须正常短触松手才双击生效，拖动、长按、第二指、CANCEL 都不补发单击。多指流中抬起一指后可继续单指拖动，全部抬手前不能切片／长按／单击。 |
| 速度菜单 | 0.05、0.10……2.00，以及 2.50、3.00、3.50、4.00、4.50、5.00，共 46 档。0.05× 按静音视频慢放处理，实际节奏仍待真机验证。 |
| 小窗 | Android 系统画中画 PiP，不是任意尺寸的悬浮窗，也不申请覆盖其他应用权限。设备必须支持并允许画中画。 |

上面的“实现”指对应源码已存在，不等同于已通过 Android 编译或实机验收。详细交互规则及冲突处理见 `docs/GESTURES.md`，本次改动见 `CHANGELOG.md`。

## 2. 构建

### 工具链

工程有意固定依赖，不宣称使用最新版本：

- Android 8.0 / API 26 起；`compileSdk=36`、`targetSdk=36`。
- JDK 17；Android Gradle Plugin 8.13.2；Gradle 8.13；Android SDK Platform 36、Build-Tools 35.0.0。
- Java 17、原生 Android View；Media3 1.9.3；SMBJ 0.14.0；OkHttp 4.12.0。

首次构建需要能访问 Google Maven、Maven Central 和 Gradle 官方分发站点，并在 SDK Manager 中接受 Android SDK 许可。本包不含 SDK、第三方 JAR、Gradle 发行包或签名私钥。

### macOS / Linux

先通过 Android Studio SDK Manager 安装上面的 SDK 组件，进入本目录执行：

```bash
# 根据本机实际位置设置；macOS 常见位置如下。
export ANDROID_HOME="$HOME/Library/Android/sdk"
# Linux 常见位置为 "$HOME/Android/Sdk"。

bash tools/build.sh
```

脚本会校验官方 Gradle 分发包的 SHA-256，生成真正的 Gradle Wrapper，再执行：

```text
:core:selfTest :app:lintDebug :app:assembleDebug
```

**只有构建成功后**，APK 才会出现在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

这不是本交付包中已经存在的文件。脚本失败会返回非零状态，不会输出伪造的 APK。生成 Wrapper 后即可使用 `./gradlew`；Android Studio 可打开工程根目录。

### Windows PowerShell

安装 JDK 17 及相同 SDK 组件，然后：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1
```

`ExecutionPolicy Bypass` 只作用于这次 PowerShell 进程；执行前可先审阅脚本。Windows 脚本未在当前 Linux 环境中执行。

### 已安装 Gradle 的环境

```bash
gradle --version  # 应为 8.13，运行 JDK 17。
gradle --no-daemon :core:selfTest :app:lintDebug :app:assembleDebug
```

Android 构建和 lint 尚未在本环境运行，首轮构建可能暴露依赖、资源或平台 API 问题，需要依据真实报错修正，不能据此源码包保证一次构建成功。

### GitHub Actions

`.github/workflows/android.yml` 提供手动触发、主分支推送及拉取请求触发的构建任务，安装 SDK 后执行核心回归、Android lint 和 Debug APK 构建，成功时上传 `FlowStream-debug-apk` 构建产物。这里仅提供配置，没有创建仓库、调用 GitHub 或执行该工作流。

## 3. 来源配置

### Samba / SMB 共享

示例值仅为格式说明：

```text
协议：SMB / Samba
名称：家中 NAS
主机：192.168.1.20
端口：445
共享名：videos
共享内根目录：电影        # 可留空；不是服务器上的绝对挂载路径
用户名：你的只读账号
密码：该账号的密码
域 / 工作组：按服务器要求填写，可留空
```

主机字段不填 `smb://`，共享名和共享内路径分开填。服务器需要启用 SMB2/3；不支持 SMB1。SMB3 加密开关是要求加密，服务器不支持时应连接失败，不以明文自动降级替代。

SMB 数据源按 `DataSpec.position` 读取文件偏移，配合 256KiB 预读及连接复用，供播放器缓冲和定位使用，不先把整个视频下载到本地。

### WebDAV

```text
协议：WebDAV
名称：媒体 WebDAV
根地址：https://nas.example/dav/
用户名：你的只读账号
密码：该账号的密码
```

支持 Basic 认证或匿名访问；暂不支持 Digest、OAuth、URL 内嵌密码和根 URL 查询令牌。默认要求 HTTPS。只有显式勾选才允许 HTTP，此时认证与视频内容没有 TLS 保护；测试服务只能放在可信局域网。

服务端需支持 `PROPFIND Depth: 1` 目录读取以及 `GET Range`。非零偏移请求若返回 200 而不是范围响应，客户端报错，不以大量丢弃前部数据假装支持定位。目录响应限定为 UTF-8；XML 体积不超过 8MiB；单目录不超过 10,000 项。媒体和重定向只能留在已配置的同一来源及根路径下，外域 CDN、根目录外分片或带鉴权查询的播放列表可能不兼容。

不绕过 TLS 证书验证，不信任所有自签名证书。未提供应用内自定义 CA 导入功能。

### 本机 WebDAV 开发夹具

随包提供的 Python 服务只用于测试，没有生产级审计、防护或 TLS，不应暴露到公网。默认绑定 `127.0.0.1`。

```bash
# 准备一个只含测试视频的本地目录。
export FLOW_USER=demo
export FLOW_PASSWORD='改成仅用于测试的密码'
python3 tools/webdav_fixture.py --root /绝对路径/测试视频
```

安卓模拟器通过 `http://10.0.2.2:8089/dav/` 访问宿主机回环服务，来源配置需开启 HTTP。实机联调时可加 `--host 0.0.0.0`，在可信局域网使用电脑局域网 IP，并限制防火墙访问。此步骤需要你实际启动服务，交付环境中没有留运行中的服务器。

## 4. 手势参数

手势死区和换片距离使用 dp；画面缩放、焦点和平移使用渲染像素 px（不乘 dp 密度）；三等分依据整个播放器触摸区域，而不是视频画面去除黑边后的尺寸。按下时固定所属区域，之后跨区不改变本次长按模式。

| 参数 | 数值 / 规则 |
| --- | --- |
| 长按判定 | 380ms；此前移动不能超过基础死区 |
| 基础死区 | `max(系统 touchSlop, 12dp)` |
| 纵向占优 | `abs(dy) >= 1.6 × abs(dx)` |
| 正常换片距离 | `max(96dp, 屏幕触摸区域高度 × 14%)` |
| 快速甩动 | 至少 64dp，纵向速度至少 1000dp/s，速度与位移同向 |
| 生效时机 | 松手时再次判定，不在手指移动中加载下一条 |
| 冷却 | 成功提交之间至少 450ms |
| 系统边缘 | 左右各 24dp、顶部 12dp、底部 32dp 不启动播放器手势 |
| 防串扰 | 已锁定纵滑不能变为长按；已触发长按不能换片；未长按的横滑不执行倒退或变速；大角度斜滑拒绝 |
| 多指 / 中断 | 第二指先恢复长按前的播放状态，再转入缩放；ACTION_CANCEL、焦点丢失、转屏、小窗切换取消整个手势和待确认单击 |
| 单双击 | 系统 `getDoubleTapTimeout()` / `getScaledDoubleTapSlop()`；第二击在合格 UP 时提交，不在第二次 DOWN 提前缩放 |
| 原始／自定义缩放 | 越过基础死区后进入 PAN，不判纵滑；静止 380ms 的中下区长按仍保留 |
| 适屏吸附 | 双指全部抬起后，自定义倍率距适屏不超过 1.5% 时吸附回适屏 |

轻触系统保留边缘不会切换播放状态或呼出菜单，这是为防系统返回／主页与播放器冲突而做的明确例外。已有可见控件优先执行自己的操作，不以全屏手势覆盖按钮。

速度映射见 `core/.../SpeedScale.java`；拖动／切片／长按仲裁见 `GestureMachine.java`；单双击见 `TapResolver.java`；画面几何见 `VideoViewport.java`。这些参数当前由代码固定，没有额外做一页手势灵敏度设置。

### 画面缩放的实现边界

本版通过 XML 选择 Media3 `TextureView`，固定视图填满窗口，仅对视频纹理应用矩阵。菜单、字幕层及屏幕三区坐标不会随画面缩放。切视频、分辨率变化、旋转和 PiP 重置为适屏；同条视频循环不主动重置，暂不把缩放状态写进会话数据库。

“1:1”依据当前视频轨的解码输出尺寸，而不是媒体服务器上可能存在的更高清版本。若系统降低整个应用的合成分辨率再放大到面板（某些电视或兼容模式），不能保证对应屏幕物理像素；非方形像素视频不能同时保持正确比例和两轴逐样本 1:1。使用 TextureView 还可能损失 SurfaceView 的功耗、帧时序及 HDR 优势，尚未做相应真机对比。参见 `docs/SOURCES.md` 中 Android 官方 Surface 和 VideoSize 文档。

## 5. 反向回看、速度和媒体边界

Media3 的 `PlaybackParameters` 只接受正速度。本版没有设置负速度，也没有伪称 ExoPlayer 能直接逐帧反向播放。实现方式是：长按回看时暂停正播并静音，以独立时间线计算逐渐减小的目标位置，约每 150ms 在播放器就绪时请求一次精确定位；正在缓冲则不继续堆积定位请求。到 0 时停住，松手恢复。

因此本版反向模式是“离散画面回看”：实际画面流畅度受到关键帧间隔、解码器、网络时延和定位支持影响，显示的 0.1×—2× 是目标时间线回退倍率，不是保证每帧都反向解码的倍率。**真正逐帧倒播尚未实现。**

0.05× 会禁用音轨，采用静音视频慢放路径，避免极低速音频处理成为约束。1×—5× 的可用性仍取决于视频编码、硬件解码吞吐和网络；没有任何机型的倍率误差、1% low、4K 吞吐或功耗实测。

识别 MP4、MKV、WebM、MOV、TS 等常见文件扩展名，以及 HLS / DASH 播放列表；识别为候选文件不等于保证所有封装、编码、字幕、DRM 都可播。没有捆绑 FFmpeg 全格式软件解码器，没有外挂字幕界面、转码服务器、DRM 授权或离线下载管理。

切换使用轻量淡入和方向提示，没有制作相邻视频实时预览的完整抖音式翻页动画；同一视频结束循环与主动上下滑换片是两个独立行为。

## 6. 生命周期、历史和隐私

播放器中的临时长按速度不写成常用速度。会话保存队列快照，服务器目录改变后旧快照不会自动重排；删除／移动文件或删除服务器配置可能导致旧会话无法续播。新增服务器得到新 ID，不会自动绑定到已删除来源的历史。

应用后台不可见时暂停并释放播放器，回来按保存位置恢复；PiP 可见期间继续播放。没有后台音频前台服务，不能把本版当作熄屏持续播放的音乐播放器。进程在两次保存之间异常终止，可能丢失最近约 5 秒进度；系统无法提供完全不丢失的异常断电保证。

密码使用 Android Keystore 中的 AES-GCM 密钥加密，密文绑定服务器 ID；用户名、服务器地址、文件名和历史是应用私有数据库里的普通元数据，不是整库加密。应用备份已关闭，密码编辑对话框使用 `FLAG_SECURE`。Keystore 密钥丢失后需删除并重建对应配置。

未加入广告、统计或崩溃上传 SDK；不主动改写远端文件；视频采用内存缓冲，没有整片磁盘缓存。SMB 明文模式和允许的 HTTP 均不意味着网络内容被加密，安全的使用方式是可信网络、只读账号及可用的 SMB3 加密 / HTTPS。

## 7. 测试和工程结构

```bash
# JDK 17+ 即可；不需要 SDK、Gradle或第三方 Java 依赖。
bash tools/test-core.sh

# 仅检查 Java 语法，不做 Android API/类型/资源编译。
java tools/JavaSyntaxCheck.java app/src/main/java core/src/main/java core/src/test/java

# Python 3.10+；测试本地夹具，不是测试 Android 客户端。
python3 tools/test_fixture.py
python3 tools/check-project.py
```

实际记录：核心 103 组通过（原有 55 组 + 新增 48 组），含 30,000 条确定性手势序列和 10,000 条几何变换序列（每条 25 次操作）；30 个 Java 文件语法解析通过；开发夹具 6 项通过。完整范围、实际命令输出和未验证项见 `docs/VALIDATION.md`。

```text
app/src/main/java/dev/flowstream/player/
  data/       服务器、会话、SQLite、Keystore
  net/        SMB 随机读取、连接池、WebDAV 浏览和 HTTP 范围请求
  playback/   MotionEvent 接入、纹理变换、长按／反向回看控制
  ui/         文件列表、服务器设置、全屏播放器
core/src/main/java/dev/flowstream/core/
  GestureMachine.java  拖动／切片／长按互斥状态机
  TapResolver.java     延迟单击、第二次松手确认双击
  VideoViewport.java   适屏／原始尺寸／双指缩放／边界计算
  SpeedScale.java      精确离散速度档位和长按速度映射
  ReverseClock.java    反向回看目标时间线
  DavUrls.java         路径编码和来源隔离
  DavParser.java       有界、安全的 DAV 目录解析
core/src/test/          无第三方依赖的离线回归
.github/workflows/     未执行的 Android CI 构建配置
tools/                 构建脚本、检查工具、只读 WebDAV 测试服务
docs/                  验证记录、架构和官方参考资料
```

本工程自身新增源码按 `LICENSE` 发布；第三方依赖继续受其各自许可证约束。发行正式版前必须完成 Android 编译、真机验收、依赖与许可审查、签名及发布配置，不能把此初始源码交付视作已经过商用验收的产品。
