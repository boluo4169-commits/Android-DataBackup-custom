# -*- coding: utf-8 -*-
"""DataBackup Companion — FTP Backup Server (GUI, standalone executable).

界面化的 Windows FTP 备份服务器：填账号密码 → 选目录 → 点「开启」，
手机端照连接卡片填写即可。服务核心在 ftp_core.py（GUI / bat / CI 共用一份）。

Original idea by Coolapk @喵脆角12448, refactored by boluo4169-commits (Coolapk @骏冲冲). MIT.

Usage:
    DataBackupFTPServer.exe                        启动图形界面
    DataBackupFTPServer.exe <user> <pass> <dir>    以这些值预填界面
    DataBackupFTPServer.exe --diagnose [--backup-dir=<路径>]   无界面生成诊断包
    DataBackupFTPServer.exe --exit-after-selftest <user> <pass> <dir>  无界面自检（CI 冒烟）
"""
import os
import queue
import subprocess
import sys
import threading
import webbrowser

import ftp_core

APP_TITLE = "DataBackup Companion — FTP 数据服务器 %s" % ftp_core.COMPANION_VERSION
LOG_MAX_LINES = 800

# 浅/深两套配色（零依赖：ttk 用 clam 主题 + 手工配色，tk.Text 单独设色）
PALETTES = {
    "light": {
        "bg": "#F5F4F0", "field": "#FFFFFF", "text": "#2C2C2A", "muted": "#888780",
        "border": "#D3D1C7", "btn": "#EDEBE5", "btn_active": "#E2DFD7",
        "select": "#B5D4F4", "ok": "#0F6E56", "err": "#A32D2D",
    },
    "dark": {
        "bg": "#1F1F1E", "field": "#141413", "text": "#E8E6E1", "muted": "#8A8880",
        "border": "#4A4A47", "btn": "#2A2A28", "btn_active": "#34342F",
        "select": "#0C447C", "ok": "#5DCAA5", "err": "#F09595",
    },
}


def attach_parent_console():
    """--windowed 打包下从 cmd 运行时，把输出接回父控制台，使 --diagnose 仍可见。"""
    if sys.stdout is not None:
        return
    if os.name != "nt":
        return
    try:
        import ctypes
        if ctypes.windll.kernel32.AttachConsole(-1):
            sys.stdout = open("CONOUT$", "w", encoding="utf-8", buffering=1)
            sys.stderr = sys.stdout
    except Exception:
        pass


def out(text=""):
    """无界面模式的输出（无控制台时静默丢弃，不影响退出码）"""
    try:
        if sys.stdout is not None:
            print(text)
    except Exception:
        pass


# ---------------------------------------------------------------------------
# 无界面模式（CI 冒烟 / 诊断包）
# ---------------------------------------------------------------------------

def headless_diagnose(backup_dir, port):
    try:
        zip_path, anomalies = ftp_core.create_diagnose_zip(
            user="", backup_dir=backup_dir, port=port
        )
    except Exception as e:
        out("[诊断] 写入诊断包失败: %s" % e)
        return 1
    out("=" * 56)
    out("[诊断] 完成")
    out("  诊断包: %s" % zip_path)
    out("  异常数: %d" % len(anomalies))
    for a in anomalies[:20]:
        out("    x %s" % a)
    if len(anomalies) > 20:
        out("    ... 共 %d 条，详见 integrity_check.txt" % len(anomalies))
    out("  请将该 zip 发给维护者，配合手机端「导出日志」使用。")
    out("=" * 56)
    return 0


def headless_selftest(user, password, backup_dir, port):
    """CI 冒烟：起服务 → 回环自检 → 退出（0 通过 / 2 失败 / 1 启动失败）"""
    service = ftp_core.FtpService(on_log=lambda m: out(m))
    ok, msg = service.start(user, password, backup_dir, port)
    out("  启动     : %s" % msg)
    if not ok:
        return 1
    passed = ftp_core.run_selftest(user, password, port)
    out("  自检测试 : %s" % ("通过" if passed else "失败"))
    out("=" * 64)
    service.stop()
    return 0 if passed else 2


# ---------------------------------------------------------------------------
# 图形界面
# ---------------------------------------------------------------------------

