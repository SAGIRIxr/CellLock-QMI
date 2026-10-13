# CellLock 0.2.0 验证记录（2026-10-13，Asia/Singapore）

## 当前设备：小米 14 Pro

- 设备：23116PN5BC，SM8650，Android 14，基带 MPSS.DE.5.0。App 包名 `com.celllock.app`，已由 Root 管理器单独授予超级用户权限；App 自身 Root 与 QMI 检测通过。
- 0.2.0 为“小区 / 锁定 / 设备”三页简约中文界面，全部功能免费，无会员或广告。Manifest 仅声明电话状态、粗略位置与精确位置读取权限，没有 `INTERNET` 权限。
- 新增观测功能后的最终构建检查：`assembleDebug`、`lintDebug` 成功，lint 为 0 errors、17 warnings；Java 参数校验 18 项、arm64 纯协议检查 58 项、信号历史逻辑 22 项通过。纯协议测试 `modemCommandsSent=0`，覆盖空成功 NR 回复、额外或重复 TLV、截断、错误类型与错误结果的区分。
- 本节证据位于开发工作区 `work/celllock/device-0.2/`。除本节明确记录外，不把下面的小米 11 Ultra 历史结果当作本机结果。

### 新机 QMI 与 NR 状态

- 两个卡槽分别严格绑定 NAS 成功，原始查询记录为 `raw-slot0.json`、`raw-slot1.json`；本次 QRTR NAS node=0、port=75。LTE_LOCK_GET 可成功返回明确空列表。
- 两卡 NR_LOCK_GET `0x010F` 的结果均为 `result=0,error=0,transportError=0`，原始 TLV 仅 `02040000000000`，没有锁定类型、PCI、ARFCN 或 band mask。
- 新后端严格将这一形态识别为 `queryAckOnly=true`；仍输出 `known=false,active=null,type=-1`。这只是查询获得应答，不表示已经解锁，也不表示 NR SET 已验证可用。
- 试验入口允许对已知回复形态发起用户明确选择的尝试，但锁定成功仍须 SET 成功并完整精确回读；清除后若仍缺少明确状态，也不得报告解除完成。
- 两卡 SYS_INFO 的 NR 服务字段均未显示当前 NR 服务；GET_ARFCN_LIST `0x00D2` 均返回错误 25。SIM 1 出现 PCI 202 / SCS 30 kHz 零散字段但没有 NR ARFCN，不能认定为当前有效 NR 目标。未自动猜测频段或子载波间隔。
- SIM 2（slot 1）NGRAN 扫描工具设置 60 秒软期限，约 61.08 秒超时退出并请求停止。`nr-scan-slot1.txt` 为 `scanRequested=true,scanCompleted=false,timedOut=true,callbackCount=0,nrCells=[]`；没有扫描结果回调，不能把 `scanError=0` 解释为扫描成功。
- 扫描记录 `dataSettingsModified=false,mobileDataConnectionRequested=false,guardFailed=false`，未启用移动数据；此结果也不能判定手机不支持 5G。本轮未发送 NR SET，NR 锁定、驻留与解除效果均未验证。

### 新机 LTE 实测

- SIM 1（slot 0）原服务小区为 EARFCN 1650 / PCI 323。通过 App 选择实际观测到的邻区 1650 / PCI 321，确认锁定。
- `lte-locked.json` 为操作后独立 GET，明确回读 `lte.known=true,active=true,cells=[{"pci":321,"earfcn":1650}]`；其中顶层 `mutationAttempted=false` 表示本次 GET 自身只读，不否定此前已从 App 发出锁定。
- 锁后首次约 4 秒的服务观测返回错误 13；未将这次暂时无有效数据解释为驻留结论。约 20 秒后再次读取，`lte-observed-locked-second.json`（21:33:46）显示服务小区为 EARFCN 1650 / PCI 321，RSRP -82.5 dBm。
- `lte-locked-home-refreshed.xml`（21:34:13）显示 Android 观测也已刷新到 PCI 321。由此同时有锁定配置、基带服务小区及 Android 界面的证据，证明固定位置下实际切到指定邻区。
- App 解除界面的 `lte-unlock-confirm-ui.xml` 显示基带配置已回读确认；`lte-after-unlock.json` 再次独立 GET 为 `lte.known=true,active=false,cells=[]`。测试另设置了 90 秒定时清锁作为后备，因此可以确认最终恢复，不把恢复动作唯一归因于 App 点击。
- 本轮没有验证移动跨区、离开目标覆盖范围或重启后持久性；新加观测界面不会据此自动扩展锁定能力的实测结论。

