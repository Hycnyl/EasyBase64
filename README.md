# EasyBase64

[English](README.en.md) · 简体中文

面向 **Android 12 及以上**的文件 Base64 编解码工具，由 [Hycnyl](https://github.com/Hycnyl) 开发。[下载 universal APK](https://github.com/Hycnyl/EasyBase64/releases/latest)，无需区分 CPU 架构。

## 功能

- **批量处理**：多选文件或扫描文件夹，可包含子目录并保留相对目录结构。
- **编码参数**：Standard / URL-safe 字母表、末尾填充（`=`）、64 / 76 字符折行或不折行、LF / CRLF、末尾换行，以及 Windows `certutil` 风格的 CERTIFICATE 包装。
- **解码参数**：可分别允许 ASCII 空白、接受 CERTIFICATE 包装和省略填充，并校验完整输入。
- **文件与任务管理**：流式转换文件；每批建立输出子目录，重名自动编号。显示逐文件状态、进度和错误，支持取消与手动重试。
- **双语界面**：简体中文 / English，支持系统动态配色、浅色 / 深色主题和自适应布局。
- **本地处理**：使用系统文件选择器访问文件，无账号、广告或遥测，应用无网络权限。

## 版本变化

- [**1.1.0**](https://github.com/Hycnyl/EasyBase64/releases/tag/v1.1.0-public)：格式参数移至输出目录之前；更新设置页、开发者及 GitHub 链接和双语参数文案。包名改为 `com.hycnyl.easybase64`，与 1.0.x 独立安装，设置、任务和文件授权不会自动迁移。
- [**1.0.0**](https://github.com/Hycnyl/EasyBase64/releases/tag/v1.0.0-public)：首个公开版本，提供文件及文件夹批量编解码、格式参数和任务管理。

## 引用与致谢

- [AndroidX](https://developer.android.com/jetpack/androidx)：Compose、Material 3、Adaptive、Room 等界面与数据组件。
- [Kotlin](https://github.com/JetBrains/kotlin) 与 [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)：语言标准库与协程支持。

以上组件采用 Apache-2.0 许可证，详见[第三方声明](docs/THIRD_PARTY_NOTICES.md)。本项目原创代码采用 [MIT License](LICENSE)，Copyright © 2026 Hycnyl。
