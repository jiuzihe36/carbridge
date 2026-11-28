/*
 * caridentity —— 直接对蓝牙控制器下发 HCI 命令，读写「对外身份」字段。
 *
 * 存在的理由：普通 App 摸不到经典蓝牙的 EIR/CoD（安卓框架不开放），但这三样
 * 才是「车机在扫描阶段就把你看成什么设备」的决定因素。盒子固件（车连易/CPC200）
 * 就是靠 hciconfig inqdata 写 EIR + class 写 CoD 做到这一点的。
 *
 * 用法（需要 root）：
 *   caridentity eir read              读当前 EIR（240B，十六进制）
 *   caridentity eir write <name>      写 EIR：名字 TLV + 两条 CarPlay UUID TLV
 *                                     （00000000-deca-fade-deca-deafdecacaff 与
 *                                       d31fbf50-5d57-2797-a240-41cd484388ec，与盒子一致）
 *   caridentity eir clear             写全 0（清空 EIR）
 *   caridentity cod read              读当前 CoD
 *   caridentity cod write <0xHHHHHH>  写 CoD
 *
 * 编译（NDK）：aarch64-linux-android26-clang -static -O2 -o caridentity caridentity.c
 */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/socket.h>
#include <sys/types.h>

#ifndef AF_BLUETOOTH
#define AF_BLUETOOTH 31
#endif
#ifndef BTPROTO_HCI
#define BTPROTO_HCI 1
#endif
#define HCI_CHANNEL_RAW 0

struct sockaddr_hci {
    unsigned short hci_family;
    unsigned short hci_dev;
    unsigned short hci_channel;
};

#define OGF_HOST_CTL 0x03
static unsigned short hci_op(uint16_t ogf, uint16_t ocf) {
    return (unsigned short) ((ocf & 0x3ff) | (ogf << 10));
}
#define OP_READ_COD   hci_op(OGF_HOST_CTL, 0x0023)
#define OP_WRITE_COD  hci_op(OGF_HOST_CTL, 0x0024)
#define OP_READ_EIR   hci_op(OGF_HOST_CTL, 0x0051)
#define OP_WRITE_EIR  hci_op(OGF_HOST_CTL, 0x0052)

static int open_hci(int dev) {
    int s = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI);
    if (s < 0) {
        perror("socket(AF_BLUETOOTH)");
        return -1;
    }
    struct sockaddr_hci sa;
    memset(&sa, 0, sizeof sa);
    sa.hci_family = AF_BLUETOOTH;
    sa.hci_dev = (unsigned short) dev;
    sa.hci_channel = HCI_CHANNEL_RAW;
    if (bind(s, (struct sockaddr *) &sa, sizeof sa) < 0) {
        perror("bind(hci)");
        close(s);
        return -1;
    }
    return s;
}

/* 发一条命令并等它的 Command Complete/Status，回填返回参数。
 * 返回参数长度（-1 = 失败）。 */
static int cmd(int s, uint16_t opcode, const uint8_t *params, int plen,
               uint8_t *ret, int retcap) {
    uint8_t pkt[4 + 255];
    pkt[0] = 0x01;                         /* HCI_COMMAND_PKT */
    pkt[1] = (uint8_t) (opcode & 0xFF);    /* opcode 小端 */
    pkt[2] = (uint8_t) (opcode >> 8);
    pkt[3] = (uint8_t) plen;
    if (plen > 0 && params != NULL) memcpy(pkt + 4, params, plen);
    if (write(s, pkt, 4 + plen) != 4 + plen) {
        perror("write(hci)");
        return -1;
    }
    for (int tries = 0; tries < 20; tries++) {
        uint8_t ev[260];
        ssize_t n = read(s, ev, sizeof ev);
        if (n <= 0) {
            perror("read(hci)");
            return -1;
        }
        if (ev[0] != 0x04 || n < 3) continue;      /* 只要事件包 */
        uint8_t code = ev[1];
        if (code == 0x0E) {                        /* Command Complete */
            if (n < 7) continue;
            uint16_t op = (uint16_t) (ev[4] | (ev[5] << 8));
            if (op != opcode) continue;
            int status = ev[6];
            int rlen = (int) ev[3] - 3;            /* 参数：ncmd(1)+op(2) 之后 */
            if (status != 0) {
                fprintf(stderr, "命令 0x%04X 失败 status=0x%02X\n", opcode, status);
                return -1;
            }
            if (rlen > 0 && ret != NULL) {
                int c = rlen < retcap ? rlen : retcap;
                memcpy(ret, ev + 7, c);
                return c;
            }
            return 0;
        }
        if (code == 0x0F) {                        /* Command Status */
            if (n < 7) continue;
            uint16_t op = (uint16_t) (ev[4] | (ev[5] << 8));
            if (op != opcode) continue;
            if (ev[6] != 0) {
                fprintf(stderr, "命令 0x%04X 被拒 status=0x%02X\n", opcode, ev[6]);
                return -1;
            }
            continue;                              /* 等后续事件 */
        }
    }
    fprintf(stderr, "命令 0x%04X 无响应\n", opcode);
    return -1;
}

