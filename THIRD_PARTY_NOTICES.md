# 第三方许可说明

根目录 [LICENSE](LICENSE) 适用于本项目原创代码，不替代第三方组件的许可证。以下列出主要运行依赖、构建 Wrapper 和配置来源；完整依赖版本以 [Gradle 版本目录](gradle/libs.versions.toml) 和解析后的依赖树为准。传递依赖与测试依赖同样保留各自许可。

| 组件 | 使用方式 | 许可证 / 来源 |
| --- | --- | --- |
| RootEncoder 2.8.1 | RTMP / common 模块依赖 | Apache-2.0；[上游项目](https://github.com/pedroSG94/RootEncoder/tree/2.8.1) |
| LAL v0.37.4 | 示例配置与原生签名规则参考；不是 APK 依赖 | MIT；[上游许可证](https://github.com/q191201771/lal/blob/v0.37.4/LICENSE) |
| AndroidX / Jetpack / Media3 | 界面、生命周期、Room、DataStore、播放器等依赖 | Apache-2.0；[AndroidX](https://source.android.com/docs/setup/about/licenses) |
| Ktor | Android 客户端与 JVM 认证服务依赖 | Apache-2.0；[上游许可证](https://github.com/ktorio/ktor/blob/main/LICENSE) |
| Koin | Android 依赖注入 | Apache-2.0；[上游项目](https://github.com/InsertKoinIO/koin) |
| Kotlin / kotlinx | 编译、协程与序列化依赖 | Apache-2.0；[Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Gradle Wrapper | `gradlew` 与 `gradle/wrapper/gradle-wrapper.jar` | Apache-2.0；[上游项目](https://github.com/gradle/gradle) |

Apache License 2.0 全文保存在 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)。分发第三方组件时保留其许可证、版权声明及上游提供的 NOTICE，不将其改标为本项目 MIT 许可。

## LAL 配置来源

`deployment/lalserver.conf.json` 基于 LAL v0.37.4 官方配置修改。相关来源适用以下原始许可：

```text
The MIT License (MIT)

Copyright (c) 2019 Chef

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
