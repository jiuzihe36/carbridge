#!/usr/bin/env python3
"""最小假 adbd（只用于验证 carbridge 内置 adb 客户端）。

实现 CNXN / AUTH / OPEN / WRTE / CLSE，把 shell 命令按预设回放。
不校验签名（测试用），但会要求客户端先走一次 AUTH。
"""
import socket
import struct
import sys
import threading

A_CNXN = 0x4E584E43
A_AUTH = 0x48545541
A_OPEN = 0x4E45504F
A_OKAY = 0x59414B4F
A_WRTE = 0x45545257
A_CLSE = 0x45534C43

PROP = "persist.sys.wirelesscarplay.address"
STRAY = False


def pkt(cmd, a0, a1, data=b""):
    return struct.pack("<6I", cmd, a0, a1, len(data), sum(data) & 0xFFFFFFFF,
                       cmd ^ 0xFFFFFFFF) + data


def read_pkt(conn):
    head = b""
    while len(head) < 24:
        b = conn.recv(24 - len(head))
        if not b:
            return None
        head += b
    cmd, a0, a1, ln, ck, magic = struct.unpack("<6I", head)
    data = b""
    while len(data) < ln:
        b = conn.recv(ln - len(data))
        if not b:
            return None
        data += b
    return cmd, a0, a1, data


def handle(conn, addr):
    print(f"[fake-adbd] connect {addr}", flush=True)
    # 1) 等 CNXN
    p = read_pkt(conn)
    if not p:
        return
    print("[fake-adbd] recv", hex(p[0]), p[3][:40], flush=True)
    # 2) 发 AUTH token
    conn.sendall(pkt(A_AUTH, 1, 0, b"\x11" * 20))
    p = read_pkt(conn)
    print("[fake-adbd] auth reply", hex(p[0]), p[1], len(p[3]), flush=True)
    # 3) 认证通过
    conn.sendall(pkt(A_CNXN, 0x01000001, 4096, b"device::fake-car\x00"))

    my_id = 100
    prop_value = ""
    while True:
        p = read_pkt(conn)
        if not p:
            break
        cmd, a0, a1, data = p
        if cmd == A_OPEN:
            my_id += 1
            cmdline = data.decode("utf-8", "replace").split("\x00")[0]
            shell = cmdline[6:] if cmdline.startswith("shell:") else cmdline
            print(f"[fake-adbd] shell: {shell}", flush=True)
            if shell.startswith("cat > "):
                conn.sendall(pkt(A_OKAY, my_id, a0))
                buf = b""
                while True:
                    q = read_pkt(conn)
                    if not q:
                        break
                    qc, qa0, qa1, qd = q
                    if qc == A_WRTE:
                        buf += qd
                        conn.sendall(pkt(A_OKAY, my_id, a0))
                        continue
                    if qc == A_CLSE:
                        break
                print(f"[fake-adbd] got file {len(buf)} bytes", flush=True)
                conn.sendall(pkt(A_CLSE, my_id, a0))
                continue
            out = run_shell(shell, prop_value)
            if STRAY:
                # 模拟真车（Android 9 adbd）的串台：上一条流的迟到 WRTE+CLSE 混进本流。
                # 不看流 id 的客户端会把 "STALE" 当成本命令的输出（真车实测错位就是这样）。
                conn.sendall(pkt(A_WRTE, my_id - 1, a0 - 1, b"STALE-PREV-STREAM\r\n"))
                conn.sendall(pkt(A_CLSE, my_id - 1, a0 - 1))
            # 模拟「车机不让 shell 写这条属性」：不落地，读回仍是空 → 应转 helper 路线
            conn.sendall(pkt(A_OKAY, my_id, a0))
            body = out.encode()
            for i in range(0, len(body), 2048) or [0]:
                chunk = body[i:i + 2048]
                conn.sendall(pkt(A_WRTE, my_id, a0, chunk))
                # 等客户端 ACK（可能先收到它的 OKAY）
                read_pkt(conn)
            conn.sendall(pkt(A_CLSE, my_id, a0))
        elif cmd == A_OKAY:
            continue
        elif cmd == A_CLSE:
            continue
    conn.close()


def run_shell(shell, prop_value):
    if shell == "getprop ro.product.model":
        return "FakeCar-CTL\r\n"
    if shell == "getprop ro.build.display.id":
        return "FAKE_17.0.1/demo\r\n"
    if shell == "getprop " + PROP:
        return (prop_value or "") + "\r\n"
    if shell.startswith("setprop " + PROP):
        return "failed to set property '" + PROP + "' to '" + shell.split(" ", 2)[2].strip() + "'\r\n"
    if "logcat" in shell:
        return ("11-22 23:00:00.000  1000  1000 I CommonUtil: isSupportCarplay():false\r\n"
                "11-22 23:00:05.000  1000  1000 I WirelessCarplayManager: connectWirelessCarplay: iPhone\r\n")
    if shell.startswith("ls -l "):
        return "-rw-rw---- 1 shell shell 12763 2026-11-23 09:00 " + shell.split(" ", 2)[2] + "\r\n"
    if shell.startswith("pm install"):
        return "Success\r\n"
    if "carbridge-carforce" in shell:
        return ("11-23 09:00:01.000  1000  1000 I carbridge-carforce: after-fetch uuid=00000000-deca-fade-deca-deafdecacafe\r\n"
                "11-23 09:00:01.010  1000  1000 I carbridge-carforce: after-fetch uuid=2d8d2466-e14d-451c-88bc-7301abea291a\r\n"
                "11-23 09:00:02.000  1000  1000 I carbridge-carforce: connectWirelessCarplayItem x3 done\r\n")
    if shell.startswith("am force-stop"):
        return ""
    if shell.startswith("am start"):
        return "Starting: Intent { cmp=com.desaysv.vehicle.carplayapp/.MainActivity }\r\n"
    return "OK\r\n"


def main():
    global STRAY
    args = [a for a in sys.argv[1:] if a != "--stray"]
    STRAY = "--stray" in sys.argv
    port = int(args[0]) if args else 5555
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port))
    s.listen(4)
    print(f"[fake-adbd] listening on {port}", flush=True)
    while True:
        c, a = s.accept()
        threading.Thread(target=handle, args=(c, a), daemon=True).start()


if __name__ == "__main__":
    main()
