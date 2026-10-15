# CellLock 原生后端

`libcelllock_qmi.so` 是 arm64 命令行程序，由 App 通过 `su` 调用。它使用本机 QMI / QRTR，每次创建独立 NAS 客户端并绑定物理卡槽；只接受下列固定命令，不接受任意二进制指令。硬件频段通过 DMS 读取。

## 命令

`slot` 为物理卡槽 `0` 或 `1`，由 App 从所选订阅映射。尖括号表示参数，不应原样输入。

```text
state <slot>
cell-lte <slot> <earfcn> <pci>
cell-nr <slot> <arfcn> <pci> <band> <scs>
freq-nr <slot> <arfcn> <scs>
cell-clear <slot> lte|nr|all
band-lte <slot> <逗号分隔频段|all>
band-nr <slot> sa|nsa|both <逗号分隔频段|all>
band-gsm <slot> <850,900,1800,1900的子集|all>
band-wcdma <slot> <逗号分隔频段|all>
rat <slot> <gsm,wcdma,lte,nr的组合|all>
nr-mode <slot> both|nsa|sa
reset <slot>
restore-lte <slot> <逗号分隔完整原始频段|none>
restore-nr <slot> sa|nsa|both <逗号分隔完整原始频段|none>
restore-legacy <slot> <16位十六进制原始掩码>
```

LTE EARFCN 范围为 0–65535，PCI 为 0–503；NR ARFCN 为 0–3279165，PCI 为 0–1007，SCS 为 15 / 30 / 60 / 120 / 240 kHz。参数必须来自实际目标；NR 小区接口本身不区分 SA / NSA，`nr-mode` 是独立配置。

`all` 使用本机硬件支持列表。普通显式频段列表必须包含该制式的受支持频段，其确认只比较硬件有效范围。`restore-*` 仅供完整快照恢复：保留并逐位比较原始位，`none` 表示保存的空集合，`restore-legacy` 写回整个 64 位掩码；不能用日常 GSM / WCDMA 选频段命令代替。Java 层保留快照完整性及 SIM 映射校验。`reset` 开放硬件支持的频段、制式和 SA + NSA，并请求解除 LTE / NR 小区锁；它不是恢复初始快照。

NR 组网模式与独立 SA / NSA 频段请求省略可选 duration，使用协议默认的永久生效，避免部分固件拒绝该字段组合。若独立频段字段仍明确返回 17 / 71 / 94，只有 SA、NSA 目标相同的 `both` 请求允许重新读取后尝试合并字段；通信错误、超时和独立请求不触发合并。SA、NSA 均回读确认后，App 在当前卡槽会话中缓存合并能力，使用 `band-nr-combined`、`restore-nr-combined` 或 `reset-combined` 避免重复失败探测。这些内部命令不新增持久设置，不在只读查询中发送探针；前两种仅接受 `both`。

## 回执与确认

stdout 为一行 JSON，字段以当前源码为准：

| 字段 | 含义 |
|---|---|
| `ok` | 读取有可解析状态，或本次写入目标已通过回读验证 |
| `stage`、`message` | 阶段及中文结果；`rejected` 为拒绝，`unknown` 为接受后无法确认，`unverified` 为未达目标或多项未全部确认 |
| `cmd`、`slot` | 命令及绑定卡槽 |
| `mutated`、`writes`、`accepted` | 是否尝试写入、尝试次数、收到成功回执次数 |
| `lteCell`、`nrCell` | 小区锁状态；未知不能解释成未锁定 |
| `pref` | 制式、NR 组网、各频段完整列表及 `legacyMask` |
| `hardware` | 硬件支持频段，部分命令不重复返回 |
| `nrCombinedOnly` | 本次使用合并 NR 字段；App 仅在 NR 目标验证通过后缓存能力 |
| `steps` | 全部开放的逐项结果：`verified` / `unchanged` / `rejected` / `unknown` / `mismatch` / `transport`，保留各项写入计数与错误码 |

NR `ackOnly=true` 表示查询只收到成功结果 TLV，没有可解码的锁定状态。它不会满足锁定或解除确认；初始未知时允许的尝试也不能作为适配完成的证明。多步写入可能部分成功，任何未通过的目标均应保留“未全部确认”与回执，不假定回滚完成。

系统选择配置可能异步生效，后端在限定时间内重新查询。原生总期限为 25 秒，App 每次 Root 调用外层期限为 40 秒。写入成功只说明配置回读，不证明手机已驻留目标。

初始快照由 Java 层按卡槽和订阅保存。schema 2 要求完整偏好及原始 64 位 `legacyMask`；恢复后额外比较 LTE、NR SA / NSA、GSM、WCDMA 全部频段位、RAT、NR 模式与 legacy mask，不先与硬件做交集。清锁范围仅为 QMI 小区锁，不修改其他工具的 EFS 文件。

## 构建与验证

小区读取使用独立的只读 `libcelllock_read.so`：`sims` 查询 UIM，`cells 0|1` 在同一 NAS 连接先绑定所选订阅，再读 SYS 0x004D → LOC 0x0043 → RF 0x0031 → SIG 0x004F → SYS 0x004D。读取总期限 6 秒；绑定失败不继续。仅保留已支持的 TLV，ICCID 仍脱敏。前后 SYS 用于 Java 层核对服务是否切换；RF 扩展 32 位频道优先，旧 16 位 NR 频道不能用于校正。具体行为和限制见 [开发交接文档](../docs/DEVELOPMENT.zh-CN.md)。

在项目根目录运行 `build-native.ps1 -Ndk <NDK目录> -BuildTests`，或按主 [README](../README.md) 构建 App。编译统一由 `native/build.ps1` 执行，直接调用默认同时生成测试。`native/test_qmi` 是离线字节编码与状态判定测试；`test_diag` 检查缓冲区，`test_read` 检查 NAS 标识保留与 ICCID 脱敏，三者均不建立基带连接。

0.3 验证进度见 [VALIDATION.md](VALIDATION.md)。5G 小区 / ARFCN 锁定效果尚未实测。协议来源与固定提交由 [SOURCES.md](SOURCES.md) 记录。
