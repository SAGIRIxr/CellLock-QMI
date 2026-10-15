# CellLock 0.4.8（本地验证，未发布）

已 Root 高通手机上的免费网络设置工具。写入功能在小米 14 Pro（SM8650 / Android 14）验证，0.4 自适应读取另在 SM8250 / Android 13 / MIUI 14 验证。无会员、广告或联网 SDK，应用未声明 `INTERNET` 权限。

[下载 v0.3.0 APK 与源码](https://github.com/SAGIRIxr/CellLock-QMI/releases/tag/v0.3.0) · [开发交接文档](docs/DEVELOPMENT.zh-CN.md) · [更新日志](CHANGELOG.md)

## 功能

- **小区**：综合普通接口、Root 电话、QMI NAS 与 NR 诊断读取所选 SIM 的服务小区和邻区，自动校正冲突的 NR 频段，并绘制最近 5 分钟的信号曲线。
- **锁定**：LTE 小区锁、5G NR 小区或频点锁；选择 LTE、NR、GSM、WCDMA 频段；设置网络制式与 5G SA / NSA 组网。
- **设备**：查看机型、硬件支持频段、Root / 后端状态、读取权限和操作记录，按需导出诊断。

## 界面

<img src="docs/images/home.png" width="200" alt="小区与信号趋势"> <img src="docs/images/lock.png" width="200" alt="小区锁定"> <img src="docs/images/bands.png" width="200" alt="频段选择"> <img src="docs/images/device.png" width="200" alt="设备信息">

## 使用

1. 安装 APK，在 Root 管理器中允许 CellLock。应用按已有权限自动选择读取方式，不会修改系统权限或定位开关；手动“刷新”仍读不到时才引导授权。
2. 选择上方 SIM。首次进入“锁定”或“设备”页会自动读取当前配置，并按卡保存首次完整读取的初始频段与制式。
3. 从“小区”页选择目标，或在“锁定”页选择“制式 / 频段 / 小区”，核对参数后确认写入。
4. 查看回读结果，再回到“小区”页观察实际驻留；配置回读成功不等于已驻留目标。

目标错误或选择无覆盖的网络可能暂时断网，离开目标覆盖区前记得恢复。应用不会打开移动数据或数据漫游。

## 两种恢复

- **恢复初始配置**：恢复该 SIM 首次保存的完整频段、制式、NR 组网及 legacy 掩码，并请求解除 LTE / NR 小区锁。
- **开放全部支持范围**：开放本机硬件支持的频段与制式，启用 SA + NSA，并请求解除小区锁。它会改变原有频段限制，不能代替“恢复初始配置”。

锁定页的“解除 5G 小区锁 / 解除 LTE 小区锁”只解除对应制式的小区、频点锁，不恢复频段或制式。两张 SIM 各自保存初始配置，操作前请核对所选卡。

**NR 小区锁和 ARFCN 频点锁的实际效果尚未完成实机验证**，详见 [验证记录](native/VALIDATION.md)。

## 构建与测试

需要 JDK 17、Android SDK 35、Android NDK。在项目根目录的 `local.properties` 配置 `sdk.dir`，然后运行：

```powershell
./build-native.ps1 -Ndk 'C:/你的/android-sdk/ndk/30.0.14904198' -BuildTests
./gradlew.bat clean assembleDebug lintDebug
```

`tests/` 下的纯逻辑测试不发送基带命令，运行方式见 [开发交接文档](docs/DEVELOPMENT.zh-CN.md)。

## 许可

GPL-3.0-only，交付完整源码。协议与传输参考公开的 qcom-band-menu、bunnylock，固定版本与许可见 [native/SOURCES.md](native/SOURCES.md)。未使用或分发 Cellular-Pro 私有代码、授权接口或手机厂商库。
