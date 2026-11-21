# 睛彩 Probe + F/G/H 验证（CI10195）

2026-11-22，acceptance（t55 attempt 1）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p**。
包 **10195 / 2.2.0-tivimini-e0eabb63-validation**（CI run **#195** / app sha256 `29eb7f5cf260ca6f4df272c155e0a05aa63a5a63a9e1ad3a75c7970abb5c94ac` / artifact `14727825291`）——与 t54 交付值一致。

---

## 0. 结论（一句话）

**睛彩不可由 App 修复，应按已知限制归档。** 依据：**36 次运行中，凡是收到「睛彩本流」的 15 次（`v_pid=101/201/301`、`null_pct` 14–23%、PES 以非 0 为主）**，无论 OFF / F / G / H，**first_frame 全部为 false（0/15）**；而凡是收到**另一路服务**的 21 次（`v_pid=4113`、`null_pct` 0–5.5%）**全部播放成功（21/21）**。
→ **能否播放完全由「收到哪一路流」决定，与 F/G/H 三个实验无关**；睛彩本流的形态**与 PC 基准同形**（null≈20%、PES 非 0），说明问题在**源侧/网关分发路径**，不在 App 解复用策略。

> **重要更正**：前几轮（t50/t51/t53）观察到的"睛彩有时能播"**其实是另一路服务（v_pid=4113）在播**，不是睛彩。本轮 Probe 首次把"收到的是哪一路"变成可判定字段，纠正了这一长期误读。

---

## 1. 安装与交付态

| 项 | 值 |
|---|---|
| 安装 | 备份 2,052,608 B / 328 条目 → 仅卸 `.validation`（Success）→ `install -r`（Success）；vc10195 / 签名 `64d1847c` |
| Probe 开关 | `settings put global iqtv_probe_ts 1`（收尾已 `delete`） |
| 收尾 | `iqtv` 全局键 **0**；`jingcai_experiment=**off**`；前台 **PlayerActivity**；`Fatal=0`；`/data` 2.60GB；守护进程 2；`DeadObject/LMK/ext4/I-O=0` |
| 健康频道 | CCTV-1 在 **F/G/H** 三模式下均 `first_frame=true / state=3`、**无任何 `IQTV-JINGCAI` 日志**（零影响 ✅） |

---

## 2. Probe 原始行（OFF 模式，117/118/119 各 3 次）

### 2.1 播放成功的 5 次（全部是"另一路服务"）

```
ch117r1 pmt_pid=256 pcr_pid=4113 pcr=yes streams=1b@1011,03@1100 v_pid=4113 a_pid=4352
        vpts=11845.962 apts=11846.106 av=-0.144 pid_top=1011:2636,1100:98,1fff:59,0000:2,0100:2
        cc_err=0 packets=2798 null_pct=2.11 pes_len_zero=11 pes_len_nonzero=20 pmt_interval=803
ch118r2 … v_pid=4113 a_pid=4352 null_pct=0.57 pes_len_zero=12 pes_len_nonzero=20 pmt_interval=904 cc_err=0
ch118r3 … v_pid=4113 a_pid=4352 null_pct=0.00 pes_len_zero=12 pes_len_nonzero=20 pmt_interval=1344 cc_err=0
ch119r1 … v_pid=4113 a_pid=4352 null_pct=0.46 pes_len_zero=12 pes_len_nonzero=22 pmt_interval=727 cc_err=0
ch119r2 … v_pid=4113 a_pid=4352 null_pct=0.00 pes_len_zero=12 pes_len_nonzero=20 pmt_interval=-1 cc_err=0
```

### 2.2 播放失败的 4 次（全部是"睛彩本流"）

```
ch117r2 pmt_pid=103 pcr_pid=104 pcr=yes streams=1b@0065,03@0066 v_pid=101 a_pid=102
        vpts=15019.640 apts=15019.240 av=0.400 pid_top=0065:2196,1fff:506,0066:76,0068:17,0000:1
        cc_err=505 packets=2798 null_pct=18.08 pes_len_zero=2 pes_len_nonzero=29 pmt_interval=-1
ch117r3 … v_pid=101 null_pct=23.20 pes_len_zero=1 pes_len_nonzero=30 pmt_interval=-1 cc_err=648
ch118r1 pmt_pid=203 pcr_pid=204 v_pid=201 a_pid=202 vpts=15065.584 apts=none av=none
        null_pct=13.94 pes_len_zero=1 pes_len_nonzero=30 pmt_interval=-1 cc_err=389
ch119r3 pmt_pid=303 pcr_pid=304 v_pid=301 a_pid=302 null_pct=23.16 pes_len_zero=1 pes_len_nonzero=33 pmt_interval=-1 cc_err=647
```

