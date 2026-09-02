# -*- coding: utf-8 -*-
"""DataBackup Companion — FTP Backup Server (standalone executable).

Original idea by Coolapk @喵脆角12448, refactored by boluo4169-commits (Coolapk @骏冲冲). MIT.

Usage:
    DataBackupFTPServer.exe [username] [password] [backup_dir]

Leave password empty to auto-generate a random one.
"""
import datetime
import json
import os
import platform
import secrets
import shutil
import socket
import string
import subprocess
import sys
import time
import zipfile

# 直连控制台时 Python 自动使用 WinConsoleIO(中文正常);
# 输出被重定向/管道时强制 UTF-8, 避免中文变问号
if sys.stdout is not None and not sys.stdout.isatty():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

try:
    from pyftpdlib.authorizers import DummyAuthorizer
    from pyftpdlib.handlers import FTPHandler
    from pyftpdlib.servers import FTPServer
except ImportError:
    print("缺少依赖: 请执行  pip install pyftpdlib")
    input("按回车退出...")
    sys.exit(1)

LISTEN_PORT = int(os.environ.get("FTP_PORT", "2121"))
PASSIVE_MIN = int(os.environ.get("FTP_PASSIVE_MIN", "60000"))
PASSIVE_PORTS = range(PASSIVE_MIN, PASSIVE_MIN + 101)


# companion 自身的版本号（独立于 App；更新时手动 bump 这里）
COMPANION_VERSION = "v1.5"

try:
    # CI 构建时注入「构建所对应的 App release tag」（如 v3.6.7），
    # 仅用于更新检测（配套 App 更新提示），不影响 companion 自身版本显示
    from _tool_version import APP_BUILD_TAG
except Exception:
    APP_BUILD_TAG = "dev"

REPO_URL = "https://github.com/boluo4169-commits/Android-DataBackup-custom"
BANNER = r"""
  ____        _           ____                _
 |  _ \  __ _| |_ __ _   |  _ \ _   _  __ _| |  _ __ ___
 | | | |/ _` | __/ _` |  | |_) | | | |/ _` | | | '_ ` _ \
 | |_| | (_| | || (_| |  |  _ <| |_| | (_| | | | | | | | |
 |____/ \__,_|\__\__,_|  |_| \_\\__,_|\__,_|_| |_| |_| |_|
 DataBackup Companion - FTP 数据服务器  {ver}
 ----------------------------------------------------------
 原始创意 : 酷安 @喵脆角12448
 重构维护 : boluo4169-commits (酷安 @骏冲冲)
 项目地址 : {repo}
 许可     : MIT
"""


def print_banner():
    print(BANNER.format(ver=COMPANION_VERSION, repo=REPO_URL))


def check_update():
    """Background: companion 跟随 App 发布节奏（exe 附加在 App release）。
    检测 App 是否有比本构建更新的 release；有则提示去 Releases 下载最新配套工具。
    """
    if APP_BUILD_TAG == "dev":
        return
    try:
        import json
        import urllib.request
        req = urllib.request.Request(
            REPO_URL.replace("https://", "https://api.") + "/releases/latest",
            headers={"User-Agent": "DataBackupCompanion"},
        )
        with urllib.request.urlopen(req, timeout=8) as r:
            data = json.load(r)
        tag = data.get("tag_name", "")
        if tag and tag != APP_BUILD_TAG:
            print(
                "  [更新提示] 配套 DataBackup 已更新到 %s（本工具构建基于 %s），"
                "最新配套工具请到 %s/releases 下载" % (tag, APP_BUILD_TAG, REPO_URL)
            )
    except Exception:
        pass


def list_lan_ips():
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127.") or ip.startswith("169.254."):
                continue
            if ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]


def gen_password(length=8):
    alphabet = string.digits + string.ascii_letters
    return "".join(secrets.choice(alphabet) for _ in range(length))


def run_selftest(user, pw, port):
    import ftplib
    import io
    name = "__selftest__.txt"
    ftp = None
    try:
        for _ in range(10):
            try:
                ftp = ftplib.FTP()
                ftp.connect("127.0.0.1", port, timeout=5)
                ftp.login(user, pw)
                break
            except Exception:
                ftp = None
                time.sleep(0.5)
        if ftp is None:
            return False
        ftp.set_pasv(True)
        ftp.storbinary("STOR " + name, io.BytesIO(b"DataBackup selftest OK\n"))
        names = ftp.nlst()
        ftp.delete(name)
        ftp.quit()
        return name in names
    except Exception:
        return False


