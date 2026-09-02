@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
set "SCRIPT_VER=1.2"
title DataBackup Companion - FTP Backup Server v1.2

REM ============================================================
REM  DataBackup Companion - FTP Backup Server (Windows)
REM
REM  Original idea by Coolapk user @miaocuijiao12448
REM    https://www.coolapk.com/feed/73346386
REM  Maintained by boluo4169-commits (DataBackup custom)
REM    https://github.com/boluo4169-commits/Android-DataBackup-custom
REM  License: MIT
REM
REM  Features:
REM    1. Auto request admin rights (UAC) and configure firewall
REM    2. Create FTP user and backup root dir (default D:\DataBackupFTP)
REM    3. Auto install Python / pyftpdlib if missing (mirror fallback)
REM    4. Auto generate 8-char random password if left empty
REM    5. List all LAN IPs for the connection card
REM    6. Run a local upload selftest after startup
REM    7. Diagnose mode: --diagnose exports environment/file/integrity report
REM
REM  Usage:
REM    Double-click to run with interactive prompts (Enter = defaults)
REM    CLI: this.bat <username> <password> <backup_dir>
REM    Diagnose: this.bat --diagnose [backup_dir]
REM ============================================================

REM ---------- request admin rights (firewall rules need them) ----------
REM Diagnose mode: skip UAC (admin not needed for --diagnose)
if /i "%~1"=="--diagnose" goto :admin_ok
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo 正在请求管理员权限...
    powershell -NoProfile -Command "$a=@(); if('%~1' -ne ''){$a+='%~1'}; if('%~2' -ne ''){$a+='%~2'}; if('%~3' -ne ''){$a+='%~3'}; Start-Process -FilePath '%~f0' -ArgumentList $a -Verb RunAs"
    exit /b
)
:admin_ok

