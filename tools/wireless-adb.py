#!/usr/bin/env python3
"""无线 adb 自动配对/连接（macOS，无需人工抄端口）。

Android 11+ 的无线调试：
  · 「IP 地址和端口」显示的是**连接端口**（固定到你这次开关为止）；
  · 点「使用配对码配对设备」弹出的对话框里那个端口是**配对端口**，随对话框出现/消失，
    而且只要锁屏或关掉对话框就一起作废。
  · 唯一必须人读的是那 6 位配对码。

所以这个脚本做三件事：
  1) 用 mDNS 找到手机的 IP 与连接端口（不用问你）；
  2) 并发扫 30000-61000 找**新出现的**开放端口，逐个试 `adb pair`（配对端口就在里面）；
  3) 配对成功后 `adb connect`，把设备打印出来。

用法：
    python3 tools/wireless-adb.py --code 123456
    python3 tools/wireless-adb.py --code 123456 --ip 192.168.66.65
    python3 tools/wireless-adb.py --connect-only        # 已配对过，只连接
"""

import argparse
import concurrent.futures
import re
import shutil
import socket
import subprocess
import sys
import time

ADB = shutil.which("adb") or "adb"
PORT_MIN, PORT_MAX = 30000, 61001


def run(cmd, timeout=20):
    """跑命令；超时也把**已经产生**的输出带回来（dns-sd 是常驻进程，只能靠超时截断）。"""
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        out = exc.stdout or ""
        err = exc.stderr or ""
        if isinstance(out, bytes):
            out = out.decode("utf-8", "replace")
        if isinstance(err, bytes):
            err = err.decode("utf-8", "replace")
        return subprocess.CompletedProcess(cmd, 0, out, err)
    except Exception as exc:  # noqa: BLE001
        return subprocess.CompletedProcess(cmd, 1, "", str(exc))


def mdns_connect_services():
    """返回 [(ip 或主机名, port)]，来自 _adb-tls-connect._tcp。"""
    out = run(["dns-sd", "-B", "_adb-tls-connect._tcp", "local"], timeout=6)
    # dns-sd 是常驻进程，超时被杀；已捕获到的行仍在 stdout
    names = set(re.findall(r"_adb-tls-connect\._tcp\.\s+(\S+)$", out.stdout, re.M))
    result = []
    for name in names:
        *_, tail = name
        inst = name.split(".")[0] if "." in name else name
        lookup = run(["dns-sd", "-L", inst, "_adb-tls-connect._tcp", "local"], timeout=6)
        m = re.search(r"can be reached at (\S+?):(\d+)", lookup.stdout)
        if m:
            result.append((m.group(1).rstrip("."), int(m.group(2))))
    return result


def local_ip_to(ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((ip, 9))
        return s.getsockname()[0]
    finally:
        s.close()


def open_port(ip, port, timeout=0.25):
    s = socket.socket()
    s.settimeout(timeout)
    try:
        return s.connect_ex((ip, port)) == 0
    except Exception:  # noqa: BLE001
        return False
    finally:
        s.close()


def scan_ports(ip, skip, workers=400):
    """并发扫端口，返回开放端口列表（排除已知连接端口）。"""
    ports = [p for p in range(PORT_MIN, PORT_MAX) if p not in skip]
    found = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(open_port, ip, p): p for p in ports}
        for fut in concurrent.futures.as_completed(futures):
            if fut.result():
                found.append(futures[fut])
    return sorted(found)


def connect(ip, port):
    out = run([ADB, "connect", f"{ip}:{port}"], timeout=15).stdout.strip()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--code", help="手机弹窗里的 6 位配对码")
    ap.add_argument("--ip", help="手机 IP（不给就用 mDNS 自动找）")
    ap.add_argument("--port", type=int, help="连接端口（不给就用 mDNS 自动找）")
    ap.add_argument("--connect-only", action="store_true")
    args = ap.parse_args()

    ip, port = args.ip, args.port
    if not ip or not port:
        services = mdns_connect_services()
        if not services:
            print("✗ mDNS 里没看到 _adb-tls-connect 服务：确认手机和 Mac 在同一个 Wi-Fi、"
                  "无线调试开着，且页面停留超过 2 秒")
            return 2
        ip, port = services[0]
        print(f"· mDNS 找到手机 {ip}:{port}")

    if open_port(ip, port):
        print(f"· 连接端口 {port} 可达")
    else:
        print(f"✗ 连接端口 {port} 不可达（手机换网/关了无线调试？）")
        return 2

    if args.connect_only:
        print(connect(ip, port))
        return 0

    if not args.code:
        print("✗ 需要 6 位配对码：python3 tools/wireless-adb.py --code 123456")
        return 2

    print(f"· 扫描 {PORT_MIN}-{PORT_MAX}，找配对端口（约 20-40 秒）…")
    candidates = scan_ports(ip, skip={port, 5555})
    print(f"· 开放端口 {len(candidates)} 个：{candidates[:12]}")

    for cand in candidates:
        out = run([ADB, "pair", f"{ip}:{cand}", args.code], timeout=15).stdout.strip()
        if "Successfully paired" in out:
            print(f"✓ 配对成功（配对端口 {cand}）")
            print(connect(ip, port))
            run([ADB, "devices", "-l"], timeout=10)
            return 0
    print("✗ 所有候选端口都没配对成功：多半是配对码/对话框已经过期（锁屏会换码）。"
          "重新打开「使用配对码配对设备」再跑一次。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
