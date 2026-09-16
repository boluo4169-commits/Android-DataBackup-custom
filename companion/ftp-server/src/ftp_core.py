# -*- coding: utf-8 -*-
"""DataBackup Companion — FTP 服务核心（无 UI 依赖）。

GUI 入口（DataBackupFTPServer.py）与 bat 内嵌副本、CI 无头冒烟共用本模块，
保证「逻辑只有一份」。本文件不含任何 print/input，输出一律通过回调或返回值交给调用方。

Original idea by Coolapk @喵脆角12448, refactored by boluo4169-commits (Coolapk @骏冲冲). MIT.
"""
import datetime
import json
import logging
import os
import platform
import secrets
import shutil
import socket
import string
import subprocess
import sys
import threading
import time
import zipfile

try:
    from pyftpdlib.authorizers import DummyAuthorizer
    from pyftpdlib.handlers import FTPHandler
    from pyftpdlib.servers import FTPServer
except ImportError:  # 由调用方决定如何提示（GUI 弹窗 / 控制台打印）
    DummyAuthorizer = FTPHandler = FTPServer = None


# companion 自身的版本号（独立于 App；更新时手动 bump 这里）
COMPANION_VERSION = "v2.0"
REPO_URL = "https://github.com/boluo4169-commits/Android-DataBackup-custom"

DEFAULT_PORT = int(os.environ.get("FTP_PORT", "2121"))
PASSIVE_MIN = int(os.environ.get("FTP_PASSIVE_MIN", "60000"))
PASSIVE_COUNT = 101
PASSIVE_PORTS = range(PASSIVE_MIN, PASSIVE_MIN + PASSIVE_COUNT)
DEFAULT_USER = "databackup"
FTP_PERM = "elradfmw"  # elr 读 + adfmw 写（无 M/T：不允许改权限与文件时间）

CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".databackup_ftp_config.json")
LEGACY_CRED_PATH = os.path.join(os.path.expanduser("~"), ".databackup_ftp_cred")


def dependency_ok():
    """pyftpdlib 是否可用"""
    return FTPServer is not None


def app_build_tag():
    """构建所对应的 App release tag（CI 注入 _tool_version.py；bat/源码直跑时为 dev）"""
    try:
        from _tool_version import APP_BUILD_TAG
        return APP_BUILD_TAG
    except Exception:
        return "dev"


def check_update():
    """查 GitHub 最新 release。返回 (最新 tag 或 None, 是否比本构建更新)。

    companion 跟随 App 发布节奏（exe 附加在 App release），所以这里比的是 App tag。
    """
    tag = app_build_tag()
    if tag == "dev":
        return None, False
    try:
        import urllib.request
        req = urllib.request.Request(
            REPO_URL.replace("https://", "https://api.") + "/releases/latest",
            headers={"User-Agent": "DataBackupCompanion"},
        )
        with urllib.request.urlopen(req, timeout=8) as r:
            latest = json.load(r).get("tag_name", "")
        return latest, bool(latest and latest != tag)
    except Exception:
        return None, False


def default_backup_dir():
    """默认备份目录：有 D 盘用 D:\\DataBackupFTP，否则 C:\\DataBackupFTP"""
    return r"D:\DataBackupFTP" if os.path.exists("D:\\") else r"C:\DataBackupFTP"


def gen_password(length=8):
    """生成随机密码（与旧版行为一致：大小写字母 + 数字）"""
    alphabet = string.digits + string.ascii_letters
    return "".join(secrets.choice(alphabet) for _ in range(length))


def primary_lan_ip():
    """默认路由出口 IP —— 手机要填的通常就是它。

    UDP connect 不会真的发包，只是让内核选好出接口，从而拿到本机在该路由上的地址。
    """
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        return ""


def list_lan_ips():
    """本机 IPv4 候选地址。

    **默认路由出口 IP 排第一位**（手机优先填它），其余的（VMware VMnet、Clash TUN
    等虚拟网卡）排在后面。旧版只是按 getaddrinfo 顺序排，导致虚拟网卡经常排在首位。
    """
    ips = []
    primary = primary_lan_ip()
    if primary and not primary.startswith("127.") and not primary.startswith("169.254."):
        ips.append(primary)
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


