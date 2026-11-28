# tools/root —— 需要 root 的身份工具

## caridentity（直接对蓝牙控制器下发 HCI 命令）

读写「扫描阶段」的对外身份：EIR（含两条 CarPlay UUID：`00000000-deca-fade-deca-deafdecacaff`、
`d31fbf50-…-41cd484388ec`，与车连易/CPC200 盒子固件同一模板）和 CoD。

```
caridentity eir read | write <name> | clear
caridentity cod read | write <0xHHHHHH>
```

编译（NDK）：
```
~/android-sdk/ndk/<ver>/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang \
    -static -O2 -o caridentity caridentity.c
```

## 设备兼容性（实测）

**需要内核暴露 `/dev/hci0`（标准 HCI socket）**。以下情况不可用：
- 高通 HIDL 蓝牙 HAL（`android.hardware.bluetooth@1.1-service-qti`）：HCI 传输在厂商
  用户态进程里，`/sys/class/bluetooth` 为空、无 `/dev/hci*` → bind 返回 `No such device`
  （实测机型：OnePlus PKG110 / ColorOS 16）。
- 判断方法：`ls /sys/class/bluetooth/` 非空且有 `hci0` 才可能可用。

## 为什么不是「100% 模拟 iPhone」

1. **MFi 证书**：Apple 签名 + 对应私钥，root 也变不出来（只有真芯片/Apple 签发）。
2. **SDP 属性全控**：SDP 数据库在 Bluedroid（用户态），不在控制器里；root 不 hook/改库无法加自定义属性。
3. EIR/CoD 只是「扫描阶段指纹」，能补但补不出上面两条。