### 0.2.0 新增观测功能

- 前台自动请求读取系统小区，完成后约 5 秒安排下一次；最多一个有效请求，15 秒超时后复位。缺少权限、定位或有效 SIM 时静默等待，后台与基带写入期间暂停。
- 这些请求不保证每 5 秒产生新无线样本。曲线按 `CellInfo.getTimestampMillis()` 去重，使用实际采样时间；只接受有效 LTE RSRP 或 NR SS-RSRP，最多 60 点、最近 5 分钟，不用请求完成时间伪造新样本。
- 信号条、数值和曲线使用红弱、橙中、绿强的连续渐变，辅以“强、较强、一般、较弱、弱”。分级是应用参考，不宣称为运营商标准。
- 切 SIM 清空历史；同 SIM 旋转保留；锁定表单模式切换不改变观测数据。小区身份、制式、频点、PCI 变化以及缺测或后台间隔断开曲线，实际样本之外不插补。
- 新增 `SignalHistoryTest` 的 22 项检查通过，覆盖真实采样时间换算、缓存/倒序/未来/无效值过滤、换小区与缺测断段、长间隔和旋转恢复、60 点容量、5 分钟过期及颜色与强弱边界；原输入校验 18 项再次通过。
- 真机自动更新：未手动点击刷新，`auto-initial.xml`（21:47:40）为 2 点，`auto-later.xml`（21:48:05）为 7 点，证明前台自动读取并接收了新时间戳样本。
- 旋转恢复：横屏再竖屏后，`auto-rotation-restored.xml`（21:48:45）为 15 点，历史保留；测试后原有旋转设置恢复为 0 / 0。切 SIM 2 后 `auto-sim2.xml` 显示新曲线 1 点，未混入 SIM 1 历史。
- 后台暂停：`auto-before-background.xml`（21:51:43）为 7 点，按 Home 在后台停留 12 秒，返回后的 `auto-after-background.xml`（21:51:59）为 8 点。观察结果与暂停、返回后取得一个新点相符，未为后台间隔补入虚构点。
- 真实信号显示与图表截图已检查：-128 dBm 为红色“弱”，-73 dBm 为绿色“强”；实际样本曲线可见。最终邻区截图中的“较强 / 一般 / 弱”文字显示完整，红橙绿强度条正常。最新版本 `final-device-probe.xml` 再次确认 Root/QMI 通、LTE 可用、NR 为试验且状态未知。
- 本轮新增内容只涉及系统小区观测与本地显示，不追加基带写入。构建、纯逻辑检查及上述新观测功能实机检查均已完成；图表通过不代表 NR 锁定通过。

### 新机数据使用与收尾

按用户约束保持移动数据及数据漫游开关关闭，没有发起测速、下载或数据连接。`data-after-lte.json`、`post-lte-restored.txt` 及新 UI 验证后的最终 `data-final.json` 均为双卡 `dataEnabled=false,dataState=0`，可见两个蜂窝 Network 均为 `ims=true,internet=false`。最终 `state-final.txt` 再次确认两个卡槽 LTE 空列表、NR `queryAckOnly=true` 且未知。本轮创建的 4 条 IPv4/IPv6 OUTPUT 保护规则已删除，按本轮标签过滤规则列表无匹配；其他规则未改动。

`traffic-before.txt` 与 `traffic-after-lte.txt` 的记录如下，单位为字节。所有已记录接口的 TX 均未变化。

| 接口 / 方向 | 测试前 | LTE 测试后 | 增量 |
|---|---:|---:|---:|
| `rmnet_data0` RX / TX | 3164 / 3966 | 3164 / 3966 | 0 / 0 |
| `rmnet_data2` RX / TX | 636 / 828 | 636 / 828 | 0 / 0 |
| `rmnet_data3` RX / TX | 65326730 / 17590582 | 65326730 / 17590582 | 0 / 0 |
| `rmnet_data1` RX | 492 | 636 | +144 |
| `rmnet_data4` RX | 0 | 43167 | +43167 |
| `rmnet_ipa0` RX | 14203295 | 14250956 | +47661 |

