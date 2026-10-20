# CellLock

[![CI](https://github.com/SAGIRIxr/CellLock-QMI/actions/workflows/ci.yml/badge.svg)](https://github.com/SAGIRIxr/CellLock-QMI/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/SAGIRIxr/CellLock-QMI)](https://github.com/SAGIRIxr/CellLock-QMI/releases/latest)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)

面向已 Root 高通 Android 手机的免费开源小区、频段与制式设置工具。无会员、无广告、无联网 SDK，应用不申请 `INTERNET` 权限。

**[下载最新版 APK](https://github.com/SAGIRIxr/CellLock-QMI/releases/latest)** · [更新日志](CHANGELOG.md) · [参与开发](CONTRIBUTING.md)

## 功能

- **小区**：综合系统接口、Root 电话服务、QMI NAS 与 NR 诊断读取所选 SIM 的服务小区和邻区，自动校正冲突的 NR 频段，并绘制最近 5 分钟的信号曲线。
- **锁定**：LTE 小区锁、5G NR 小区或频点锁；选择 LTE、NR、GSM、WCDMA 频段；设置网络制式与 5G SA / NSA 组网。
- **配置**：按 SIM 保存多份配置快照，一键恢复；快速清除与完整清除两种解锁方式。
- **设备**：查看机型、硬件支持频段、Root 与后端状态、读取权限和操作记录，按需导出诊断信息。

## 界面

<img src="docs/images/home.png" width="200" alt="小区与信号趋势"> <img src="docs/images/lock.png" width="200" alt="小区锁定"> <img src="docs/images/bands.png" width="200" alt="频段选择"> <img src="docs/images/device.png" width="200" alt="设备信息">

## 系统要求

- 高通平台、arm64，Android 11 及以上
- 已 Root（Magisk、KernelSU、APatch 等），并在 Root 管理器中授权 CellLock

已在小米 14 Pro（SM8650 / Android 14）与 SM8250 / Android 13 设备上测试。其他机型和固件的接口可能不同，首次使用建议先保存配置快照。

## 使用

1. 安装 APK，在 Root 管理器中允许 CellLock。应用按已有权限自动选择读取方式，读不到数据时才引导授权。
2. 在顶部选择 SIM。首次完整读取会自动保存一份配置快照；可在锁定页右上角“配置管理”中新增快照。
3. 在“小区”页点选目标，或在锁定页填写参数后确认。目标频段不在当前允许列表时会自动追加。
4. 查看操作回执后观察实际驻留情况——配置写入成功不代表已驻留到目标小区。

锁定到错误目标或无覆盖的网络可能导致暂时断网，离开目标覆盖区前请先解锁。长按小区条目可添加备注并收藏，之后可从书签或“已保存小区”快速填表。

### 配置与解锁

| 操作 | 作用 |
|---|---|
| 保存 / 读取配置 | 按 SIM 保存多份快照和备注，恢复其中的频段、制式、组网与小区锁 |
| 快速清除 | 开放硬件支持的全部频段，解除 LTE / NR 小区与频点锁，保留制式和组网，不重启基带 |
| 完整清除 | 额外清理持久化的锁定项与制式偏好，并重启基带一次（影响双卡），完成后请手动刷新 |
| 开放全部支持频段 | 只调整频段，保留小区锁、制式与组网 |
| 表单内“清除” | 只解除对应的 LTE 或 NR 小区 / 频点锁 |

写入前可选择先备份，备份失败时操作会中止。

## 从源码构建

需要 JDK 17、Android SDK 35 和 Android NDK（CI 使用 `30.0.14904198`）。在项目根目录的 `local.properties` 中配置 `sdk.dir`，然后：

```powershell
./build-native.ps1 -Ndk <NDK目录>
./gradlew assembleRelease
```

未配置签名时会生成未签名的 `app-release-unsigned.apk`。签名方式、测试与项目结构见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可

[GPL-3.0-only](LICENSE)。QRTR 传输与部分协议实现参考 [btsearch/bunnylock](https://github.com/btsearch/bunnylock) 与 [Fronsipswu/qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu)，详见 [第三方来源](native/SOURCES.md)。
