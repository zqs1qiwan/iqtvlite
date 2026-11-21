# CI 稳定签名：validation 包跨 run 同证书（免卸载升级）

状态：已实现（代码 + 文档 + 本机脚本）；**生效需要维护者加 4 个 GitHub Secrets**（基线审计时
`gh secret list` 为空，见 `docs/MIGRATION_BASELINE.md`）。

## 1. 问题：每个 run 的 APK 签名都不同

CI 的 debug/validation 包一直用 runner 上的**临时 Android debug keystore**签名（`~/.android/debug.keystore`，
每个 job 重新生成）。因此同一个 applicationId 的 APK，证书指纹随 run 变化：

| CI run | APK 证书 SHA-256（前 8 位） |
| --- | --- |
| 10135 | `89d0cced` |
| 10136 | `2913b033` |
| 10137 | `c2546971` |

直接后果：真机 `adb install -r` 一律失败 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能**卸载重装**——
违反 `docs/DEVICE_HEALTH_PROTOCOL.md`（不卸载、不清数据），且丢最近观看/设置/台标缓存。

证书与 applicationId、versionCode、ABI 无关，只由签名私钥决定；所以唯一解是让每次 CI 用**同一把私钥**。

## 2. 方案（本仓库已实现）

```
GitHub Secrets(4)  ──►  apk.yml "Prepare stable validation signing (optional)"
                          │  解码 base64 到 $RUNNER_TEMP/validation-signing.keystore（umask 077）
                          │  校验口令/别名（keytool -list，配错立刻红）
                          │  写 $GITHUB_ENV：VALIDATION_KEYSTORE_FILE/_PASSWORD / VALIDATION_KEY_ALIAS/_PASSWORD
                          ▼
                       android/app/build.gradle.kts
                          signingConfigs.ciStable（仅当 keystore 文件存在）
                          debug（=validation 包）/ release 构建类型使用它
                          ▼
                       app-debug.apk 每次 run 都由同一私钥签名 → 证书指纹恒定
```

要点：

- **有 secrets**：`signingConfigs.ciStable` 生效，APK 证书 = 固定 keystore 证书，跨 run 完全一致
  → 设备可一直 `adb install -r` 覆盖升级，永不卸载。
- **无 secrets**：完全保持现状（ephemeral debug keystore）。CI 仍然构建成功，Step Summary 明确标注
  "not configured / unstable"，不会假装稳定。
- **只配了一部分**：直接失败并列出缺哪个 secret。宁可红，也不要静默回退到每次都会变的 debug key。
- **兼容旧名**：`ANDROID_SIGNING_KEYSTORE_BASE64` / `ANDROID_SIGNING_STORE_PASSWORD` /
  `ANDROID_SIGNING_KEY_ALIAS` / `ANDROID_SIGNING_KEY_PASSWORD` 仍可用（新名优先）。

### 涉及的 secret 名（首选）

| Secret | 内容 |
| --- | --- |
| `VALIDATION_KEYSTORE_BASE64` | keystore 文件的 base64，**单行** |
| `VALIDATION_KEYSTORE_PASSWORD` | keystore（store）口令 |
| `VALIDATION_KEY_ALIAS` | 密钥别名，例如 `iqtvlite-validation` |
| `VALIDATION_KEY_PASSWORD` | key 口令；PKCS12 下必须等于 store 口令 |

## 3. 一次性配置（维护者本机执行）

### 3.1 生成固定 keystore

Windows（本仓库脚本，需要 JDK 17+ 的 `keytool`）：

```powershell
# 默认写到 local\validation-signing.jks（/local/ 已 gitignore），并生成单行 base64 文本
pwsh scripts/gen-validation-keystore.ps1 -StorePassword '<强口令>'
```

脚本会打印 alias、SHA-256 指纹、base64 文件路径与下一步命令。重建已有 keystore 必须显式 `-Force`
（重建 = 换证书 = 已装包无法覆盖升级）。

macOS / Linux（等价 keytool 命令）：

```bash
mkdir -p local
keytool -genkeypair -v \
  -keystore local/validation-signing.jks -storetype PKCS12 \
  -alias iqtvlite-validation -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
  -validity 10000 -dname "CN=IQTV Lite Validation, OU=CI, O=IQTV, C=CN" \
  -storepass '<强口令>' -keypass '<强口令>'
keytool -list -v -keystore local/validation-signing.jks -storepass '<强口令>' -alias iqtvlite-validation
```

