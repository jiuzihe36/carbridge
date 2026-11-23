/*
 * iphone_bt —— 让本机在**射频层**像一个真 iPhone（EIR + 名字 + CoD + 可被发现）。
 *
 * 为什么需要它：车机（高通 Blue SDK / 德赛这类）在 inquiry 阶段读经典蓝牙的
 * EIR（AD type 0x07 里的 128 位 Service UUID）来判断「这是不是支持无线 CarPlay
 * 的 Apple 设备」。Android 应用层写不进 EIR（AOSP 的 BTM_AddEirService 只吃
 * 16 位 UUID），`listenUsingRfcommWithServiceRecord` 只写 SDP。所以要么在射频层
 * 把 EIR 写进去，要么车机永远把你当普通蓝牙设备。
 *
 * 做法：直接对控制器下发 HCI 命令
 *   0x0C13 Write_Local_Name
 *   0x0C24 Write_Class_Of_Device
 *   0x0C1A Write_Scan_Enable          （inquiry scan + page scan）
 *   0x0C52 Write_Extended_Inquiry_Response
 * 然后**周期性重发**：AOSP 在可发现状态变化 / 蓝牙开关 / 服务增删时会重新写 EIR，
 * 单次写入会被覆盖（实测必须循环重发，参照 jsntsch/CarplayAPK 的 PoC）。
 *
 * 需要 root（CAP_NET_ADMIN + HCI 原始套接字）。
 *
 * 用法：
 *   iphone_bt --apply              写一次并打印每条命令的状态
 *   iphone_bt --loop 2             每 2 秒重发（模块的 service.sh 就这么跑）
 *   iphone_bt --loop 2 --full      额外带上配件侧的两条 UUID（默认不带）
 *   iphone_bt --name 一加ace5      改回原名字（卸载时用）
 */
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#ifndef AF_BLUETOOTH
#define AF_BLUETOOTH 31
#endif
#define BTPROTO_HCI 1
#define HCI_CHANNEL_RAW 0

struct sockaddr_hci {
    unsigned short hci_family;
    unsigned short hci_dev;
    unsigned short hci_channel;
};

/* ---- HCI 常量 ---- */
#define HCI_COMMAND_PKT 0x01
#define HCI_EVENT_PKT 0x04
#define EVT_CMD_COMPLETE 0x0e

#define OP_WRITE_LOCAL_NAME 0x0C13
#define OP_WRITE_COD 0x0C24
#define OP_WRITE_SCAN_ENABLE 0x0C1A
#define OP_WRITE_EIR 0x0C52

/* ---- 默认身份 ---- */
static const char *DEFAULT_NAME = "iPhone";
/* iPhone 的 Class of Device：Phone / Smartphone + 常用服务位（0x5A020C） */
static const uint32_t DEFAULT_COD = 0x5A020C;

/*
 * EIR 里的 128 位 UUID —— 字节序是**小端**（规范串整体反转）。
 * 这三条是「手机（device）角色」该广播的：
 *   2d8d2466-…  CarPlay 网关（车机靠它区分 CarPlay 设备 / 普通蓝牙设备）
 *   00000000-deca-fade-deca-deafdecacafe  iPhone 侧 iAP2，会话跑在这条
 *   02030302-…  Wireless iAP v2（真 iPhone 同时暴露）
 */
static const uint8_t UUID_CARPLAY[16] = {
        0x1a, 0x29, 0xea, 0xab, 0x01, 0x73, 0xbc, 0x88,
        0x1c, 0x45, 0x4d, 0xe1, 0x66, 0x24, 0x8d, 0x2d};
static const uint8_t UUID_IAP2[16] = {
        0xfe, 0xca, 0xca, 0xde, 0xaf, 0xde, 0xca, 0xde,
        0xde, 0xfa, 0xca, 0xde, 0x00, 0x00, 0x00, 0x00};
static const uint8_t UUID_WIRELESS_IAP2[16] = {
        0x77, 0x0a, 0x6a, 0x10, 0xa2, 0x22, 0xf2, 0x86,
        0x5f, 0x41, 0x19, 0x1d, 0x02, 0x03, 0x03, 0x02};
/* 配件（车机）角色才会广播的两条；默认不发，--full 时才加 */
static const uint8_t UUID_CARPLAY_LEGACY[16] = {
        0xd3, 0x1f, 0xbf, 0x50, 0x5d, 0x57, 0x27, 0x97,
        0xa2, 0x40, 0x41, 0xcd, 0x48, 0x43, 0x88, 0xec};
