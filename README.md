<div align="center">

<h1>DataBackup</h1>

**定制版**

基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改的个人定制版本

[![GitHub release](https://img.shields.io/github/v/release/boluo4169-commits/Android-DataBackup-custom?color=orange)](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases)
[![License](https://img.shields.io/github/license/boluo4169-commits/Android-DataBackup-custom?color=ff69b4)](./LICENSE)

需要 Root 权限的数据备份应用

</div>

---

## 🔥 澎湃（HyperOS）系统专项修复（v3.5.0 重点）

针对澎湃用户「低版本系统（OS3）备份 → 升级/移植高版本（OS4）后恢复失败」的系列问题，全部修复并经真实用户实测验证（澎湃机型 25102RKBEC，OS3 → 移植 OS4，账号数据已正常恢复）：

- **数据迁移导入闪退**：导入/导出几 GB 迁移包时大文件复制在 UI 主线程执行导致 ANR 被杀 → 全部 IO 移至 IO 线程 + 1MB 大缓冲，不再卡死闪退
- **导入弹窗应用名乱码**：libsu Shell 输出对 UTF-8 中文转义（不同设备转义形式不同）→ 双保护解码（八进制 + Latin-1 兜底），中文应用名正常显示
- **跨系统升级后导入报错**：新系统上备份目录未创建导致解压失败 → 导入前自动创建目标目录
- **跨系统恢复失败**（v3.4.0）：包名被目录名污染、安装后 PackageManager 未刷新、版本降级被拒，3 个原版遗留 bug 全部修复

## 📦 数据迁移（v3.2.2 核心功能，v3.5.0 完善）

换机场景下，备份数据一键打包迁移，无需重新下载应用资源：

- **导出备份**：勾选应用（支持搜索、用户空间筛选、排序、全选/反选）→ 系统文件选择器选位置 → 弹窗提示「加份保险」（建议用文件管理器把整个备份目录打包 zip 做额外保障）→ 后台打包成迁移包（tar.zst）→ 进度卡显示，完成后手动返回
- **导入备份**：独立导入页，选择迁移包 → 自动解析（进度条 + 应用列表确认）→ 解压到本机备份目录 → 自动刷新恢复列表
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
- **日志导出**：设置 → 高级 → 导出日志，系统文件管理器选择位置，打包为 zip
- **关于页面**：捐赠入口区分「定制版作者（微信赞赏码）」与「原作者」，链接指向本仓库

## 原版功能

* :deciduous_tree: **Root 支持**：支持 [Magisk](https://github.com/topjohnwu/Magisk)、[KernelSU](https://github.com/tiann/KernelSU)、[APatch](https://github.com/bmax121/APatch)
* :cyclone: **多用户支持**
* :cloud: **云备份**（WebDAV / SMB / SFTP / FTP）
* :sunglasses: **100% 数据完整性**
* :zap: **快速**
* :sunny: **简单易用**

## 注意事项

- ⚠️ **跨系统大版本恢复属高风险操作**（如澎湃 OS 3 → 移植的澎湃 OS 4），系统底层变化大，数据 / APK 很可能不兼容，失败是常态。**建议同系统版本备份 + 恢复。** 定制版已针对此类场景做了大量兼容修复，若仍失败请按下面方式反馈日志。
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
