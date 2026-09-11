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

### v3.10.0

- 修复返回列表页后顶部筛选按钮、切换用户空间失灵的问题（两个列表页同时存在时状态断开）。
- 批量选择不再勾选系统应用：一键备份和「选择全部」只选你自己装的应用，「加载系统应用」只负责是否显示；想单独备份某个系统应用，手动勾仍然可以。
- 顶部已选 / 总数跟随「加载系统应用」开关，关掉后只统计第三方应用。
- 多用户空间删除后：恢复列表标注「用户（已删除）」，恢复目标只列真实存在的用户，备份页残留勾选自动清理。
- 没备份过的应用不再显示「文件路径」（那条路径实际并不存在）。
- 补齐 15 种语言翻译；中文选项显示为「中文简体 / 中文繁体」，移除「中文（香港）」（原用户自动走繁体）。
- 一键备份引导页：勾选没变化时不重复计算大小；加载圈加粗并改为圆头。
- 备份设置页：杀死应用选项置顶、「保留历史备份」新装默认开启、「定时备份」入口移到「备份与迁移」页。

### v3.9.0

- ⚠️ **签名更换**：旧签名随目录误删丢失，改用新签名签发，**老用户必须卸载后重新安装**（可先用 App 内「备份自身配置」保存备份记录和云账号）。
- 新增「云端目录仅用包名」开关（默认开启）：解决 FTP / 网盘对中文目录名乱码、导致上传失败的问题。
- 修复定时备份在冷启动时环境未初始化而失败（zstd not found / tar 参数错误）。
- 修复云端恢复列表在纯包名模式下识别不到备份的问题。
- 日志系统加固：崩溃信息更完整，导出日志自带系统崩溃档案。

### v3.8.5

- 修复 FTP 上传大文件失败：去掉与响应读取冲突的保活机制、超时放宽到 300 秒、改用文件大小校验完整性。
- 备份被系统杀掉时自动收集温度、内存、日志等取证信息，方便判断是温控还是内存回收。
- 云端上传失败不再导致进程崩溃，改为标记失败原因。
- 详情页大小计算超时放宽到 60 秒；局域网权限按系统版本正确申请。

## 功能

- 备份/恢复应用、数据、APK、权限、SSAID（Android ID），支持多用户
- 数据迁移：备份数据一键打包迁到新机，支持导出/导入、云端中转
- 备份完整性检查：恢复前校验归档与 .md5 是否成对，缺失会列出提示
- 修复数据属主：游戏重装后外部存储残留旧属主导致写不进去，恢复时自动修复，也可在设置里手动扫描
- 安全加固：root 命令参数转义、迁移包导入安全校验、WebDAV 强制 HTTPS
- 保留历史备份：自动保留历史版本，可设最大保留数
- 备份目录带应用名，文件管理器里一眼可辨
- 云备份：WebDAV / FTP / SMB / SFTP
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

- 跨系统大版本恢复属高风险操作（如澎湃 OS 3 → 移植的澎湃 OS 4），系统底层变化大，数据 / APK 很可能不兼容，失败是常态。建议同系统版本备份 + 恢复。定制版已针对此类场景做了兼容修复，若仍失败请导出日志反馈。
- 第三方修改版应用（内置模块版）的兼容性提醒：LSPatch 重打包的「内置模块版」应用（如带防撤回的修改版 Telegram / QQ），其内嵌的 Xposed 兼容层可能与过新的系统不兼容——实测 Android 17（澎湃 OS4）上启动即崩（`NoSuchMethodError: XmlUtils.readMapXml`）。这类应用恢复数据后闪退 ≠ 备份工具有问题，全新安装同样会崩。排查方法：先卸载重装该应用（不恢复数据）看是否仍崩；v3.6.6 起导出的日志自带系统崩溃档案（`system_evidence.txt`），可直接看到目标应用的真实死因。
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