static const uint8_t UUID_IAP2_ACCESSORY[16] = {
        0xff, 0xca, 0xca, 0xde, 0xaf, 0xde, 0xca, 0xde,
        0xde, 0xfa, 0xca, 0xde, 0x00, 0x00, 0x00, 0x00};

static void logline(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

static void logline(const char *fmt, ...) {
    char ts[32];
    time_t now = time(NULL);
    struct tm tmv;
    localtime_r(&now, &tmv);
    strftime(ts, sizeof ts, "%H:%M:%S", &tmv);
    fprintf(stdout, "%s  ", ts);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
    fputc('\n', stdout);
    fflush(stdout);
}

#ifdef SOCK_CLOEXEC
#define CB_SOCK_FLAGS (SOCK_RAW | SOCK_CLOEXEC)
#else
#define CB_SOCK_FLAGS SOCK_RAW
#endif

static int hci_open(void) {
    int fd = socket(AF_BLUETOOTH, CB_SOCK_FLAGS, BTPROTO_HCI);
    if (fd < 0) {
        logline("socket(AF_BLUETOOTH) 失败: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_hci addr;
    memset(&addr, 0, sizeof addr);
    addr.hci_family = AF_BLUETOOTH;
    addr.hci_dev = 0;
    addr.hci_channel = HCI_CHANNEL_RAW;
    if (bind(fd, (struct sockaddr *) &addr, sizeof addr) < 0) {
        if (errno == ENODEV) {
            logline("本机内核没有暴露 HCI 设备（hci_uart refcount=0、/sys/class/bluetooth 为空）");
            logline("⇒ 这台的蓝牙是「用户态直驱 UART」（高通常见形态），raw HCI 用不了");
            logline("⇒ EIR 注入在这个 ROM 上不可行，需要 hook 蓝牙进程，不是命令能解决的");
        } else {
            logline("bind(hci0) 失败: %s", strerror(errno));
        }
        close(fd);
        return -1;
    }
    struct timeval tv;
    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    return fd;
}

/** 下发一条 HCI 命令，返回控制器回的状态字节（0x00 = 成功），负数是本地错误。 */
static int hci_cmd(int fd, uint16_t opcode, const uint8_t *params, uint8_t plen) {
    uint8_t buf[4 + 255];
    buf[0] = HCI_COMMAND_PKT;
    buf[1] = (uint8_t) (opcode & 0xff);
    buf[2] = (uint8_t) (opcode >> 8);
    buf[3] = plen;
    if (plen) memcpy(buf + 4, params, plen);
    ssize_t w = write(fd, buf, 4 + plen);
    if (w != 4 + plen) return -errno;

    uint8_t ev[1 + 260];
    for (int tries = 0; tries < 32; tries++) {
        ssize_t n = read(fd, ev, sizeof ev);
        if (n < 7) {
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return -ETIMEDOUT;
            if (n < 0) return -errno;
            continue;
        }
        if (ev[0] != HCI_EVENT_PKT || ev[1] != EVT_CMD_COMPLETE) continue;
        uint16_t op = (uint16_t) (ev[4] | (ev[5] << 8));
        if (op != opcode) continue;
        return ev[6];
    }
    return -ETIMEDOUT;
}

static int cmd_name(int fd, const char *name) {
    uint8_t p[248];
    memset(p, 0, sizeof p);
    strncpy((char *) p, name, sizeof p - 1);
    return hci_cmd(fd, OP_WRITE_LOCAL_NAME, p, sizeof p);
}

static int cmd_cod(int fd, uint32_t cod) {
    uint8_t p[3] = {(uint8_t) (cod & 0xff), (uint8_t) ((cod >> 8) & 0xff),
                    (uint8_t) ((cod >> 16) & 0xff)};
    return hci_cmd(fd, OP_WRITE_COD, p, 3);
}

static int cmd_scan_enable(int fd, uint8_t mode) {
    return hci_cmd(fd, OP_WRITE_SCAN_ENABLE, &mode, 1);
}

static size_t eir_ad(uint8_t *eir, size_t off, uint8_t type,
                     const uint8_t *data, size_t len) {
    if (off + 2 + len > 240) return off;
    eir[off] = (uint8_t) (len + 1);
    eir[off + 1] = type;
    if (len) memcpy(eir + off + 2, data, len);
    return off + 2 + len;
}

static size_t build_eir(uint8_t *eir, const char *name, int full) {
    memset(eir, 0, 240);
    size_t off = 0;

    /* AD 0x09 = Complete Local Name */
    off = eir_ad(eir, off, 0x09, (const uint8_t *) name, strlen(name));

    /* AD 0x07 = Complete List of 128-bit Service Class UUIDs（小端逐条拼） */
    uint8_t uuids[16 * 5];
    size_t n = 0;
    memcpy(uuids + n, UUID_CARPLAY, 16); n += 16;
    memcpy(uuids + n, UUID_IAP2, 16); n += 16;
    memcpy(uuids + n, UUID_WIRELESS_IAP2, 16); n += 16;
    if (full) {
        memcpy(uuids + n, UUID_CARPLAY_LEGACY, 16); n += 16;
        memcpy(uuids + n, UUID_IAP2_ACCESSORY, 16); n += 16;
    }
    off = eir_ad(eir, off, 0x07, uuids, n);
    return off;
}

static int cmd_eir(int fd, const char *name, int full) {
    uint8_t eir[240];
    build_eir(eir, name, full);

    uint8_t params[241];
    params[0] = 0x00;          /* FEC_Required = 0 */
    memcpy(params + 1, eir, sizeof eir);
    return hci_cmd(fd, OP_WRITE_EIR, params, sizeof params);
}

static const char *status_text(int st) {
    static char buf[32];
    if (st == 0) return "ok";
    if (st < 0) {
        snprintf(buf, sizeof buf, "%s", strerror(-st));
        return buf;
    }
    snprintf(buf, sizeof buf, "status=0x%02X", st);
    return buf;
}

static int apply_once(int fd, const char *name, uint32_t cod, int full) {
    int r_name = cmd_name(fd, name);
    int r_cod = cmd_cod(fd, cod);
    int r_eir = cmd_eir(fd, name, full);
    int r_scan = cmd_scan_enable(fd, 0x03);   /* inquiry + page scan */

    logline("name=\"%s\" → %s | CoD=0x%06X → %s | EIR → %s | scan=0x03 → %s",
            name, status_text(r_name), cod, status_text(r_cod),
            status_text(r_eir), status_text(r_scan));
    if (r_eir < 0) return r_eir;
    return (r_name == 0 && r_cod == 0 && r_eir == 0) ? 0 : -1;
}

int main(int argc, char **argv) {
    const char *name = DEFAULT_NAME;
    uint32_t cod = DEFAULT_COD;
    int loop = 0;
    int interval = 2;
    int full = 0;
    int dump_eir = 0;
    int probe = 0;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--loop") && i + 1 < argc) {
            loop = 1;
            interval = atoi(argv[++i]);
            if (interval < 1) interval = 1;
        } else if (!strcmp(argv[i], "--apply") || !strcmp(argv[i], "--once")) {
            loop = 0;
        } else if (!strcmp(argv[i], "--name") && i + 1 < argc) {
            name = argv[++i];
        } else if (!strcmp(argv[i], "--cod") && i + 1 < argc) {
            cod = (uint32_t) strtoul(argv[++i], NULL, 0);
        } else if (!strcmp(argv[i], "--probe")) {
            probe = 1;
        } else if (!strcmp(argv[i], "--dump-eir")) {
            dump_eir = 1;
        } else if (!strcmp(argv[i], "--full")) {
            full = 1;
        } else if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            fprintf(stderr,
                    "usage: iphone_bt [--apply|--loop N] [--name NAME] [--cod 0x5A020C] [--full]\n");
            return 2;
        }
    }

    if (probe) {
        int fd = hci_open();
        if (fd < 0) return 3;
        close(fd);
        logline("HCI 可用（hci0 可 bind），可以注入");
        return 0;
    }

    if (dump_eir) {
        uint8_t eir[240];
        size_t used = build_eir(eir, name, full);
        printf("EIR 有效字节 %zu / 240\n", used);
        for (size_t k = 0; k < used; k++) printf("%02x ", eir[k]);
        printf("\n");
        return 0;
    }

    logline("iphone_bt 启动：name=%s cod=0x%06X mode=%s full=%d",
            name, cod, loop ? "loop" : "apply", full);

    if (!loop) {
        int fd = hci_open();
        if (fd < 0) return 1;
        int rc = apply_once(fd, name, cod, full);
        close(fd);
        return rc == 0 ? 0 : 1;
    }

    int fd = -1;
    int failures = 0;
    for (;;) {
        if (fd < 0) {
            fd = hci_open();
            if (fd < 0) {
                if (failures++ % 30 == 0) logline("HCI 打不开，30s 后重试（蓝牙可能刚被关掉）");
                sleep(30);
                continue;
            }
        }
        int rc = apply_once(fd, name, cod, full);
        if (rc < 0) {
            /* 控制器/栈重启后旧 socket 会失效，重建一次 */
            close(fd);
            fd = -1;
        }
        sleep(interval);
    }
}