def pick_folder():
    """Open a native folder-picker dialog. Returns path or None."""
    try:
        import tkinter as tk
        from tkinter import filedialog
        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        path = filedialog.askdirectory(title="选择备份保存目录")
        root.destroy()
        return path or None
    except Exception:
        print("（无法打开文件夹选择窗口）")
        return None


# ---------------------------------------------------------------------------
# 诊断模式（--diagnose）：
# 一键导出「环境信息 + FTP 状态 + 备份文件清单 + 完整性检查」，与手机端
# 「导出日志」组成双向反馈材料。用户反馈问题时运行一次，把生成的 zip 发来即可。
# ---------------------------------------------------------------------------

def _human_size(num):
    """字节数转可读大小"""
    try:
        num = float(num)
    except Exception:
        return "?"
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(num) < 1024.0 or unit == "TiB":
            return "%.1f %s" % (num, unit)
        num /= 1024.0
    return "%.1f B" % num


def _port_in_use(port):
    """检查端口是否被占用（尝试绑定即知）"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("0.0.0.0", port))
        return False
    except OSError:
        return True
    finally:
        s.close()


def _collect_environment(backup_dir, port):
    """环境信息文本"""
    lines = []
    lines.append("DataBackup Companion 诊断报告")
    lines.append("生成时间: %s" % datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    lines.append("=" * 56)
    lines.append("[环境]")
    lines.append("系统: %s" % platform.platform())
    lines.append("Python: %s" % sys.version.replace("\n", " "))
    lines.append("主机名: %s" % socket.gethostname())
    lines.append("局域网 IP: %s" % (" 或 ".join(list_lan_ips())))
    lines.append("备份目录: %s" % backup_dir)
    try:
        usage = shutil.disk_usage(os.path.abspath(backup_dir) if os.path.exists(backup_dir) else ".")
        lines.append("磁盘空间: 总 %s / 剩余 %s" % (_human_size(usage.total), _human_size(usage.free)))
    except Exception as e:
        lines.append("磁盘空间: 读取失败 (%s)" % e)
    lines.append("端口 %d: %s" % (port, "已被占用（可能有其他 FTP 服务）" if _port_in_use(port) else "空闲"))
    lines.append("被动端口段: %d ~ %d" % (PASSIVE_MIN, PASSIVE_MIN + 100))
    # 防火墙当前配置文件状态（可能需管理员，失败则记录）
    try:
        out = subprocess.run(
            ["netsh", "advfirewall", "show", "currentprofile"],
            capture_output=True, text=True, timeout=10, errors="replace",
        ).stdout
        lines.append("防火墙(当前配置): %s" % " ".join(out.split()) if out.strip() else "无输出")
    except Exception as e:
        lines.append("防火墙: 查询失败 (%s)" % e)
    return "\n".join(lines) + "\n"


def _collect_ftp_status(user, port):
    """FTP 服务状态文本（密码不输出）"""
    lines = []
    lines.append("[FTP 服务]")
    lines.append("用户名: %s（密码不输出）" % (user or "databackup"))
    lines.append("监听端口: %d" % port)
    lines.append("端口状态: %s" % ("已被占用" if _port_in_use(port) else "空闲"))
    lines.append("自检: 诊断模式未启动服务，跳过（正常启动时会自动自检）")
    return "\n".join(lines) + "\n"


def _walk_entries(root):
    """遍历目录，返回 (相对路径, 是否目录, 大小) 列表，按路径排序。
    跳过历史诊断包（DataBackup_diagnose_*.zip），避免诊断包互相包含。"""
    entries = []
    if not os.path.isdir(root):
        return entries
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        rel = os.path.relpath(dirpath, root)
        for d in sorted(dirnames):
            full = os.path.join(dirpath, d)
            entries.append((os.path.join(rel, d) if rel != "." else d, True, 0))
        for f in sorted(filenames):
            if f.startswith("DataBackup_diagnose_") and f.endswith(".zip"):
                continue
            full = os.path.join(dirpath, f)
            try:
                size = os.path.getsize(full)
            except OSError:
                size = -1
            entries.append((os.path.join(rel, f) if rel != "." else f, False, size))
    return entries


def _group_stats(entries):
    """按顶级目录分组统计：返回 {group: (count_files, total_size)}"""
    stats = {}
    for rel, is_dir, size in entries:
        if is_dir:
            continue
        group = rel.split(os.sep)[0] if os.sep in rel else "(根目录)"
        cnt, total = stats.get(group, (0, 0))
        stats[group] = (cnt + 1, total + max(size, 0))
    return stats


def _collect_file_inventory(backup_dir):
    """文件清单 + 分组统计 + 0 字节文件"""
    entries = _walk_entries(backup_dir)
    lines = []
    lines.append("[备份文件清单]")
    stats = _group_stats(entries)
    for group in sorted(stats):
        cnt, total = stats[group]
        lines.append("%s/: %d 个文件, 共 %s" % (group, cnt, _human_size(total)))
    lines.append("")
    lines.append("[目录树]")
    for rel, is_dir, size in entries:
        if is_dir:
            lines.append("  [目录] %s" % rel)
        else:
            lines.append("  %s  (%s)" % (rel, _human_size(size)))
    # 0 字节文件
    zero_files = [rel for rel, is_dir, size in entries if not is_dir and size == 0]
    lines.append("")
    lines.append("[0 字节文件]")
    if zero_files:
        for f in zero_files:
            lines.append("  %s" % f)
    else:
        lines.append("  无")
    return "\n".join(lines) + "\n"


def _is_valid_json(path):
    try:
        # utf-8-sig 兼容带 BOM 的 json（部分工具写入会带 BOM）
        with open(path, "r", encoding="utf-8-sig") as fh:
            json.load(fh)
        return True
    except Exception:
        return False


def _collect_integrity(backup_dir):
    """深度完整性检查：config json 合法性 + .md5 与归档配对 + 迁移包清单"""
    lines = []
    anomalies = []
    lines.append("[完整性检查]")

    apps_root = os.path.join(backup_dir, "apps")
    if os.path.isdir(apps_root):
        app_dirs = sorted(
            d for d in os.listdir(apps_root)
            if os.path.isdir(os.path.join(apps_root, d))
        )
        lines.append("应用备份（apps/）: %d 个应用" % len(app_dirs))
        for app in app_dirs:
            app_path = os.path.join(apps_root, app)
            version_dirs = sorted(
                d for d in os.listdir(app_path)
                if os.path.isdir(os.path.join(app_path, d))
            )
            for ver in version_dirs:
                ver_path = os.path.join(app_path, ver)
                files = [f for f in os.listdir(ver_path) if os.path.isfile(os.path.join(ver_path, f))]
                config_ok = os.path.exists(os.path.join(ver_path, "package_restore_config.json"))
                archives = [f for f in files if f.endswith((".tar", ".tar.zst", ".zst"))]
                md5s = [f for f in files if f.endswith(".md5")]
                # md5 配对：每个归档应有同名 .md5；每个 .md5 应有对应归档
                archive_no_md5 = [f for f in archives if (f + ".md5") not in md5s]
                md5_no_archive = [f for f in md5s if f[: -len(".md5")] not in archives]
                zero = [f for f in files if os.path.getsize(os.path.join(ver_path, f)) == 0]
                issues = []
                if not config_ok:
                    issues.append("config 缺失")
                if archive_no_md5:
                    issues.append("归档缺 md5: %s" % ",".join(archive_no_md5))
                if md5_no_archive:
                    issues.append("md5 无对应归档: %s" % ",".join(md5_no_archive))
                if zero:
                    issues.append("0 字节: %s" % ",".join(zero))
                # config json 合法性（深度级）
                cfg_path = os.path.join(ver_path, "package_restore_config.json")
                if config_ok and not _is_valid_json(cfg_path):
                    issues.append("config 不是合法 JSON")
                if issues:
                    lines.append("  ✗ %s/%s: %s" % (app, ver, "; ".join(issues)))
                    anomalies.append("%s/%s: %s" % (app, ver, "; ".join(issues)))
                else:
                    lines.append("  ✓ %s/%s: %d 归档, md5 配对 OK" % (app, ver, len(archives)))
    else:
        lines.append("应用备份（apps/）: 目录不存在（可能从未备份应用）")

    migration_dir = os.path.join(backup_dir, "migration")
    lines.append("")
    lines.append("[云端迁移包（migration/）]")
    if os.path.isdir(migration_dir):
        pkgs = sorted(
            f for f in os.listdir(migration_dir)
            if os.path.isfile(os.path.join(migration_dir, f))
        )
        if pkgs:
            for f in pkgs:
                size = os.path.getsize(os.path.join(migration_dir, f))
                lines.append("  %s  (%s)" % (f, _human_size(size)))
        else:
            lines.append("  目录为空")
    else:
        lines.append("  目录不存在（尚未导出过云端迁移包）")

    lines.append("")
    lines.append("[异常汇总]")
    if anomalies:
        for a in anomalies:
            lines.append("  ✗ %s" % a)
    else:
        lines.append("  无")

    return "\n".join(lines) + "\n", anomalies


def _collect_summary_json(backup_dir, anomalies):
    """机器可读汇总（便于程序解析/快速核对）"""
    entries = _walk_entries(backup_dir)
    stats = _group_stats(entries)
    return {
        "generated_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "platform": platform.platform(),
        "backup_dir": backup_dir,
        "lan_ips": list_lan_ips(),
        "port": LISTEN_PORT,
        "port_in_use": _port_in_use(LISTEN_PORT),
        "groups": {k: {"files": v[0], "total_bytes": v[1]} for k, v in stats.items()},
        "anomalies": anomalies,
    }


def run_diagnose(user, backup_dir, port):
    """非交互生成诊断 zip 到备份目录，打印结果后由调用方退出"""
    print("[诊断] 正在收集环境与文件信息...")
    backup_dir = backup_dir or (r"D:\DataBackupFTP" if os.path.exists("D:\\") else r"C:\DataBackupFTP")
    os.makedirs(backup_dir, exist_ok=True)

    env_text = _collect_environment(backup_dir, port)
    ftp_text = _collect_ftp_status(user, port)
    inv_text = _collect_file_inventory(backup_dir)
    integrity_text, anomalies = _collect_integrity(backup_dir)
    summary = _collect_summary_json(backup_dir, anomalies)

    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    zip_path = os.path.join(backup_dir, "DataBackup_diagnose_%s.zip" % ts)

    try:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("environment.txt", env_text, compress_type=zipfile.ZIP_DEFLATED)
            zf.writestr("ftp_status.txt", ftp_text, compress_type=zipfile.ZIP_DEFLATED)
            zf.writestr("file_inventory.txt", inv_text, compress_type=zipfile.ZIP_DEFLATED)
            zf.writestr("integrity_check.txt", integrity_text, compress_type=zipfile.ZIP_DEFLATED)
            zf.writestr(
                "diagnose.json",
                json.dumps(summary, ensure_ascii=False, indent=2),
                compress_type=zipfile.ZIP_DEFLATED,
            )
    except Exception as e:
        print("[诊断] 写入诊断包失败: %s" % e)
        try:
            input("按回车退出...")
        except Exception:
            pass
        sys.exit(1)

    print("=" * 56)
    print("[诊断] 完成")
    print("  诊断包: %s" % zip_path)
    print("  异常数: %d" % len(anomalies))
    for a in anomalies[:20]:
        print("    ✗ %s" % a)
    if len(anomalies) > 20:
        print("    ... 共 %d 条，详见 integrity_check.txt" % len(anomalies))
    print("  请将该 zip 发给维护者，配合手机端「导出日志」使用。")
    print("=" * 56)


def _cred_path():
    return os.path.join(os.path.expanduser("~"), ".databackup_ftp_cred")


def load_saved_cred():
    """读取上次保存的 FTP 账号密码；无/损坏返回 None。"""
    try:
        with open(_cred_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict) and d.get("user") and d.get("password"):
            return d
    except Exception:
        pass
    return None


def save_cred(user, password):
    """保存 FTP 账号密码，供下次启动沿用（明文，仅限可信局域网场景）。"""
    try:
        with open(_cred_path(), "w", encoding="utf-8") as f:
            json.dump({"user": user, "password": password}, f)
    except Exception:
        pass


def main():
    print_banner()

    import threading
    threading.Thread(target=check_update, daemon=True).start()

    # Separate flags (--xxx) from positional arguments (user/password/dir)
    raw = sys.argv[1:]
    flags = [a for a in raw if a.startswith("--")]
    positional = [a for a in raw if not a.startswith("--")]
    while len(positional) < 3:
        positional.append("")

    user = positional[0].strip()
    password = positional[1]
    backup_dir = positional[2].strip()

    # 诊断模式：非交互生成诊断 zip（环境/文件清单/完整性），生成后退出，不启动 FTP 服务。
    # 用法：DataBackupFTPServer(.exe) --diagnose [--backup-dir <路径>]
    # 不带路径时使用默认备份目录；用户反馈问题时运行一次，把 zip 发给维护者
    # （配合手机端「导出日志」使用）。
    if "--diagnose" in flags:
        diag_dir = ""
        for a in raw:
            if a.startswith("--backup-dir="):
                diag_dir = a.split("=", 1)[1].strip()
        run_diagnose(user=user, backup_dir=diag_dir or backup_dir, port=LISTEN_PORT)
        sys.exit(0)

    if not user:
        user = input("请输入 FTP 用户名 [databackup]: ").strip()
    if not user:
        user = "databackup"
    backup_dir = positional[2].strip()

    # 密码: 沿用上次(如有) > 随机生成 or 手动输入
    if not password:
        saved = load_saved_cred()
        if saved is not None:
            ans = input("\n检测到上次的账号密码（%s / %s），是否继续沿用？[Y/n]: " % (saved["user"], saved["password"])).strip().lower()
            if ans in ("", "y", "yes"):
                if not user:
                    user = saved["user"]
                password = saved["password"]
                print("已沿用上次账号密码。")
        if not password:
            print("\n请选择密码方式:")
            print("  [1] 自动生成随机密码（推荐, 8 位字母数字）")
            print("  [2] 手动输入密码")
            mode = input("选择 [1]: ").strip()
            if mode == "2":
                password = input("请输入密码: ").strip()
            if not password:
                password = gen_password()
                print("已生成随机密码: %s   （连接信息卡片中也会显示）" % password)

    # 非自检/诊断模式保存凭据，下次启动可沿用（避免每次重新配置账号密码）
    if "--exit-after-selftest" not in flags and "--selftest" not in flags:
        save_cred(user, password)

    # 目录: 默认 or 文件夹选择对话框
    if not backup_dir:
        print("\n请选择备份保存位置:")
        print("  [1] 默认路径（有 D 盘用 D:\\DataBackupFTP, 否则 C:\\DataBackupFTP）")
        print("  [2] 浏览文件夹...")
        mode = input("选择 [1]: ").strip()
        if mode == "2":
            backup_dir = pick_folder() or ""
            if backup_dir:
                print("备份保存到: %s" % backup_dir)
        if not backup_dir:
            print("未选择文件夹或已取消, 使用默认路径。")
    if not backup_dir:
        backup_dir = r"D:\DataBackupFTP" if os.path.exists("D:\\") else r"C:\DataBackupFTP"

    os.makedirs(backup_dir, exist_ok=True)
    lan_ips = list_lan_ips()
    primary_ip = lan_ips[0]

    authorizer = DummyAuthorizer()
    authorizer.add_user(user, password, backup_dir, perm="elradfmw")

    handler = FTPHandler
    handler.authorizer = authorizer
    handler.masquerade_address = primary_ip
    handler.passive_ports = PASSIVE_PORTS
    handler.banner = "DataBackup Companion FTP server ready."
    # pyftpdlib 默认 300s 控制连接空闲超时：备份大应用（微信 user 等数 GB）上传期间控制连接
    # 长时间无命令，会被服务器主动断开 → 客户端报 "Software caused connection abort"，备份失败。
    # 禁用空闲超时（0 = 不超时），由客户端超时兜底。
    handler.timeout = 0

    print("=" * 64)
    print("DataBackup Companion FTP 数据服务器")
    print("=" * 64)

    try:
        server = FTPServer(("0.0.0.0", LISTEN_PORT), handler)
    except OSError as e:
        print("错误: 端口 %d 绑定失败 (%s)。可能已有 FTP 服务在运行。" % (LISTEN_PORT, e))
        print("提示: 可用环境变量 FTP_PORT / FTP_PASSIVE_MIN 换端口后重试。")
        try: input("按回车退出...")
        except Exception: pass
        sys.exit(1)

    print("请在手机 DataBackup（云备份 -> FTP）中填写:")
    print("  地址     : %s" % " 或 ".join(lan_ips))
    print("             （手机需与电脑连同一个 Wi-Fi, 通常填 192.168.x.x）")
    print("  端口     : %d" % LISTEN_PORT)
    print("  用户名   : %s" % user)
    print("  密码     : %s" % password)
    print("  远程目录 : /")
    print("  传输模式 : 被动(PASV)")
    print("-" * 64)
    print("  备份目录 : %s" % backup_dir)
    print("  安全提示 : FTP 为明文协议, 仅限可信局域网内使用,")
    print("             不要暴露到公网; 用完可直接关闭本窗口。")

    import threading
    serving = threading.Thread(target=server.serve_forever, daemon=True)
    serving.start()

    ok = run_selftest(user, password, LISTEN_PORT)
    print("  自检测试 : %s" % ("通过" if ok else "失败"))
    print("=" * 64)

    if "--exit-after-selftest" in flags:
        server.close_all()
        sys.exit(0 if ok else 2)

    try:
        serving.join()
    except KeyboardInterrupt:
        server.close_all()
        print("\n服务已停止。")


if __name__ == "__main__":
    main()
