# -*- coding: utf-8 -*-
"""DataBackup Companion — FTP Backup Server (standalone executable).

Original idea by Coolapk @喵脆角12448, refactored by boluo4169-commits. MIT.

Usage:
    DataBackupFTPServer.exe [username] [password] [backup_dir]

Leave password empty to auto-generate a random one.
"""
import os
import secrets
import socket
import string
import sys
import time

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

LISTEN_PORT = 2121
PASSIVE_PORTS = range(60000, 60101)


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


def main():
    # Separate flags (--xxx) from positional arguments (user/password/dir)
    raw = sys.argv[1:]
    flags = [a for a in raw if a.startswith("--")]
    positional = [a for a in raw if not a.startswith("--")]
    while len(positional) < 3:
        positional.append("")

    user = positional[0].strip() or "databackup"
    password = positional[1]
    backup_dir = positional[2].strip()

    if not password:
        password = gen_password()
        print("已为您自动生成密码: %s   （连接信息卡片中也会显示）" % password)
    if not backup_dir:
        backup_dir = r"D:\DataBackupFTP" if os.path.exists("D:\\") else r"C:\DataBackupFTP"
        print("备份保存路径: %s" % backup_dir)

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

    print("=" * 64)
    print("DataBackup Companion FTP 数据服务器")
    print("=" * 64)

    try:
        server = FTPServer(("0.0.0.0", LISTEN_PORT), handler)
    except OSError as e:
        print("错误: 端口 %d 绑定失败 (%s)。可能已有 FTP 服务在运行。" % (LISTEN_PORT, e))
        input("按回车退出...")
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
