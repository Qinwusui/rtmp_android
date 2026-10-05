# Android 屏幕与摄像头合流采集

将摄像头主画面、屏幕小窗和麦克风音频合成为一路 RTMP，推送到本地 LAL 服务器。Android 应用采用 Kotlin，包名为 `com.wusui.rtmpcapture`，界面使用中文。采集与转发不生成音视频文件。

项目声明支持 Android 7 起（minSdk 24），目标为 Android 17（targetSdk 37）。当前实际验证设备为 MI 8 / Android 10 / API 29，其他系统版本与长期验收范围见 [验证记录](docs/verification.md)。

## 功能与默认配置

- 摄像头为主画面，屏幕小窗默认位于右下角，宽度为输出画面的 30%，高度不超过 40%，保持屏幕比例；支持位置、大小与旋转调整。
- 前台服务持有采集资源，退出界面后继续推流。熄屏或屏幕授权撤销时释放屏幕资源，摄像头和麦克风继续运行；重新系统授权后可恢复屏幕合流。
- 相机被系统临时占用时保留最后画面，保持音频和 RTMP，并尝试恢复相机。系统独占摄像头期间无法生成新的摄像头画面。
- 摄像头画面跟随设备持握方向纠正，支持横屏、竖屏和倒置持握；关闭系统自动旋转时仍可更新方向，设备平放时保留最近有效方向。切换方向只更新 GPU 变换与居中裁剪，输出分辨率保持不变。
- 自动补光默认使用设备当地时间 18:00–06:00，也支持常开、关闭；无闪光灯时禁用补光选项。
- 提供服务器管理、采集控制、画质与布局设置、运行状态、会话记录和手动 HTTP-FLV 监看。
- 显示编码帧率、传输码率、队列丢帧、重连次数和屏幕状态；支持有界队列、自适应码率及连接退避重试。

| 参数 | 默认值 |
| --- | --- |
| 视频编码 | 硬件 H.264，单个视频编码器 |
| 分辨率 / 帧率 | 1280×720 / 30fps |
| 视频目标码率 | 3Mbps，自适应下限 1Mbps |
| 音频 | AAC-LC，48kHz，单声道，64kbps |
| RTMP 队列 | 最多 128 个编码帧，包含音视频 |
| 网络重试 | 1、2、4、8、16、30 秒，附加 ±20% 抖动 |

启动时检查相机输出尺寸、帧率和硬件编码器能力；驱动实际拒绝配置时停止并释放资源。分辨率、帧率和码率修改在下一次启动生效。拥塞清理积压并请求关键帧时保留同一连接的时间戳原点，重连只替换传输连接。

## 项目结构

```text
app/                 Compose 界面、ViewModel、Koin、前台服务、数据保存
capture/             Camera2、MediaProjection、EGL 合成、编码与 RTMP
auth-server/         Ktor JVM 发布认证服务示例
deployment/          认证环境变量示例和 LAL 配置模板
docs/                部署说明与实际验证记录
scripts/             构建、真机安装测试、HTTP-FLV 检查、耐久采样
gradle/              Wrapper 与依赖版本目录
app/schemas/         Room 数据库 schema，需纳入版本控制
```

视频管线为 `Camera2 + MediaProjection → OpenGL ES → MediaCodec H.264 → RTMP`。麦克风通过 AudioRecord 采集并编码为 AAC，两路使用统一的单调时钟。RTMP 使用 RootEncoder 2.8.1 的 RTMP 模块。

界面使用 Compose、ViewModel、StateFlow 和 Koin；DataStore 保存采集设置，Room 保存服务器配置、会话摘要及关键事件，Ktor Client 负责认证与可选的 LAL 状态查询。

## 构建与安装

当前构建流程基于 macOS，使用已有工具链：

| 工具 | 版本 / 要求 |
| --- | --- |
| JDK | Android Studio 内置 JDK 25 |
| Gradle | 9.5.0，必须已存在于本地 Wrapper 缓存 |
| Android Gradle Plugin | 9.3.2 |
| Android SDK / Build Tools | 37.0 / 37.0.0 |
| NDK | 已安装的 26.1.10909125，仅用于 strip 依赖自带的 native 库 |
| 真机测试辅助工具 | ADB；Python 3 |

Wrapper 和构建配置禁止自动下载工具链；缺少的项目依赖允许下载。依赖版本统一在 [版本目录](gradle/libs.versions.toml) 中管理。