说明：

- `PKCS12` 只支持 store 口令 == key 口令，脚本已强制；两个 password secret 填同一个值即可。
- RSA 2048 + SHA256withRSA：Android 7+（minSdk 24）全兼容，签名体积开销固定。
- 有效期 10000 天：证书过期前不需要重新生成（过期会导致新包无法覆盖升级）。
- 私钥**只能离线备份**（keystore + 口令）；丢失后无法再用同一证书签名。

### 3.2 转成单行 base64

```bash
# Linux
base64 -w0 local/validation-signing.jks > local/validation-signing.jks.b64.txt
# macOS
base64 -i local/validation-signing.jks | tr -d '\r\n' > local/validation-signing.jks.b64.txt
```

Windows 用脚本时已生成 `local\validation-signing.jks.b64.txt`（单行、无换行）。CI 侧也会 `tr -d ' \t\r\n'`
容错，但 GitHub Secrets 存单行最稳。

### 3.3 写入 GitHub Secrets

命令行（需 GitHub CLI 并已登录，`<owner>/<repo>` 换成实际值）：

```powershell
# Windows PowerShell：从文件读取，保持单行、无尾随换行
Get-Content -Raw local\validation-signing.jks.b64.txt | gh secret set VALIDATION_KEYSTORE_BASE64 -R <owner>/<repo>
gh secret set VALIDATION_KEYSTORE_PASSWORD -R <owner>/<repo> --body "<store 口令>"
gh secret set VALIDATION_KEY_ALIAS        -R <owner>/<repo> --body "iqtvlite-validation"
gh secret set VALIDATION_KEY_PASSWORD     -R <owner>/<repo> --body "<与 store 口令相同>"
```

```bash
# Linux / macOS
gh secret set VALIDATION_KEYSTORE_BASE64 -R <owner>/<repo> < local/validation-signing.jks.b64.txt
gh secret set VALIDATION_KEYSTORE_PASSWORD -R <owner>/<repo> --body '<store 口令>'
gh secret set VALIDATION_KEY_ALIAS -R <owner>/<repo> --body 'iqtvlite-validation'
gh secret set VALIDATION_KEY_PASSWORD -R <owner>/<repo> --body '<与 store 口令相同>'
```

网页方式：仓库 → Settings → Secrets and variables → Actions → New repository secret，逐个添加上面 4 个名字。

口令/别名不要带换行、首尾空格或 `%` 之外的奇怪字符（它们会被写进 `$GITHUB_ENV` 的一行）。

### 3.4 触发一次 run 确认已生效

`Actions → Build Test APK → Run workflow`（或 push 一个分支提交）。构建日志应出现：

```
Notice: Stable validation signing enabled (keystore alias: iqtvlite-validation).
```

并且 run 的 Summary 里 APK signing 表格为 `active`，`configured keystore cert` 有值。

## 4. 如何确认“签名稳定”

### 4.1 看 CI 自带的证据（每个 run 都有）

- Actions run 的 **Step Summary** → `### APK signing certificate` 表格（debug APK / androidTest APK /
  configured keystore cert 三个 SHA-256）。
- 产物 artifact `iqtvlite-android-reports` 内的 `signing-certificate.txt`：

```
debug-apk-sha256: 3f1c...
androidtest-apk-sha256: 3f1c...
configured-keystore-sha256: 3f1c...
```

CI 还会自动断言两件事，任一不满足即失败：

1. 主 APK 与 androidTest APK 必须**同一证书**（否则 instrumentation 对不可用）；
2. 配了固定 keystore 时，APK 证书必须**等于 keystore 证书**（防止 Gradle 没吃到 signingConfig 却静默过绿）。

### 4.2 拉两个不同 run 的 APK 对比（本地，非设备命令）

```bash
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
"$APKSIGNER" verify --print-certs app-debug-run10138.apk | grep 'certificate SHA-256 digest'
"$APKSIGNER" verify --print-certs app-debug-run10139.apk | grep 'certificate SHA-256 digest'
# 两行必须完全一致，且等于 keystore 证书：
keytool -printcert -jarfile app-debug-run10138.apk | grep 'SHA256:'
keytool -list -v -keystore local/validation-signing.jks -alias iqtvlite-validation -storepass '<口令>' | grep 'SHA256:'
```

等价判别：不配 zip 的证书对比也可用 `keytool -printcert -jarfile <apk>`；注意 `apksigner verify` 对
v2/v3 签名的 APK 更可靠（本仓库 debug 包由 AGP 默认签名，含 v1+v2）。