这些计数增长的具体来源未单独确认，不将其直接归因于 App、IMS 或计费流量；接口之间可能存在上下层重复统计，不相加为用户流量。没有 `INTERNET` 权限或数据开关关闭，均不能单独证明所有底层接口计数绝对不增长。

新增自动刷新、信号渐变和图表验证后的 `traffic-final.txt` 与 `traffic-after-lte.txt` 中所有已记录接口的 RX/TX 计数相同；这段 UI 验证期间没有观察到这些接口进一步增长，不扩展为全轮测试所有接口始终零增长的结论。

## 历史设备：小米 11 Ultra

下面各节记录旧机的已完成验证，不适用于小米 14 Pro。旧版本测试项数量及流量快照也按原始历史记录保留。

设备：小米 11 Ultra / M2102K1C / star，Android 14，SM8350；执行上下文为 Magisk root。

### 旧机：构建与纯协议检查

- Android NDK `30.0.14904198`，aarch64-linux-android26-clang，C11，`-Wall -Wextra -Werror -O2 -fPIE -pie` 全部通过。
- 真机执行 `test_qmi`：37 项协议检查通过，`modemCommandsSent=0`，包含主项目增加的执行前实时条件复核。
- 覆盖 LTE/NR 单小区编码、清锁编码、NR 64 字节 band mask、数值溢出/命令注入字符拒绝、长度错误/缺失 TLV/错误响应解析、NR GET INTERNAL(3) 不能报告已解锁、精确参数与额外 band bit 校验。

### 旧机：DCI 只读版本查询

- 手机没有 `/dev/diag`；动态加载手机自身 libdiag 后成功通过 socket 通信。
- DCI register / send / release 返回值均 1001；DeInit 为 1。
- VERNO 返回 55 字节，首字节 0。
- 不切换诊断模式，不修改 SELinux、USB 配置、系统属性，不复制厂商库进行分发。

### 旧机：QMI 只读状态查询

- NAS 服务 QRTR node=0，port=71。
- SIM slot 0、slot 1 分别使用独立 socket，BIND_SUBSCRIPTION 0x0045 均 result=0/error=0。
- 两槽 LTE_LOCK_GET 0x00D7 均 result=0/error=0；TLV 0x10=`00`，明确空列表。
- 两槽 NR_LOCK_GET 0x010F 均 result=1/error=3；状态正确输出 `known=false, active=null`。
- 可选 GET_SUPPORTED_MESSAGES 0x001E 返回 error=71，未用于推断功能支持。

### 旧机：LTE 写入、回读与恢复（2026-10-13）

- 已从 Magisk 超级用户界面单独授予 `com.celllock.app` Root，授予电话与精确位置权限。最新 APK 已安装，App 内 Root 与 QMI 查询再次通过。
- 目标采用 SIM 1 当前已注册的 LTE B3 / EARFCN 1650 / PCI 323，未猜测未观测到的小区。
- 命令行实测先 GET 确认 LTE 为空，再执行 LTE_LOCK_SET 0x00D8；返回成功，GET 精确回读 `cells=[{"pci":323,"earfcn":1650}]`。随后立即解除，GET 回读 `active=false,cells=[]`。
- 锁定与解除的最终回执均为 `success=true, stage="verified", readbackVerified=true, result=0, error=0`。恢复后再次独立状态查询，LTE 仍为空。
- App 界面也完成“观测参数填入 → 锁定确认 → 精确回读成功 → 解除 → LTE 空列表”的独立实测，验证了 App 到 Root 后端的完整调用路径。
- 开发工作区原始记录：`work/celllock/lte-validation/before.json`、`lock.json`、`unlock.json`、`after.json`、`observed.json`。测试结束时 LTE 锁定已恢复为空。
- 当前小区测试证明配置按预期回读，邻区驻留另见下节；尚未验证移动跨区或重启持久性。

### 旧机：LTE 邻区锁定与驻留观测（2026-10-13）

