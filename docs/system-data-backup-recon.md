# 系统数据备份调研：短信 / 通话记录（壁纸本期不做）

> 设备：一加 15（PLK110）Android 16 / SDK 36，ColorOS 16.0.3.503，KernelSU root
> 调研方式：只读。`adb shell su -c` 读私有目录；库结构用「`cp` 到 `/data/local/tmp` → `adb pull` → 本地 Python `sqlite3`」分析（已连 `-wal/-shm` 概念核对，本机无 WAL 故无影响）。所有输出已脱敏（无短信正文/号码/姓名）。

## 结论先行
- 短信库与通话记录库**均不使用 WAL 模式**（实测 `journal_mode=delete`，设备上仅有 0 字节的 `-journal`，无 `-wal`/`-shm`）。备份只需复制 `.db` 主文件，恢复也不必处理 WAL 伙伴文件。【实测确认】
- 短信数据主库为 CE 存储 `mmssms.db`；`/data/user_de/0` 同名库 `sms` 表为 0 行，可忽略。通话记录的 `calls` 表独立存在于 `calllog.db`，与联系人库 `contacts2.db` 是**不同文件**（后者用 WAL，但不在本次范围）。【实测确认】
- 恢复两条路线都可行：「整库文件替换」最完整；「content insert」可行但只能填部分列。【推断为主，路径/属主/标签为实测】

## A. 短信

### A1 候选路径（命令：`su -c 'ls -lZ /data/data/com.android.providers.telephony/databases/'`）
| 路径 | 大小 | 属主(数字) | SELinux | 备注 |
|---|---|---|---|---|
| `.../databases/mmssms.db` | 864256 | 1001:1001 (radio) | `u:object_r:radio_data_file:s0` | 主库，`-rw-rw----` |
| `.../databases/mmssms.db-journal` | 0 | 1001:1001 | `radio_data_file:s0` | 0 字节，无待提交事务 |
| `/data/user/0/.../mmssms.db` | 864256 | 1001:1001 | 同上 | 与 `/data/data` 为同一 inode（symlink） |
| `/data/user_de/0/.../databases/mmssms.db` | 196608 | 1001:1001 | 同上 | user_de 库，`sms`=0 行 |
| `-wal` / `-shm` | 不存在 | — | — | rollback 模式，无 WAL 文件 |

`/data/user/0/com.android.providers.telephony/` 下仅有 `cache/ code_cache/ databases/`，**无其它 db**。

### A2 库结构（本地 `sqlite3` 分析复制件；`journal_mode` 存于文件头，复制件等价）
- `PRAGMA user_version` = **910000**；`PRAGMA journal_mode` = **delete**
- 表清单（节选）：`sms, threads, canonical_addresses, addr, part, pdu, attachments, raw, sr_pending, pending_msgs, …`（共 20 张，含 ColorOS `oplus_*`/`rcs_*` 扩展列）
- `sms` 关键列：`_id, thread_id, address, person, date, date_sent, protocol, read, status, type, subject, body, service_center, locked, sub_id, creator, seen, …`（另有大量 `oplus_/rcs_` 厂商列）
- 当前行数：**sms = 1315，threads = 413**
- MMS 附件：`app_parts/` 目录**不存在**（实测 `ls app_parts` 0 文件）。MMS 附件以 BLOB 存于 `part` 表 `body` 列，随 `mmssms.db` 一并备份，无需单独处理。【实测确认】

## B. 通话记录

### B4/B5 候选路径与 `calls` 表位置（命令：`su -c 'ls -lZ /data/data/com.android.providers.contacts/databases/'`）
| 路径 | 大小 | 属主(数字) | SELinux | 备注 |
|---|---|---|---|---|
| `databases/calllog.db` | 495616 | 10073:10073 (u0_a73) | `u:object_r:privapp_data_file:s0:c512,c768` | 含 `calls` 表，`-rw-rw----` |
| `databases/calllog.db-journal` | 0 | 10073 | 同上 | 0 字节 |
| `databases/contacts2.db` | 1298432 | 10073 | 同上 | 联系人库；`journal_mode=wal` |
| `databases/contacts2.db.old1` | 1298432 | 10073 | 同上 | 旧备份，非活动 |
| `breeno_calllog.db` / `bluetooth_contacts.db` / `old_phone_record.db` / `profile.db` | 各异 | 10073 | 同上 | ColorOS 附加库，非标准通话记录 |

