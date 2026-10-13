# CellLock 0.3 原生后端

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
```

LTE EARFCN 范围为 0–65535，PCI 为 0–503；NR ARFCN 为 0–3279165，PCI 为 0–1007，SCS 为 15 / 30 / 60 / 120 / 240 kHz。参数必须来自实际目标；NR 小区接口本身不区分 SA / NSA，`nr-mode` 是独立配置。

`all` 使用本机硬件支持列表。显式频段列表可保留硬件不支持的原始位，以便恢复初始设置，但必须包含该制式的受支持频段。`reset` 开放硬件支持的频段、制式和 SA + NSA，并请求清除 LTE / NR 小区锁；它不是恢复初始快照。

## 回执与确认

stdout 为一行 JSON，字段以当前源码为准：

| 字段 | 含义 |
|---|---|
| `ok` | 读取有可解析状态，或本次写入目标已通过回读验证 |
| `stage`、`message` | 阶段及中文结果；`unverified` 表示尚未全部确认 |
| `cmd`、`slot` | 命令及绑定卡槽 |
| `mutated`、`writes`、`accepted` | 是否尝试写入、尝试次数、收到成功回执次数 |
| `lteCell`、`nrCell` | 小区锁状态；未知不能解释成未锁定 |
| `pref` | 制式、NR 组网、各频段完整列表及 `legacyMask` |
| `hardware` | 硬件支持频段，部分命令不重复返回 |

NR `ackOnly=true` 表示查询只收到成功结果 TLV，没有可解码的锁定状态。它不会满足锁定或解除确认；初始未知时允许的尝试也不能作为适配完成的证明。多步写入可能部分成功，任何未通过的目标均应保留“未全部确认”与回执，不假定回滚完成。

系统选择配置可能异步生效，后端在限定时间内重新查询。原生总期限为 25 秒，App 每次 Root 调用外层期限为 40 秒。写入成功只说明配置回读，不证明手机已驻留目标。

初始快照由 Java 层按卡槽和订阅保存。schema 2 要求完整偏好及原始 64 位 `legacyMask`；恢复后额外比较 LTE、NR SA / NSA、GSM、WCDMA 全部频段位、RAT、NR 模式与 legacy mask，不先与硬件做交集。清锁范围仅为 QMI 小区锁，不修改其他工具的 EFS 文件。

## 构建与验证

在项目根目录运行 `build-native.ps1 -Ndk <NDK目录> -BuildTests`，或按主 [README](../README.md) 构建 App。`native/test_qmi` 是离线字节编码与状态判定测试，不建立基带连接。旧 DCI 探测组件不是当前界面的后端。

0.3 验证进度见 [VALIDATION.md](VALIDATION.md)，旧机与 0.2 证据原样保存在 [VALIDATION-0.2.md](VALIDATION-0.2.md)。5G 小区 / ARFCN 锁定效果尚未实测。协议来源与固定提交由 [SOURCES.md](SOURCES.md) 记录。
