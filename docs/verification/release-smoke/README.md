# 签名 release 实际操作验证

验证对象为 `EasyBase64-1.0.0-universal.apk`，SHA-256：
`8b1eb2fadddf8600ae755a16a3618c1266e6d5cbb4d0e11868a4d8457cea1123`。
先卸载 debug 和两个测试包，再直接安装上述签名 release。`install.txt`、`launch.txt` 和 `installed-package.txt` 记录安装与启动；冷启动返回 `Status: ok`。

在 Android 12 / API 31 x86_64 软件模拟器中，通过真实应用界面和系统 DocumentsUI 完成：

1. 将确定性 65,537 字节二进制样本放入 `Download/EasyBase64Smoke/Input/sample.bin`。
2. 在应用选择 `Encoded` 为输出目录，通过 Android 的「USE THIS FOLDER → ALLOW」取得授权；使用「Add files」选择样本。
3. 点击「Start encoding」，等待前台任务完成。生成的 87,384 字节输出逐字节等于 Python `base64.b64encode`。
4. 选择独立 `Decoded` 输出目录，切换 Decode，通过文件选择器选中上一步生成的 `sample.bin.b64`，点击「Start decoding」。
5. 拉取整个受控目录，运行 `verify_outputs.py`：解码结果逐字节等于原始样本，源文件不变，未发现 `.easybase64-*` 临时残留。

`result.json` 保存实际路径、字节数、SHA-256 与验证结果；`pulled/` 保留真实输出，`expected.b64` 是 Python 生成的独立参考值。`output-tree-grant.png/xml` 保存系统授权界面；相邻 UI 文本与 `docs/screenshots/release-*-completed.png` 保存完成状态。

`logcat.txt` 记录两个转换过程的 conversion 通知（含前台服务标志）。唯一 `FATAL EXCEPTION` 属于 Android 系统自带的 shell `uiautomator` XML 导出器（PID 4346 的 accessibility 空节点错误），不是 EasyBase64（PID 4216）；改用 `uiautomator dump --compressed` 后继续正常验证，应用未崩溃。

手机验证使用 540×960 像素、density 210、font_scale 1.0。转换完成后再调整到 960×640 像素、density 128，即约 1200×800 dp，截取 `docs/screenshots/release-wide.png`。宽屏为模拟器窗口尺寸验证。精确输出见 `wide-geometry.txt`。