- `calls` 表**位于 `calllog.db`**（本地验证：`calllog.db` 含 `calls`；`contacts2.db` 仅有 `affiliated_calls` 关联表，无 `calls`）。【实测确认】
- `calls` 关键列：`_id, number, presentation, date, duration, type, features, subscription_id, new, name, numbertype, countryiso, geocoded_location, normalized_number, photo_id, last_modified, simid, …`（含 `oplus_` 厂商列）
- 当前行数：**calls = 1137**
- 联系人库与通话记录**不是同一文件**：`calllog.db`（`delete` 模式）独立于 `contacts2.db`（`wal` 模式）。备份粒度只需 `calllog.db` 一个文件。【实测确认】

## C. 壁纸
本期不做（用户决定推迟，理由：生效成本高、可能涉及重启 system_server）。

## D. 恢复可行性预判（最关键）

### D10 直接替换文件（逐条操作，命令列出但**不执行**）
**短信：**
1. `am force-stop com.android.providers.telephony` ← 必须先停 provider 释放 db 句柄（**不执行**）
2. `cp <备份>/mmssms.db /data/data/com.android.providers.telephony/databases/mmssms.db`
3. `chown 1001:1001 /data/data/com.android.providers.telephony/databases/mmssms.db`
4. `chmod 660 .../mmssms.db`
5. `restorecon /data/data/com.android.providers.telephony/databases/mmssms.db`（自动恢复 `u:object_r:radio_data_file:s0`）
6. 旧 `-journal`（0 字节）可保留或删，无影响
7. 是否重启：**无需整机重启，也无需重启 system_server**。provider 被杀后下次被访问会自动重启并重新打开新 db 即生效。【推断】

**通话记录：**
1. `am force-stop com.android.providers.contacts` ← **不执行**
2. `cp <备份>/calllog.db /data/data/com.android.providers.contacts/databases/calllog.db`
3. `chown 10073:10073 .../calllog.db`
4. `chmod 660 .../calllog.db`
5. `restorecon .../calllog.db`（恢复 `u:object_r:privapp_data_file:s0:c512,c768`，含 MCS 类别，由路径策略决定故自动正确）
6. 同样无需整机 / system_server 重启，provider 重启即生效。【推断】

> 注：`restorecon` 比手填 `chcon` 更安全——标签由路径策略决定，能精确还原 `:c512,c768` 类别。两库均为 `delete` 模式，**恢复时无需处理 `-wal/-shm`**。【实测确认模式；推断 restorecon 行为】

### D11 用系统接口写入
- **短信**：`content insert --uri content://sms/inbox --bind address:s:... --bind body:s:... --bind date:i:... --bind type:i:... --bind read:i:0`。需 `WRITE_SMS`；root 下 `su 1001 -c 'content insert ...'` 以 provider 自身 uid 调用可绕过权限校验。**结论：可行，但只能填部分列，且 `threads`/`canonical_addresses` 不会自动重建，完整性不如整库替换。**【推断】
- **通话记录**：`content insert --uri content://call_log/calls --bind number:s:... --bind date:i:... --bind duration:i:... --bind type:i:...`。需 `READ/WRITE_CALL_LOG`；`su 10073 -c content insert` 绕过。**结论：可行但列多、部分由系统生成，整库替换更稳妥。**【推断】
- **`cmd` 子命令**：无直接 sms/call_log 的 `cmd` 写入入口（无 `cmd sms` / `cmd calllog`）。【实测确认】

### D12 标注汇总
- 路径 / 属主(数字) / SELinux / 大小 / 行数 / 列名 / `journal_mode`：**【实测确认】**（设备 `ls -lZ`、`ls -n`，本地 `sqlite3` 分析复制件）
- `restorecon` 自动还原类别、force-stop 后免重启即生效、`content insert` 可行性：**【推断】**（基于 Android provider 机制，未做破坏性验证）
