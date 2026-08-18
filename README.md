<div align="center">

<h1>DataBackup</h1>

**定制版**

基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改的个人定制版本

[![GitHub release](https://img.shields.io/github/v/release/boluo4169-commits/Android-DataBackup-custom?color=orange)](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases)
[![License](https://img.shields.io/github/license/boluo4169-commits/Android-DataBackup-custom?color=ff69b4)](./LICENSE)

需要 Root 权限的数据备份应用

</div>

---

## 📦 数据迁移（v3.2.2 核心功能）

换机场景下，备份数据一键打包迁移，无需重新下载应用资源：

- **导出备份**：从已备份的应用中选择（支持搜索、用户空间筛选、排序、全选/反选），可展开单独勾选受保护版本，打包成迁移包（tar.zst）导出到任意位置
- **导入备份**：选择迁移包后自动解析其中的应用，一键解压到本机备份目录，自动刷新恢复列表
- 迁移包包含应用的**主备份 + 受保护版本**，导入后原样保留

## ✨ 其他定制改动

- **性能优化**：修复澎湃 OS / 小米机型列表滑动卡顿、详情页「计算中」卡死、扫描卡界面等问题
- **保留历史备份**：每次备份自动保留历史版本，最新备份为正常版本，历史版本带盾牌序号，可设最大保留数
- **云备份修复**：修复云端（WebDAV）中文应用名备份闪退、多版本归档、保护标签失效等问题
- **权限恢复优化**：「恢复权限」开关新增说明，提示澎湃系统用户关闭以避免恢复时权限全部放行
- **备份目录加应用名**：目录名改为「应用名_包名」，文件管理器里一眼识别
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

- ⚠️ **跨系统大版本恢复属高风险操作**（如澎湃 OS 3 → 移植的澎湃 OS 4），系统底层变化大，数据 / APK 很可能不兼容，失败是常态。**建议同系统版本备份 + 恢复。**
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
