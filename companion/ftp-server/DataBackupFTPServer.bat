@echo off
chcp 65001 >nul
setlocal EnableExtensions
title DataBackup Companion - FTP Backup Server v1.0

REM ============================================================
REM  DataBackup Companion - FTP 数据服务器 (Windows 一键部署)
REM
REM  原始创意与初版脚本: 酷安 @喵脆角12448
REM    https://www.coolapk.com/feed/73346386
REM  重构维护: boluo4169-commits (DataBackup 定制版)
REM    https://github.com/boluo4169-commits/Android-DataBackup-custom
REM  许可: MIT
REM
REM  功能:
REM    1. 自动请求管理员权限(UAC)并配置防火墙放行
REM    2. 创建 FTP 用户与备份根目录(默认 D:\DataBackupFTP)
REM    3. 缺少 Python / pyftpdlib 时自动安装(清华镜像回退官方源)
REM    4. 密码留空时自动生成 8 位随机强密码
REM    5. 列出本机所有局域网 IP, 手机端照抄连接信息卡片即可
REM    6. 启动后自动做一次本机上传自检
REM
REM  用法:
REM    直接双击运行, 按提示输入(全部可直接回车用默认值)
REM    命令行: 本脚本.bat <用户名> <密码> <备份目录>
REM ============================================================

REM ---------- request admin rights (firewall rules need them) ----------
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo 正在请求管理员权限...
    powershell -NoProfile -Command "$a=@(); if('%~1' -ne ''){$a+='%~1'}; if('%~2' -ne ''){$a+='%~2'}; if('%~3' -ne ''){$a+='%~3'}; Start-Process -FilePath '%~f0' -ArgumentList $a -Verb RunAs"
    exit /b
)

echo.
echo     ****************************************************
echo     *          DataBackup Companion                    *
echo     *          FTP 数据服务器  v1.0                     *
echo     *                                                  *
echo     *   原始创意: 酷安 @喵脆角12448                     *
echo     *   重构维护: boluo4169-commits                     *
echo     ****************************************************
echo.

REM ---------- custom username / password / backup folder ----------
set "FTP_USER=%~1"
set "FTP_PASS=%~2"
set "FTP_DIR=%~3"

if "%FTP_USER%"=="" set /p "FTP_USER=请输入 FTP 用户名 [databackup]: "
if "%FTP_USER%"=="" set "FTP_USER=databackup"

set "PASS_IS_RANDOM=0"
if "%FTP_PASS%"=="" set /p "FTP_PASS=请输入 FTP 密码 [留空自动生成随机密码]: "
if "%FTP_PASS%"=="" (
    for /f %%i in ('powershell -NoProfile -Command "-join((48..57)+(65..90)+(97..122) | Get-Random -Count 8 | ForEach-Object {[char]$_})"') do set "FTP_PASS=%%i"
    set "PASS_IS_RANDOM=1"
)

if "%FTP_DIR%"=="" set /p "FTP_DIR=请设置备份保存路径 [D:\DataBackupFTP]: "
if "%FTP_DIR%"=="" (
    if exist "D:\" (
        set "FTP_DIR=D:\DataBackupFTP"
    ) else (
        echo [提示] 未检测到 D 盘, 改为 C:\DataBackupFTP
        set "FTP_DIR=C:\DataBackupFTP"
    )
)
if not exist "%FTP_DIR%" mkdir "%FTP_DIR%" 2>nul
if not exist "%FTP_DIR%" (
    echo [错误] 无法创建备份目录: %FTP_DIR%
    pause
    exit /b 1
)
echo 备份目录: %FTP_DIR%

REM ---------- port 2121 pre-check ----------
netstat -ano | findstr ":2121" >nul 2>&1
if %errorlevel% equ 0 (
    echo [警告] 端口 2121 可能已被占用, 若启动失败请关闭占用程序或修改端口。
)

