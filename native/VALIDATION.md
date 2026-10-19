# 验证记录

本文按版本与阶段归档已有结果；历史记录中的“本轮”“尚未实现”和恢复约定仅适用于当时版本，不能作为当前操作说明。当前行为见 [开发交接](../docs/DEVELOPMENT.zh-CN.md)，版本变化见 [更新日志](../CHANGELOG.md)。原始现场证据保存在被忽略的本地诊断目录，不随源码交付。

## 当前 0.4.9 验证摘要（2026-10-16）

已有记录在 SM8250 / Android 13 / MIUI 14 完成正式 App 验收：269 项 UI / 命令、137 项弹窗 / 生命周期、34 项实际读取；快速两场景、修正后的主 / 副订阅完整清除及无变更路线通过。实测固定 EFS 清理加 SSR 会保留 QMI LTE 小区锁，因此在 EFS 前复用 cell-clear lte 清除已知锁，失败停止后续清理；修正后两卡配置、18 个固定项与系统设置回到基线。结算后页面切换、旋转或进程重建不自动读取，手动刷新恢复读取。归档记录中的最终主机检查为 1016 项，clean 构建与 lint 通过。

真实拒绝、超时、注销异常、其他设备、冷开机、LTE restrict 实际 PUT、GSM / WCDMA 活动锁仍未验证。副订阅 NR 频点文件原本缺失，未验证其已有内容的删除；NR 清后 GET 仍可能未知 / error 3，不能据此认定全部无锁。

文档整理前的本地 rebase 检查另已通过原生 NDK 构建、Java 主机纯逻辑测试、RootShell 18 场景、QMI 147 项及 EFS 内存自检，以及 clean assembleDebug / lintDebug（0 errors / 27 warnings）。Windows GCC 缺少 dlfcn.h 和 linux/qrtr.h，DIAG / 读取内存测试仅完成 NDK 编译，未在主机执行。本次文档整理没有重新运行构建、测试或真机操作。

## 0.3.0（2026-10-13）

原记录时区为 Asia/Singapore；设备为小米 14 Pro / SM8650 / Android 14。以下保留当时证据路径、数量与恢复契约。

### 设备与测试边界

- 当前设备：小米 14 Pro / 23116PN5BC / SM8650 / Android 14，已 Root。物理 slot 0 / 1 对应界面 SIM 1 / 2；每次后端任务独立核验订阅与卡槽。
- 测试保持移动数据和数据漫游关闭；不通过测速、下载或开启数据获取 NR 目标。
- 初始 SIM 1 的 RAT 为 28（不含 NR）；SIM 2 的 RAT 为 92（含 NR），但 NR SA / NSA 频段都仅 n1。按卡分别保存这些原始差异，不把整机写成“未开启 NR”。
- 用户已授权 SIM 2 正常参与可逆写入测试，与 SIM 1 同样遵循写前记录、测后恢复并回读的流程。本轮已经分别记录两张卡的独立结果。
- 写入测试前保存原值，结束后完整回读比对。LTE / NR 小区状态与频段 / 制式状态分别判断；NR 成功空应答不代表已解除。
- 本轮设备原始记录位于开发工作区 `work/celllock/device-0.3/`，纯测试记录位于 `work/celllock/validation-0.3/`。

### 已完成：Java 纯逻辑测试

快照和历史曲线测试已由主代理在 Android runtime 通过 `app_process` 执行；最新版输入校验在主机 JDK 执行。它们均不调用后端或发送基带命令，源码随项目 `tests/` 交付。

| 测试 | 结果 | 证据 |
|---|---|---|
| `SnapshotsTest` | 136 项通过，0 失败，`modemCommandsSent=0` | `validation-0.3/snapshots-result.json` |
| `InputValidatorTest` | 最新 46 项通过（主机 JDK） | 主代理本轮执行记录；`input-result.txt` 是此前 20 项的 Android 记录 |
| `SignalHistoryTest` | 22 项通过 | `validation-0.3/signal-history-result.txt` |

快照用例覆盖：保留硬件不支持的 B2 / B250 等原始位；少一个不支持位仍判不匹配；NR SA / NSA 不同时分别生成恢复命令；legacyMask 缺失或位差异拒绝匹配；partial preference 不冒充完整状态；缺失 LTE cells 或 NR typed payload 不能解释为未锁定或已锁定；各恢复命令保持原卡槽，不把初始恢复替换为 reset / all。

快照验证所用逻辑测试包：`celllock-pure-tests.jar`，SHA-256 `0A9018C9B4AE1B5C5BA6CDF60CD5067FC18C61D13948BC450FF1E23291ED526A`。该包仅含测试及其数据模型依赖，不含调用手机基带的执行代码；其中输入测试仍为此前 20 项，最新版 46 项在主机另行执行，不能混用版本数量。

### 本轮构建与原生测试

