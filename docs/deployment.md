# 本地 LAL 与认证服务

部署示例以 LAL v0.37.4 为基准。认证规则已对照该版本 `pkg/logic/simple_auth.go` 的 `SimpleAuthCalcSecret`；配置文件版本为 `v0.4.1`。

## LAL

复制 `deployment/lalserver.conf.json`，将 `simple_auth.key` 中的 `__LAL_KEY__` 替换成自己的值（不要提交真实 key），然后启动已有的 `lalserver`：

```sh
lalserver -c /path/to/lalserver.conf.json
```

默认 RTMP 端口 1935、HTTP-FLV 端口 8080。`simple_auth.pub_rtmp_enable=true`，`dangerous_lal_secret` 留空，避免万能签名绕过。HTTP-FLV 播放验证默认关闭，适用于隔离的本地网络；对外部署须按需求设置访问控制。

配置关闭 `record.enable_flv`、`record.enable_mpegts`、HLS、HTTP-TS、RTSP、relay 与 pprof。HTTP-FLV 缓存最多一个 GOP/60 帧，存于内存。HTTP API 8083 仅监听 127.0.0.1，避免暴露无认证管理接口；可经受控反向代理提供状态查询。

LAL 日志级别设置为 error（3），因为其原生 simple_auth 在 warn 级别记录 key 和签名。不要在实际含凭据环境中启用详细协议日志。

## Ktor 示例认证服务

已有 JDK 25 可运行 JVM 17 字节码。构建安装分发目录：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :auth-server:installDist
```

按 `deployment/auth.env.example` 设置以下环境变量，再使用已有 JDK 启动：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' auth-server/build/install/auth-server/bin/auth-server
```

交付目录 `artifacts/auth-server` 也包含构建后的 JVM 分发文件，可用同样的环境变量启动其 `bin/auth-server`。

| 变量 | 含义 |
| --- | --- |
| `AUTH_USERNAME` | 唯一示例账号 |
| `AUTH_PASSWORD` | 该账号密码 |
| `AUTH_STREAMS` | 允许的流名，逗号分隔，仅字母数字下划线连字符 |
| `LAL_KEY` | 必须与 LAL `simple_auth.key` 完全一致 |
| `AUTH_BIND` | 默认 127.0.0.1；局域网访问可显式设 0.0.0.0 |
| `AUTH_PORT` | 默认 8081 |

示例不读取 `.env` 文件，需要在外部环境中注入。服务拒绝缺失配置，不记录请求正文。请求需 `Content-Type: application/json` 及 1–4096 字节的 `Content-Length`，响应为 `Cache-Control: no-store`。真实部署使用 HTTPS 反向代理，并由代理限制请求体大小及认证请求频率。示例只演示一个账号；多个账号需要接入账号与权限存储。

App 配置示例：

- RTMP：`rtmp://<服务器IP>:1935/live/demo`
- 流名：`demo`
- 认证：`http://<服务器IP>:8081/v1/publish-token`
- 监看：`http://<服务器IP>:8080/live/demo.flv`

## USB 真机本地联调

通过 `adb -s <serial> reverse tcp:1935 tcp:1935`（同样设置 8080/8081/8083）可在手机配置中使用 `127.0.0.1` 访问电脑服务。该方式验证采集与传输，但不覆盖 Wi-Fi 拥塞和 Android 17 局域网权限；后续验收须使用真实局域网地址。

## 安全与签名限制

签名只与 key、流名有关，不携带账号、过期时间或随机数。认证服务返回成功后，LAL 独立校验签名。已泄露签名不能单独撤销；更换 key 会使全部旧签名失效。账号与流权限改变只影响之后的签名请求。

LAL v0.37.4 在认证检查前先发送 `NetStream.Publish.Start`，随后错误认证会关闭连接。因此“发布握手成功”不能代替服务器已收到音视频的检查。调试时使用受控的状态 API或 HTTP-FLV 解码验证实际数据，且不要将 URL 参数写入验证日志。
