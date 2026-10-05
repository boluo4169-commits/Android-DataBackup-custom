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

### v3.14.0

- **备份不再重复劳动**：新增增量备份 —— 备份前先比一遍源目录的清单（路径 / 大小 / 修改时间），没变就跳过、不再重新打包；只有真正改动的类型才重打，其余从上一版归档里直接继承。压缩等级、排除项等设置改过会自动回到全量重打，目录读不全（权限不足等）也一律当「已变」处理 —— 宁可白打一次，不会漏掉真实变化。云端备份不参与增量（云端是「先写本地暂存再上传」，跳过会连上传一起跳掉、导致远端缺一整类数据）。
- **给每份备份单独写「备注」，列表里一眼分辨**：详情页新增备注行，点开写一句（如「干净初始版，别覆盖」），列表里该备份下方直接显示。备注按**版本**存 —— 同一个应用的主版本、保护版本、本地版、云端版各写各的、互不影响（比标签自由：标签是可复用的分类，备注是这一份备份专属的说明）。列表搜索也会匹配备注。
- **修「重载之后云端备份列表变空」**：重载会先删掉识别为脏数据的旧记录，但删完没重建 —— 重建依赖后台扫描任务，而那个任务失败一次就会长期占着名字，让后续请求被静默忽略。现在删完立刻写回。实测一次重载后云端 9 条记录全部回来（修复前会变成 0 条）。
- **修四类数据（USER_DE / DATA / OBB / MEDIA）恢复时不会被默认勾选**：重载里标记「哪些类型可恢复」的 8 行代码把值写进了一个随即丢弃的临时对象，赋值从未生效 —— 表现为远端明明有归档却不勾选。已修正。
- **修「某类数据被清空后，它从主备份里消失」**：例如 OBB 被卸载清掉后再备份，主备份会直接少一项（旧归档只剩在保护版本里）。现在会保留上一份归档，主版本始终完整。

### v3.13.4

- **把上一版没修完的地方补上**：上次只挡了 `.trashed-`（系统回收站），漏了同一机制的 `.pending-`（写入中的文件）—— 在系统里这两个前缀由**同一条规则**判定，一起挡掉才算完整。现在应用媒体、文件媒体、备份、恢复四处用同一份判据。
- 文件媒体侧的备份/恢复也会在日志里记下跳过了什么了（以前只有应用数据侧有），而且现在会**列出具体跳过的文件名**（前 5 个 + 总数），不再只报一个匹配模式。

### v3.13.3

- **修掉「恢复相册后照片不见了」**：系统删照片是**原地改名**成 `.trashed-…`（文件还留在目录里，相册不显示，30 天后才真删）。以前备份会把这些"已删除"的文件一起打包，恢复出来又被系统按名字判成已删 —— 相册看不见、到期后连文件一起消失。现在备份与恢复都跳过这类文件。
- 顺带说明：如果恢复后发现照片变少，检查一下「设置 → 干净恢复」是否开着 —— 它会在解压前清空目标目录。

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

## 致谢

感谢每一位反馈问题、提供日志、帮忙测试的朋友 —— 这个定制版的绝大部分改进都来自你们。完整名单见 [CONTRIBUTORS.md](./CONTRIBUTORS.md)。

> 反馈问题、提供日志、帮忙测试，都是对这个项目最大的帮助。

## 捐赠

如果你觉得这个定制版对你有帮助，欢迎支持：

**定制版作者（我）** — 微信赞赏码：

<img src="https://cdn.jsdelivr.net/gh/boluo4169-commits/Android-DataBackup-custom@main/docs/wechat_sponsor.png" width="240" alt="微信赞赏码" />

**原作者 [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup)**：

- PayPal：https://paypal.me/XayahSuSuSu
- 爱发电：https://afdian.net/a/XayahSuSuSu

## 许可证

本项目基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改，遵循 [GNU General Public License v3.0](./LICENSE)。