首次获取源码后，在项目根目录配置本地 SDK 路径并构建：

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
RTMP_ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
printf 'sdk.dir=%s\n' "$RTMP_ANDROID_SDK" > local.properties
./scripts/build.sh
```

`local.properties` 不提交到 Git。自定义 JDK 可通过 `RTMP_JAVA_HOME` 传入构建脚本；请同时检查 `gradle.properties` 中的本地 JDK 路径配置。

构建脚本执行 Debug / Release 构建、单元测试、Android Lint 和认证服务分发构建，输出：

```text
artifacts/rtmpcapture-debug.apk
artifacts/rtmpcapture-release.apk
artifacts/release-mapping.txt
auth-server/build/install/auth-server/
```

```sh
adb -s '<真机序列号>' install -r artifacts/rtmpcapture-release.apk
```

Release 启用 R8 full mode 和资源压缩，当前使用 debug key 签名以便审阅安装；正式分发前需配置私有签名。签名密钥与密码不纳入版本控制。

## 配置服务器与使用

先按 [部署说明](docs/deployment.md) 启动认证服务与 LAL。为实际部署创建本地副本：

```sh
cp deployment/auth.env.example deployment/auth.env.local
cp deployment/lalserver.conf.json deployment/lalserver.local.conf.json
```

修改副本中的账号、密码、允许的流名和 LAL key，两端 key 必须一致。这两个本地配置文件已被 `.gitignore` 排除。认证服务从进程环境读取配置，不会自动加载环境变量文件。

在应用“服务器”页面填写：

| 字段 | 示例 |
| --- | --- |
| RTMP 地址 | `rtmp://<服务器IP>:1935/live/demo` |
| 流名 | `demo` |
| 认证接口 | `http://<服务器IP>:8081/v1/publish-token` |
| 用户名 / 密码 | 与认证服务配置一致 |
| HTTP-FLV 监看（可选） | `http://<服务器IP>:8080/live/demo.flv` |

RTMP 地址最后一段须等于流名，配置地址不接受内嵌凭据或查询参数。

1. 点击“启动采集”，授予摄像头、麦克风权限。Android 13+ 请求通知权限，Android 17+ 另请求局域网权限。
2. 点击“恢复屏幕合流”，接受本次系统屏幕采集授权；每次新的屏幕采集都需要重新授权。
3. 屏幕合流时本地合成预览关闭，切换至需要共享的应用。监看默认关闭，须手动开启，监看音频默认静音。
4. 点击应用或通知中的“停止采集”释放采集与推流资源。

认证接口接收 `username`、`password`、`stream`，校验账号与流权限后返回 `lalSecret`。应用将其添加为 RTMP 参数 `lal_secret`，LAL 使用原生规则 `MD5(UTF8(key + streamName))` 校验发布。

**原生签名没有过期时间或单独撤销能力。** 修改账号权限只影响后续签名请求；已发出的签名仍可使用，更换 LAL key 才能整体失效。明确的认证拒绝会终止重试；连续三次发布成功后两秒内断开也会停止，并提示检查配置与服务器。

密码通过 Android Keystore AES-GCM 加密后写入 Room，签名只保存在内存。凭据与监看页使用 `FLAG_SECURE`。HTTP 适用于可信局域网，远程认证应使用 HTTPS。LAL 模板关闭录制、HLS 及详细认证日志，采用 RTMP / HTTP-FLV 转发。

## 测试与验证

仅使用真机测试，不启动模拟器。

```sh
# 单元测试与静态检查
./gradlew :capture:testDebugUnitTest :auth-server:test :app:lint :capture:lint

# 构建并运行 Debug 真机 instrumentation
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/device_tests.py --serial '<真机序列号>'
```

测试脚本默认安装 Debug 应用和测试 APK；可用 `--adb <路径>` 指定 ADB。`--local-backend` 会访问测试认证服务、创建示例配置，并实际启停摄像头和麦克风。运行此选项前须配置测试后端、ADB reverse 和系统权限，详情见 [测试源码](app/src/androidTest/java/com/wusui/rtmpcapture/PackagedIntegrationTest.kt) 与 [验证记录](docs/verification.md)。

检查已运行的 HTTP-FLV 流及采样 8 小时状态：

```sh
python3 scripts/probe_flv.py 'http://<服务器IP>:8080/live/demo.flv' --seconds 10
python3 scripts/endurance.py --serial '<真机序列号>' --hours 8 --out artifacts/endurance.jsonl
```

这两个脚本输出媒体元数据、时间戳及运行统计，不录制音视频。它们不替代实际声画同步、ANR、补光和长期设备验收。

MI 8 上已实测约 30.01fps，并验证屏幕恢复、旋转、系统相机抢占恢复、断网重连及后台持续推流。**8 小时耐久、实际声画同步和其他 Android 版本尚未验收**；完整结果以 [验证记录](docs/verification.md) 为准。

`artifacts/` 是本地交付目录，不提交到 Git；既有交付包含 `artifacts/auth-server`、`artifacts/verification` 和 `artifacts/apks.json`。重新构建认证服务的分发目录为 `auth-server/build/install/auth-server`。

## 许可证

本项目原创代码采用 [MIT License](LICENSE)，版权所有者为 `wusui`。允许使用、修改和商业分发，分发时须保留版权及许可声明。

第三方依赖、Gradle Wrapper 及 LAL 配置来源保留各自许可证，见 [第三方许可说明](THIRD_PARTY_NOTICES.md)。
