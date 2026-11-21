# 文档与资源索引

本目录是 iqtv 项目的可见文档入口，后续项目文档统一存放于这里。

## 当前项目文档

- [`PRD.md`](PRD.md)：产品目标、交互/网络需求、验收清单。
- [`../HANDOFF.md`](../HANDOFF.md)：本次移植的当前状态、未提交差异、构建结果、真机证据、已知问题和交接步骤。

## 设计和技术资料

- [`reference/tivimini/PAGE_AndroidTV_IPTV_页面设计.md`](reference/tivimini/PAGE_AndroidTV_IPTV_页面设计.md)：原始页面布局设计。
- [`reference/tivimini/TECH_AndroidTV_IPTV_技术架构.md`](reference/tivimini/TECH_AndroidTV_IPTV_技术架构.md)：原始技术架构文档。
- [`reference/tivimini/PRD_AndroidTV_IPTV_播放器抽屉需求.md`](reference/tivimini/PRD_AndroidTV_IPTV_播放器抽屉需求.md)：源仓库原始播放器抽屉需求。
- [`reference/tivimini/EPG_XMLTV_Parser_Design_Kotlin.md`](reference/tivimini/EPG_XMLTV_Parser_Design_Kotlin.md)：XMLTV / EPG 解析设计。
- [`reference/tivimini/IPTV_Catchup_Timeshift_Design_Kotlin.md`](reference/tivimini/IPTV_Catchup_Timeshift_Design_Kotlin.md)：Catch-up / Timeshift 设计。
- [`reference/tivimini/iptv_电视端播放器开发文档.md`](reference/tivimini/iptv_电视端播放器开发文档.md)：源仓库开发设计记录。
- [`reference/tivimini/iptv.md`](reference/tivimini/iptv.md)：源仓库项目约定。
- `../AGENTS.md`：本项目 Android 构建、测试和编码约定。
- `../README.md`：项目入口与目录/命令说明。

## 资源目录

- `../app/src/main/res/`：应用实际使用的图标、banner、主题与 drawable 资源。
- `../screenshot/`：原 tivimini 仓库配套截图。
- `../artifacts/iqtv-debug-2026-11-18.apk`：本次构建的 debug APK（不是 Release）。
- [`evidence/2026-11-18/`](evidence/2026-11-18/)：本次配置页、信息条、数字跳台和播放页真机截图。
- [`../testdata/iptv-test.m3u`](../testdata/iptv-test.m3u)：指定测试源下载快照；仅用于验证，不应据此删除扫码配置或限制播放列表。
- `../e.xml`：源仓库 XMLTV 样例数据。
- `../logo.png`、`../logo_banner.png`、`../tv.png`：源仓库品牌图片资源；应用实际引用资源以 `app/src/main/res/` 为准。

## 文档维护

- 新的产品决策先更新 `PRD.md`，再实现。
- 每轮接手后更新根目录 `HANDOFF.md` 中的状态与证据。
- 原始 tivimini 资料保持在 `reference/tivimini/`，改动时不要覆盖来源副本。
- 设备截图注明日期；测试列表只存开发快照，避免把临时服务响应当成长期保证。
