# EasyBase64

English · [简体中文](README.md)

A file Base64 encoder and decoder for **Android 12 and later**, developed by [Hycnyl](https://github.com/Hycnyl). [Download the universal APK](https://github.com/Hycnyl/EasyBase64/releases/latest); no CPU architecture selection is needed.

## Features

- **Batch processing:** select multiple files or scan a folder, optionally including subfolders and preserving relative paths.
- **Encoding options:** Standard / URL-safe alphabets, trailing padding (`=`), no wrapping or 64 / 76-character lines, LF / CRLF, a final newline, and Windows `certutil`-style CERTIFICATE wrapping.
- **Decoding options:** independently allow ASCII whitespace, accept CERTIFICATE wrapping and accept omitted padding, with full-input validation.
- **Files and tasks:** stream file conversions into a separate output subfolder for each batch, with automatic numbering for duplicate names. View per-file status, progress and errors; cancel or retry manually.
- **Bilingual interface:** English / Simplified Chinese, dynamic system colors, light / dark themes and adaptive layouts.
- **Local processing:** access files through the system picker, with no account, ads, telemetry or Internet permission.

## Version changes

- [**1.1.0**](https://github.com/Hycnyl/EasyBase64/releases/tag/v1.1.0-public): move Format options before Output folder; update Settings, developer and GitHub links, and bilingual option wording. The application ID changes to `com.hycnyl.easybase64`, installed separately from 1.0.x; settings, tasks and file permissions do not migrate automatically.
- [**1.0.0**](https://github.com/Hycnyl/EasyBase64/releases/tag/v1.0.0-public): first public release, with batch file/folder conversion, format options and task management.

## Credits

- [AndroidX](https://developer.android.com/jetpack/androidx): Compose, Material 3, Adaptive, Room and other UI/data components.
- [Kotlin](https://github.com/JetBrains/kotlin) and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines): the standard library and coroutine support.

These components use the Apache-2.0 license; see [third-party notices](docs/THIRD_PARTY_NOTICES.md). Original project code is licensed under the [MIT License](LICENSE), Copyright © 2026 Hycnyl.
