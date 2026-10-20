# 将 EasyBase64 分享到 GitHub

本说明用于你手动创建仓库和发布 Release；准备材料的过程没有执行远程推送或发布。应用代码和已验证的 APK 保持不变，公开材料已包含中文 `README.md`、英文 `README.en.md`、MIT `LICENSE` 与第三方许可说明。

## 1. 新建一个空仓库

在自己的 GitHub 账号下新建仓库，例如 `EasyBase64`，可见性选择 **Public**。不要勾选自动创建 README、许可证或 `.gitignore`：这些文件已在源码包中，重复初始化可能产生无关的提交冲突。下文的 `OWNER` 请替换为你的 GitHub 用户名或组织名；仓库名称不同也请同步替换。[GitHub 官方导入说明](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)

## 2. 上传源码小包：建立一份新的提交历史

源码小包不含 `.git`。将它解压到一个独立目录，进入同时包含 `README.md` 和 `gradlew` 的项目根目录，再运行：

```sh
git init -b main
git add .
git status --short
git commit -m "Prepare EasyBase64 1.0.0 for public sharing"
git tag v1.0.0-public
git remote add origin https://github.com/OWNER/EasyBase64.git
git push -u origin main
git push origin v1.0.0-public
```

提交前查看 `git status`，确认暂存的都是公开源码和文档。若 Git 提示没有姓名或邮箱，请先在本仓库设置自己的 `user.name` 与 `user.email`，再重试提交。推送时使用你已经配置的 GitHub 登录方式。

`v1.0.0-public` 用于这份公开整理后的源码；软件版本仍是 **1.0.0**，APK 没有因整理文档而改变。

## 3. 可选：保留原有提交历史

如果希望保留开发历史，使用可选的 **公开准备版 Git bundle**，替代第 2 步；不要先执行 `git init`。将下方 `PATH_TO_PUBLIC_BUNDLE` 替换为该 bundle 的实际路径：

```sh
git clone PATH_TO_PUBLIC_BUNDLE EasyBase64
cd EasyBase64
git switch main
git remote set-url origin https://github.com/OWNER/EasyBase64.git
git push -u origin main
git push origin v1.0.0-public
```

此 bundle 已包含公开整理提交和 `v1.0.0-public` 标签，也保留原来的 `v1.0.0` 标签。上面的命令上传主分支历史和公开标签；若还要让旧标签出现在 GitHub，可另外执行 `git push origin v1.0.0`。无需删除、移动或重建原标签。

源码小包流程与 bundle 流程二选一即可。

## 4. 发布可安装 APK

仓库上传完成后，在 GitHub 的 **Releases → Draft a new release** 中选择 `v1.0.0-public` 标签，填写标题，例如 `EasyBase64 1.0.0`，复制已准备的中英文发布说明，然后添加公开附件。[GitHub 官方 Release 说明](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)

建议公开附件为：

- 原有 `EasyBase64-1.0.0-universal.apk`，用户直接安装，不需选择处理器架构。
- `easybase64-certificate.pem` 公钥证书。
- `APK-SIGNATURE.txt` 签名验证摘要。
- **公开附件专用**的 SHA-256 校验清单。
- 按需附加源码包和独立验证资料包；Debug 包属于可选下载。

不要将 APK 放进 Git 源码提交，也不必重新构建或重新签名这份已验证的 APK。不要把包含私密文件条目的旧总交付校验清单直接当作公开附件清单。分片文件的名称、重组与校验方法以随包提供的下载说明为准。

## 5. 私密签名材料单独保存

**以下内容不得上传到公开仓库、GitHub Release、Issue 或讨论区：**

- `EasyBase64-signing-private.zip`。
- `signing/easybase64-delivery.jks`、`signing/SIGNING-PRIVATE.txt`。
- 含实际密码的 `signing.properties`。
- 旧的 `EasyBase64-1.0.0-delivery.zip`：它包含私密签名包。

公开证书和签名指纹可以分享，私钥与口令不能分享。请将私密签名包保存在自己的私密备份位置。

需要在本地继续开发并使用相同签名时，另建一份本地工作副本，在那份副本中恢复签名材料；**不要把私密签名 ZIP 解压到本次公开准备目录中**。恢复步骤见私密签名说明。普通公开克隆可构建 Debug APK；签名 Release 需要维护者自己的本地签名配置。签名材料不得提交到 Git，维护者签名验收脚本也不属于普通克隆的必跑步骤。
