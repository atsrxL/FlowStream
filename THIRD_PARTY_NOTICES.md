# 第三方依赖说明

本源码包不捆绑第三方二进制。Gradle 在具备网络的构建环境解析以下直接依赖及其传递依赖；它们的上游许可证不受本工程 MIT LICENSE 替代。

| 直接依赖 | 固定版本 | 上游 |
| --- | --- | --- |
| Android Gradle Plugin | 8.13.2 | https://developer.android.com/build |
| Media3 exoplayer / HLS / DASH / UI / OkHttp datasource / session | 1.9.3 | https://github.com/androidx/media |
| AndroidX annotation-experimental | 1.3.1 | https://developer.android.com/jetpack/androidx/releases/annotation |
| OkHttp | 4.12.0 | https://github.com/square/okhttp |
| SMBJ | 0.14.0 | https://github.com/hierynomus/smbj |
| SLF4J NOP | 2.0.16 | https://www.slf4j.org/ |
| desugar_jdk_libs | 2.1.5 | https://github.com/google/desugar_jdk_libs |

此清单不等于完整依赖树、SBOM、许可证全文合集或漏洞扫描结果。发布二进制前应基于实际 Gradle 解析结果收集所有传递依赖许可证，并检查 NOTICE 文件和各许可证的分发要求。

构建后可先运行 `./gradlew :app:dependencies --configuration debugRuntimeClasspath` 查看实际解析树。未在当前环境中解析该依赖树，不能保证没有版本替换或传递依赖变化。
