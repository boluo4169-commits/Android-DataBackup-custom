# DataBackup Companion — FTP 数据服务器

Windows 上一键部署 FTP 备份服务器,配合 [DataBackup 定制版](https://github.com/boluo4169-commits/Android-DataBackup-custom) 的云备份(FTP)功能,实现「手机 → 电脑」无线中转备份:

- 不占手机存储空间,备份直接落在电脑硬盘
- Wi-Fi 6/7 无线传输(120MB/s+)远快于 USB 2.0(40MB/s)
- 适合刷机前的整机备份 / 换机迁移场景

> 原始创意与初版脚本来自酷安 **@喵脆角12448**([原帖](https://www.coolapk.com/feed/73346386)),经授权重构维护(酷安 @骏冲冲),遵循 MIT 许可证。

## 运行环境要求

- **系统**:Windows 10 / 11(64 位)
- **权限**:需要**管理员权限**(bat 版启动时会自动请求;exe 版建议右键「以管理员身份运行」,否则可能无法正常监听端口)
- **Python**:仅 bat 脚本版需要(启动时会自动检测/安装依赖 pyftpdlib);exe 版已打包 Python 运行环境,**无需安装**
- **网络**:电脑与手机需连接**同一个局域网**(同一 Wi-Fi / 路由器),FTP 为明文协议,请勿暴露到公网
- **杀毒软件**:如拦截 python.exe / 自检失败,请放行本程序(仅监听本地局域网端口 2121)

## 快速开始

**方式一（推荐，零依赖）**：从 [Releases](https://github.com/boluo4169-commits/Android-DataBackup-custom/releases) 下载 `DataBackupFTPServer.exe`，双击运行——无需安装 Python。

**方式二（透明可审）**：下载/查看本目录的 `DataBackupFTPServer.bat` 脚本版（需要联网自动安装 Python 环境）。

两者之后：

1. 按提示回车/输入即可（会自动请求管理员权限）
2. 启动完成后，窗口会显示**连接信息卡片**（地址/端口/用户名/密码）
3. 手机 DataBackup → 云备份 → FTP，照卡片填写即可

全部选项支持直接回车用默认值；密码留空会自动生成 8 位随机强密码并当场显示。

## 手机端配置对照

| 窗口卡片 | App 字段 |
|----------|----------|
| 地址 | 主机(host),通常填 `192.168.x.x` |
| 端口 | `2121` |
| 用户名 / 密码 | 对应填写 |
| 远程目录 | `/` |

传输模式保持被动(PASV);手机需与电脑连接同一个 Wi-Fi。

## 故障排查

| 现象 | 处理 |
|------|------|
| 手机连不上 | 确认同一局域网;关闭电脑端「公用网络」防火墙配置文件或改「专用网络」;逐个尝试卡片列出的 IP |
| 启动报端口占用 | 关闭其他 FTP 服务,或修改脚本中的 `FTP_PORT` 与被动端口段 |
| 自检失败 | 查看防火墙放行;确认杀毒软件未拦截 python.exe |
| 传输中断 | 关闭电脑休眠(控制面板 → 电源选项) |

## 诊断信息导出（反馈问题时使用）

遇到「连不上 / 备份找不到 / 文件异常」等问题需要反馈维护者时，**不要只发截图**，一键导出诊断包：

```
DataBackupFTPServer.exe --diagnose
```

- 在**备份目录**生成 `DataBackup_diagnose_<时间>.zip`，包含：
  - `environment.txt`：系统 / Python 版本 / 局域网 IP / 防火墙状态 / 端口占用 / 磁盘空间
  - `ftp_status.txt`：FTP 服务配置（密码不输出）
  - `file_inventory.txt`：备份目录完整文件清单 + 按 apps/files/migration 分组统计
  - `integrity_check.txt`：完整性检查——应用配置 json 是否合法、归档与 .md5 是否配对、0 字节文件、云端迁移包列表
  - `diagnose.json`：机器可读汇总（异常列表）
- 备份目录不在默认位置时指定：`DataBackupFTPServer.exe --diagnose --backup-dir <路径>`
- 请**配合手机端 DataBackup「设置 → 高级 → 导出日志」**一起发送，形成完整反馈材料

## 安全提示

FTP 为明文协议,**仅限可信的家庭/办公局域网使用**,不要将端口暴露到公网;传输完成后建议直接关闭服务窗口。
