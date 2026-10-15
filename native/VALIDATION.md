# CellLock 0.3.0 验证记录

日期：2026-10-13（Asia/Singapore）。本文件记录 0.3，不能将旧版通过项目当成本轮重新验证。

## 设备与测试边界

- 当前设备：小米 14 Pro / 23116PN5BC / SM8650 / Android 14，已 Root。物理 slot 0 / 1 对应界面 SIM 1 / 2；每次后端任务独立核验订阅与卡槽。
- 测试保持移动数据和数据漫游关闭；不通过测速、下载或开启数据获取 NR 目标。
- 初始 SIM 1 的 RAT 为 28（不含 NR）；SIM 2 的 RAT 为 92（含 NR），但 NR SA / NSA 频段都仅 n1。按卡分别保存这些原始差异，不把整机写成“未开启 NR”。
- 用户已授权 SIM 2 正常参与可逆写入测试，与 SIM 1 同样遵循写前记录、测后恢复并回读的流程。本轮已经分别记录两张卡的独立结果。
- 写入测试前保存原值，结束后完整回读比对。LTE / NR 小区状态与频段 / 制式状态分别判断；NR 成功空应答不代表已解除。
- 本轮设备原始记录位于开发工作区 `work/celllock/device-0.3/`，纯测试记录位于 `work/celllock/validation-0.3/`。

## 已完成：Java 纯逻辑测试

快照和历史曲线测试已由主代理在 Android runtime 通过 `app_process` 执行；最新版输入校验在主机 JDK 执行。它们均不调用后端或发送基带命令，源码随项目 `tests/` 交付。

| 测试 | 结果 | 证据 |
|---|---|---|
| `SnapshotsTest` | 136 项通过，0 失败，`modemCommandsSent=0` | `validation-0.3/snapshots-result.json` |
| `InputValidatorTest` | 最新 46 项通过（主机 JDK） | 主代理本轮执行记录；`input-result.txt` 是此前 20 项的 Android 记录 |
| `SignalHistoryTest` | 22 项通过 | `validation-0.3/signal-history-result.txt` |

快照用例覆盖：保留硬件不支持的 B2 / B250 等原始位；少一个不支持位仍判不匹配；NR SA / NSA 不同时分别生成恢复命令；legacyMask 缺失或位差异拒绝匹配；partial preference 不冒充完整状态；缺失 LTE cells 或 NR typed payload 不能解释为未锁定或已锁定；各恢复命令保持原卡槽，不把初始恢复替换为 reset / all。

快照验证所用逻辑测试包：`celllock-pure-tests.jar`，SHA-256 `0A9018C9B4AE1B5C5BA6CDF60CD5067FC18C61D13948BC450FF1E23291ED526A`。该包仅含测试及其数据模型依赖，不含调用手机基带的执行代码；其中输入测试仍为此前 20 项，最新版 46 项在主机另行执行，不能混用版本数量。

## 本轮构建与原生测试