### 4.3 真机侧预期（由验收同学执行，本任务不跑设备命令）

- **切换的第一次**：历史已装包是旧 debug 证书（基线记录为
  `646dbf613bfa1995e895c7cd16676c7bb9cba075d5cb7498eaf2b1abfc5ead42`），与任何 run 的证书都不同，
  因此第一次切到固定证书仍需**一次**卸载重装（这是唯一一次）。
- **之后**：因为 versionCode = `10000 + GITHUB_RUN_NUMBER` 单调递增，且证书恒定，
  `adb install -r` 可直接覆盖升级，不再需要卸载、不再丢数据。
  同一 run 内重跑（versionCode 相同）也能 `-r` 覆盖，不会降级失败。

## 5. 不变项（与任务/仓库约束一致）

- `applicationId` 不变：主包 `tv.iqtvlite.app`；隔离验证包 `tv.iqtvlite.app.validation`（`-PISOLATED_TEST_PACKAGE=true`）。
- `main` 分支、tag、Release 流程不变：`apk.yml` 的 tag → GitHub Release 步骤原样保留（签名身份随 secrets
  一起变得稳定，这是改进而非策略变更）。
- 体积门禁不变（≤30MB，`.github/scripts/size-gate.sh` 等）。
- `release` buildType 的 minify/shrink/proguard 逻辑未改；本次只调整了“用哪把钥匙签名”的取值来源。
- 仓库不包含任何私钥/口令；`.gitignore` 覆盖 `*.jks`、`*.keystore`、`*.b64.txt`、`/local/`。
- 不改动 `docs/DEVICE_HEALTH_PROTOCOL.md` 的“不卸载/不清数据”要求，本方案正是为了长期满足它。

## 6. 风险与代价

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| keystore 丢失 | 无法再用原证书签名，已装包只能一次性卸载 | 离线备份 keystore + 口令（密码管理器/离线介质） |
| keystore 泄露 | 他人可伪造同包名更新 | 立即换钥并记录一次性卸载代价 |
| 证书过期 | 过期后新包无法覆盖安装 | 有效期 10000 天；到期前重建并更新 secrets |
| 口令含换行/空格 | `$GITHUB_ENV`/`--body` 解析异常或 keytool 失败 | 只用单行、无首尾空格的口令 |
| 只配部分 secrets | CI 失败（这是设计） | 补齐 4 个或全删 |

**不可逆**：换证书等于换应用身份，历史 debug 证书无法“找回”或与新证书合并。

## 7. 本任务的验证范围说明

- 已在本机静态验证：`apk.yml` YAML 可解析（16 steps，顺序不变）；`scripts/gen-validation-keystore.ps1`
  PowerShell 语法检查通过，并用桩 `keytool` 跑通“生成 → 指纹解析 → base64 写入 → 提示下一步”全流程，
  另验证缺 keytool / 非法扩展名两条失败路径。
- **未在 CI 侧实跑**：这 4 个 secrets 尚未配置，且本机只有 platform-tools（无 JDK / 无 compileSdk 平台 / 无 `bash`），
  无法本地执行 workflow 的 shell 段或 Gradle 配置。因此“稳定签名已生效”必须在维护者加完 secrets 后，由后续 run 的
  Step Summary / `signing-certificate.txt` 确认（见 4.1）；在 secrets 到位前，CI 仍按旧行为构建成功（不破坏 CI）。
- 本任务未执行任何 `adb` / 设备命令。

## 8. 相关文件

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/apk.yml` | `Prepare stable validation signing (optional)` 解码 secrets → env；`Verify APK signing certificate` 断言 + Step Summary + `signing-certificate.txt` |
| `android/app/build.gradle.kts` | `signingConfigs.ciStable`（仅当 keystore 文件存在），debug/validation 构建类型使用；全缺省时回退默认 debug 签名 |
| `scripts/gen-validation-keystore.ps1` | 本机生成固定 keystore + 单行 base64 + 指纹，输出 secrets 配置命令 |
| `.gitignore` | 忽略 `*.jks`、`*.keystore`、`*.b64.txt`、`/local/` |
| `docs/MIGRATION_BASELINE.md` | 基线记录：历史已装包证书、当时 `gh secret list` 无 secret |
| `docs/DEVICE_HEALTH_PROTOCOL.md` | 为什么必须避免卸载重装 |