static void hexdump(const char *tag, const uint8_t *b, int n) {
    printf("%s", tag);
    for (int i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n");
}

/* EIR TLV 构造：与盒子固件 etc_bluetooth_eir_info 同一模板 */
static int build_eir(uint8_t *out, const char *name) {
    memset(out, 0, 240);
    int p = 0;
    /* 1) 名字：len=17 type=0x09，值固定 16B（不足补 0）——盒子就是 16B 定长 */
    out[p++] = 17;
    out[p++] = 0x09;
    int n = (int) strlen(name);
    if (n > 16) n = 16;
    memcpy(out + p, name, n);
    p += 16;
    /* 2) 128-bit UUID 列表：先 incomplete 再 complete（与盒子同一写法） */
    static const uint8_t uuids[32] = {
            0x00, 0x00, 0x00, 0x00, 0xde, 0xca, 0xfa, 0xde,
            0xde, 0xca, 0xde, 0xaf, 0xde, 0xca, 0xca, 0xff,   /* decafade…caff */
            0xd3, 0x1f, 0xbf, 0x50, 0x5d, 0x57, 0x27, 0x97,
            0xa2, 0x40, 0x41, 0xcd, 0x48, 0x43, 0x88, 0xec};  /* d31fbf50…88ec */
    out[p++] = 17;
    out[p++] = 0x06;
    memcpy(out + p, uuids, 16);
    p += 16;
    out[p++] = 17;
    out[p++] = 0x07;
    memcpy(out + p, uuids + 16, 16);
    p += 16;
    return p;   /* = 54 */
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr,
                "用法:\n  %s eir read|clear|write <name>\n  %s cod read|write <0xHHHHHH>\n",
                argv[0], argv[0]);
        return 2;
    }
    int s = open_hci(0);   /* hci0 */
    if (s < 0) return 1;
    int rc = 0;

    if (strcmp(argv[1], "eir") == 0) {
        if (strcmp(argv[2], "read") == 0) {
            uint8_t ret[300];
            int n = cmd(s, OP_READ_EIR, NULL, 0, ret, sizeof ret);
            if (n < 0) { rc = 1; }
            else {
                /* Command Complete 参数 = status(已在 cmd 内部剥掉) + 240B EIR */
                hexdump("EIR=", ret, n);
            }
        } else if (strcmp(argv[2], "write") == 0) {
            if (argc < 4) { fprintf(stderr, "缺名字\n"); rc = 2; }
            else {
                uint8_t eir[240];
                build_eir(eir, argv[3]);
                if (cmd(s, OP_WRITE_EIR, eir, 240, NULL, 0) < 0) rc = 1;
                else printf("已写入 EIR（name=%s + 两条 CarPlay UUID）\n", argv[3]);
            }
        } else if (strcmp(argv[2], "clear") == 0) {
            uint8_t eir[240];
            memset(eir, 0, sizeof eir);
            if (cmd(s, OP_WRITE_EIR, eir, 240, NULL, 0) < 0) rc = 1;
            else printf("已清空 EIR\n");
        } else { fprintf(stderr, "未知子命令 %s\n", argv[2]); rc = 2; }
    } else if (strcmp(argv[1], "cod") == 0) {
        if (strcmp(argv[2], "read") == 0) {
            uint8_t ret[8];
            int n = cmd(s, OP_READ_COD, NULL, 0, ret, sizeof ret);
            if (n < 3) rc = 1;
            else printf("CoD=0x%02X%02X%02X\n", ret[2], ret[1], ret[0]);
        } else if (strcmp(argv[2], "write") == 0) {
            if (argc < 4) { fprintf(stderr, "缺 CoD\n"); rc = 2; }
            else {
                unsigned long v = strtoul(argv[3], NULL, 0);
                uint8_t p[3] = {(uint8_t) (v & 0xFF), (uint8_t) ((v >> 8) & 0xFF),
                                (uint8_t) ((v >> 16) & 0xFF)};
                if (cmd(s, OP_WRITE_COD, p, 3, NULL, 0) < 0) rc = 1;
                else printf("已写入 CoD=0x%06lX\n", v);
            }
        } else { fprintf(stderr, "未知子命令 %s\n", argv[2]); rc = 2; }
    } else {
        fprintf(stderr, "未知命令 %s\n", argv[1]);
        rc = 2;
    }
    close(s);
    return rc;
}
