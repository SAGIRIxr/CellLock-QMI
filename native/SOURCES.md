# 原生后端来源与许可

本目录公开交付代码使用 **GPL-3.0-only**。完整许可证见 [LICENSE](../LICENSE)。修改后的完整可构建源代码随软件提供。

## QMI/QRTR

- `qrtr_transport.inc` 的服务发现、带期限的收发代码改编自 [btsearch/bunnylock 的 qrtr.c](https://github.com/btsearch/bunnylock/blob/a0dc51f85e2f34431dfc5f82a42b8b7482cb8790/app/src/main/cpp/qrtr.c)，提交 `a0dc51f85e2f34431dfc5f82a42b8b7482cb8790`，项目 GPL-3.0。
- `celllock_qmi.c` 的小区、频段和制式协议编码核对自 [Fronsipswu/qcom-band-menu 原生实现](https://github.com/Fronsipswu/qcom-band-menu/blob/a66248e383f02899bab36a2401d8470d45e18ac9/app/binary/qcom-bandlockd_v5-0-0.c)，提交 `a66248e383f02899bab36a2401d8470d45e18ac9`，项目 GPL-3.0；亦核对 [BunnyLock Qualcomm.kt](https://github.com/btsearch/bunnylock/blob/a0dc51f85e2f34431dfc5f82a42b8b7482cb8790/app/src/main/java/dev/ririxi/bunnylock/root/Qualcomm.kt)。
- 新增固定命令 CLI、严格 SIM 绑定、参数验证、超时、JSON 状态、未知状态处理、SET 后精确回读验证、部分执行披露及协议测试。
- 0.3 新增所选 SIM 的频段/制式固定命令；未采用上游任意 RAW QMI 接口、PS detach/attach、PLMN/IMS 设置、基带重启或驻留进程。

## 频段与制式协议

上述固定提交的原生文件中，常数区、`query()`、`query_hardware()`、`cmd_rat()`、`cmd_lte()`、`cmd_nr()`、`cmd_gsm()`、`cmd_wcdma()` 及 NR mode setter 为对应依据：

| 服务 / 消息 | 本项目使用的字段 |
|---|---|
| NAS 3 / `0x0033` SET system selection | duration `0x17` 单字节 1；RAT `0x11` 两字节；GSM/WCDMA 共用 legacy `0x12` 八字节；LTE `0x15` 八字节或扩展 `0x24` 三十二字节；NR 合并 `0x2B`、SA `0x2F`、NSA `0x30` 各六十四字节；NR mode `0x2E` 四字节 |
| NAS 3 / `0x0034` GET system selection | RAT `0x11`，legacy `0x12`，LTE `0x15` 或扩展 `0x23`，NR mode `0x2B`，SA `0x2C`，NSA `0x2D`；不能混用 SET 的 TLV 编号 |
| DMS 2 / `0x0045` GET band capability | legacy `0x01` 八字节，LTE `0x10` 八字节，扩展 LTE `0x12` 与 NR `0x13` 为 `u16 count + count × u16 band` |
| NAS 3 / `0x0045` bind subscription | 与同消息号的 DMS 查询属于不同服务；固定 TLV `0x01` 的值为 slot 0/1，必须成功后才查询或修改该卡 |

所有多字节整数均为小端。频段掩码的第 `band - 1` 位对应该频段。NR mode 的 0/1/2 分别为 SA+NSA / NSA / SA；RAT 的 GSM/WCDMA/LTE/NR 为 `0x04/0x08/0x10/0x40`。

NR mode、独立 SA / NSA 频段请求省略可选 duration，使用协议默认的 Permanent；其他偏好仍显式携带 duration。字段映射和默认值另核对 [2020 NAS IDL 的公开镜像](https://github.com/comprehensive9/vendor_qcom_proprietary/blob/36fc163a534963a5b3af52186af5efcc63401ad2/qcril-qmi-services-headers/network_access_service_v01.h#L5907)与[编码表](https://github.com/comprehensive9/vendor_qcom_proprietary/blob/36fc163a534963a5b3af52186af5efcc63401ad2/qcril-qmi-services/network_access_service_v01.c#L4844)；[同版本 QCRIL 模式调用](https://github.com/comprehensive9/vendor_qcom_proprietary/blob/36fc163a534963a5b3af52186af5efcc63401ad2/qcril-nr/modules/nas/src/qcril_qmi_nas2.cpp#L2196)只发送 NR mode。这是第三方托管的厂商资料镜像，并非 Qualcomm 官方发布或开源授权；本项目仅独立实现报文字段，不复制厂商头文件或实现，也不将其纳入编译、打包。此处理不扩大 NR 小区锁能力，普通 QMI 配置写入仍要求回读一致；完整清除的重载后状态不自动核验，见下节固定 EFS 范围。

错误码 17（MISSING_ARG）、71（INVALID_QMI_COMMAND）和 94（NOT_SUPPORTED）另核对 [libqmi 的 QmiProtocolError 原始定义](https://github.com/linux-mobile-broadband/libqmi/blob/d9e8af5cae7fdf9749baf87606568d3af4d03082/src/libqmi-glib/qmi-errors.h)。LTE 扩展字段仅在 71 / 94 时尝试旧字段；NR 独立频段字段在 17 / 71 / 94 时，仅 SA、NSA 目标相同且两者原值可读的请求允许尝试合并字段，并分别回读确认。超时、内部错误不触发第二种写法。没有复制 libqmi 实现代码。

成功要求逻辑写入获确认且随后读取目标字段一致。频段命令按硬件支持的位验证；快照恢复的完整列表、RAT、NR mode 与 `legacyMask` 另由应用最终读取逐位比较。legacy 写入保留其他制式与未命名位。NR 查询只有成功结果 TLV 时保持状态未知，即使清除命令返回成功也不宣称已解除。App 频段开放只处理支持频段，快速清除另处理小区锁；原生旧 reset 仍核验频段、RAT、NR mode 与小区锁。分项失败不报告整体成功。没有基于未知原值的自动回滚。

## DCI 接口依据

`celllock_diag.c` 运行时仅加载手机已有 `/vendor/lib64/libdiag.so`。ABI 名称、参数及 `DIAG_DCI_NO_ERROR=1001` 核对公开镜像 `os-vector/wire-os` 提交 `0c3484f229d693cb76934d205a26768cb31ef959` 的 `diag/include/diag_lsm_dci.h`。

**未将 Qualcomm 头文件、实现源码或手机的 libdiag.so 复制进可分发交付物。** 编译不依赖上述资料或厂商库文件。

## 只读测量

- `celllock_read.c` 复用现有 QRTR 传输，仅允许 UIM `0x002F/0x0047`、NAS 绑定 `0x0045`、位置 `0x0043`、系统信息 `0x004D`、射频 `0x0031` 与信号 `0x004F` 查询；不开放 RAW 请求或任意消息。LTE 同频/异频、扩展 EARFCN、NR `0x2E/0x2F` 字段核对 [libqmi NAS 定义](https://github.com/linux-mobile-broadband/libqmi/blob/d9e8af5cae7fdf9749baf87606568d3af4d03082/data/qmi-service-nas.json)，UIM 卡槽核对同提交的 [UIM 定义](https://github.com/linux-mobile-broadband/libqmi/blob/d9e8af5cae7fdf9749baf87606568d3af4d03082/data/qmi-service-uim.json)。仅借助协议定义，不复制 libqmi 实现。
- `celllock_diag.c` 动态使用设备自己的 libdiag，短会话订阅唯一 `0xB97F`，依次停止订阅、释放客户端、反初始化。API 依据见上节；不改变 logging mode、USB、SELinux、NV 或 EFS。
- SM8250 / MIUI 14 真机确认 APK 安装目录的 linker namespace 禁止直接加载厂商 libdiag；`RadioReader` 仅将随包自编 ELF 复制到 Root 私有 `/data/local/tmp/celllock-diag.XXXXXX` 目录执行，退出时清理。不复制厂商库，也不修改 linker 配置。其他 ROM 仍可能不支持该接口。
- 0.4.1 将短会话内日志保留为有界批次（最多 32 包 / 96 KiB，超过时淘汰最旧完整包），完成注销后输出；Java 合并邻区，重复小区以最近包覆盖。`test_diag.c` 仅验证内存缓冲边界，不初始化 DCI 或访问设备接口。
- Java `MeasurementDecoder` 的 NR 2.7 布局参考 [SCAT 固定提交的 NR 解码器](https://github.com/fgsect/scat/blob/07c729a41ed38884dfbcc5c9ed55a22e548db285/src/scat/parsers/qualcomm/diagnrlogparser.py)，并用自编探针的真实回包核对；保留小区级 Q7 RSRQ，0 表示没有有效该测量。其他版本明确拒绝；没有移植 SCAT 的解析实现。日志 channel 不代表 SIM，QXDM 时钟未完成跨休眠校准；0.4.2 仅允许最近收到且与服务频段 / 频点相同、PCI 合法的观测填入所选 SIM 的候选表单，仍不伪造归属或绘制趋势。
- 新增辅助程序均为本项目代码，手机库、Cellular-Pro 及反编译代码不作为编译或打包输入。
- 0.4.3 NAS 服务小区保留 PLMN、TAC、LTE CI / NR NCI，LTE 邻区不继承这些身份。LTE TAC / CI 与 NR NCI 按上述 libqmi 定义读小端整数；NR TAC 为三个网络序字节。PLMN 按同提交的 [qmicli NAS 字段显示](https://github.com/linux-mobile-broadband/libqmi/blob/d9e8af5cae7fdf9749baf87606568d3af4d03082/src/qmicli/qmicli-nas.c) 读取低半字节优先 BCD，保留完整 PLMN，不猜测拆分 MCC/MNC；仅核对格式，没有复制上游实现。ICCID 仍在原生输出前清零；脱敏测试归档另行清零 NAS 网络标识，生产解码不再提前丢弃有效字段。`test_read.c` 仅验证这两种字段保留边界，不调用后端。

## 固定 EFS 清理与重载

`celllock_efs.c`复用设备libdiag及既有DCI注册/退出和看门狗，不开放RAW DIAG。`efs_protocol.inc`只编码固定路径的GET39、STAT15、UNLINK8、固定LTE PUT38、SYNC48/STATUS49和单次重载`4b250300`。清理协议以保留的Cellular-Pro出站报文事实及本项目独立真机回执为依据重新实现，没有复制其反编译代码或打包第三方私有组件；出站帧不等于本项目设备执行成功。

LTE PUT38为14字节头/参数（data_len36、flags0x80040、mode1）、36字节零内容和NUL结尾固定路径。Java先确认现有文件布局，固定写入后须同步及GET全零；不创建缺失结构。mode_pref删除和重载前QMI nr-mode both属于本项目追加范围，原Cellular-Pro清锁样本未包含mode_pref UNLINK；acq_db不纳入。副订阅只用表内明确路径，不对其他项拼接后缀。

重载帧是modem crash/SSR，影响两卡；产品等待单次helper回包/超时及安全退出，不等待服务恢复或自动重发，不把断连解释成成功。Java依原始回执区分拒绝、未发送与未知，退出未确认保持RootShell保护。真实验证范围统一见 [验证记录](VALIDATION.md)，不将内存编码测试视为厂商语义已通过。

## 构建与分发

四个后端均为使用 NDK Clang 编译的 arm64-v8a / API 26 / PIE 可执行 ELF，以 .so 后缀随 jniLibs 打包，必须保留 extractNativeLibs=true。测试与厂商库不打入 APK。构建入口、输出和测试方法见 [原生说明](README.md) 与 [开发交接](../docs/DEVELOPMENT.zh-CN.md)。

不额外分发厂商库，不要求修改 SELinux、USB 配置或系统属性。固定 EFS 写入与单次 SSR 仅限上节描述的范围，不开放任意请求。
