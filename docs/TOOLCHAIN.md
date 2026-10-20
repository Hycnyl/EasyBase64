# Toolchain record / 工具链记录

Configured and checked on 2026-10-20 in Debian 13 Linux x86_64.

| Component | Pinned version |
| --- | --- |
| JDK | Eclipse Temurin 21.0.12.1+1, full JDK including `javac` |
| Gradle Wrapper | 9.8.2 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin / Compose compiler plugin | 2.4.21 |
| KSP | 2.3.12 |
| Android compile platform | 37.2, revision 1, stable |
| Android Build Tools | 37.0.0 |
| Android command-line tools | 23.0, build 16111833 |
| Platform tools used for validation | 37.0.1 |
| Compose BOM | 2026.09.00 |
| Material 3 Adaptive | 1.3.0 |
| Room | 2.8.5 |

The project targets Android 16 / API 36 and requires Android 12 / API 31. Compiling with the stable 37.2 platform does not raise the minimum installation version. AGP 9.4 requires Gradle 9.6 or newer and JDK 17 or newer. The project uses AGP's built-in Kotlin support, with Kotlin 2.4.21 selected explicitly.

运行 `bash scripts/setup-android.sh` 可在 Linux x86_64 上安装固定版本的完整 JDK、Android SDK，并验证 Gradle Wrapper。默认目录是工程下的 `.toolchains`；也可以设置 `EASYBASE64_TOOLCHAINS=/your/path`。脚本生成本地 `local.properties` 和工具链 `env.sh`，不写入网络代理或账户凭据。下载的 JDK 和命令行工具均检查固定 SHA-256。其他系统可使用 Android Studio 配置表中版本。

Run `bash scripts/setup-android.sh` on Linux x86_64, then source the generated `.toolchains/env.sh`. The bootstrap supports a custom `EASYBASE64_TOOLCHAINS` directory. It uses official Google, Gradle, and Eclipse Adoptium downloads and verifies pinned SHA-256 values for JDK and command-line-tools archives. SDK package metadata supplies the SDK archive checksums. No account or backend is needed to build.

The initial environment had only a Java runtime. A full JDK was installed before compilation; a successful `java -version` alone does not establish the presence of a compiler.

When switching JDKs in a managed environment, retain the host's CA trust. The setup script copies the Debian system Java trust store when present; this preserves a managed HTTPS proxy's CA without disabling TLS certificate verification. Proxy addresses remain environment-specific and are not committed in project configuration.

## Emulator configuration

- Android Emulator 37.2.12, stable.
- Android 12 / API 31 AOSP x86_64 system image revision 5.
- Pixel 2 profile, initially 1080 × 1920 at 420 dpi.
- 2 CPU cores, 2048 MB guest memory.
- No hardware virtualization available; software TCG CPU and SwiftShader graphics.
- Headless, audio/cameras/snapshots disabled.

This configuration records the attempted runtime environment, not a claim that any particular instrumentation test passed. See the delivered test report for completed checks and limitations.

Official references:

- https://developer.android.com/build/releases/agp-9-4-0-release-notes
- https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256
- https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1
