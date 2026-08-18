<div align="center">

<h1>DataBackup</h1>

**定制版**

基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改的个人定制版本

[![GitHub release](https://img.shields.io/github/v/release/boluo4169-commits/Android-DataBackup-custom?color=orange)](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases)
[![License](https://img.shields.io/github/license/boluo4169-commits/Android-DataBackup-custom?color=ff69b4)](./LICENSE)

需要 Root 权限的数据备份应用

</div>

---

## 本定制版改动

### 📦 数据迁移（v3.2.2 新增）

换机场景一键打包迁移备份数据：

- **导出备份**：从已备份的应用中选择（支持搜索、用户空间筛选、排序、全选/反选），可展开单独勾选受保护版本，打包成迁移包（tar.zst）导出到任意位置
- **导入备份**：选择迁移包自动解析应用，一键解压到本机备份目录，自动刷新恢复列表
- 迁移包包含应用的**主备份 + 受保护版本**，导入后原样保留，换机迁移无需重新下载

### 🚀 性能优化（修复澎湃 OS / 小米机型卡顿）

针对澎湃 OS（HyperOS）上「列表滑动卡顿、详情页数据一直『计算中』、扫描本机应用卡界面」等问题，做了系统性优化：

- 应用图标加载增加全局内存缓存，并把图片转换移入后台线程，列表滑动流畅
- 应用详情页数据大小计算改为并行 + 超时，不再卡在「计算中」
- 修复 native 遍历大目录时的空指针隐患
- 扫描本机应用时跳过不必要的存储统计，界面不再因扫描卡顿
- 移除列表条目位移动画，降低刷新卡顿

### 📦 保留历史备份

- 新增「保留历史备份」开关（默认关闭）：开启后每次备份自动保留历史版本，不再覆盖
- 支持设置「最大保留版本数」，超出后自动删除最旧版本
- 最新备份始终是正常版本，历史版本自动标记为「受保护」并带盾牌序号

### 🏷️ 多版本标识

- 恢复列表里受保护版本显示备份时间 + 盾牌序号（1/2/3），一眼分辨新旧
- 同一应用多个版本一起恢复时弹提醒，避免误覆盖

### 📂 备份目录加应用名

- 备份目录名从纯包名改为「应用名_包名」（如 `Via_mark.via`），在文件管理器里一眼识别
- 特殊字符自动清洗，秒级时间戳避免同名冲突
- 兼容旧格式目录，已有备份不受影响

### 📋 日志导出

- 设置 → 高级 → 导出日志，调起系统文件管理器，自由选择保存位置
- 将近期日志打包为 zip 压缩包
- 应用崩溃堆栈自动写入日志，闪退后可导出分析

### 🔧 其他优化

- 「随机化 Android ID」弹窗强调需手动重启设备才会生效；「恢复 Android ID」不再弹窗
- 压缩等级优化：修复 zstd `--ultra` 参数冗余；选「TAR（不压缩）」时压缩等级滑杆自动置灰
- 「杀死应用选项」文案改为描述性（命令行强制结束 / 系统强制停止）
- 修复恢复列表多版本时间 / 护盾序号错乱问题
- 引导页「恢复设置」入口加图标
- 应用详情新增「文件路径」一键复制

---

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

<img src="./docs/wechat_sponsor.png" width="240" alt="微信赞赏码" />

**原作者 [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup)**：

- PayPal：https://paypal.me/XayahSuSuSu
- 爱发电：https://afdian.net/a/XayahSuSuSu

## 许可证

本项目基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改，遵循 [GNU General Public License v3.0](./LICENSE)。