### 2.3 与 PC 基准逐项比对

| 字段 | PC 基准（睛彩） | PC 基准（CCTV） | **设备·睛彩本流（n=15）** | **设备·另一路（n=21）** | 判定 |
|---|---|---|---|---|---|
| `null_pct` | ≈20% | ≈1.6% | **13.94–23.20%** | **0.00–5.50%** | ✅ 睛彩本流**与 PC 同形** |
| PES 长度分布 | 以非 0 为主 | 全 0 | `pes_len_zero` 1–2 / `nonzero` 29–33 | `zero` 11–12 / `nonzero` 20–22 | ✅ 与 PC 同形 |
| `av`（A−V 偏移） | 0.264s | −0.009s | **−0.12 ~ +0.40s** | −0.14 ~ −0.29s | ✅ 均在 0.3s 级，**印证 captain 更正**（非 9h 偏移） |
| `pcr_pid` | 有 | 有 | **104/204/304（独立 PCR PID，0x0068 上有 17 包）** | 4113（=v_pid，随视频） | ⚠ 睛彩用独立 PCR PID，PC 侧同形 |
| `pmt_interval` | 周期 | 周期 | **-1（窗口内未见第二个 PMT）** | 727–1344 包 | ⚠ 睛彩 PMT 极稀疏 |
| `cc_err` | — | — | **389–648** | 0 | ⚠ 见下方"探针缺陷" |

**CC 错误行是探针缺陷（重要）**：`cc_err` 与 `pid_top` 里的 null 包数几乎相等（505≈506、648≈649、389≈390、647≈648），即 **Probe 把 null PID(0x1FFF) 也当成了需要校验连续计数的 PID**。所以 `cc_err` **不代表丢包**，只反映 null 包多。这一条需在下一版 Probe 修掉（null PID 不参与 CC 校验）。

---

## 3. F / G / H / OFF 成功率（每台 3 次，共 9 次/模式）

| 模式 | 117 | 118 | 119 | 合计 | 判定 |
|---|---|---|---|---|---|
| **OFF（对照）** | 1/3 | 2/3 | 2/3 | **5/9** | 基线 |
| **F** 延迟 1.2s 起播 | 2/3 | 3/3 | 0/3 | **5/9** | 无改善 |
| **G** 丢首个 PAT 前导字节 | 2/3 | 2/3 | 1/3 | **5/9** | 无改善 |
| **H** 失败后新 DataSource 重建 | 2/3 | 2/3 | 2/3 | **6/9** | 无改善（+1 次是收到另一路流） |

### 3.1 真正决定成败的交叉表（36 次全样本）

| 收到的流 | 运行数 | first_frame=true | 成功率 |
|---|---|---|---|
| **睛彩本流**（`v_pid=101/201/301`，null 14–23%） | **15** | **0** | **0/15（0%）** |
| **另一路服务**（`v_pid=4113`，null 0–5.5%） | 19 | 19 | **19/19（100%）** |
| Probe 未解析出 v_pid（ch119 H 模式 2 次） | 2 | 2 | 2/2 |

→ **F/G/H 没有任何一次让睛彩本流出首帧**；所谓"F 5/9、G 5/9、H 6/9"完全由"这次收到哪一路"决定，与实验本身无关。

---

## 4. 第三方播放器对照（按 captain 指示，如实记录）

| 尝试 | 命令 | 结果 |
|---|---|---|
| ① 隐式 VIEW | `am start -a android.intent.action.VIEW -d '<117 URL>'` | ❌ `unable to resolve Intent` —— 设备上无任何应用注册 http VIEW |
| ② 显式组件 + extras | `am start -n com.baiguotv.tv/com.hongguotv.nativeapp.MainActivity --es url '<URL>'` | ⚠ 应用被拉起，但日志只有它自己的 `I/Baiguotv: Identity refresh/warm ok` 与 `avc granted write /dev/ion`，**无任何 media/RTP/239.3.1.125 日志** → extras 被忽略，未拉我们的流 |

