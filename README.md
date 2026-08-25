<div align="center">

<h1>DataBackup</h1>

**定制版**

基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改的个人定制版本

[![GitHub release](https://img.shields.io/github/v/release/boluo4169-commits/Android-DataBackup-custom?color=orange)](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases)
[![License](https://img.shields.io/github/license/boluo4169-commits/Android-DataBackup-custom?color=ff69b4)](./LICENSE)

需要 Root 权限的数据备份应用

</div>

---

## 🛡️ 备份可靠性加固（v3.6.6 新增）

- **备份大应用不再误报失败**：打包期间应用后台写入（典型：微信清缓存后系统重建表情目录）触发的 `file changed as we read it` 不再导致备份报错——归档本身完整，自动保留并照常通过校验；应用数据与媒体备份均已覆盖
- **外部存储 SELinux 标签加固**：恢复 `Android/{data,obb,media}` 时无条件走系统 `restorecon` 重打正确标签，不再信任可能错误的残留标签（游戏「存储空间不可用」类问题的进一步加固）
- **删除多用户空间应用的闪退修复**：恢复列表删除双开空间（user 999 等）下唯一的备份应用时 Tab 越界闪退，已修复
- **「立即检查并修复」功能修复**：属主扫描的 stat 参数解析错误导致所有目录被误报为属主错位、修复全部失败，已修正
- **日志与诊断增强**：崩溃报告自动附带本进程 logcat 与完整版本信息；导出日志新增 `system_evidence.txt`（系统 logcat 尾部 + 系统 dropbox 崩溃档案）；日志文件名改为日期时间格式

## 🔧 修复数据属主（v3.6.5 新增）

游戏卸载重装后，外部存储（`Android/data`、`obb` 等）可能残留旧 UID 属主的数据目录，导致游戏写不进去而更新失败（如 UE4 报 556793857）：

- **恢复设置新增「修复数据属主」开关**（默认开启）：每次恢复应用数据完成后自动校验数据目录属主，发现残留自动修复
- **设置页「数据修复」分区可手动触发**：一键扫描并修复所有应用的数据目录（内部 + 外部存储），支持深度检测，可发现目录内部的属主残留；跳过其他活跃应用所属目录（双开安全）
- **修复备份 APK 报错**（v3.6.5 补丁）：安全加固误给 APK 源路径加引号导致 `./*.apk` 通配符无法展开，备份 APK 直接失败；已修复恢复

## 🔒 安全加固（v3.6.0 新增）

- **修复 root shell 命令注入隐患**：备份/恢复、权限、SELinux、目录操作等所有 root 命令的路径参数统一走 POSIX 单引号转义，应用名含特殊字符不再有被注入执行的风险
- **迁移包导入安全门闩**：包内条目名含危险字符（`../`、绝对路径等）时整包拒绝导入，防止解压越权写入
- **迁移包完整性校验**：导出完成页展示 SHA-256 校验码（一键复制）；导入页可粘贴校验码强校验，不匹配直接拒绝；本机导出历史自动记录，方便核对
- **WebDAV 强制 HTTPS**：未开启「允许不安全连接」时只允许 https 连接，账号密码不再明文传输
- **移除休眠的 dex 特权模块**：删除未启用的 dex/ 目录（84 个文件），仓库更干净

## 🔥 澎湃（HyperOS）系统专项修复（v3.5.0 重点）

针对澎湃用户「低版本系统（OS3）备份 → 升级/移植高版本（OS4）后恢复失败」的系列问题，全部修复并经真实用户实测验证（澎湃机型 25102RKBEC，OS3 → 移植 OS4，账号数据已正常恢复）：

- **数据迁移导入闪退**：导入/导出几 GB 迁移包时大文件复制在 UI 主线程执行导致 ANR 被杀 → 全部 IO 移至 IO 线程 + 1MB 大缓冲，不再卡死闪退
- **导入弹窗应用名乱码**：libsu Shell 输出对 UTF-8 中文转义（不同设备转义形式不同）→ 双保护解码（八进制 + Latin-1 兜底），中文应用名正常显示
- **跨系统升级后导入报错**：新系统上备份目录未创建导致解压失败 → 导入前自动创建目标目录
- **跨系统恢复失败**（v3.4.0）：包名被目录名污染、安装后 PackageManager 未刷新、版本降级被拒，3 个原版遗留 bug 全部修复

## 📦 数据迁移（v3.2.2 核心功能，v3.5.0 完善）

换机场景下，备份数据一键打包迁移，无需重新下载应用资源：

- **导出备份**：勾选应用（支持搜索、用户空间筛选、排序、全选/反选）→ 系统文件选择器选位置 → 弹窗提示「加份保险」（建议用文件管理器把整个备份目录打包 zip 做额外保障）→ 后台打包成迁移包（tar.zst）→ 进度卡显示，完成后手动返回；完成页展示 SHA-256 校验码
- **导入备份**：独立导入页，选择迁移包 → 自动解析（进度条 + 应用列表确认）→ 解压到本机备份目录 → 自动刷新恢复列表；可选粘贴 SHA-256 校验码强校验，危险条目名整包拒绝
- 迁移包包含应用的**主备份 + 受保护版本**，导入后原样保留
- 导入/导出全程 IO 线程执行，8GB 级别大迁移包流畅不卡；中断残留的临时文件下次进入自动清理

## ✅ 备份完整性检查（v3.4.0 新增）

恢复前自动扫描每个勾选应用的备份目录，校验归档文件（如 `user.tar.zst`）与 `.md5` 是否成对：

- 归档缺失 / 备份目录为空 → 弹窗列出「应用名 + 缺失文件」，由你决定继续还是取消
- 不再出现恢复进行到一半才报 "Not exist" 的情况

## ✨ 其他定制改动

- **备份目录加应用名**：目录名改为「应用名_包名」，文件管理器里一眼识别
- **保留历史备份**：每次备份自动保留历史版本，最新备份为正常版本，历史版本带盾牌序号，可设最大保留数
- **云备份修复**：修复云端（WebDAV）中文应用名备份闪退、多版本归档、保护标签失效、路径 URL 编码等问题
- **详情页大小不再归零**：本地重算为 0 时保留备份时写入的大小（OPPO 用户反馈修复）
- **性能优化**：修复澎湃 OS / 小米机型列表滑动卡顿、详情页「计算中」卡死、扫描卡界面等问题
- **权限恢复优化**：「恢复权限」开关新增说明，提示澎湃系统用户关闭以避免恢复时权限全部放行
- **日志导出**：设置 → 高级 → 导出日志，系统文件管理器选择位置，打包为 zip；v3.6.6 起附带系统取证信息（系统 logcat 尾部 + 系统崩溃档案），文件名含可读时间，应用崩溃堆栈自动写入
- **关于页面**：捐赠入口区分「定制版作者（微信赞赏码）」与「原作者」，链接指向本仓库

## 🖥️ 配套工具：FTP 数据服务器（Windows）

手机存储不够放备份？USB 2.0 传输太慢？用电脑当中转站：

- **一键部署** FTP 备份服务器（自动配置防火墙 / Python 环境 / 随机密码），双击运行、照着窗口里的连接信息在 App 云备份（FTP）里填写即可
- 手机 → 电脑无线传输，Wi-Fi 6/7 下远快于 USB；备份直接落在电脑硬盘，不占手机空间
- 原始创意来自酷安 [@喵脆角12448](https://www.coolapk.com/feed/73346386)（经授权重构），详见 [companion/ftp-server](./companion/ftp-server/README.md)，脚本与说明从 [Releases](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases) 获取

## 原版功能

* :deciduous_tree: **Root 支持**：支持 [Magisk](https://github.com/topjohnwu/Magisk)、[KernelSU](https://github.com/tiann/KernelSU)、[APatch](https://github.com/bmax121/APatch)
* :cyclone: **多用户支持**
* :cloud: **云备份**（WebDAV / SMB / SFTP / FTP）
* :sunglasses: **100% 数据完整性**
* :zap: **快速**
* :sunny: **简单易用**

## 注意事项

- ⚠️ **跨系统大版本恢复属高风险操作**（如澎湃 OS 3 → 移植的澎湃 OS 4），系统底层变化大，数据 / APK 很可能不兼容，失败是常态。**建议同系统版本备份 + 恢复。** 定制版已针对此类场景做了大量兼容修复，若仍失败请按下面方式反馈日志。
- ⚠️ **第三方修改版应用（内置模块版）的兼容性提醒**：LSPatch 重打包的「内置模块版」应用（如带防撤回等功能的修改版 Telegram / QQ），其内嵌的 Xposed 兼容层可能与过新的系统不兼容——实测 Android 17（澎湃 OS4）上启动即崩（`NoSuchMethodError: XmlUtils.readMapXml`）。这类应用**恢复数据后闪退 ≠ 备份工具有问题**：全新安装同样会崩。排查方法：先卸载重装该应用（不恢复数据）看是否仍崩；v3.6.6 起导出的日志自带系统崩溃档案（`system_evidence.txt`），可直接看到目标应用的真实死因。
- 备份包尽量**本地直传**（数据线 / 同一存储），避免经网盘、第三方工具多次中转导致数据包损坏。
- 恢复失败请先**导出日志**（设置 → 高级 → 导出日志）再反馈，否则无法定位问题。

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