- 目标来自实际观测：SIM 1 / EARFCN 1650 / PCI 333，锁前为约 -86 dBm 的邻区。
- SET 成功，锁定参数回读精确一致：`cells=[{"pci":333,"earfcn":1650}]`、`readbackVerified=true`。
- 锁后 4、8、12 秒分别进行一次基带观测，三次均为 `lte.known=true, earfcn=1650, pci=333`，观测信号约 -83.9 dBm。由此确认固定位置下实际驻留到指定邻区，而不只是配置回读一致。
- 随后解除获得基带成功回执及 `readbackVerified=true`，最终独立查询为 `lte.known=true, active=false, cells=[]`。
- 完整记录目录：`work/celllock/neighbor-validation/celllock-neighbor-validation/`，含 `before.json`、`lock.json`、`observed-1.json`、`observed-2.json`、`observed-3.json`、`unlock.json`、`after.json`。外层重复文件来自 adb pull 目录行为，不代表额外重复测试。
- 此轮没有测试移动跨区、离开目标覆盖范围或重启后的行为。

### 旧机：数据使用约束

- 用户授权无人值守继续验证，并明确禁止使用移动数据。
- 移动数据与数据漫游开关全程为 0，默认网络为 Wi-Fi；未执行测速、下载或联网请求。
- 前两次 LTE 测试截至 `traffic-after-ui` 快照时，接口计数未增加。基线与最终 `traffic-final` 快照的计数对比如下，单位为字节：

| 接口 / 方向 | 基线 | 最终收尾快照 | 增量 |
|---|---:|---:|---:|
| `rmnet_data0` RX | 720 | 720 | 0 |
| `rmnet_data0` TX | 856 | 856 | 0 |
| `r_rmnet_data0` RX | 96 | 1632 | +1536 |
| `r_rmnet_data0` TX | 812 | 812 | 0 |
| `ipa` RX | 1044 | 2708 | +1664 |
| `ipa` TX | 2116 | 2628 | +512 |

- 增长并非只在邻区锁定时出现，本机这些少量计数增长的具体来源未知，不能断言由锁定测试造成。不能表述为“全程所有接口零增长”；接口计数不直接等同于运营商计费记录，未取得运营商账单证据，不能保证绝对零计费。
- App Manifest 未声明 `INTERNET` 权限。QMI/QRTR 操作使用本机基带控制通道。
- 收尾仅删除自建的 4 条带 `celllock-no-mobile-data` 标签的 IPv4/IPv6 OUTPUT 保护规则，其他防火墙规则未动。数据/漫游开关全为 0，默认网络为 Wi-Fi 100。
- 最终 `final-state-slot0` 与 `final-state-slot1` 均为 LTE 明确空列表，NR 未知（INTERNAL 3）。

### 旧机：5G 与其他尚未完成的验证

- 两个卡槽 NR GET 仍返回 INTERNAL(3)。最新 App 观测为 LTE 3 个、NR 0 个；原生只读观测 `nr.known=false, endcAvailable=false`，尚未取得可确认的 NR 锁定目标。
- NR 配置查询：模式掩码 `0x58` 含 NR、`disabledModes=0`、`ENDCenabled=true`。这些配置值与当前服务网络的 ENDC 可用性不同，不能替代实际 NR 目标观测。
- GET ARFCN `0x00D2` 返回错误 25，未提供有效目标参数。
- 通过 Root 临时工具发起的一次 NGRAN 扫描被厂商 RIL 返回 `INVALID_ARGUMENTS`（错误 44）；App 收到 `ERROR_INVALID_SCAN`（2）。扫描失败不能证明该手机不支持 5G，也没有产生可用于锁定的 NR 目标。
- 本轮未发送 NR SET。真实 NR 设置、锁后回读、目标驻留和解除均未验证。SA / NSA 选项与试验命令入口存在，不代表这两种组网方式均已通过实机验收。
- NR GET INTERNAL 不等于“设备不支持 5G”，也不等于“5G 已解除”。保留未知直到有直接证据。
- 未测试重启后保持锁定；未验证其他工具的 EFS、频段或制式限制。

## 两台设备通用的恢复判定

在 App 选择原 SIM 与对应制式后使用对应解除按钮，再查询状态。LTE 的明确恢复证据为 `known=true,active=false,cells=[]`；NR 只有得到可解码的明确状态才能确认恢复，INTERNAL(3) 与空成功应答 `queryAckOnly=true` 都必须显示未知。Root 被拒绝时，先在 Root 管理器的超级用户界面检查本 App 单独授权。本工具仅解除所选制式的 QMI 小区锁定。
