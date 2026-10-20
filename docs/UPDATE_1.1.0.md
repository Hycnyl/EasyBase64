# EasyBase64 1.1.0 / 源码更新说明

1.1.0 更新应用 ID 与命名空间为 `com.hycnyl.easybase64`；1.0.x 为 `com.orbis.easybase64`。因此新版属于独立安装，旧版本的偏好设置、任务记录与 SAF 文件夹授权不会自动迁移。

This version uses application ID and namespace `com.hycnyl.easybase64`. It is a separate Android installation and cannot update 1.0.x in place. Preferences, task history, and persisted SAF grants do not automatically transfer.

版本：versionName `1.1.0`、versionCode `3`；Android 12+。主界面将格式参数移动到输出目录之前，并调整部分中英文文案。

The source snapshot includes Android app, core and data modules and their associated tests. The matching signed APK and release attachments will be published separately.

This source-only push does not assert that a fresh comprehensive test suite has been run. No signing keystore or private credentials are included.
