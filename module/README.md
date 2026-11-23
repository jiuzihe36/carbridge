# iPhone 蓝牙身份（SukiSU / KernelSU 模块）

在**射频层**把手机伪装成 iPhone：往控制器写 EIR（128 位 CarPlay UUID）、Class of Device、蓝牙名，
并保持可被发现。**不替换任何系统文件**，卸载即恢复。

## 为什么需要它

车机（高通 Blue SDK / 德赛这一代）在 inquiry 阶段读经典蓝牙的 **EIR**（AD type `0x07` 里的
128 位 Service UUID）来判断「这是不是支持无线 CarPlay 的 Apple 设备」。Apple 原文：

> Accessories may use this information to distinguish between devices with Apple CarPlay enabled
> and general Bluetooth devices.

Android 应用层**写不进 EIR**：AOSP 的 `BTM_AddEirService()` 只吃 16 位 UUID，
`listenUsingRfcommWithServiceRecord()` 只写 SDP。所以只要车机按 EIR 过滤，
普通 App 怎么改都没用——这就是 `jsntsch/CarplayAPK`（别克 INFO3.6）在固件层确认过的结论：
iAP2 / mDNS / 配对状态都绕不过，唯一解是手机侧注入 EIR。

## 它写什么

| HCI 命令 | 内容 | 作用 |
|---|---|---|
| `0x0C13` Write_Local_Name | `iPhone` | 车机列表里显示成 iPhone |
| `0x0C24` Write_Class_Of_Device | `0x5A020C` | Phone / Smartphone（与 iPhone 一致） |
| `0x0C1A` Write_Scan_Enable | `0x03` | inquiry + page scan，车机搜得到 |
| `0x0C52` Write_Extended_Inquiry_Response | 240 字节 EIR | **关键**：AD `0x07` 里带三条 128 位 UUID |

默认带的三条（手机角色该广播的）：

```
2d8d2466-e14d-451c-88bc-7301abea291a   CarPlay 网关（车机按它区分 CarPlay 设备）
00000000-deca-fade-deca-deafdecacafe   iPhone 侧 iAP2
02030302-1d19-415f-86f2-22a2106a0a77   Wireless iAP v2
```

`--full` 会额外加配件侧的两条（`ec884348…`、`…cacaff`）。**默认不加**——真 iPhone 是设备角色，
冒充配件可能让车机把你当接收端。

## 为什么每 2 秒重发

AOSP 在「可发现状态变化 / 蓝牙开关 / SDP 服务增删」时会重新下发 EIR，把我们的写入覆盖掉。
单次写入 = 车机下一次 inquiry 就看不到 CarPlay 标记。所以 `service.sh` 用 `--loop 2` 常驻刷新。

## 安装

**SukiSU / KernelSU 管理器**：模块 → 从本地安装 → 选 `build/sukisu-iphone-bt-1.0.0.zip` → 重启。

**命令行**：

```bash
adb push build/sukisu-iphone-bt-1.0.0.zip /data/local/tmp/
adb shell su -c "ksud module install /data/local/tmp/sukisu-iphone-bt-1.0.0.zip"
adb reboot
```

装完可点模块的「操作」按钮立刻应用一次（不用等重启）。

## 验收

手机侧（命令是否真的被控制器接受）：

```bash
adb shell su -c "tail -5 /data/local/tmp/iphone_bt.log"
# 期望：name="iPhone" → ok | CoD=0x5A020C → ok | EIR → ok | scan=0x03 → ok
```

外部设备侧（最硬的证据）——任一 Linux 机器上，手机保持可被发现时：

```bash
sudo btmon | grep -i "1a 29 ea ab"      # 或 bluetoothctl → scan on，看 EIR 里的 128 位 UUID
```

车机侧：Settings 那行 `/**(支持无线 CarPlay 连接)**/` 出现，或车机 logcat 里出现
`Wireless Carplay Device connected`。

## 恢复

- 模块里**没有** `system/` 目录，没改任何系统文件。
- 卸载时 `uninstall.sh` 会停掉循环，并 `svc bluetooth disable/enable` 一次——AOSP 会把
  CoD / 名字 / EIR 全部按它自己的配置重写回来，手机立刻恢复原样。
- 也可以手动 `adb shell su -c "pkill -f iphone_bt"` 再关开一次蓝牙。

## 边界（写清楚，别期待它做不到的事）

- 只解决**射频层身份**。SDP 记录（`…cafe` / `2d8d2466`）仍由 carbridge App 注册；iAP2 字段、
  AirPlay 身份也都在 App 里。两边要一起用。
- 写不进 **Apple 厂商自定义数据**（AD `0xFF` + Company ID `0x004C`）：我们手上没有真 iPhone 的
  原始 EIR 抓包，凭空造字节可能让车机更不认。需要时先在一台 iPhone 旁边用 `btmon` 抓一份。
- 改不了 **SDP 自定义属性**（Android 只给 name + UUID）和 **HFP 的 AT 身份**（`CGMI/CGMM/CGMR`）。
- 控制器拒绝时会在日志里写 `status=0x..`；`0x01` 是不支持该命令，`0x0C` 是参数不被接受。
- 少数 ROM 的蓝牙栈会持续和我们对写（谁来后谁生效）；若日志里 `EIR → ok` 但车机仍不认，
  先把刷新间隔降到 1 秒再看，别先怀疑字节。

## 构建

```bash
./module/build.sh     # 需要 NDK（默认 ~/android-sdk/ndk/*）
# → build/sukisu-iphone-bt-1.0.0.zip
```

主机上想只看 EIR 字节（不碰 HCI）：

```bash
clang -O2 -o /tmp/iphone_bt iphone_bt.c && /tmp/iphone_bt --dump-eir
```