def port_in_use(port):
    """检查端口是否被占用（尝试绑定即知）"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("0.0.0.0", port))
        return False
    except OSError:
        return True
    finally:
        s.close()


# ---------------------------------------------------------------------------
# 配置持久化：单个 json（旧版明文 cred 文件自动迁移）
# ---------------------------------------------------------------------------

DEFAULT_CONFIG = {
    "user": DEFAULT_USER,
    "password": "",
    "port": DEFAULT_PORT,
    "backup_dir": "",
    "dir_history": [],
}


def load_config():
    """读取配置；无配置时回退旧版 cred 文件的账号密码。返回 dict（字段已归一化）。"""
    cfg = dict(DEFAULT_CONFIG)
    cfg["dir_history"] = []
    try:
        if os.path.exists(CONFIG_PATH):
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                if isinstance(data.get("user"), str):
                    cfg["user"] = data["user"]
                if isinstance(data.get("password"), str):
                    cfg["password"] = data["password"]
                if isinstance(data.get("port"), int) and 1 <= data["port"] <= 65535:
                    cfg["port"] = data["port"]
                if isinstance(data.get("backup_dir"), str):
                    cfg["backup_dir"] = data["backup_dir"]
                hist = data.get("dir_history")
                if isinstance(hist, list):
                    cfg["dir_history"] = [d for d in hist if isinstance(d, str) and d]
        else:
            # 旧版 cred 文件（仅账号密码）迁移，首次保存后即写入新配置文件
            if os.path.exists(LEGACY_CRED_PATH):
                with open(LEGACY_CRED_PATH, "r", encoding="utf-8") as f:
                    old = json.load(f)
                if isinstance(old, dict) and old.get("user") and old.get("password"):
                    cfg["user"] = old["user"]
                    cfg["password"] = old["password"]
    except Exception:
        pass
    if not cfg["user"]:
        cfg["user"] = DEFAULT_USER
    if not cfg["backup_dir"]:
        cfg["backup_dir"] = default_backup_dir()
    return cfg


def save_config(user, password, port, backup_dir, dir_history=None):
    """保存配置（明文，仅限可信局域网场景；与旧版 cred 文件同口径）"""
    data = {
        "user": user,
        "password": password,
        "port": int(port),
        "backup_dir": backup_dir,
        "dir_history": list(dir_history or [])[:10],
    }
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------------
# FTP 服务：启停封装（GUI 的「开启 / 停止」按钮直接调它）
# ---------------------------------------------------------------------------

class FtpService:
    """FTP 服务生命周期封装。

    on_log(text)   : 运行日志（连接、命令、传输），由调用方决定写到哪
    on_conn(delta) : 在线连接数变化（+1 / -1），失败不影响服务
    """

    def __init__(self, on_log=None, on_conn=None):
        self._on_log = on_log
        self._on_conn = on_conn
        self._server = None
        self._thread = None
        self.user = ""
        self.password = ""
        self.backup_dir = ""
        self.port = DEFAULT_PORT
        self.lan_ips = []
        self.connections = 0
        if on_log is not None:
            self._silence_library_logger()

    @staticmethod
    def _silence_library_logger():
        """日志统一走 on_log 回调，关掉 pyftpdlib 自带的 stderr 输出，避免重复刷屏。

        要点：pyftpdlib 的 is_logging_configured() 只看「logger 有没有 handler」，
        没有就自己挂一个 StreamHandler 到 stderr。所以这里挂一个 NullHandler 顶替，
        否则 FTPServer 构造时会重配 logger（level 拉回 INFO）并重新开始打印。
        """
        try:
            lib_logger = logging.getLogger("pyftpdlib")
            lib_logger.handlers.clear()
            lib_logger.addHandler(logging.NullHandler())
            lib_logger.propagate = False
            lib_logger.setLevel(logging.CRITICAL)
        except Exception:
            pass

    # -- 内部 ---------------------------------------------------------------
    def _log(self, msg):
        if self._on_log is not None:
            try:
                self._on_log(str(msg).rstrip())
            except Exception:
                pass

    def _make_handler(self):
        """动态生成 handler 子类：日志外抛 + 连接计数，其余照抄原参数"""
        service = self

        def _prefix(handler):
            """与 pyftpdlib 默认 log_prefix 对齐的前缀：用户名@IP"""
            ip = getattr(handler, "remote_ip", "") or ""
            name = getattr(handler, "username", "") or "-"
            return ("%s@%s" % (name, ip)) if ip else ""

        class CompanionFTPHandler(FTPHandler):
            # pyftpdlib 2.2.0 签名: log(msg, logfun=logger.info) / logerror(msg)
            # 覆盖 log 即覆盖「登录成功 + 传输完成 + 白名单命令(DELE/RMD/CWD/MKD…)」；
            # logline 是 DEBUG 开关控制的逐条命令流，不覆盖（日志区没必要刷屏）。
            def log(self, msg, logfun=None):
                service._log("%s %s" % (_prefix(self), msg))

            def logerror(self, msg):
                service._log("%s [错误] %s" % (_prefix(self), msg))

            def on_connect(self):
                service.connections += 1
                service._log("客户端已连接: %s" % self.remote_ip)
                if service._on_conn is not None:
                    try:
                        service._on_conn(service.connections)
                    except Exception:
                        pass

            def on_disconnect(self):
                service.connections = max(0, service.connections - 1)
                service._log("客户端已断开: %s" % self.remote_ip)
                if service._on_conn is not None:
                    try:
                        service._on_conn(service.connections)
                    except Exception:
                        pass

        authorizer = DummyAuthorizer()
        authorizer.add_user(
            self.user, self.password, self.backup_dir, perm=FTP_PERM
        )
        CompanionFTPHandler.authorizer = authorizer
        # 不要设 masquerade_address！pyftpdlib 默认用「客户端连进来的那个本机地址」
        # （dispatchers.py: local_ip = cmd_channel.socket.getsockname()[0]）作为 PASV 地址，
        # 多网卡机器上这才是对的。旧版硬设成 lan_ips[0]（虚拟机网卡常排首位）→ 手机填
        # 真实网卡地址连进来后，PASV 阶段被指向虚拟网卡地址，传输必失败。
        CompanionFTPHandler.masquerade_address = None
        CompanionFTPHandler.passive_ports = PASSIVE_PORTS
        CompanionFTPHandler.banner = "DataBackup Companion FTP server ready."
        # pyftpdlib 默认 300s 控制连接空闲超时：备份大应用（微信 user 等数 GB）上传期间
        # 控制连接长时间无命令，会被服务器主动断开 → 客户端报 "Software caused connection abort"。
        # 禁用空闲超时（0 = 不超时），由客户端超时兜底。
        CompanionFTPHandler.timeout = 0
        return CompanionFTPHandler

    # -- 对外 ---------------------------------------------------------------
    @property
    def is_running(self):
        return self._server is not None

    def start(self, user, password, backup_dir, port):
        """启动服务。返回 (是否成功, 提示文本)；异常一律转成文本，不抛出。"""
        if self.is_running:
            return False, "服务已在运行"
        if not dependency_ok():
            return False, "缺少依赖 pyftpdlib，请先执行  pip install pyftpdlib"
        if not user:
            return False, "用户名不能为空"
        if not password:
            return False, "密码不能为空（可点「随机」生成）"
        if not backup_dir:
            return False, "请先选择备份保存目录"
        if not (isinstance(port, int) and 1 <= port <= 65535):
            return False, "端口需为 1 ~ 65535"
        if not os.path.isdir(backup_dir):
            try:
                os.makedirs(backup_dir, exist_ok=True)
            except Exception as e:
                return False, "目录不存在且无法创建: %s (%s)" % (backup_dir, e)
        if port_in_use(port):
            return False, "端口 %d 已被占用（可能已有 FTP 服务在运行）" % port

        self.user = user
        self.password = password
        self.backup_dir = backup_dir
        self.port = port
        self.lan_ips = list_lan_ips()
        self.connections = 0

        try:
            handler = self._make_handler()
            server = FTPServer(("0.0.0.0", port), handler)
        except Exception as e:
            self._server = None
            return False, "启动失败: %s" % e

        if self._on_log is not None:
            self._silence_library_logger()  # 兜底再压一次（构造过程可能重配过 logger）

        self._server = server
        self._thread = threading.Thread(target=server.serve_forever, daemon=True)
        self._thread.start()
        self._log("服务已启动，监听 0.0.0.0:%d，根目录 %s" % (port, backup_dir))
        return True, "服务已启动"

    def stop(self, wait=True):
        """停止服务。返回是否确实执行了停止。"""
        if not self.is_running:
            return False
        try:
            self._server.close_all()
        except Exception as e:
            self._log("停止服务时出现异常: %s" % e)
        if wait and self._thread is not None:
            try:
                self._thread.join(timeout=5)
            except Exception:
                pass
        self._server = None
        self._thread = None
        self.connections = 0
        self._log("服务已停止")
        return True

    def connection_card(self):
        """手机端填写用的连接信息（列表形式，每行一项）"""
        ips = self.lan_ips or list_lan_ips()
        primary = ips[0]
        others = ips[1:]
        rows = [("地址", "%s（推荐）" % primary)]
        if others:
            rows.append(
                ("备用地址", " 或 ".join(others) + "（多半是虚拟网卡，手机连不上）")
            )
        rows += [
            ("端口", str(self.port)),
            ("用户名", self.user),
            ("密码", self.password),
            ("远程目录", "/"),
            ("传输模式", "被动(PASV)"),
        ]
        return rows

    def connection_card_text(self):
        return "\n".join("%s: %s" % (k, v) for k, v in self.connection_card())


def run_selftest(user, password, port):
    """本地回环自检：连接 → 登录 → 上传 → 列表 → 删除。成功返回 True。"""
    import ftplib
    import io

    name = "__selftest__.txt"
    ftp = None
    try:
        for _ in range(10):
            try:
                ftp = ftplib.FTP()
                ftp.connect("127.0.0.1", port, timeout=5)
                ftp.login(user, password)
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


# ---------------------------------------------------------------------------
# 诊断模式（--diagnose）：
# 一键导出「环境信息 + FTP 状态 + 备份文件清单 + 完整性检查」，与手机端
# 「导出日志」组成双向反馈材料。用户反馈问题时运行一次，把生成的 zip 发来即可。
# ---------------------------------------------------------------------------

def human_size(num):
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


def collect_environment(backup_dir, port):
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
        lines.append("磁盘空间: 总 %s / 剩余 %s" % (human_size(usage.total), human_size(usage.free)))
    except Exception as e:
        lines.append("磁盘空间: 读取失败 (%s)" % e)
    lines.append("端口 %d: %s" % (port, "已被占用（可能有其他 FTP 服务）" if port_in_use(port) else "空闲"))
    lines.append("被动端口段: %d ~ %d" % (PASSIVE_MIN, PASSIVE_MIN + PASSIVE_COUNT - 1))
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


def collect_ftp_status(user, port):
    """FTP 服务状态文本（密码不输出）"""
    lines = []
    lines.append("[FTP 服务]")
    lines.append("用户名: %s（密码不输出）" % (user or DEFAULT_USER))
    lines.append("监听端口: %d" % port)
    lines.append("端口状态: %s" % ("已被占用" if port_in_use(port) else "空闲"))
    lines.append("自检: 诊断模式未启动服务，跳过（正常启动时会自动自检）")
    return "\n".join(lines) + "\n"


def walk_entries(root):
    """遍历目录，返回 (相对路径, 是否目录, 大小) 列表，按路径排序。
    跳过历史诊断包（DataBackup_diagnose_*.zip），避免诊断包互相包含。"""
    entries = []
    if not os.path.isdir(root):
        return entries
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        rel = os.path.relpath(dirpath, root)
        for d in sorted(dirnames):
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


def group_stats(entries):
    """按顶级目录分组统计：返回 {group: (count_files, total_size)}"""
    stats = {}
    for rel, is_dir, size in entries:
        if is_dir:
            continue
        group = rel.split(os.sep)[0] if os.sep in rel else "(根目录)"
        cnt, total = stats.get(group, (0, 0))
        stats[group] = (cnt + 1, total + max(size, 0))
    return stats


def collect_file_inventory(backup_dir):
    """文件清单 + 分组统计 + 0 字节文件"""
    entries = walk_entries(backup_dir)
    lines = []
    lines.append("[备份文件清单]")
    stats = group_stats(entries)
    for group in sorted(stats):
        cnt, total = stats[group]
        lines.append("%s/: %d 个文件, 共 %s" % (group, cnt, human_size(total)))
    lines.append("")
    lines.append("[目录树]")
    for rel, is_dir, size in entries:
        if is_dir:
            lines.append("  [目录] %s" % rel)
        else:
            lines.append("  %s  (%s)" % (rel, human_size(size)))
    zero_files = [rel for rel, is_dir, size in entries if not is_dir and size == 0]
    lines.append("")
    lines.append("[0 字节文件]")
    if zero_files:
        for f in zero_files:
            lines.append("  %s" % f)
    else:
        lines.append("  无")
    return "\n".join(lines) + "\n"


def is_valid_json(path):
    try:
        # utf-8-sig 兼容带 BOM 的 json（部分工具写入会带 BOM）
        with open(path, "r", encoding="utf-8-sig") as fh:
            json.load(fh)
        return True
    except Exception:
        return False


def collect_integrity(backup_dir):
    """深度完整性检查：config json 合法性 + .md5 与归档配对 + 迁移包清单。
    返回 (文本, 异常列表)"""
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
                cfg_path = os.path.join(ver_path, "package_restore_config.json")
                if config_ok and not is_valid_json(cfg_path):
                    issues.append("config 不是合法 JSON")
                if issues:
                    lines.append("  x %s/%s: %s" % (app, ver, "; ".join(issues)))
                    anomalies.append("%s/%s: %s" % (app, ver, "; ".join(issues)))
                else:
                    lines.append("  - %s/%s: %d 归档, md5 配对 OK" % (app, ver, len(archives)))
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
                lines.append("  %s  (%s)" % (f, human_size(size)))
        else:
            lines.append("  目录为空")
    else:
        lines.append("  目录不存在（尚未导出过云端迁移包）")

    lines.append("")
    lines.append("[异常汇总]")
    if anomalies:
        for a in anomalies:
            lines.append("  x %s" % a)
    else:
        lines.append("  无")

    return "\n".join(lines) + "\n", anomalies


def collect_summary_json(backup_dir, anomalies, port):
    """机器可读汇总（便于程序解析/快速核对）"""
    entries = walk_entries(backup_dir)
    stats = group_stats(entries)
    return {
        "generated_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "platform": platform.platform(),
        "backup_dir": backup_dir,
        "lan_ips": list_lan_ips(),
        "port": port,
        "port_in_use": port_in_use(port),
        "groups": {k: {"files": v[0], "total_bytes": v[1]} for k, v in stats.items()},
        "anomalies": anomalies,
    }


def create_diagnose_zip(user, backup_dir, port):
    """生成诊断 zip，返回 (zip 路径, 异常条数)；失败抛异常由调用方处理。
    注意：诊断包不再打印任何东西——GUI 与控制台各自决定怎么展示。"""
    backup_dir = backup_dir or default_backup_dir()
    os.makedirs(backup_dir, exist_ok=True)

    env_text = collect_environment(backup_dir, port)
    ftp_text = collect_ftp_status(user, port)
    inv_text = collect_file_inventory(backup_dir)
    integrity_text, anomalies = collect_integrity(backup_dir)
    summary = collect_summary_json(backup_dir, anomalies, port)

    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    zip_path = os.path.join(backup_dir, "DataBackup_diagnose_%s.zip" % ts)

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
    return zip_path, anomalies