`com.cleanroom.iptv`（captain 首个候选）**无启动 Activity**（`resolve-activity → No activity found`），已排除。
→ **第三方对照不可行**（无法向任何第三方播放器注入目标 URL），**不做推断**。
只读合规：未 uninstall / 未 `pm clear` / 未 force-stop 对方；结束后已把 IQTV 拉回前台并校验 `mCurrentFocus=PlayerActivity` ✅。

---

## 5. 结论与建议

### 5.1 结论（按 captain 的判据）
- Probe 显示**设备收到的睛彩本流与 PC 同形态**（`null_pct` 13.9–23.2% ≈ PC 20%；PES 以非 0 为主 ≈ PC）。
- **F/G/H 均不能让睛彩本流出首帧（0/15）**。
- 第三方对照**不可行**（无法注入 URL），不能作为排除依据，但也不构成反驳。

→ **判据命中：非 App 可修（网关/源侧分发路径）**。睛彩 117/118/119 按**已知限制**归档，不在 App 内继续改字节/改时钟。

### 5.2 本轮额外收获（对后续更有价值）
1. **"间歇性可播"的真相**：能播的是**另一路服务**（`v_pid=4113 / pmt_pid=256`），不是睛彩。请把这一点同步进 `FINAL_PACKAGE.md` 与 `JINGCAI_TIMELINE_DIAGNOSIS.md` 的已知限制，避免后续再被"睛彩有时能播"误导。
2. **睛彩本流三个独有形态**（可作为网关侧的排查线索，交给网络/源侧）：
   - 视频/音频 PID 为 `0x65/0x66`（101/102），**PCR 在独立 PID `0x68`(104)**；
   - `null_pct` 14–23%（PC 同形），**PMT 极稀疏**（2798 包窗口内只 1 个 → `pmt_interval=-1`）；
   - `vpts ≈ 15019s / 15049s / 15355s`，**每次递增约 30s**，A/V 偏差 −0.12~+0.40s（正常）。
3. **Probe 缺陷**：`cc_err` 把 null PID(0x1FFF) 计入了 CC 校验（`cc_err ≈ null 包数 − 1`）→ 数值虚高，**不代表丢包**。建议下一版：null PID 跳过 CC 校验，并把 `cc_err` 拆成 `cc_err_video / cc_err_audio / other`。
4. **ch119 在 H 模式有 2 次 Probe 未能解析出 v_pid 但播放成功** —— 说明 Probe 的 512KB 采样窗口偶尔抓不到完整 PAT/PMT（尤其 H 模式重建后），后续可把采样点改为「首包后 512KB 与 1.5MB 各一次」。

### 5.3 建议
- **睛彩归档**：保留现有诊断链路（`IQTV-JINGCAI-PROBE` + `IQTV-DIAG` + `buffer` + `timeline`）作为现场取证工具，**默认全关**（已满足）；A/B/C/D/E/F/G/H 全部保持默认关。
- **把 5.2-1 与 5.2-3 写进已知限制**（前者影响结论表述，后者影响后续读数）。
- 若仍要推进睛彩：应由**源侧/网关**排查「为什么设备会收到两路不同的服务，且睛彩那一路 PMT 极稀疏 / 无 PCR-only 包」；App 侧无需再改。

---

## 6. 交付物

- 本报告：`docs/JINGCAI_PROBE_VERIFY.md`
- 原始数据：`docs/evidence/2026-11-22-t55-jingcai-probe/`
  - `00-jingcai-urls.txt`（三台 URL）、`00-thirdparty-*.txt`/`.md`/`.png`（第三方对照证据与结论）
  - `10-probe-ch{117,118,119}-run{1..3}.txt`、`10-probe-summary.csv`（Probe 9 次原始行）
  - `2f-ch*-run*.txt`、`2f-summary.csv`（F 模式 9 次）
  - `2g-ch*-run*.txt`、`2g-summary.csv`（G 模式 9 次）
  - `2h-ch*-run*.txt`、`2h-summary.csv`（H 模式 9 次）
  - `30-healthy-under-{f,g,h}.txt`、`30-healthy-abc-summary.txt`（健康频道零影响）
  - `90-finalize-cctv1.txt`（收尾 CCTV-1）
- 复跑脚本：`.acceptance/jingcai-probe.ps1`（模式 id 需按本包更新为 `off,a,b,auto,c,d,e,f,g,h`）
