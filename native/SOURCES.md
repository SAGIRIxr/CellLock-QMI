# 第三方来源与协议参考

CellLock 以 [GPL-3.0-only](../LICENSE) 发布。下列上游代码与公开资料按固定提交引用。

## 改编的代码

| 本项目文件 | 上游 | 许可 |
|---|---|---|
| `qrtr_transport.inc`（服务发现、带期限收发） | [btsearch/bunnylock `qrtr.c`](https://github.com/btsearch/bunnylock/blob/a0dc51f85e2f34431dfc5f82a42b8b7482cb8790/app/src/main/cpp/qrtr.c) @ `a0dc51f` | GPL-3.0 |
| `celllock_qmi.c`（小区、频段、制式协议编码） | [Fronsipswu/qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu/blob/a66248e383f02899bab36a2401d8470d45e18ac9/app/binary/qcom-bandlockd_v5-0-0.c) @ `a66248e`；并核对 [bunnylock `Qualcomm.kt`](https://github.com/btsearch/bunnylock/blob/a0dc51f85e2f34431dfc5f82a42b8b7482cb8790/app/src/main/java/dev/ririxi/bunnylock/root/Qualcomm.kt) | GPL-3.0 |

在上游基础上，本项目新增固定命令 CLI、严格 SIM 绑定、参数校验、超时、JSON 状态输出、写入后回读验证与协议测试，不提供任意 RAW QMI 接口。

## 协议参考

以下资料仅用于核对报文字段定义，相关解析与编码均为本项目独立实现：

- **QMI NAS / UIM 字段与错误码**：[libqmi](https://github.com/linux-mobile-broadband/libqmi/tree/d9e8af5cae7fdf9749baf87606568d3af4d03082) @ `d9e8af5` 的 `qmi-service-nas.json`、`qmi-service-uim.json`、`qmi-errors.h` 与 `qmicli-nas.c`。
- **NR 5G 测量日志 0xB97F（v2.7）布局**：[fgsect/scat](https://github.com/fgsect/scat/blob/07c729a41ed38884dfbcc5c9ed55a22e548db285/src/scat/parsers/qualcomm/diagnrlogparser.py) @ `07c729a`。
- **DIAG DCI 接口**：`os-vector/wire-os` @ `0c3484f` 的 `diag/include/diag_lsm_dci.h`。运行时动态加载设备自带的 `/vendor/lib64/libdiag.so`，编译不依赖该库。

## 频段与制式协议摘要

| 服务 / 消息 | 使用的字段 |
|---|---|
| NAS `0x0033` SET system selection | duration `0x17`；RAT `0x11`；GSM/WCDMA legacy `0x12`；LTE `0x15` 或扩展 `0x24`；NR 合并 `0x2B`、SA `0x2F`、NSA `0x30`；NR mode `0x2E` |
| NAS `0x0034` GET system selection | RAT `0x11`；legacy `0x12`；LTE `0x15` 或扩展 `0x23`；NR mode `0x2B`；SA `0x2C`；NSA `0x2D` |
| DMS `0x0045` GET band capability | legacy `0x01`；LTE `0x10`；扩展 LTE `0x12`；NR `0x13` |
| NAS `0x0045` bind subscription | TLV `0x01` = 卡槽 0 / 1，成功后才查询或修改该卡 |

多字节整数均为小端，频段掩码第 `band - 1` 位对应该频段。NR mode 0 / 1 / 2 分别为 SA+NSA / NSA / SA；RAT 的 GSM / WCDMA / LTE / NR 为 `0x04 / 0x08 / 0x10 / 0x40`。
