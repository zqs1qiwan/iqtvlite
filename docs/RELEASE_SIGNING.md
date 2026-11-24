# IQTV Lite 正式签名与 GitHub 手动发版指南

本文档面向仓库维护者，说明如何生成固定签名密钥（Keystore）、在 GitHub Secrets 中配置，以及如何手动触发正式生产包（`tv.iqtvlite.app`）与 GitHub Release 发布。

> **重要提醒**：Android 对同一包名（`tv.iqtvlite.app`）的覆盖安装及应用内自动更新要求签名证书完全一致。  
> 若更换新证书发布新版本，已安装旧证书版本（如 `v0.1.0` 使用原证书）的设备需先卸载重装一次；确定并长期保留同一套正式密钥后，后续所有更新均可直接覆盖安装。

---

## 1. 生成自定义 Keystore（一次即可）

在已安装 JDK（包含 `keytool`）的电脑本地执行：

```powershell
keytool -genkeypair -v -keystore iqtvlite-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias iqtvlite
```

- 按提示设置密钥库密码与密钥密码并妥善离线备份 `.jks` 文件与口令。
- **切勿**将 `.jks` / `.keystore` 文件或任何明文密码提交到 Git 仓库。

将生成的证书转为无换行单行 Base64 字符串（Windows PowerShell 示例，直接复制到剪贴板）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("iqtvlite-release.jks")) | Set-Clipboard
```

---

## 2. 在 GitHub 配置 Repository Secrets

打开仓库页面 → **`Settings`** → 左侧栏 **`Secrets and variables`** → **`Actions`** → **`Repository secrets`** → 点击 **`New repository secret`**，新建以下 **4 个**密钥（必须全部配置，否则无法构建并发布正式签名包）：

| Secret 名称 | 说明 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 上一步导出的 `.jks` / `.keystore` 单行 Base64 内容 |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore 密钥库密码（`storepass`） |
| `RELEASE_KEY_ALIAS` | 生成密钥时指定的 `-alias` 别名（例如 `iqtvlite`） |
| `RELEASE_KEY_PASSWORD` | 该别名的密钥密码（若与密钥库密码相同，填写一致即可） |

> 注：[.github/workflows/apk.yml](../.github/workflows/apk.yml) 同时向下兼容 `VALIDATION_KEYSTORE_*` 与 `ANDROID_SIGNING_*` 命名，优先读取 `RELEASE_KEYSTORE_*`。

---

## 3. 手动触发构建与发版

打开仓库 **`Actions`** → 选择 **`IQTV Lite Build & Release`** → 点击 **`Run workflow`**：

- **`release_version`**：正式版本名称，例如 `0.1.1`（发布 Release 时会自动加 `v` 前缀生成 `v0.1.1`）。
- **`release_version_code`**：递增整型版本号，例如 `101`。
- **`build_release_apk`**：保持勾选（构建 `tv.iqtvlite.app` 正式包并上传至工作流产物 `iqtvlite-release-apk`）。
- **`publish_github_release`**：
  - 不勾选：仅运行单测、静态检查并生成工作流内可下载的正式签名 APK，适合发版前验证。
  - 勾选：自动创建/更新 GitHub Release **`v<release_version>`**（设为 `Latest`）并同步更新 **`latest`** 滚动 Release 与对应 Tag，供应用内「设置 → 关于 → 检查更新」拉取。