| 项目 | 状态 | 结果记录 |
|---|---|---|
| 原生 C 严格编译与新版纯协议测试 | 已通过；手机重跑 105 项全部通过，mock transport，不访问基带或网络 | `validation-0.3/native-result.json` |
| App `assembleDebug` / `lintDebug` | NOTICE 更新后的最终构建通过；lint 0 errors、19 warnings | `app/build/reports/lint-results-debug.txt` |
| APK 安装 | 最终包 188551 字节，已安装，最终页面截图来自此包 | 下列 SHA-256 及交付校验文件 |
| 交付入口 | APK 与完整源码 | [v0.3.0](https://github.com/SAGIRIxr/CellLock-QMI/releases/tag/v0.3.0) |

当前原生后端 `libcelllock_qmi.so` SHA-256：`8C039F489838CD305028860939F3E20E5AA21F85EFF8C638380D0D80FD58505F`。不能用旧版 58 项协议测试数量描述重写后的 0.3 后端。

最终 APK SHA-256：`D3343F687542CDE3BA7EA5DD58C2229AC05461738C20CB596AAE10B1D0164558`，已离线核对最终构建产物。

## 0.3 实机验证进度

| 项目 | 当前状态 | 所需证据 |
|---|---|---|
| 双 SIM 只读状态与硬件频段 | 新后端读取通过，卡槽分别核对 | `baseline-slot0.json`、`baseline-slot1.json`、`final-pref-restored-slot0.json`、`final-pref-restored-slot1.json` |
| SIM 2 LTE 小区设置 / 解除 | App 写入 1650 / PCI 321、独立 GET 精确确认；App 解除后 LTE 空列表。实际 LTE 目标驻留未验证 | `sim2-lte-after.json`、`sim2-lte-clear-result.xml`、`sim2-lte-restored.json` |
| SIM 1 LTE 频段 | App 选择 B3 写入，独立 GET 确认；原始 54 位精确恢复 | `baseline-slot0.json`、`lte-band-after.json`、`lte-band-restored.json` |
| SIM 2 NR 频段 | App 写入 SA + NSA 的 n1 / n78 并独立回读；随后通过命令行分别恢复 SA、NSA 原始 n1 | `sim2-nr-confirm.xml`、`sim2-nr-after.json`、`final-pref-restored-slot1.json` |
| 双卡 RAT、SIM 2 NR 组网 | 直接调用 APK 同一后端完成写入与恢复，均 `ok=true,stage=verified` | `sim1-rat-*.json`、`sim2-rat-*.json`、`sim2-mode-*.json`，详见下节 |
| 初始恢复 / 全部开放 | 尚未完成本轮实测 | 每步回执、最终完整偏好与小区锁状态 |
| 回执重派与旋转 | 返回前台可见 App 应用频段成功回执，随后横屏 / 竖屏两次旋转仍保留 | `sim2-return-result.xml`、`sim2-result-landscape.xml`、`sim2-result-portrait.xml`、`sim2-band-confirmed.png` |
| 三页整体显示及频段页面 | 最终包四张真实截图；频段清空时应用按钮禁用 | `final-home.png`、`final-device.png`、`final-lock.png`、`final-bands.png`、`empty-bands.xml` |
| 前台自动刷新、曲线与切卡 | 新采样时间戳增长、真实历史点数、切到 SIM 2 清空历史已观察；后台暂停本轮未专门计数验收 | `final-home.xml`、`sim2-selected.xml`；历史逻辑 22 项测试 |
| 移动数据关闭与测试收尾 | 最终双卡数据关闭，无 cellular INTERNET 网络；漫游三项为 0；两卡配置均与基线一致 | `data-guard-final.json`、`data-settings-final.json`、`final-slot0.json`、`final-slot1.json` |

未完成项目保持未验证，不因已有实现或纯逻辑通过而改记为真机验收通过。

### 已完成：SIM 1 LTE 频段写入与恢复

通过 App 在 SIM 1 选择 B3 并确认写入，随后独立查询 `lte-band-after.json` 为 `pref.lte=[3]`、`rat=28`。测试前 `baseline-slot0.json` 的 LTE 偏好有 54 个频段位，包含硬件不支持的 B250 / B252 / B255。

`lte-band-restored.json` 与基线逐项离线比较确认：LTE 54 个原始位完全一致，RAT、NR SA、NR NSA 与 legacyMask 也保持一致。App 确认与回执界面记录为 `lte-band-confirm.xml`、`lte-band-result-rotated.xml`。此项证明 LTE 频段写入与恢复，不替代其他功能测试。

### 已完成：SIM 2 NR 频段与双卡制式

SIM 2 的 NR 频段通过 **App 界面**选择 SA + NSA 的 n1 / n78 并确认。独立 GET `sim2-nr-after.json` 返回 SA / NSA 均为 `[1,78]`，RAT=92、nrMode=0。之后通过同一后端命令行分别将 SA、NSA 恢复为原始 `[1]`。`sim2-return-result.xml` 显示“应用频段 · 已确认”，横屏及竖屏两次旋转后的回执仍保留；截图为 `sim2-band-confirmed.png`。

以下制式与组网检查由主代理 **直接调用本 APK 的同一原生后端**完成，不记作 App 按钮触发：

| 项目 | 写入与恢复 | 证据 |
|---|---|---|
| SIM 1 RAT | 28（2G/3G/4G）→16（仅 LTE）→28 | `sim1-rat-lte.json`、`sim1-rat-restored.json` |
| SIM 2 RAT | 92（2G/3G/4G/5G）→80（LTE+NR）→92 | `sim2-rat-lte-nr.json`、`sim2-rat-restored.json` |
| SIM 2 NR mode | 0（SA+NSA）→2（仅 SA）→0 | `sim2-mode-sa.json`、`sim2-mode-restored.json` |

上述写入和恢复回执均为 `ok=true,stage=verified`。`verify_preferences.py` 已由主代理执行并对比完整偏好；另行离线核对 `final-pref-restored-slot0/1.json` 与 `baseline-slot0/1.json`，两卡 `pref` 整体完全一致，包括硬件不支持频段位和 legacyMask。本记录只读复核原始 JSON，没有再次执行该手机测试脚本。

### SIM 2 LTE 小区配置与同期 NR 观测

App 已向 SIM 2 下发 LTE EARFCN 1650 / PCI 321。随后独立 `state` 的 `sim2-lte-after.json` 为 `lteCell.known=true,cells=[{"earfcn":1650,"pci":321}]`，因此可以确认小区锁配置回读一致。

同期 `sim2-lte-serving.xml` 的系统服务小区为 **5G NR n1、ARFCN 422930、PCI 633、约 -116 dBm**，不是这次 LTE 目标的驻留证明。它是本轮新增真实 NR 观测，不说明 NR 锁已生效；LTE 实际目标驻留未验收。

App 解除结果见 `sim2-lte-clear-result.xml`，随后独立 `sim2-lte-restored.json` 为明确 LTE 空列表。本轮虽安排了 100 秒定时清除后备，但 App 成功及空列表回读早于后备执行，因此本项确认为 App 完成解除，不记作命令行清除。两卡偏好配置已恢复；NR 小区锁状态仍未知。

### 最终配置与数据状态

离线逐项比较 `final-slot0.json`、`final-slot1.json` 与各自 `baseline-slot0.json`、`baseline-slot1.json`：两卡 `pref`、`lteCell`、`nrCell` 整体分别一致。NR 一致指同样的未知状态，不表示已证明清除未知锁定。

`data-guard-final.json` 显示双卡 `dataEnabled=false`，唯一记录的蜂窝网络为 IMS 且 `internet=false`；`data-settings-final.json` 中三项数据漫游设置均为 0。测试临时添加的四条 iptables 规则已撤销。`traffic-before.txt`、`traffic-final.txt` 只保留原始流量计数，不据此声称计费流量为零。

## 5G 小区锁验证仍未完成

NR 小区锁与 ARFCN 频点锁均有协议实现和界面入口，但目前没有完成真实目标的设置、配置回读、驻留及解除验证。本轮虽已读到上述真实 NR 小区，仍需核对目标参数、SSB 频点与 SCS 后完成独立测试，不能沿用旧结论“始终没有 NR 观测”。出现 NR 服务之后的 `sim2-nr-state-live.json` 仍返回 `ackOnly=true`，说明锁定状态继续不可解码；它既不是“未锁定”，也不是“已适配”的证明。本轮未贸然追加 NR 小区 SET。

0.3 将小区锁、频段选择、制式与 SA / NSA 组网分开。频段或 RAT 写入得到确认，也不能替代 NR 小区锁的实测。未验证重启持久性、移动跨区表现或其他工具的 EFS 设置。

## 初始恢复契约

schema 2 快照只保存完整且可恢复的偏好配置，包括原始 legacy mask，按物理卡槽和订阅 ID 区分。恢复目标为最初频段 / 制式 / NR 组网，再请求清除小区锁；不恢复之前的小区锁定目标。

偏好恢复通过需完整比较所有保存的频段位（包括硬件不支持位）、RAT、NR 模式和 legacyMask。小区清除另需明确状态；NR 仍未知时整套恢复不能显示全部成功。`reset` 的目标为硬件支持范围全部开放，不能将其称作“原样恢复”。