REM ---------- locate Python, install if missing ----------
set "PYEXE="
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PYEXE=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PYEXE if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "PYEXE=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
if not defined PYEXE if exist "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" set "PYEXE=%LOCALAPPDATA%\Programs\Python\Python311\python.exe"
if not defined PYEXE (where python >nul 2>&1 && set "PYEXE=python")
if not defined PYEXE (where py >nul 2>&1 && set "PYEXE=py -3")
if defined PYEXE (%PYEXE% -c "import sys" >nul 2>&1 && goto :python_ok)
set "PYEXE="
echo 未检测到 Python, 正在自动安装 Python 3.12, 请稍候...
winget install --id Python.Python.3.12 -e --silent --scope user --accept-package-agreements --accept-source-agreements --disable-interactivity
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PYEXE=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PYEXE (where python >nul 2>&1 && set "PYEXE=python")
if defined PYEXE (%PYEXE% -c "import sys" >nul 2>&1 && goto :python_ok)
echo [错误] Python 仍然不可用, 请手动安装后重新运行脚本:
echo         winget install --id Python.Python.3.12 -e
pause
exit /b 1
:python_ok
echo Python 路径: %PYEXE%

REM ---------- ensure pyftpdlib is installed ----------
%PYEXE% -c "import pyftpdlib" >nul 2>&1
if errorlevel 1 (
    echo 正在使用国内镜像源安装 pyftpdlib...
    %PYEXE% -m pip install pyftpdlib -i https://pypi.tuna.tsinghua.edu.cn/simple --disable-pip-version-check -q
)
%PYEXE% -c "import pyftpdlib" >nul 2>&1
if errorlevel 1 (
    echo 国内镜像失败, 尝试官方 PyPI 源...
    %PYEXE% -m pip install pyftpdlib --disable-pip-version-check -q
)
%PYEXE% -c "import pyftpdlib" >nul 2>&1
if errorlevel 1 (
    echo 尝试用户目录安装...
    %PYEXE% -m pip install pyftpdlib --user -i https://pypi.tuna.tsinghua.edu.cn/simple --disable-pip-version-check -q
)
%PYEXE% -c "import pyftpdlib" >nul 2>&1
if errorlevel 1 (
    echo [错误] pyftpdlib 安装失败, 请手动执行:
    echo         %PYEXE% -m pip install pyftpdlib -i https://pypi.tuna.tsinghua.edu.cn/simple
    pause
    exit /b 1
)
echo pyftpdlib: OK

REM ---------- firewall rules (2121 + passive 60000-60100) ----------
netsh advfirewall firewall show rule name="DataBackup FTP 2121" >nul 2>&1
if errorlevel 1 netsh advfirewall firewall add rule name="DataBackup FTP 2121" dir=in action=allow protocol=TCP localport=2121
netsh advfirewall firewall show rule name="DataBackup FTP Passive 60000-60100" >nul 2>&1
if errorlevel 1 netsh advfirewall firewall add rule name="DataBackup FTP Passive 60000-60100" dir=in action=allow protocol=TCP localport=60000-60100
echo 防火墙规则: 已就绪

REM ---------- extract the embedded python script ----------
set "WKDIR=%TEMP%\DataBackupFTP_server"
if not exist "%WKDIR%" mkdir "%WKDIR%"
powershell -NoProfile -Command "$l = Get-Content -LiteralPath '%~f0'; $m = ($l | Select-String '^###PYCODE###$').LineNumber; if(-not $m){ Write-Error 'PYCODE marker not found'; exit 1 }; [IO.File]::WriteAllLines('%WKDIR%\ftp_server.py', $l[$m..($l.Count-1)])"
if errorlevel 1 (
    echo [错误] 内嵌服务端代码提取失败
    pause
    exit /b 1
)

REM ---------- security notice ----------
echo.
echo **********************************************************************
echo   注意: FTP 为明文协议, 请仅在可信的家庭/办公局域网内使用,
echo         不要将端口暴露到公网。传输完成后可直接关闭本窗口。
echo **********************************************************************

