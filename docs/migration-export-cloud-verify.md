# 数据迁移「导出到云端」真机验证清单

针对 2026-09-13 云端导出修复（未提交改动）的逐项验证。每条用例都标注了它覆盖的代码位置，失败时可直接定位。

## 0. 前置

| 项 | 值 |
|---|---|
| 设备 | 已 root、Shell 已授权（`su -c` 可用） |
| 包名 | `com.databackup.version`（`foss` / `premium` flavor 会带 `.foss` / `.premium` 后缀，按实装填写） |
| 云端账号 | 至少一个 WebDAV；FTP/SFTP/SMB 各配一个更稳（四家的上传实现是独立代码路径） |
| 备份数据 | 勾选 2~5 GB 的数据量——太小看不出进度条问题，也来不及在上传中途断网 |

命令里的变量：

```bash
PKG=com.databackup.version
FILES=/data/data/$PKG/files
```

## 通用抓取命令

```bash
# 开测前清缓冲，避免混入旧日志
adb logcat -c

# 应用日志：打包/上传/清理的关键行
adb logcat -v time | grep -E "SHELL_CODE|SHELL_IN|SHELL_OUT|CloudRepository|Uploading|Failed to delete"

# ANR（主线程被阻塞时才会出现；本次改动不涉及，作为回归监控）
adb shell dumpsys dropbox --print data_app_anr | head -120

# 崩溃
adb shell dumpsys dropbox --print data_app_crash | head -80

# 应用私有目录里的临时包（正常情况下应为空——每个用例结束后都查一次）
adb shell su -c "ls -l $FILES | grep -E 'DataBackup_migration|import_'"
```

云端侧核对：用文件管理器 / FTP 客户端直接看 `{remote}/migration` 目录（`{remote}` = 该云端账号配置的远程根目录）。

---

## 用例 1 — WebDAV 云端导出

覆盖：`CloudRepository.upload`（P1）、`WebDAVClientImpl.upload` 大小校验（P2-1）、上传进度节流（P2-2）

| 项 | 内容 |
|---|---|
| 步骤 | 备份与迁移 → 导出备份 → 勾选 2~5 个应用 → 「导出到云端」→ 选 WebDAV 账号 |
| 预期 | 4 段进度（校验 / 打包 / 校验码 / 上传）逐段推进；「上传」段进度平滑（回调已节流到约 3 次/秒上限）；结束显示成功并给出 SHA-256 |
| 失败判据 | ① 出现「导出失败：Failed to delete …」→ P1 未生效；② 「上传」段进度条高频抖动/掉帧 → P2-2 未生效；③ 结束后 `$FILES` 下仍有 `DataBackup_migration_*.tar.zst` → 临时包未清 |
| 云端核对 | `{remote}/migration` 下出现 1 个 `DataBackup_migration_yyyyMMdd_HHmmss.tar.zst`，字节数与本地打包体积一致 |

## 用例 2 — 同账号二次导出（目录幂等）

覆盖：`client.mkdirRecursively(remoteMigrationDir)`

| 项 | 内容 |
|---|---|
| 步骤 | 紧接着用例 1，再导出一次（同一个云端账号） |
| 预期 | 同样成功；`{remote}/migration` 下出现第 2 个包（文件名时间戳不同） |
| 失败判据 | 报错含 `405` / `already exists` / `mkdir` 相关字样 → `mkdirRecursively` 在 WebDAV 上不幂等 |
| 说明 | 这是本次复查的重点之一（`mkdirRecursively` 是重构时新加的调用）。四家实现均为「先 exists 判断再建」，预期通过 |

## 用例 3 — 上传中途断网

覆盖：`WebDAVClientImpl` / `FTPClientImpl` / `SFTPClientImpl` / `SMBClientImpl` 的失败清理（P2-1 + P3-1）

| 项 | 内容 |
|---|---|
| 步骤 | 导出到云端，等进度走到「上传」段且百分比开始上升 → 关 WiFi / 开飞行模式 |
| 预期 | ① 界面报「导出失败」并带出原因首行 ② `$FILES` 下**无**临时包残留 ③ 无崩溃、无卡死 |
| **已知限制** | **`{remote}/migration` 会留下半包，这是物理限制**——网络已断，客户端无法再发删除请求。远端残包只能事后手动清理 |
| 失败判据 | ① 界面无失败提示，或卡在进度页不动 ② `$FILES` 下仍有 `DataBackup_migration_*.tar.zst` ③ 应用崩溃 |
| 说明 | 本次改动的清理逻辑覆盖的是「**连接仍可用、但传输失败**」的场景（FTP 服务器拒绝、字节数不符、远端大小不符）——此时能删远端。断网属于「连接已失效」，删不了，**不算 bug** |
| WebDAV 补充 | WebDAV 只在「远端能报出有效大小（>0）」时才检出截断；服务端不返回 `getcontentlength` 时会跳过比对（宁可漏报也不误报） |

## 用例 4 — 快速连点两次「导出到云端」

覆盖：`startExportToCloud` 的同步置位（P2-3）

| 项 | 内容 |
|---|---|
| 步骤 | 打开云端选择弹窗后，尽快连点两次同一个账号（或在列表页连点两次「导出到云端」按钮） |
| 预期 | 只启动一次导出（第二次点击被 `_isExporting` 挡掉） |
| 失败判据 | `{remote}/migration` 下出现两个时间戳**相同**的包；或 logcat 出现两组并行的 `SHELL_CODE` / 打包日志 → 竞态未修好 |
| 说明 | 修复前 `_isExporting` 的置位在协程体内（IO 线程），与检查之间存在窗口 |

## 用例 5 — 导出中切屏 / 转屏

覆盖：`CancellationException` rethrow（上一轮改动）

| 项 | 内容 |
|---|---|
| 步骤 | 导出进行到「打包」或「上传」段时，旋转屏幕（或切后台再回来） |
| 预期 | 不出现「导出失败：The coroutine scope left the composition」；导出继续或明确终止，临时包不残留 |
| 失败判据 | 出现上述文案 → 组合作用域未真正替换为 `viewModelScope` |

## 用例 6 — FTP / SFTP / SMB 各导出一次（回归）

覆盖：三家客户端的 `upload` 改动（清理分支不应影响成功路径）

| 项 | 内容 |
|---|---|
| 步骤 | 分别选 FTP、SFTP、SMB 账号各导出一次 |
| 预期 | 均成功；上传进度按 1 秒节奏推进（这三家走 Timer，不是 WebDAV 的按字节回调） |
| 失败判据 | 成功路径报 `Failed to write remote file: 0 byte.` 或 `Failed to delete` → 新增的清理分支被误触发 |

## 回归项 — 本地导出（SAF）仍正常

覆盖：`_isExporting` 同步置位同时改动了 `startExport`（本地路径）

| 项 | 内容 |
|---|---|
| 步骤 | 走一次本地「导出备份」，保存到系统文件选择器指定的位置 |
| 预期 | 成功，SHA-256 展示正常，`$FILES` 下无 `DataBackup_migration_*.tar.zst` |
| 失败判据 | 首次点击无反应（置位后协程未启动）→ 需回查 `startExport` 的改动 |

---

## 结果记录模板

| 用例 | 结果 | 现象 / 日志关键行 | 结论 |
|---|---|---|---|
| 1 WebDAV 导出 | | | |
| 2 二次导出 | | | |
| 3 断网 | | | |
| 4 连点两次 | | | |
| 5 切屏 | | | |
| 6 FTP/SFTP/SMB | | | |
| 回归 本地导出 | | | |