| 项目 | 状态 | 结果记录 |
|---|---|---|
| 原生 C 严格编译与新版纯协议测试 | 已通过；手机重跑 105 项全部通过，mock transport，不访问基带或网络 | `validation-0.3/native-result.json` |
| App `assembleDebug` / `lintDebug` | NOTICE 更新后的最终构建通过；lint 0 errors、19 warnings | `app/build/reports/lint-results-debug.txt` |
| APK 安装 | 最终包 188551 字节，已安装，最终页面截图来自此包 | 下列 SHA-256 及交付校验文件 |
| 交付入口 | APK 与完整源码 | [v0.3.0](https://github.com/SAGIRIxr/CellLock-QMI/releases/tag/v0.3.0) |

当前原生后端 `libcelllock_qmi.so` SHA-256：`8C039F489838CD305028860939F3E20E5AA21F85EFF8C638380D0D80FD58505F`。不能用旧版 58 项协议测试数量描述重写后的 0.3 后端。

最终 APK SHA-256：`D3343F687542CDE3BA7EA5DD58C2229AC05461738C20CB596AAE10B1D0164558`，已离线核对最终构建产物。

### 0.3 实机验证进度

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

#### 已完成：SIM 1 LTE 频段写入与恢复

通过 App 在 SIM 1 选择 B3 并确认写入，随后独立查询 `lte-band-after.json` 为 `pref.lte=[3]`、`rat=28`。测试前 `baseline-slot0.json` 的 LTE 偏好有 54 个频段位，包含硬件不支持的 B250 / B252 / B255。

`lte-band-restored.json` 与基线逐项离线比较确认：LTE 54 个原始位完全一致，RAT、NR SA、NR NSA 与 legacyMask 也保持一致。App 确认与回执界面记录为 `lte-band-confirm.xml`、`lte-band-result-rotated.xml`。此项证明 LTE 频段写入与恢复，不替代其他功能测试。

#### 已完成：SIM 2 NR 频段与双卡制式

SIM 2 的 NR 频段通过 **App 界面**选择 SA + NSA 的 n1 / n78 并确认。独立 GET `sim2-nr-after.json` 返回 SA / NSA 均为 `[1,78]`，RAT=92、nrMode=0。之后通过同一后端命令行分别将 SA、NSA 恢复为原始 `[1]`。`sim2-return-result.xml` 显示“应用频段 · 已确认”，横屏及竖屏两次旋转后的回执仍保留；截图为 `sim2-band-confirmed.png`。

以下制式与组网检查由主代理 **直接调用本 APK 的同一原生后端**完成，不记作 App 按钮触发：

| 项目 | 写入与恢复 | 证据 |
|---|---|---|
| SIM 1 RAT | 28（2G/3G/4G）→16（仅 LTE）→28 | `sim1-rat-lte.json`、`sim1-rat-restored.json` |
| SIM 2 RAT | 92（2G/3G/4G/5G）→80（LTE+NR）→92 | `sim2-rat-lte-nr.json`、`sim2-rat-restored.json` |
| SIM 2 NR mode | 0（SA+NSA）→2（仅 SA）→0 | `sim2-mode-sa.json`、`sim2-mode-restored.json` |

上述写入和恢复回执均为 `ok=true,stage=verified`。`verify_preferences.py` 已由主代理执行并对比完整偏好；另行离线核对 `final-pref-restored-slot0/1.json` 与 `baseline-slot0/1.json`，两卡 `pref` 整体完全一致，包括硬件不支持频段位和 legacyMask。本记录只读复核原始 JSON，没有再次执行该手机测试脚本。

#### SIM 2 LTE 小区配置与同期 NR 观测

App 已向 SIM 2 下发 LTE EARFCN 1650 / PCI 321。随后独立 `state` 的 `sim2-lte-after.json` 为 `lteCell.known=true,cells=[{"earfcn":1650,"pci":321}]`，因此可以确认小区锁配置回读一致。

同期 `sim2-lte-serving.xml` 的系统服务小区为 **5G NR n1、ARFCN 422930、PCI 633、约 -116 dBm**，不是这次 LTE 目标的驻留证明。它是本轮新增真实 NR 观测，不说明 NR 锁已生效；LTE 实际目标驻留未验收。

App 解除结果见 `sim2-lte-clear-result.xml`，随后独立 `sim2-lte-restored.json` 为明确 LTE 空列表。本轮虽安排了 100 秒定时清除后备，但 App 成功及空列表回读早于后备执行，因此本项确认为 App 完成解除，不记作命令行清除。两卡偏好配置已恢复；NR 小区锁状态仍未知。

#### 最终配置与数据状态

离线逐项比较 `final-slot0.json`、`final-slot1.json` 与各自 `baseline-slot0.json`、`baseline-slot1.json`：两卡 `pref`、`lteCell`、`nrCell` 整体分别一致。NR 一致指同样的未知状态，不表示已证明清除未知锁定。

`data-guard-final.json` 显示双卡 `dataEnabled=false`，唯一记录的蜂窝网络为 IMS 且 `internet=false`；`data-settings-final.json` 中三项数据漫游设置均为 0。测试临时添加的四条 iptables 规则已撤销。`traffic-before.txt`、`traffic-final.txt` 只保留原始流量计数，不据此声称计费流量为零。

### 5G 小区锁验证仍未完成

NR 小区锁与 ARFCN 频点锁均有协议实现和界面入口，但目前没有完成真实目标的设置、配置回读、驻留及解除验证。本轮虽已读到上述真实 NR 小区，仍需核对目标参数、SSB 频点与 SCS 后完成独立测试，不能沿用旧结论“始终没有 NR 观测”。出现 NR 服务之后的 `sim2-nr-state-live.json` 仍返回 `ackOnly=true`，说明锁定状态继续不可解码；它既不是“未锁定”，也不是“已适配”的证明。本轮未贸然追加 NR 小区 SET。

0.3 将小区锁、频段选择、制式与 SA / NSA 组网分开。频段或 RAT 写入得到确认，也不能替代 NR 小区锁的实测。未验证重启持久性、移动跨区表现或其他工具的 EFS 设置。

### 初始恢复契约

schema 2 快照只保存完整且可恢复的偏好配置，包括原始 legacy mask，按物理卡槽和订阅 ID 区分。恢复目标为最初频段 / 制式 / NR 组网，再请求清除小区锁；不恢复之前的小区锁定目标。

偏好恢复通过需完整比较所有保存的频段位（包括硬件不支持位）、RAT、NR 模式和 legacyMask。小区清除另需明确状态；NR 仍未知时整套恢复不能显示全部成功。`reset` 的目标为硬件支持范围全部开放，不能将其称作“原样恢复”。

## 0.4.0～0.4.8 读取与恢复演进

以下为原开发交接记录，按原阶段保留；0.4.9 已更改快照及清锁流程。

0.4.8 将普通选频段与初始配置恢复分开：`restore-lte`、`restore-nr` 和 `restore-legacy` 使用完整原值比较和写回，快照沿用 schema 2、原有保存键与首次保存规则。NR 独立字段明确拒绝后，只有 SA / NSA 目标相同的请求可使用合并字段；缓存仅在已验证的当前卡槽会话中生效，普通读取不做写探针。全部开放输出逐项结构化结果；`OperationReport` / `ResultItem` 供结果、日志及旋转恢复共用，界面不解析中文回执判断状态。

三页标题统一 24sp、移除右上 CellLock；其他观测说明在展开时显示，上下 margin 各 12dp，收起不留说明间距。`Ui.dialog` 复用现有配色和控件，固定标题与底部操作，正文滚动；确认任务、结果、记录展开与滚动位置在 Bundle 中恢复，确认后仍重新核验真实 SIM。设备页和锁定页共用 Session 的首次自动读取条件；Root 拒绝后不自动重复申请。

0.4 在 `modifications` 分支本地接入自适应读取，未发布、未上传。正式 Activity 已在小米 M2012K11AC / SM8250 / Android 13 / MIUI 14 验证：电话、精确及近似位置权限全部拒绝、系统定位关闭时仍可取得两卡 Root 电话测量与设备级 NR 邻区；还验证了普通接口、厂商 AppOps 受限回退、Root 拒绝及恢复、旋转和后台退出。

无新增 Root 开关，也无自动授权或定位设置修改。普通订阅受限时依次尝试 Root 电话、UIM 和公开卡状态；小区优先使用有新时间戳的普通接口，受限或只有旧缓存时尝试 Root 电话。0.4.4 起 Root 可用且映射未改变时，即使电话已有完整服务身份也补读严格绑定的 NAS，再补充设备级 NR DIAG。UIM-only 不伪造 subId；NAS/DCI 时间或映射未完成核验时不冒充可信服务测量，同频候选资格见下方 0.4.2 说明。Session 写入前后均重新读取真实订阅映射，缓存只用于界面草稿。

已通过：读取解析 364 项（含既有双卡原始回包和 30 个 NR 日志）、输入 46 项、历史 22 项、SIM 状态 8 项、快照 136 项及原生 mock 协议 105 项；构建与 lint 通过（0 errors / 25 warnings）。正常 DCI 会话能退出、后台无读取子进程或临时目录残留；厂商初始化挂死、异常注销及其他设备适配尚未真机演练。

本轮修复 APK 安装目录的 linker namespace 无法加载厂商 libdiag：仅将自编 DIAG ELF 放入 Root 私有 `/data/local/tmp` 临时目录执行，正常退出删除，不复制厂商库或修改系统配置。DIAG 单次无日志时独立保留最多 60 秒的已接收观测并标年龄，不参与曲线或锁定。Root 重新允许后，手动刷新会补读真实订阅，保留原先所选物理槽。

0.4.1 后续修复：系统返回 PCI 不可用时保留有效信号，仅限制目标；Root 可用时仍尝试补全身份。DIAG 从只取最后一包改为合并短会话的最多 32 包 / 96 KiB，避免漏掉前面包中的邻区。当前主机检查 689 项、原生缓冲边界自检通过，普通读取回归有同权限下的新旧正式包对照。详细条件及本轮恢复基线见上述读取验证记录。

0.4.2 按用户要求放宽候选资格：诊断 / NAS 邻区只要与当前服务制式、频段、频点一致且 PCI 合法，最近接收的观测可填入所选 SIM 的锁定表单；不伪造 SIM 归属、原始测量时间或服务角色。写入参数及订阅到卡槽核验仍由原流程负责。`DiagnosticCells` 合并最近 15 秒诊断观测，每个小区独立过期；选卡、服务频段或频点变化清空，读取无包后等待 5 秒再探测。普通 / Root 电话逐字段处理不可用值，NAS / DIAG 已知占位 PCI 不再丢弃整条记录；未知协议、截断、非法非占位身份仍拒绝。保留并显示可得的 RSRQ、SINR、RSSI、CSI 指标、CI/NCI、TAC、MCC/MNC、运营商、角色及频段来源；其他来源的有效记录不会因补读覆盖而消失。主机检查见本轮读取验证记录，其他机型及实际 NR 驻留不据此认定通过。

0.4.3 工作区审查修复：空结果的权限引导不再次调用手动刷新，恢复现有 5 秒调度；权限历史按 `permissionAsked:<权限名>` 分别保存，旧的统一标记不阻止首次申请另一权限。NR 同频候选的推算频段通过现有 `Bands.nrBandForArfcn` 优先匹配已知服务频段，真实系统报告的频段不覆盖；邻区显示及锁定表单使用同一规则。DIAG 入口连续两轮无包后重新尝试两个入口，手动刷新重置探测，不推断 processor 与 SIM 的对应关系。NAS 保留服务 PLMN、TAC、CI/NCI，LTE 邻区不继承服务标识；ICCID 始终脱敏，测试归档单独脱敏网络字段。读取完整性统一使用 `RadioCell.hasMappedIdentity`，仍与写入资格分开。`RootShell.quote` 及已有频点常数复用；删除无调用的 `CellsPage.serving()` 和重复命令拷贝。原生编译集中到 `native/build.ps1`，根目录脚本仅转调，原有输出位置与入口保持可用。

0.3 已完成的验证：

- App 界面设置 SIM 1 的 LTE B3、SIM 2 的 NR SA + NSA n1 / n78，独立读取确认后恢复完整原始频段位。
- 直接调用 APK 同一后端验证 SIM 1 RAT 28→16→28，SIM 2 RAT 92→80→92，SIM 2 NR mode 0→2→0；完整偏好与原值一致。这部分不是 App 按钮触发。
- App 设置 SIM 2 LTE EARFCN 1650 / PCI 321，独立 GET 确认锁定配置；App 解除后再次读到 LTE 空列表。同期系统界面显示的是 5G NR 服务，不能据此宣布 LTE 目标驻留已验证。
- 已出现真实 NR 系统观测：n1、ARFCN 422930、PCI 633、约 -116 dBm。这是读取证据，**不是 NR 小区锁或 NR ARFCN 锁定成功证据**，也不能直接补出未测得的 SCS。
- `assembleDebug` / `lintDebug` 通过，lint 0 errors / 19 warnings。快照 136 项、输入校验 46 项、信号历史 22 项、原生纯协议 105 项通过。
- 最终包已安装；三页与频段页均有真实截图，前台新时间戳采样、曲线绘点、切卡清历史、回执横竖旋转保留已观察。后台暂停本轮未单独计数验收，整套初始恢复 / 全部开放仍未完成真机验收。
- 最终两卡偏好与 LTE / NR 小区状态均与各自基线一致；其中 NR 状态仍未知。双卡移动数据关闭，漫游设置为 0，无 cellular INTERNET 网络；原始流量计数不作为零计费证明。

5G 小区锁和 NR ARFCN 锁定已有实现，但真实设置、配置回读、驻留、解除尚未完成整套验收。出现 NR 服务后，NR 锁状态 GET 仍是 ackOnly，不能据此恢复未知的原锁状态，因此本轮没有贸然追加 NR 小区写入。测试期间移动数据与漫游保持关闭；两张 SIM 都可参与可逆测试，必须分别保存原值并恢复。详细证据和后续更新统一维护在 `native/VALIDATION.md`。

最新归档验证摘要（2026-10-15，沿用既有记录，本次文档整理未重新执行）：

| 版本 | 已记录的检查 | 边界 |
|---|---|---|
| 0.4.6 / code 10 | 主机 706 项、原生 QMI mock 105 项及读取 / DIAG 内存自检、Android 页面 54 项；实际 App UID 在权限全允许 / 全拒绝下各 20 项；构建及 lint 通过（0 errors / 23 warnings） | 旧错误频段通过脱敏格式和模型夹具回归；未在真机再次出现，不等于现场复现 |
| 0.4.7 / code 11 | 主机 721 项、Android 页面 / 命令 76 项、分类修复阶段实际 App UID 检查 39 项；构建及 lint 通过（0 errors / 24 warnings） | 后续文字隐藏重跑页面检查并确认真机显示，未重跑 39 项实际 UID 检查 |

验证设备不支持双卡同时 5G；双 NR 载波仅有模型 / 解码回归。实际 NSA、NR 锁定 / 解除 / 驻留、SCS 锁定效果、长时间稳定性和其他机型仍未完成验收。上述读取和页面检查不执行网络设置 SET，不能作为写入成功证据。


### 旧页面与测试覆盖补充

以下保留当时的展示规则和测试覆盖，当前新增功能不据此认定已通过。

0.4.4 的页面副标题、SIM 来源后缀、当前连接详情及采集来源说明已隐藏；邻区保留来源和信号质量，底层 `details` 与诊断能力保持。锁定页按制式 / 频段 / 小区、NR / LTE 排序，读取失败仍显示未知。NAS 仅补充新观测，不覆写已有电话数据；相同制式、频点、PCI 且身份不冲突时去重，纯频点提示不重复已有小区。服务选择先比较可信映射，再比较 NR / LTE 和身份完整度，避免补读 NR 抢占已映射 LTE。

0.4.5 邻区隐藏系统 / Root 电话来源的可见文字与无障碍来源播报，仍保留原始数据用于诊断。小区采集进行中或被设置任务暂停时显示提示；新鲜观测不因普通刷新禁用，过期观测继续拒绝填表。Android 检查补入真实 Root 电话邻区夹具，以及离页后观测变旧、回页禁用和读取 / 设置暂停提示，避免仅用 DIAG 夹具漏测来源。

0.4.7 由 `RadioCell.sameCell / surrounding / isNeighborOf` 统一判断显示去重和周边资格，`CellsPage` 将其他观测默认折叠，隐藏缺少 PCI 的纯频点提示和条目的“SIM 归属未知”后缀，底层信息保留。不同完整身份、TAC 或已确认频段的记录不合并；冲突服务保留待核对，同频候选仍按现有规则填表，不伪造 SIM 归属。主机检查覆盖双 NR 载波分别判断、冲突及过期副本；Android 页面检查覆盖折叠、切卡、数量、空记录及校正目标。

## 2026-10-15 实现前调查

默认完整范围路线是历史研究，当前版本已移除登记入口，不能据此操作当前 App。D1 指目标频段追加，D2 指清锁研究，D3 指快照，D4 指 Root 会话复用。

### 2026-10-15 实现前验证补充

本轮沿用手机原有 0.4.8，未安装修改版主 App。全开下 n28 同频及 n28→n41 跨频直接小区锁可确认驻留，完整偏好不变；仅允许 n28 时锁 n41、反向仅允许 n41 时锁 n28，均只有 SET 接受，GET3，观察期未驻留。n78 无可信现场目标，不能使用预览示例代替。省略系统选择字段、恢复持久频段偏好都未清掉 n28 限制，真正自动协议仍未解决。

LTE 两目标和 NR 两频点已在临时固定探针上完成“两项→单项→重放两项”的配置往返，正式多目标恢复尚需实现；LTE 固件可调整目标顺序。n28/SCS15、n41/SCS30 的稳定测量与锁参数对应已交叉确认；其他 SCS/重叠频段未覆盖。普通电话观测和 DIAG 在仅 n28 时未找到已知 n41 目标，没有建立可用独立定向测量接口，查询只作参考，不能阻止合法历史目标填表。

真实应用 UID 下可复用 Magisk shell；临时拒绝授权后，旧 shell 仍为 UID0，新申请被拒绝，恢复原策略后新申请成功。授权缓存与常驻 shell 可以结合，但不能让已运行进程立即失权。D4 已确认进程期间长期复用，后台/空闲不主动关闭，授权缓存保留；失效时按需有限重建，不能中断 DIAG 清理或重发可能已发送的写请求。当前 RootShell 的 18 个主机模拟场景本轮通过，不代表修改版主 App 的长期/生命周期真机验收完成。

收尾双卡可读配置、应用偏好、安装文件哈希、网络开关、Root 原策略与 SIM 映射及临时目录等 16 项核对全部通过。NR 与基线相同仍为未知/error3。原始证据、方案及 D1～D4 后续决定保存在本地忽略目录 `local-diagnostics/2026-10-15/old-app-lock-comparison/`，不包含于公开源码交付。

### 2026-10-15 D2 后续研究补充

用户提供的 Cellular-Pro 新分析及清除抓包已另行复核。当前手机同版本样本存在相关 EFS 路径；本机旧 QMI 锁 n28 会创建主订阅64字节 NR SA/NSA覆盖项，内容与 QMI 位图对应。只删除本轮创建的两项后，GET确认缺失，SYNC/STATUS确认同步完成，运行时 SA/NSA 仍为 n28；删除后再请求恢复持久频段偏好也未解除。该结果支持继续研究重新加载步骤，不能称一键清除已经成功。别机抓包的末帧与公开工具 modem crash 命令一致，未在本机执行；正常基带重启也会影响双卡，是否接受此代价另列待决定。

本轮已将两卡可读偏好和小区状态恢复，并还原8项原本缺失的 EFS 状态；安装哈希、Preferences、网络设置、DIAG退出和临时目录等15项最终核对通过。没有安装新主 App、执行基带重启或改动正式业务代码。新报告及原始回执仍仅存上述忽略目录的 `unlock-research/`，引用用户资料原件及既有实测。精确重写相同 QMI 列表仍会留下覆盖项，未来 D2 若接入 EFS 恢复，D3 快照还需记录已验证项的存在/缺失/未知与原字节，不能将 QMI 值相同宣称为持久存储或出厂状态完全还原。

### 2026-10-15 默认完整范围路线验证

用户提供真正清除后的默认状态后，本机读出NR SA/NSA各46位、LTE51位，均与硬件支持列表不同；8项频段EFS均缺失。旧版后端从单频段限制写回该完整范围，完整偏好与默认基线一致；固定探针NR SA512位全1、旧版LTE256位全1则被基带明确拒绝。NR旧CLI最初因1024字节CSV缓冲限制拒绝，未发到基带，该无效样本与后续直接协议拒绝分开归档。

同卡LTE+NR同时限制后的组合试验进一步通过：只写回可靠默认完整范围并独立回读，再删除主订阅4个相关频段覆盖项、确认同步，运行时及受检EFS缺失态均回到干净基线，无需重启基带。最终另做只读核对，16项全部通过。证据在同一忽略目录的 `full-range-validation/`，总方案已引用；正式业务代码和手机旧0.4.8未改。此路线需要可靠默认范围来源，不能将首次读取、普通快照或本机固定值当成跨设备默认；GSM/WCDMA/legacy、小区组合清除及跨重启仍未验证。

后续交互按用户选择拆分：配置管理沿用现有选项弹窗，独立提供“清除全部锁定”和“开放全部支持频段”，保留保存/读取配置及小区页签的专用清除。组合清锁恢复可靠默认范围并清相关覆盖项及小区/频点锁；频段开放只设置硬件支持范围，不清小区/频点锁；专用清除不改频段。三种操作均保留 RAT/NR mode，复用确认及备份三选并明确实际范围。缺可靠默认范围时不静默降级为硬件列表。该决定及独立验收条件已写入本地总方案2.2、后8a与D2，该决定时尚未修改正式业务实现；现已接入 0.4.9，验证范围见下节。


### 原生配置往返与早期集成

2026-10-15，SM8250 / MIUI 14 的原有 0.4.8 后端已验证 NR PCI 锁与 ARFCN 频点锁的设置及独立回读。频段全开时，n28 同频及 n28→n41 跨频 PCI 锁可实际驻留，未附带频段、RAT 或 NR mode 写入；显式仅允许 n28 时直锁 n41、反向仅允许 n41 时直锁 n28，SET 被接受但 GET 返回错误 3，观察期内未驻留目标。RAT 排除 NR 或仅 NSA 时，即便目标配置可回读，也不能认定实际接入成功。

NR 已锁后清除被接受，GET 仍可能错误 3；重复清除的 SET 可被拒绝，不能把所有 GET3 当作明确无锁。临时固定探针另验证 LTE 两小区、NR 两频点的完整列表重放；LTE 回读顺序可能变化，目标集合相同不应误报失败。该轮旧 CLI 只提供单目标设置；0.4.9 已新增完整列表恢复，实际效果仍待新版验收。

本机省略系统选择字段、请求恢复持久频段偏好后，显式 n28 限制仍在；尚未验证真正清频段/恢复自动的通用接口。当前 `reset` 仍是开放硬件支持列表并调整制式/组网，不能称为字面上的清除全部锁定。这些是本机有限条件下的结论，未验证 n78、其他机型或重启持久性。原始记录保存在被忽略的本地诊断目录，不随源码交付。


0.4.9 已在受检 alioth 主卡槽完成新App的组合写入验证：默认范围登记、运行时完整范围恢复、四项UNLINK、SYNC/STATUS和八项缺失回读均通过，并另核对双卡基线。EFS与原DIAG复用Root私有临时目录执行，避免APK路径的隔离linker namespace拒绝加载厂商库。NR清除请求已接受，但清后GET仍为未知/error3；跨重启、更广设备/卡槽和其他持久锁类仍未验收。前文0.4.8记录保留为历史依据，不代替本版结果。


## 2026-10-16 集成与界面验证过程

以下阶段记录按发生顺序保留。临时 0.4.10 已恢复为 0.4.9 / 13；可关闭追加选项、默认登记及旧清全路线均已被后续实现替代。

### 0.4.9 实现与本轮验证（2026-10-16）

已实现分卡小区收藏、备注及删除、历史目标填入原表单、统一备份三选、D1 显式追加、D3 已知/未知快照、完整列表恢复和三个独立清锁/开放入口；沿用现有 Ui 的卡片、选项、输入及可滚动弹窗，固定底部三选。D4 授权缓存和常驻 shell 保留。

本轮主机检查：Java 纯逻辑 586 项（包含快照 174、小区/规划 36）、原生 QMI 147 项、固定 EFS 报文边界检查、RootShell 18 场景、恢复清理 25 项通过；`assembleDebug` / `lintDebug` 通过，lint 0 errors / 25 warnings。Android UI/弹窗测试已更新且编译，尚未在设备运行。新增测试覆盖旧快照不清未知锁、完整 NR 掩码/频点列表、LTE 重排、保存隔离与失败、D1 并集及同频不改频段。EFS 测试只构造内存报文，不证明厂商接口实际可用。

用户接入真机后已覆盖安装0.4.9：页面/命令113项、弹窗/生命周期107项、实际读取35项、新功能写入102项及独立收尾26项通过。验证了保存及登记默认、实际长按保存、历史填表、三选备份、完整锁参数恢复、D1并集、专用清除、频段开放、受限清全、另一SIM不变、后台21秒Root复用及死shell重连。NR清后GET仍为未知/error3，因此清全保留分项及“部分完成”，不声称已确认所有锁类。跨重启、多日后台和其他设备/固件/卡槽仍未验收。详细实施报告、安装包及验证摘要保存在同一忽略的本地调研目录 `local-diagnostics/2026-10-15/old-app-lock-comparison/implementation/`，总方案相应章节引用。

真机发现并修正EFS组件直接从APK路径执行时的linker namespace限制；现在与RadioReader的DIAG复用RootShell同一临时复制、执行和trap清理入口，原有18个Root模拟场景回归通过。频段开放确认说明同步实际行为：已允许全部支持位时不重复写入，也不强制删除硬件能力外的原始默认位。

此前608,502字节APK有272,780字节增量打包空隙；相同条目完整重打包即消除，最终约335,814字节。新增内容约60KiB，主要为Java功能和EFS组件，无新增第三方依赖或测试组件打入正式APK。交付前继续使用clean重打包并检查ZIP空隙；不靠删除功能减小体积。

### 0.4.10 界面调整（2026-10-16）

修复 LineIcon 未计算 padding 导致书签过大的问题；保留 48dp 点击区域，缩为 24dp 图标，透明底色。保存列表隐藏附加观测信息，时间在列表和详情中独立显示为 11sp 浅灰色。配置管理改为深绿色按钮，锁定页收藏入口使用白底细边框；SCS 清除系统背景 tint，同组字段统一 NR-ARFCN / PCI / NR Band / SCS，中文说明保留专业含义。

用户本轮将 D1 默认值改为开启：仅对完整已知允许列表排除的目标，在新确认时默认追加，可关闭；旋转恢复关闭选择，目标已允许时仍无频段写入。沿用原备份三选、并集、写前重读及制式/组网保持逻辑。D3、D4 不变。

0.4.10（versionCode 14）已覆盖安装到当前 alioth。586 项主机、117 项 Android UI/绘制/命令和 112 项真实弹窗/生命周期检查通过；7 张真机截图已核对。全部偏好值与测试前相同，测试夹具和测试包已清理。clean assembleDebug / lintDebug 通过（0 errors / 27 warnings，新增两条为国际化提示）；APK 337,486 字节，无 ZIP 内部空隙，安装哈希一致。产物与截图在本地忽略目录 `local-diagnostics/2026-10-15/old-app-lock-comparison/implementation/ui-polish-0.4.10/`，详见其中 `界面调整与验证.zh-CN.md`。本轮未重做实际锁网写入或跨重启测试，沿用上一轮已记录的底层能力边界。

### 0.4.9 样式复核与版本恢复（2026-10-16）

用户明确样式调整继续沿用 0.4.9，因此版本恢复为 0.4.9 / 13，已用 `adb install -r -d` 从临时 0.4.10 降级覆盖安装，未卸载主 App。上一节及其产物保留为历史记录。

书签隐藏方式改为 INVISIBLE，使三页标题行保持相同高度；收藏条目改浅灰独立圆角，内边距 12dp、间距 8dp；锁定为实心主按钮，清除及配置管理复用白底绿色描边；频段 FlowLayout 改为自适应列数的等宽网格，均分像素余量，完整行占满可用宽度。按用户最新要求删除追加频段复选框，保留自动追加及最终确认前的简短说明；已允许目标仍不额外写频段，返回不执行。

189 项 UI/绘制/命令及 116 项实际弹窗/生命周期检查通过，覆盖三页顶部对齐、字体缩放和窄屏网格、隐藏/空列表、无追加选项和旋转保持等。修改前后分别保存 11 张真机截图，主要变更已核对；全部偏好值相同，测试包和临时图片清理完成。clean assembleDebug / lintDebug 通过（0 errors / 27 warnings）；APK 336,842 字节，无 ZIP 内部空隙，安装与交付哈希一致。证据、安装包及 `样式复核与验证.zh-CN.md` 在 `local-diagnostics/2026-10-15/old-app-lock-comparison/implementation/ui-refinement-0.4.9/`。本轮未重做实际基带写入、Root 或跨重启测试，D3、D4 及既有硬件能力边界不变。

### 0.4.9 入口与整页层级复核（2026-10-16）

此前只统一描边仍留下两个问题：锁定/清除间距为 9dp，而配置管理前为 20dp；收藏整行按钮挤在两组分段控件之间。现将配置管理移到锁定页固定标题栏右侧，三个锁定页签随时可用；将收藏改为锁定目标卡片右上角的无边框文字入口，继续打开同一份分卡记录，填入原表单后由用户确认。底部只保留实心锁定与描边专用清除，间距统一为 12dp。复用 Ui.textButton，不修改其他按钮的配色；小区页现有书签保持原样。

顶部高度由标题行最小 48dp 保证，无入口时也不收缩。NR 参数列的间隔改为随列隐藏的 margin，锁频点时 ARFCN 与 SCS 左右对齐；NR 状态提示隐藏时不保留额外空隙。269 项 UI/绘制/命令及 125 项实际弹窗/生命周期检查通过，新增覆盖 320/360dp、字体 1.0/1.3、NR 小区/频点/LTE 三表单入口与间距、忙时禁止打开以及三个锁定页签的真实配置管理入口。本轮构建和 lint 通过，未新增依赖或改变基带操作。截图、APK、偏好核对和完整记录在本地忽略目录 `local-diagnostics/2026-10-15/old-app-lock-comparison/implementation/ui-actions-0.4.9/` 的 `入口与整页复核.zh-CN.md`；上一轮样式记录保留作为对照。

## 早期手工测试入口（历史参考）

以下保留原手工编译 / dex 运行方法，供追溯测试路径；不是当前推荐入口。尤其快照示例未列出当前新增的 SavedRecords 等依赖，不保证直接执行成功。当前完整编译输入与运行方式以 tests/run-host.ps1 及开发交接为准；不要把历史命令执行失败误判为当前测试失败。

参数、曲线与 SIM 状态测试可直接在 PC JDK 运行：

```powershell
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
New-Item -ItemType Directory -Force tests/local-classes | Out-Null
& $javac -encoding UTF-8 -d tests/local-classes app/src/main/java/com/celllock/app/InputValidator.java tests/InputValidatorTest.java app/src/main/java/com/celllock/app/SignalHistory.java tests/SignalHistoryTest.java app/src/main/java/com/celllock/app/SimStatus.java tests/SimStatusTest.java
& $java -cp tests/local-classes com.celllock.app.InputValidatorTest
& $java -cp tests/local-classes com.celllock.app.SignalHistoryTest
& $java -cp tests/local-classes com.celllock.app.SimStatusTest
```

快照测试依赖 Android `org.json`，不能用 SDK 的 stub jar 直接当桌面运行库。可将当前模型和测试编译为 dex，在 Android runtime 执行；这只运行内存逻辑，不调用后端：

```powershell
$sdk = $env:ANDROID_SDK_ROOT
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $env:JAVA_HOME 'bin/jar.exe'
$androidJar = Join-Path $sdk 'platforms/android-35/android.jar'
$d8 = Join-Path $sdk 'build-tools/35.0.0/lib/d8.jar'
New-Item -ItemType Directory -Force tests/snapshot-classes | Out-Null
& $javac -encoding UTF-8 -cp $androidJar -d tests/snapshot-classes app/src/main/java/com/celllock/app/Models.java app/src/main/java/com/celllock/app/Snapshots.java tests/SnapshotsTest.java
& $jar --create --file tests/snapshot-classes.jar -C tests/snapshot-classes .
& $java -cp $d8 com.android.tools.r8.D8 --lib $androidJar --min-api 30 --output tests/snapshot-tests.jar tests/snapshot-classes.jar
adb push tests/snapshot-tests.jar /data/local/tmp/celllock-snapshot-tests.jar
adb shell 'CLASSPATH=/data/local/tmp/celllock-snapshot-tests.jar app_process /system/bin com.celllock.app.SnapshotsTest'
```
