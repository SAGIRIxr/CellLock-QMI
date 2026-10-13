# 原生后端来源与许可

本目录公开交付代码使用 **GPL-3.0-only**。完整许可证见 `LICENSE`。修改后的完整可构建源代码随软件提供。

## QMI/QRTR

- `qrtr_transport.inc` 的服务发现、带期限的收发代码改编自 [btsearch/bunnylock 的 qrtr.c](https://github.com/btsearch/bunnylock/blob/a0dc51f85e2f34431dfc5f82a42b8b7482cb8790/app/src/main/cpp/qrtr.c)，提交 `a0dc51f85e2f34431dfc5f82a42b8b7482cb8790`，项目 GPL-3.0。
- `celllock_qmi.c` 的小区、频段和制式协议编码核对自 [Fronsipswu/qcom-band-menu 原生实现](https://github.com/Fronsipswu/qcom-band-menu/blob/a66248e383f02899bab36a2401d8470d45e18ac9/app/binary/qcom-bandlockd_v5-0-0.c)，提交 `a66248e383f02899bab36a2401d8470d45e18ac9`，项目 GPL-3.0；亦核对 [BunnyLock Qualcomm.kt](https://github.com/btsearch/bunnylock/blob/a0dc51f85e2f34431dfc5f82a42b8b7482cb8790/app/src/main/java/dev/ririxi/bunnylock/root/Qualcomm.kt)。
- 新增固定命令 CLI、严格 SIM 绑定、参数验证、超时、JSON 状态、未知状态处理、SET 后精确回读验证、部分执行披露及协议测试。
- 0.3 新增所选 SIM 的频段/制式固定命令；未采用上游任意 RAW QMI 接口、PS detach/attach、PLMN/IMS 设置、基带重启或驻留进程。

## 0.3 频段与制式协议

上述固定提交的原生文件中，常数区、`query()`、`query_hardware()`、`cmd_rat()`、`cmd_lte()`、`cmd_nr()`、`cmd_gsm()`、`cmd_wcdma()` 及 NR mode setter 为对应依据：

| 服务 / 消息 | 本项目使用的字段 |
|---|---|
| NAS 3 / `0x0033` SET system selection | duration `0x17` 单字节 1；RAT `0x11` 两字节；GSM/WCDMA 共用 legacy `0x12` 八字节；LTE `0x15` 八字节或扩展 `0x24` 三十二字节；NR 合并 `0x2B`、SA `0x2F`、NSA `0x30` 各六十四字节；NR mode `0x2E` 四字节 |
| NAS 3 / `0x0034` GET system selection | RAT `0x11`，legacy `0x12`，LTE `0x15` 或扩展 `0x23`，NR mode `0x2B`，SA `0x2C`，NSA `0x2D`；不能混用 SET 的 TLV 编号 |
| DMS 2 / `0x0045` GET band capability | legacy `0x01` 八字节，LTE `0x10` 八字节，扩展 LTE `0x12` 与 NR `0x13` 为 `u16 count + count × u16 band` |
| NAS 3 / `0x0045` bind subscription | 与同消息号的 DMS 查询属于不同服务；固定 TLV `0x01` 的值为 slot 0/1，必须成功后才查询或修改该卡 |

所有多字节整数均为小端。频段掩码的第 `band - 1` 位对应该频段。NR mode 的 0/1/2 分别为 SA+NSA / NSA / SA；RAT 的 GSM/WCDMA/LTE/NR 为 `0x04/0x08/0x10/0x40`。

错误码 71（INVALID_QMI_COMMAND）和 94（NOT_SUPPORTED）另核对 [libqmi 的 QmiProtocolError 原始定义](https://github.com/linux-mobile-broadband/libqmi/blob/main/src/libqmi-glib/qmi-errors.h)（2026-10-13 读取）。只有这两种明确拒绝允许尝试旧字段；超时、内部错误及其他失败不触发第二种写法。没有复制 libqmi 实现代码。

成功要求逻辑写入获确认且随后读取目标字段一致。频段命令按硬件支持的位验证；“恢复初始设置”的完整列表、RAT、NR mode 与 `legacyMask` 另由应用最终读取逐位比较。legacy 写入保留其他制式与未命名位。NR 查询只有成功结果 TLV 时保持状态未知，即使清除命令返回成功也不宣称已解除。“全部开放”分别核验 SA、NSA、NR mode、legacy、LTE、RAT 和小区锁，部分失败不会报告整体成功。没有基于未知原值的自动回滚。

## DCI 版本探测

`celllock_probe.c` 为本任务编写，运行时仅加载手机已有 `/vendor/lib64/libdiag.so`。ABI 名称、参数及 `DIAG_DCI_NO_ERROR=1001` 核对公开镜像 `os-vector/wire-os` 提交 `0c3484f229d693cb76934d205a26768cb31ef959` 的 `diag/include/diag_lsm_dci.h`。只调用固定版本读取指令 `DIAG_VERNO_F=0`。

**未将 Qualcomm 头文件、实现源码或手机的 libdiag.so 复制进可分发交付物。** 编译不依赖上述资料或厂商库文件。

## 构建

Windows PowerShell：`./build.ps1 -Ndk 'C:/path/to/android-ndk'`。

- 使用 Android NDK Clang，arm64-v8a，API 26，PIE。
- `libcelllock_qmi.so` 和 `libcelllock_probe.so` 是可执行 ELF，使用 `.so` 文件名便于 APK 的 `jniLibs/arm64-v8a` 打包；必须 `extractNativeLibs=true`，以 nativeLibraryDir 中实际文件路径调用。
- `test_qmi` 是无基带访问的协议测试可执行文件；不必打入 APK。`test_transport.inc` 在测试编译时完整替换 socket/QRTR 收发，模拟数据仅在内存中变化；生产编译不包含该文件。
- 亦可在本机 GCC 运行：`gcc -std=c11 -O2 -Wall -Wextra -Werror test_qmi.c -o test_qmi_host`，然后执行生成的程序。测试覆盖报文边界、编码、严格绑定、失败回读、超时、部分写入及恢复验证。
- 不需要额外下载/分发厂商库，不要求修改 SELinux、USB 配置或系统属性。
