# CellLock 原生后端

`libcelllock_qmi.so` 是 arm64 命令行程序，由 App 通过 `su` 调用。它使用本机 QMI / QRTR，每次创建独立 NAS 客户端并绑定物理卡槽；只接受下列固定命令，不接受任意二进制指令。硬件频段通过 DMS 读取。

## 命令

`slot` 为物理卡槽 `0` 或 `1`，由 App 从所选订阅映射。尖括号表示参数，不应原样输入。cell-lte / cell-nr 分别锁 LTE / NR PCI 小区，freq-nr 锁 NR 频点，cell-clear 解除对应运行时锁，band-* 设置频段，rat 设置制式，nr-mode 设置组网；state 只读状态及硬件能力。

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
cell-lte-list <slot> <earfcn,pci,...>
cell-nr-mask <slot> <arfcn> <pci> <bands> <scs>
freq-nr-list <slot> <arfcn,scs,...>
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

QMI 系统选择配置可能异步生效，后端在限定时间内重新查询。QMI 原生总期限为 25 秒，App 对应 Root 调用外层期限为 40 秒；EFS 期限见下文。写入成功只说明配置回读，不证明手机已驻留目标。

配置快照由 Java 层按卡槽/订阅管理，新项 schema 3，兼容 schema 2 与旧单对象。保留原始完整频段位及已知/未知锁状态；完整恢复命令新增 `cell-lte-list <slot> <earfcn,pci,...>`（最多64项）、`cell-nr-mask <slot> <arfcn> <pci> <bands> <scs>`、`freq-nr-list <slot> <arfcn,scs,...>`（最多16项）。CLI 严格校验参数；LTE 与 NR 列表完整核对，允许顺序重排而不丢数量。旧单目标命令保留。NR PCI JSON 额外输出完整 bands 数组。

App 不再把旧 `reset` 暴露为清全或频段开放：开放仅写 band-*；快速清除在开放后依次执行现有 LTE/NR cell-clear，保留 RAT/NR mode。完整清除由 Java 在重载前执行 nr-mode both 及独立回读、已知 QMI LTE 小区锁清除、固定 EFS 清理、必要同步与核对，再发送一次固定 SSR；回执及安全退出处理结束即结算，不等待恢复或自动回读。两路均不使用默认登记或机型白名单。快照中 EFS 字节仅记录，不自动重放；固定操作范围见下文。

## 只读组件

`libcelllock_read.so` 通过 UIM 读取 SIM，通过严格绑定的 NAS 查询所选订阅。小区查询在共用 6 秒期限内执行 SYS → LOC → RF → SIG → SYS；绑定失败不继续，ICCID 脱敏。前后服务身份及射频关联由 Java 核验，旧 16 位 NR 频道不能用于频段校正。

`libcelllock_diag.so` 只订阅设备级 NR 测量的固定 0xB97F 2.7 格式，不据此推断 SIM 归属。短会话依次停止订阅、释放客户端和反初始化；退出未确认时暂停后续基带操作。DIAG / EFS 程序复制到 Root 私有临时目录执行，退出时清理，不修改 linker 配置。

## 固定持久项清理

`libcelllock_efs.so` 仅接受以下动作，不接受路径、任意字节或 RAW DIAG：

| 动作 | 范围 |
|---|---|
| `get-0`～`get-17`、`stat-0`～`stat-17` | 查询固定项，编号见 [efs_protocol.inc](efs_protocol.inc) |
| `unlink-0`～`unlink-16` | 删除对应固定项；Java 仅选取所选订阅已明确映射的项 |
| `put-lte-restrict` | 仅写固定 36 字节零结构；Java 核对原有布局，不创建缺失文件 |
| `sync`、`sync-status <token>` | 同步并核对完成状态 |
| `reload-once` | 单次 modem crash / SSR，影响双卡，不重发或等待恢复 |

主订阅处理四个频段偏好、mode_pref、LTE / NR PCI、NR / GSM / WCDMA 频点项及 LTE 限制结构；副订阅仅处理已明确映射的四个频段偏好、mode_pref、NR 频点与 WCDMA PSC 项，不机械拼接后缀。必要删除、写入、同步或核对未完成，不发送重载；没有持久变更时跳过同步，仍只发送一次重载。退出未确认保持保护，不将断连当作成功。

EFS 外层期限 22 秒、原生看门狗 18 秒、单次回包期限 5 秒。协议字段参考见 [SOURCES](SOURCES.md)。

## 构建与测试

从项目根目录运行 `./build-native.ps1 -Ndk <NDK目录>`，四个可执行 ELF 输出到 `app/src/main/jniLibs/arm64-v8a`。加 `-BuildTests` 会在 `native/` 下额外生成四个测试程序，测试不打入 APK，也不连接基带：

| 测试 | 检查范围 |
|---|---|
| `test_qmi` | mock transport、协议编码、列表 / 掩码、错误与回读判定 |
| `test_diag` | 日志批次与内存缓冲边界 |
| `test_read` | NAS 身份字段与 ICCID 脱敏 |
| `test_efs` | 固定白名单、请求编码、长度与参数边界 |

`test_qmi` 与 `test_efs` 也可用主机 GCC 直接编译运行（CI 即如此），详见 [CONTRIBUTING](../CONTRIBUTING.md)。
