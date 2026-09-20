<div align="center">

<h1>DataBackup</h1>

**定制版**

基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改的个人定制版本

[![GitHub release](https://img.shields.io/github/v/release/boluo4169-commits/Android-DataBackup-custom?color=orange)](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases)
[![License](https://img.shields.io/github/license/boluo4169-commits/Android-DataBackup-custom?color=ff69b4)](./LICENSE)

需要 Root 权限的数据备份应用

</div>

---

## 更新日志

### v3.13.1

- **重载的日志干净了**：重载以前会把 `.md5` 校验文件也当成备份归档去解析，每种类型都白算一次、白记两行日志。现在统一跳过。实测 9 个应用（13.96 GB）的日志从 445 行降到 274 行。
- **顺带一条实用结论**：只想让列表认出你新拷进来的备份、不需要刷新图标和版本号时，**先把「解析安装包」关掉再点重载** —— 实测 4.01 秒降到 0.18 秒。
- **修复重载失败后卡在加载中**：以前中途出错就一直转圈；现在无论成功失败都会正常结束。
- **重载页的「版本」不再显示英文**：中文界面下显示「当前版本」。
- 修正压缩等级 / 压缩线程数的说明：**关闭开关只是把这项折叠起来，设置依然生效**（默认 1 级 / 2 线程）。

### v3.13.0

- **云端备份支持分卷上传**：部分网盘（WebDAV 等）限制单文件大小，超限就传不上去。**设置 → 备份设置 →「云端分卷大小」**可选 关闭 / 1 GB / 2 GB / 4 GB（默认关闭）：开启后，云端上传的归档超过设定值会切成若干分卷逐卷上传；恢复或导入时按顺序下载并自动合并，校验码仍是整档的值，与不分卷时完全一致。**本地备份不受影响** —— 本地始终是一个完整文件。覆盖应用备份、文件备份、一键迁移导出三条云端路径。
- **归档过大提醒**：云端归档超过 10 GB 又没开分卷时，在任务日志里提示一次并带上实际大小，方便判断要不要开分卷。
- **应用内「更新日志」**：主页顶栏的版本号徽章、**设置 → 关于 → 更新日志** 都能打开更新日志页，看当前版本与历史版本改了什么；离线可看，不用再跳浏览器。
- **澎湃系统默认关闭「恢复权限」**：HyperOS 上这个开关不是"还原备份时的授权状态"，而是恢复时**强制授予全部权限**；现改为默认关闭、交给系统自己恢复，手动改过则以你的设置为准。
- 修复**恢复分卷备份时应用数据被跳过**（恢复侧判断没考虑分卷形态）；修复**分卷合并写出损坏文件**（合并改为流式拼接 + 长度自校验）。
- 设置排版：「云端分卷大小」移到「杀死应用选项」下方；「压缩等级」「压缩线程数」改为开关 + 可折叠（默认展开，行为不变）。

### v3.12.1

- **修复应用装得多时列表扫描卡顿**：根因不在扫描本身，而在**进度通知** —— 每处理一个应用就更新一次前台通知，而系统更新通知只能在主线程做、每次还要跨进程通信一次。实测 47 个应用时通知在 0.79 秒内触发 291 次全部落在主线程，开销是实际工作量的 5 倍以上；现改为 250 ms 合帧（首尾必发），复测 291 → 6 次。
- **修复后台启动时任务加载失败**：应用在后台时系统拒绝 `startForegroundService()`（Android 12+ 限制），异常冒泡使 Worker 以失败收场、恢复页备份列表加载不出来；现在统一吞掉该异常（通知只是进度展示，不该让业务失败）。
- 优化：权限定义按权限名缓存、`SsaidUtil` 按 userId 复用、Room 转换器复用 Gson 实例。

## 功能

- 备份/恢复应用、数据、APK、权限、SSAID（Android ID），支持多用户
- 系统数据备份：短信（含彩信）、通话记录、WiFi 网络，本地 / 云端双通路，恢复前自动检查库结构兼容性
- 数据迁移：备份数据一键打包迁到新机，支持导出/导入、云端中转
- 备份完整性检查：恢复前校验归档与 .md5 是否成对，缺失会列出提示
- 修复数据属主：游戏重装后外部存储残留旧属主导致写不进去，恢复时自动修复，也可在设置里手动扫描
- 安全加固：root 命令参数转义、迁移包导入安全校验、WebDAV 强制 HTTPS
- 保留历史备份：自动保留历史版本，可设最大保留数
- 备份目录带应用名，文件管理器里一眼可辨
- 云备份：WebDAV / FTP / SMB / SFTP
- 云端分卷上传：归档超过设定值时切卷上传，绕开网盘单文件大小限制，恢复时自动按序下载合并
- 应用内更新日志：主页版本号徽章 / 设置 → 关于 查看当前版本与历史版本改了什么
- 随机化 Android ID / GAID，恢复时可选
- 日志导出：设置 → 高级 → 导出日志

## 配套工具：FTP 数据服务器（Windows）

手机存储不够放备份？USB 2.0 传输太慢？用电脑当中转站：

- 双击运行即部署 FTP 备份服务器（自动配防火墙 / Python 环境 / 随机密码），照着窗口里的连接信息在 App 云备份（FTP）里填写即可
- 手机 → 电脑无线传输，Wi-Fi 6/7 下比 USB 快；备份直接落在电脑硬盘，不占手机空间
- 反馈问题时运行 `DataBackupFTPServer.exe --diagnose`，导出环境信息 + 备份文件清单 + 完整性检查（zip），配合手机端「导出日志」一起看
- 原始创意来自酷安 [@喵脆角12448](https://www.coolapk.com/feed/73346386)（经授权重构，重构者酷安 @骏冲冲），详见 [companion/ftp-server](./companion/ftp-server/README.md)，脚本与说明从 [Releases](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases) 获取

## 运行环境

- Root 权限：支持 [Magisk](https://github.com/topjohnwu/Magisk) / [KernelSU](https://github.com/tiann/KernelSU) / [APatch](https://github.com/bmax121/APatch)
- 系统版本：Android 7.0+（API 24），推荐 Android 10 及以上
- 存储空间：备份目录默认在内部存储 `DataBackup/`，体积约等于应用数据体积，请预留空间
- 云备份（可选）：WebDAV / FTP / SMB / SFTP，需自行准备服务器，或使用上面的 FTP 数据服务器（Windows 端，需管理员权限、与手机同一局域网）

## 原版功能

* Root 支持：[Magisk](https://github.com/topjohnwu/Magisk)、[KernelSU](https://github.com/tiann/KernelSU)、[APatch](https://github.com/bmax121/APatch)
* 多用户支持
* 云备份（WebDAV / SMB / SFTP / FTP）
* 100% 数据完整性
* 快速
* 简单易用

## 注意事项

- ⚠️ **v3.8.6 起更换了签名**：旧签名已随目录误删丢失，改用新签名签发。**从 v3.8.5 及更早版本升级的老用户必须先卸载再安装**（可先用 App 内「备份自身配置」保存备份记录与云账号，卸载不会丢备份数据）。
- 跨系统大版本恢复属高风险操作（如澎湃 OS 3 → 移植的澎湃 OS 4），系统底层变化大，数据 / APK 很可能不兼容，失败是常态。建议同系统版本备份 + 恢复。定制版已针对此类场景做了兼容修复，若仍失败请导出日志反馈。
- 第三方修改版应用（内置模块版）的兼容性提醒：LSPatch 重打包的「内置模块版」应用（如带防撤回的修改版 Telegram / QQ），其内嵌的 Xposed 兼容层可能与过新的系统不兼容——实测 Android 17（澎湃 OS4）上启动即崩（`NoSuchMethodError: XmlUtils.readMapXml`）。这类应用恢复数据后闪退 ≠ 备份工具有问题，全新安装同样会崩。排查方法：先卸载重装该应用（不恢复数据）看是否仍崩；v3.6.6 起导出的日志自带系统崩溃档案（`system_evidence.txt`），可直接看到目标应用的真实死因。
- **系统数据（短信 / 通话记录）跨品牌恢复有风险**：恢复是整库覆盖，而不同品牌的数据库结构可能不同（例如一加的库带 `oplus_*` / `rcs_*` 字段，小米期望的是 `miui_*`）。覆盖过去之后，目标机的应用会按它自己的结构去查询，对不上的字段就查不到。应用会在恢复前比对结构并弹窗列出缺失内容，看到提示建议先确认清楚再继续 —— 同品牌之间通常没有差异，不会打扰你。
- 备份包尽量本地直传（数据线 / 同一存储），避免经网盘、第三方工具多次中转导致数据包损坏。
- 恢复失败请先导出日志（设置 → 高级 → 导出日志）再反馈。

## 下载

从 [Releases](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases/latest) 获取最新 APK。

## 捐赠

如果你觉得这个定制版对你有帮助，欢迎支持：

**定制版作者（我）** — 微信赞赏码：

<img src="https://cdn.jsdelivr.net/gh/boluo4169-commits/Android-DataBackup-custom@main/docs/wechat_sponsor.png" width="240" alt="微信赞赏码" />

**原作者 [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup)**：

- PayPal：https://paypal.me/XayahSuSuSu
- 爱发电：https://afdian.net/a/XayahSuSuSu

## 许可证

本项目基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改，遵循 [GNU General Public License v3.0](./LICENSE)。