def main_gui(preset_user="", preset_password="", preset_dir=""):
    import tkinter as tk
    from tkinter import filedialog, messagebox, scrolledtext, ttk

    cfg = ftp_core.load_config()
    user0 = preset_user or cfg["user"]
    password0 = preset_password or cfg["password"]
    dir0 = preset_dir or cfg["backup_dir"]
    port0 = cfg["port"]
    theme_name = cfg.get("theme", "light")
    if theme_name not in PALETTES:
        theme_name = "light"
    dir_history = list(cfg["dir_history"])
    if dir0 not in dir_history:
        dir_history.insert(0, dir0)

    log_queue = queue.Queue()
    service = ftp_core.FtpService(
        on_log=lambda m: log_queue.put(m),
        on_conn=lambda n: log_queue.put(("CONN", n)),
    )

    root = tk.Tk()
    root.title(APP_TITLE)
    root.geometry("760x600")
    root.minsize(680, 540)

    # ---------- 顶部：状态 + 启停 ----------
    top = ttk.Frame(root)
    top.pack(fill=tk.X, padx=12, pady=(12, 6))

    status_var = tk.StringVar(value="● 未启动")
    status_label = ttk.Label(top, textvariable=status_var)
    status_label.pack(side=tk.LEFT)

    stop_button = ttk.Button(top, text="停止", width=10, state=tk.DISABLED)
    stop_button.pack(side=tk.RIGHT, padx=(8, 0))
    start_button = ttk.Button(top, text="开启", width=10)
    start_button.pack(side=tk.RIGHT)

    # ---------- 表单 ----------
    form = ttk.Frame(root)
    form.pack(fill=tk.X, padx=12, pady=6)
    form.columnconfigure(0, weight=1)
    form.columnconfigure(1, weight=1)

    def labeled(parent, text, row, col):
        ttk.Label(parent, text=text).grid(
            row=row * 2, column=col, sticky=tk.W, padx=(0, 8), pady=(4, 2)
        )
        holder = ttk.Frame(parent)
        holder.grid(row=row * 2 + 1, column=col, sticky=tk.EW, padx=(0, 8), pady=(0, 6))
        return holder

    user_holder = labeled(form, "用户名", 0, 0)
    user_var = tk.StringVar(value=user0)
    ttk.Entry(user_holder, textvariable=user_var).pack(fill=tk.X)

    port_holder = labeled(form, "监听端口", 0, 1)
    port_var = tk.StringVar(value=str(port0))
    ttk.Entry(port_holder, textvariable=port_var).pack(fill=tk.X)

    pass_holder = labeled(form, "密码（手机端需填写此密码）", 1, 0)
    pass_row = ttk.Frame(pass_holder)
    pass_row.pack(fill=tk.X)
    pass_var = tk.StringVar(value=password0)

    def toggle_password():
        entry.configure(show="" if entry.cget("show") else "*")

    def random_password():
        pass_var.set(ftp_core.gen_password())
        entry.configure(show="")

    ttk.Button(pass_row, text="随机", width=6, command=random_password).pack(side=tk.RIGHT)
    ttk.Button(pass_row, text="显示", width=6, command=toggle_password).pack(side=tk.RIGHT, padx=(0, 4))
    entry = ttk.Entry(pass_row, textvariable=pass_var, show="*")
    entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))

    dir_holder = labeled(form, "备份保存目录（备份直接落在电脑硬盘）", 1, 1)
    dir_row = ttk.Frame(dir_holder)
    dir_row.pack(fill=tk.X)
    dir_var = tk.StringVar(value=dir0)
    dir_box = ttk.Combobox(dir_row, textvariable=dir_var, values=tuple(dir_history))
    dir_box.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))

    def pick_dir():
        path = filedialog.askdirectory(title="选择备份保存目录", initialdir=dir_var.get() or None)
        if path:
            dir_var.set(os.path.normpath(path))

    ttk.Button(dir_row, text="浏览", width=6, command=pick_dir).pack(side=tk.RIGHT)

    # ---------- 连接信息卡片 ----------
    card_box = ttk.LabelFrame(root, text="手机端照此填写（DataBackup → 云备份 → FTP）")
    card_box.pack(fill=tk.X, padx=12, pady=(6, 0))

    card_text = tk.Text(card_box, height=6, wrap=tk.NONE, font=("Consolas", 9))
    card_text.pack(fill=tk.X, padx=8, pady=(4, 4))
    card_text.configure(state=tk.DISABLED)

    card_buttons = ttk.Frame(card_box)
    card_buttons.pack(fill=tk.X, padx=8, pady=(0, 6))

    def refresh_card():
        service.user = user_var.get().strip()
        service.password = pass_var.get()
        try:
            service.port = int(port_var.get().strip())
        except ValueError:
            service.port = ftp_core.DEFAULT_PORT
        if not service.lan_ips:
            service.lan_ips = ftp_core.list_lan_ips()
        card_text.configure(state=tk.NORMAL)
        card_text.delete("1.0", tk.END)
        card_text.insert(tk.END, service.connection_card_text())
        card_text.configure(state=tk.DISABLED)

    def copy_card():
        text = card_text.get("1.0", tk.END).strip()
        if not text:
            return
        root.clipboard_clear()
        root.clipboard_append(text)
        log("[界面] 连接信息已复制到剪贴板")

    ttk.Button(card_buttons, text="复制全部", command=copy_card).pack(side=tk.LEFT)
    ttk.Button(card_buttons, text="刷新地址", command=refresh_card).pack(side=tk.LEFT, padx=(6, 0))

    # ---------- 日志 ----------
    log_box = ttk.LabelFrame(root, text="运行日志")
    log_box.pack(fill=tk.BOTH, expand=True, padx=12, pady=(8, 0))
    log_widget = scrolledtext.ScrolledText(log_box, height=10, wrap=tk.WORD, font=("Consolas", 9))
    log_widget.pack(fill=tk.BOTH, expand=True, padx=8, pady=6)
    log_widget.configure(state=tk.DISABLED)

    def log(text):
        """界面线程内写日志"""
        if not text:
            return
        log_widget.configure(state=tk.NORMAL)
        log_widget.insert(tk.END, text + "\n")
        # 超长时裁掉最早的行，避免长时间运行内存膨胀
        line_count = int(log_widget.index("end-1c").split(".")[0])
        if line_count > LOG_MAX_LINES:
            log_widget.delete("1.0", "%d.0" % (line_count - LOG_MAX_LINES))
        log_widget.see(tk.END)
        log_widget.configure(state=tk.DISABLED)

    # ---------- 底部 ----------
    bottom = ttk.Frame(root)
    bottom.pack(fill=tk.X, padx=12, pady=10)

    def open_backup_dir():
        path = dir_var.get().strip()
        if not path or not os.path.isdir(path):
            messagebox.showwarning("提示", "目录不存在：%s" % path)
            return
        try:
            os.startfile(path)  # type: ignore[attr-defined]
        except Exception as e:
            messagebox.showerror("打开失败", str(e))

    def make_diagnose():
        path = dir_var.get().strip() or ftp_core.default_backup_dir()
        log("[诊断] 正在收集环境与文件信息...")

        def work():
            try:
                zip_path, anomalies = ftp_core.create_diagnose_zip(
                    user=user_var.get().strip(), backup_dir=path, port=_current_port()
                )
                log_queue.put("[诊断] 诊断包已生成: %s（异常 %d 条）" % (zip_path, len(anomalies)))
                for a in anomalies[:10]:
                    log_queue.put("  x %s" % a)
            except Exception as e:
                log_queue.put("[诊断] 生成失败: %s" % e)

        threading.Thread(target=work, daemon=True).start()

    ttk.Button(bottom, text="生成诊断包", command=make_diagnose).pack(side=tk.LEFT)
    ttk.Button(bottom, text="打开备份目录", command=open_backup_dir).pack(side=tk.LEFT, padx=(8, 0))
    ttk.Button(
        bottom,
        text="项目主页",
        command=lambda: webbrowser.open(ftp_core.REPO_URL),
    ).pack(side=tk.LEFT, padx=(8, 0))

    is_dark_var = tk.BooleanVar(value=(theme_name == "dark"))
    theme_check = ttk.Checkbutton(bottom, text="深色模式", variable=is_dark_var)
    theme_check.pack(side=tk.LEFT, padx=(12, 0))

    ttk.Label(
        bottom,
        text="FTP 为明文协议，仅限可信局域网内使用",
    ).pack(side=tk.RIGHT)

    # ---------- 主题（零依赖：clam 主题 + 手工配色） ----------
    def apply_theme(name):
        """切换浅色/深色。ttk 走 clam 主题逐项配色，tk.Text 与经典滚动条单独设色。"""
        pal = PALETTES.get(name, PALETTES["light"])
        style = ttk.Style(root)
        try:
            style.theme_use("clam")  # vista/winnative 不允许自定义大部分颜色
        except tk.TclError:
            pass

        style.configure(
            ".", background=pal["bg"], foreground=pal["text"],
            fieldbackground=pal["field"], bordercolor=pal["border"],
            lightcolor=pal["bg"], darkcolor=pal["bg"], troughcolor=pal["field"],
            focuscolor=pal["select"],
        )
        style.configure("TFrame", background=pal["bg"])
        style.configure("TLabelframe", background=pal["bg"], bordercolor=pal["border"])
        style.configure("TLabelframe.Label", background=pal["bg"], foreground=pal["muted"])
        style.configure("TLabel", background=pal["bg"], foreground=pal["text"])
        style.configure(
            "TButton", background=pal["btn"], foreground=pal["text"],
            bordercolor=pal["border"], arrowcolor=pal["text"],
        )
        style.map(
            "TButton",
            background=[("active", pal["btn_active"]), ("disabled", pal["bg"])],
            foreground=[("disabled", pal["muted"])],
        )
        style.configure(
            "TCheckbutton", background=pal["bg"], foreground=pal["text"],
            indicatorcolor=pal["field"],
        )
        style.map("TCheckbutton", background=[("active", pal["bg"])])
        style.configure(
            "TEntry", fieldbackground=pal["field"], foreground=pal["text"],
            insertcolor=pal["text"], bordercolor=pal["border"],
        )
        style.configure(
            "TCombobox", fieldbackground=pal["field"], background=pal["btn"],
            foreground=pal["text"], arrowcolor=pal["text"],
            bordercolor=pal["border"],
        )
        style.map(
            "TCombobox",
            fieldbackground=[("readonly", pal["field"])],
            foreground=[("readonly", pal["text"])],
        )
        # 下拉列表是经典 Listbox，只能通过 option 数据库改色
        root.option_add("*TCombobox*Listbox.background", pal["field"])
        root.option_add("*TCombobox*Listbox.foreground", pal["text"])
        root.option_add("*TCombobox*Listbox.selectBackground", pal["select"])
        root.option_add("*TCombobox*Listbox.selectForeground", pal["text"])

        root.configure(bg=pal["bg"])
        for text_widget in (card_text, log_widget):
            text_widget.configure(
                bg=pal["field"], fg=pal["text"], insertbackground=pal["text"],
                selectbackground=pal["select"], highlightbackground=pal["border"],
            )
            # scrolledtext 自带的是经典 tk.Scrollbar（tk.Text 的兄弟控件）
            for sibling in text_widget.master.winfo_children():
                if sibling.winfo_class() == "Scrollbar":
                    sibling.configure(
                        bg=pal["btn"], troughcolor=pal["bg"],
                        activebackground=pal["btn_active"],
                        highlightbackground=pal["bg"],
                    )

        # 状态文本颜色随主题（文字本身由 set_running 控制）
        set_running(service.is_running)
        return pal

    def on_theme_toggle():
        nonlocal theme_name
        theme_name = "dark" if is_dark_var.get() else "light"
        apply_theme(theme_name)
        log("[界面] 已切换到%s模式" % ("深色" if theme_name == "dark" else "浅色"))
        try:
            ftp_core.save_config(
                user_var.get().strip(), pass_var.get(), _current_port(),
                dir_var.get().strip(), list(dir_box.cget("values")), theme_name,
            )
        except Exception:
            pass

    # ---------- 状态机 ----------
    def _current_port():
        try:
            return int(port_var.get().strip())
        except ValueError:
            return ftp_core.DEFAULT_PORT

    def set_running(running):
        def set_enabled(holders, enabled):
            """递归启用/禁用表单控件（holder 里还套了一层 Frame，只改直接子节点会漏）"""
            state = tk.NORMAL if enabled else tk.DISABLED
            stack = list(holders)
            while stack:
                widget = stack.pop()
                for child in widget.winfo_children():
                    stack.append(child)
                    try:
                        child.configure(state=state)
                    except tk.TclError:
                        pass

        if running:
            status_var.set("● 服务运行中")
            status_label.configure(foreground=PALETTES[theme_name]["ok"])
            start_button.configure(state=tk.DISABLED)
            stop_button.configure(state=tk.NORMAL)
            set_enabled((user_holder, pass_holder, port_holder, dir_holder), False)
        else:
            status_var.set("● 未启动")
            status_label.configure(foreground=PALETTES[theme_name]["err"])
            start_button.configure(state=tk.NORMAL)
            stop_button.configure(state=tk.DISABLED)
            set_enabled((user_holder, pass_holder, port_holder, dir_holder), True)
            # 密码框恢复掩码显示
            entry.configure(show="*")

    def remember_dir(path):
        try:
            history = [d for d in (dir_box.cget("values") or []) if d and d != path]
        except Exception:
            history = []
        history.insert(0, path)
        dir_box.configure(values=tuple(history[:10]))

    def start_service():
        user = user_var.get().strip()
        password = pass_var.get()
        path = dir_var.get().strip()
        port = _current_port()

        ok, msg = service.start(user, password, path, port)
        log("[服务] %s" % msg)
        if not ok:
            messagebox.showerror("无法启动", msg)
            return

        remember_dir(path)
        ftp_core.save_config(user, password, port, path, list(dir_box.cget("values")), theme_name)
        refresh_card()
        set_running(True)

        # 启动后自动回环自检（避免「看起来启动了但连不上」）
        def selftest():
            passed = ftp_core.run_selftest(user, password, port)
            log_queue.put("[自检] %s" % ("通过（写入/列出/删除均正常）" if passed else "失败，请检查防火墙或杀毒软件"))
            if not passed:
                log_queue.put("[自检] 手机端若连不上：确认同一局域网、放行 python/本程序 的入站规则")

        threading.Thread(target=selftest, daemon=True).start()

    def stop_service():
        service.stop()
        set_running(False)

    start_button.configure(command=start_service)
    stop_button.configure(command=stop_service)

    # ---------- 日志泵：服务线程 → 界面线程 ----------
    def pump_log_queue():
        drained = 0
        while drained < 200:
            try:
                item = log_queue.get_nowait()
            except queue.Empty:
                break
            drained += 1
            if isinstance(item, tuple) and item[0] == "CONN":
                count = item[1]
                extra = "（%d 个连接）" % count if service.is_running else ""
                status_var.set("● 服务运行中%s" % (extra if count else ""))
                continue
            log(item)
        root.after(150, pump_log_queue)

    def on_close():
        if service.is_running and not messagebox.askyesno(
            "退出", "FTP 服务正在运行，退出将停止服务。确定退出吗？"
        ):
            return
        service.stop()
        if not service.is_running:
            # 退出前保存当前界面上的配置（含目录历史）
            try:
                ftp_core.save_config(
                    user_var.get().strip(),
                    pass_var.get(),
                    _current_port(),
                    dir_var.get().strip(),
                    list(dir_box.cget("values")),
                    theme_name,
                )
            except Exception:
                pass
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)

    # ---------- 初始状态 ----------
    if not ftp_core.dependency_ok():
        start_button.configure(state=tk.DISABLED)
        log("[依赖] 未安装 pyftpdlib，请执行:  pip install pyftpdlib  （exe 版已内置，无需安装）")
    if ftp_core.port_in_use(_current_port()):
        log("[提示] 端口 %d 已被占用，可能是其它 FTP 服务正在运行" % _current_port())
    refresh_card()
    pump_log_queue()
    theme_check.configure(command=on_theme_toggle)
    apply_theme(theme_name)

    def check_update_async():
        """后台检查配套 App 是否有新版本（exe 随 App release 发布）"""
        latest, newer = ftp_core.check_update()
        if newer:
            log_queue.put(
                "[更新] 配套 DataBackup 已更新到 %s（本工具构建基于 %s），"
                "最新配套工具请到项目 Releases 下载" % (latest, ftp_core.app_build_tag())
            )

    threading.Thread(target=check_update_async, daemon=True).start()

    if preset_user or preset_password or preset_dir:
        log("[界面] 已按命令行参数预填账号密码与目录")
    log("[界面] 填好账号密码与目录后点「开启」；手机端按上方卡片填写")

    root.mainloop()
    return 0