echo.
echo    ____        _           ____                _
echo   ^|  _ \  __ _^| ^|_ __ _   ^|  _ \ _   _  __ _^| ^|  _ __ ___
echo  ^| ^| ^| ^|^/ _` ^| __/ _` ^|  ^| ^|_) ^| ^| ^| ^|^/ _` ^| ^| ^| '_ ` _ \
echo  ^| ^|_^| ^| (_^| ^| ^|^| (_^| ^|  ^|  _ ^<^| ^|_^| ^| (_^| ^| ^| ^| ^| ^| ^| ^| ^|
echo  ^|____/ \__,_^\__\__,_^|  ^|_^| \_\\__,_^\__,_^|_^| ^|_^| ^|_^| ^|_^|
echo   DataBackup Companion - FTP 数据服务器  v%SCRIPT_VER%
echo   ------------------------------------------------------
echo   原始创意 : 酷安 @喵脆角12448
echo   重构维护 : boluo4169-commits (酷安 @骏冲冲)
echo   项目地址 : https://github.com/boluo4169-commits/Android-DataBackup-custom
echo   许可     : MIT
echo.

REM ---------- custom username ----------
REM Diagnose: skip interactive prompts (usage: this.bat --diagnose [backup_dir])
if /i "%~1"=="--diagnose" goto :diag_no_interactive
set "FTP_USER=%~1"
if "%FTP_USER%"=="" set /p "FTP_USER=请输入 FTP 用户名 [databackup]: "
if "%FTP_USER%"=="" set "FTP_USER=databackup"

REM ---------- password: reuse last > random or manual ----------
set "FTP_PASS=%~2"
set "PASS_MODE=1"

REM ---------- reuse last credentials (if any) ----------
if "%FTP_PASS%"=="" (
    set "SAVED_CRED="
    for /f "usebackq delims=" %%u in (`powershell -NoProfile -Command "$p=\"$env:USERPROFILE\.databackup_ftp_cred\"; if(Test-Path $p){try{$d=Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json; Write-Output ($d.user + '|' + $d.password)}catch{}}"`) do set "SAVED_CRED=%%u"
    if defined SAVED_CRED (
        for /f "tokens=1,2 delims=|" %%a in ("!SAVED_CRED!") do set "SAVED_USER=%%a" & set "SAVED_PASS=%%b"
        echo.
        echo 检测到上次的账号密码（!SAVED_USER! / !SAVED_PASS!），是否继续沿用？[Y/n]
        set "REUSE="
        set /p "REUSE=: "
        if /i not "!REUSE!"=="n" (
            if "%FTP_USER%"=="" set "FTP_USER=!SAVED_USER!"
            set "FTP_PASS=!SAVED_PASS!"
            echo 已沿用上次账号密码。
        )
    )
)

if "%FTP_PASS%"=="" (
    echo.
    echo 请选择密码方式:
    echo   [1] 自动生成随机密码（推荐, 8 位字母数字）
    echo   [2] 手动输入密码
    set /p "PASS_MODE=选择 [1]: "
)
if "%FTP_PASS%"=="" if "%PASS_MODE%"=="2" set /p "FTP_PASS=请输入密码: "
if "%FTP_PASS%"=="" (
    for /f %%i in ('powershell -NoProfile -Command "-join((48..57)+(65..90)+(97..122) | Get-Random -Count 8 | ForEach-Object {[char]$_})"') do set "FTP_PASS=%%i"
    echo 已生成随机密码: %FTP_PASS%   （连接信息卡片中也会显示）
)
if "%FTP_PASS%"=="" (
    for /f %%i in ('powershell -NoProfile -Command "-join((48..57)+(65..90)+(97..122) | Get-Random -Count 8 | ForEach-Object {[char]$_})"') do set "FTP_PASS=%%i"
    echo 未输入有效密码, 已改用随机密码: %FTP_PASS%
)

REM ---------- backup folder: default or folder picker ----------
set "FTP_DIR=%~3"
set "DIR_MODE=1"
if "%FTP_DIR%"=="" (
    echo.
    echo 请选择备份保存位置:
    echo   [1] 默认路径（有 D 盘用 D:\DataBackupFTP, 否则 C:\DataBackupFTP）
    echo   [2] 浏览文件夹...
    set /p "DIR_MODE=选择 [1]: "
)
if "%FTP_DIR%"=="" if "%DIR_MODE%"=="2" for /f "delims=" %%i in ('powershell -STA -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; $f=New-Object System.Windows.Forms.FolderBrowserDialog; $f.Description='选择备份保存目录'; if($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK){$f.SelectedPath}"') do set "FTP_DIR=%%i"
if "%FTP_DIR%"=="" echo 未选择文件夹或已取消, 使用默认路径。
if "%FTP_DIR%"=="" if exist "D:\" set "FTP_DIR=D:\DataBackupFTP"
if "%FTP_DIR%"=="" set "FTP_DIR=C:\DataBackupFTP"
if not exist "%FTP_DIR%" mkdir "%FTP_DIR%" 2>nul
if not exist "%FTP_DIR%" (
    echo [错误] 无法创建备份目录: %FTP_DIR%
    pause
    exit /b 1
)
echo 备份目录: %FTP_DIR%

REM ---------- save credentials for next launch (reuse) ----------
powershell -NoProfile -Command "$p=\"$env:USERPROFILE\.databackup_ftp_cred\"; try{@{user='%FTP_USER%';password='%FTP_PASS%'} | ConvertTo-Json | Set-Content $p -Encoding UTF8}catch{}"

REM ---------- diagnose mode: skip interactive prompts ----------
:diag_no_interactive
if /i "%~1"=="--diagnose" (
    set "FTP_DIR=%~2"
    if "%FTP_DIR%"=="" if exist "D:\" set "FTP_DIR=D:\DataBackupFTP"
    if "%FTP_DIR%"=="" set "FTP_DIR=C:\DataBackupFTP"
    set "FTP_USER=%~3"
    if "%FTP_USER%"=="" set "FTP_USER=databackup"
    set "FTP_PASS=diagnose"
    echo [诊断模式] 备份目录: %FTP_DIR%
)

REM ---------- check latest release ----------
REM Checked after interactive prompts (no startup delay); keep on one line (no block-wrap)
if /i "%~1"=="--diagnose" goto :skip_update_check
set "LATEST_TAG="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "try { (Invoke-RestMethod -Uri 'https://api.github.com/repos/boluo4169-commits/Android-DataBackup-custom/releases/latest' -TimeoutSec 5).tag_name } catch {}"`) do if not "%%i"=="" set "LATEST_TAG=%%i"
if defined LATEST_TAG echo   最新版本   : %LATEST_TAG%   （如有更新请到上方 Releases 页下载）
:skip_update_check

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

REM ---------- firewall rules (2121 + passive 60000-60100); diagnose mode skips ----------
if /i "%~1"=="--diagnose" (
    echo 诊断模式: 跳过防火墙配置
) else (
    netsh advfirewall firewall show rule name="DataBackup FTP 2121" >nul 2>&1
    if errorlevel 1 netsh advfirewall firewall add rule name="DataBackup FTP 2121" dir=in action=allow protocol=TCP localport=2121
    netsh advfirewall firewall show rule name="DataBackup FTP Passive 60000-60100" >nul 2>&1
    if errorlevel 1 netsh advfirewall firewall add rule name="DataBackup FTP Passive 60000-60100" dir=in action=allow protocol=TCP localport=60000-60100
    echo 防火墙规则: 已就绪
)

REM ---------- extract the embedded python script ----------
set "WKDIR=%TEMP%\DataBackupFTP_server"
if not exist "%WKDIR%" mkdir "%WKDIR%"
powershell -NoProfile -Command "$l = Get-Content -LiteralPath '%~f0' -Encoding UTF8; $m = ($l | Select-String '^###PYCODE###$').LineNumber; if(-not $m){ Write-Error 'PYCODE marker not found'; exit 1 }; [IO.File]::WriteAllLines('%WKDIR%\ftp_server.py', $l[$m..($l.Count-1)])"
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

REM ---------- start the FTP server (--diagnose generates a diagnose zip and exits) ----------
echo.
echo 正在启动 FTP 服务器, 请保持本窗口开启(关闭窗口即停止服务)...
set "PYTHONIOENCODING=utf-8"
%PYEXE% -X utf8 -u "%WKDIR%\ftp_server.py" --selftest %*

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
import datetime
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
import zipfile

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


# ---------------------------------------------------------------------------
# 诊断模式（--diagnose）：一键导出环境/文件清单/完整性，配合手机端「导出日志」
# ---------------------------------------------------------------------------

def _human_size(num):
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
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("0.0.0.0", port))
        return False
    except OSError:
        return True
    finally:
        s.close()


def _collect_environment(backup_dir, port):
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
    lines.append("被动端口段: %d ~ %d" % (PASSIVE_PORTS[0], PASSIVE_PORTS[-1]))
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
    lines = []
    lines.append("[FTP 服务]")
    lines.append("用户名: %s（密码不输出）" % (user or "databackup"))
    lines.append("监听端口: %d" % port)
    lines.append("端口状态: %s" % ("已被占用" if _port_in_use(port) else "空闲"))
    lines.append("自检: 诊断模式未启动服务，跳过（正常启动时会自动自检）")
    return "\n".join(lines) + "\n"


def _walk_entries(root):
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


def _group_stats(entries):
    stats = {}
    for rel, is_dir, size in entries:
        if is_dir:
            continue
        group = rel.split(os.sep)[0] if os.sep in rel else "(根目录)"
        cnt, total = stats.get(group, (0, 0))
        stats[group] = (cnt + 1, total + max(size, 0))
    return stats


def _collect_file_inventory(backup_dir):
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
        with open(path, "r", encoding="utf-8-sig") as fh:
            json.load(fh)
        return True
    except Exception:
        return False


def _collect_integrity(backup_dir):
    lines = []
    anomalies = []
    lines.append("[完整性检查]")

    apps_root = os.path.join(backup_dir, "apps")
    if os.path.isdir(apps_root):
        app_dirs = sorted(d for d in os.listdir(apps_root) if os.path.isdir(os.path.join(apps_root, d)))
        lines.append("应用备份（apps/）: %d 个应用" % len(app_dirs))
        for app in app_dirs:
            app_path = os.path.join(apps_root, app)
            version_dirs = sorted(d for d in os.listdir(app_path) if os.path.isdir(os.path.join(app_path, d)))
            for ver in version_dirs:
                ver_path = os.path.join(app_path, ver)
                files = [f for f in os.listdir(ver_path) if os.path.isfile(os.path.join(ver_path, f))]
                config_ok = os.path.exists(os.path.join(ver_path, "package_restore_config.json"))
                archives = [f for f in files if f.endswith((".tar", ".tar.zst", ".zst"))]
                md5s = [f for f in files if f.endswith(".md5")]
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
        pkgs = sorted(f for f in os.listdir(migration_dir) if os.path.isfile(os.path.join(migration_dir, f)))
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


def run_diagnose():
    """非交互生成诊断 zip 到备份目录（FTP_DIR），完成后退出"""
    print("[诊断] 正在收集环境与文件信息...")
    backup_dir = BACKUP_DIR
    os.makedirs(backup_dir, exist_ok=True)

    env_text = _collect_environment(backup_dir, LISTEN_PORT)
    ftp_text = _collect_ftp_status(FTP_USER, LISTEN_PORT)
    inv_text = _collect_file_inventory(backup_dir)
    integrity_text, anomalies = _collect_integrity(backup_dir)
    summary = _collect_summary_json(backup_dir, anomalies)

    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    zip_path = os.path.join(backup_dir, "DataBackup_diagnose_%s.zip" % ts)
    try:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("environment.txt", env_text)
            zf.writestr("ftp_status.txt", ftp_text)
            zf.writestr("file_inventory.txt", inv_text)
            zf.writestr("integrity_check.txt", integrity_text)
            zf.writestr("diagnose.json", json.dumps(summary, ensure_ascii=False, indent=2))
    except Exception as e:
        print("[诊断] 写入诊断包失败: %s" % e)
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


def main():
    if "--diagnose" in sys.argv[1:]:
        run_diagnose()
        sys.exit(0)
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
    # pyftpdlib 默认 300s 控制连接空闲超时：大文件上传期间控制连接无命令会被服务器断开
    # （客户端报 Software caused connection abort）→ 禁用空闲超时，由客户端超时兜底。
    handler.timeout = 0

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