REM ---------- start the FTP server ----------
echo.
echo 正在启动 FTP 服务器, 请保持本窗口开启(关闭窗口即停止服务)...
set "PYTHONIOENCODING=utf-8"
%PYEXE% -X utf8 -u "%WKDIR%\ftp_server.py" --selftest

echo.
echo 服务已停止, 可以关闭本窗口
pause
exit /b

###PYCODE###
# -*- coding: utf-8 -*-
"""DataBackup Companion FTP server (extracted by the launcher).

Credentials and paths come from environment variables set by the launcher:
  FTP_USER, FTP_PASS, FTP_DIR, FTP_PORT (optional, default 2121)
Run with --selftest to perform a local upload test after startup.

Original idea by Coolapk @喵脆角12448, refactored by boluo4169-commits. MIT.
"""
import os
import socket
import sys
import time

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

FTP_USER = os.environ.get("FTP_USER", "databackup")
FTP_PASS = os.environ.get("FTP_PASS", "databackup")
BACKUP_DIR = os.environ.get("FTP_DIR", r"D:\DataBackupFTP")
LISTEN_IP = "0.0.0.0"
LISTEN_PORT = int(os.environ.get("FTP_PORT", "2121"))
PASSIVE_PORTS = range(60000, 60101)


def list_lan_ips():
    """Return all usable IPv4 addresses (loopback/link-local removed)."""
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
    lan_ips = list_lan_ips()
    primary_ip = lan_ips[0]
    os.makedirs(BACKUP_DIR, exist_ok=True)

    authorizer = DummyAuthorizer()
    authorizer.add_user(FTP_USER, FTP_PASS, BACKUP_DIR, perm="elradfmw")

    handler = FTPHandler
    handler.authorizer = authorizer
    handler.masquerade_address = primary_ip
    handler.passive_ports = PASSIVE_PORTS
    handler.banner = "DataBackup Companion FTP server ready."

    try:
        server = FTPServer((LISTEN_IP, LISTEN_PORT), handler)
    except OSError as e:
        print("错误: 端口 %d 绑定失败 (%s)。" % (LISTEN_PORT, e))
        print("可能已经有一个 FTP 服务在运行(端口被占用)。")
        sys.exit(1)

    print("=" * 64)
    print("DataBackup FTP 数据服务器已启动")
    print("  监听地址 : %s:%d" % (LISTEN_IP, LISTEN_PORT))
    print("  备份目录 : %s" % BACKUP_DIR)
    print("  被动模式 : 端口 %d-%d, 伪装 IP %s"
          % (PASSIVE_PORTS[0], PASSIVE_PORTS[-1], primary_ip))
    if primary_ip == "127.0.0.1":
        print("  [警告] 未能检测到局域网 IP, 其他设备可能无法使用被动模式。")
    print("-" * 64)
    print("请在手机 DataBackup(云备份 -> FTP)中填写以下信息:")
    print("  地址     : %s" % " 或 ".join(lan_ips))
    print("             (手机需与电脑连同一个 Wi-Fi, 通常填 192.168.x.x)")
    print("  端口     : %d" % LISTEN_PORT)
    print("  用户名   : %s" % FTP_USER)
    print("  密码     : %s" % FTP_PASS)
    print("  远程目录 : /   (FTP 根目录 = %s)" % BACKUP_DIR)
    print("  传输模式 : 被动(PASV)")
    print("-" * 64)

    import threading
    serving = threading.Thread(target=server.serve_forever, daemon=True)
    serving.start()

    if "--selftest" in sys.argv[1:]:
        ok = run_selftest(FTP_USER, FTP_PASS, LISTEN_PORT)
        print("  自检测试 : %s" % ("通过" if ok else "失败"))

    print("  停止服务 : 关闭本窗口或按 Ctrl+C")
    print("=" * 64)

    try:
        serving.join()
    except KeyboardInterrupt:
        server.close_all()
        print("\n服务已停止。")


if __name__ == "__main__":
    main()