# ---------------------------------------------------------------------------

def main():
    attach_parent_console()

    raw = sys.argv[1:]
    flags = [a for a in raw if a.startswith("--")]
    positional = [a for a in raw if not a.startswith("--")]
    while len(positional) < 3:
        positional.append("")

    user = positional[0].strip()
    password = positional[1]
    backup_dir = positional[2].strip()

    if "--diagnose" in flags:
        diag_dir = ""
        for a in raw:
            if a.startswith("--backup-dir="):
                diag_dir = a.split("=", 1)[1].strip()
        sys.exit(headless_diagnose(diag_dir or backup_dir, ftp_core.DEFAULT_PORT))

    if "--exit-after-selftest" in flags:
        if not user:
            user = ftp_core.DEFAULT_USER
        if not password:
            password = ftp_core.gen_password()
        if not backup_dir:
            backup_dir = ftp_core.default_backup_dir()
        sys.exit(headless_selftest(user, password, backup_dir, ftp_core.DEFAULT_PORT))

    if not ftp_core.dependency_ok():
        # GUI 也要能打开（让用户看到明确提示），但缺依赖时 pip 提示更直接
        try:
            import tkinter  # noqa: F401
        except ImportError:
            out("缺少依赖: 请执行  pip install pyftpdlib")
            sys.exit(1)

    try:
        sys.exit(main_gui(user, password, backup_dir))
    except Exception as e:
        # GUI 起不来（无桌面环境等）时退回无界面自检，便于排查
        out("[错误] 图形界面启动失败: %s" % e)
        sys.exit(1)


if __name__ == "__main__":
    main()
