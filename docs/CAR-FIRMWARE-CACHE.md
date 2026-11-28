# 车机侧 CarPlay 缓存（固件实证）

来源：`~/Downloads/nv8341-out/product.img`（德赛 T1E / NV8341 车机固件，2026-11 快照）。
分析工具：py-ext4（提取）、jadx（APK 资源）、nm/objdump/strings（原生库）。

## 组件

| 路径 | 说明 |
|---|---|
| `/app/Carplay/Carplay.apk` | 车机 CarPlay 应用壳（34KB，**只有资源**；代码在 `oat/arm64/Carplay.vdex`，Android 9 的 **compact-dex**，jadx 解不了） |
| `/app/Carplay/oat/arm64/Carplay.vdex/.odex` | 应用代码（cdex001） |
| `/lib64/libcarplayserver_R14_vehicle.so` | **CarPlay 服务器（Apple R14 栈移植）**，设备缓存逻辑在这里 |
| `/lib64/libcarplay_vehicle_jni.so` | JNI 层（`getWirelessCarPlaySetupUUID`、`hostBluetoothAddress`） |
| `/etc/vehicle.carplay.conf` | 开关项（`CONF_ENABLE_CARPLAY=true` 等） |
| `/etc/vehicle.ipod.conf` | 车机作为配件的身份：Name=CarPlay / ModelNumber=HeadUnit / Manufacture=VENDOR / SerialNumber=0000000000000 |
| `/etc/vehicle.carplaySDK.conf` | SDK 配置 |

## 缓存文件（「手机在车机上的缓存」就是它们）

| 文件 | 证据 | 含义 |
|---|---|---|
| **`/data/vehicle/data/carplay_device_info.dat`** | `libcarplayserver`：`SVPCarPlay_DeviceMgr::ReadDeviceListFile` / `WriteAllCarplayDevicesInfo`，用 `fopen/fread/fwrite` 读写 | CarPlay **设备列表缓存**（已连过的手机）。键：`deviceID`、`displayUUID`、`transportType`、`name`、`model`、`BTLE`；另有 `Check_Device_SerialNum` 序列号校验 |
| `/data/vehicle/data/wireless_carplay_vendor_ie.dat` | 同库字符串 | 无线 CarPlay 的 vendor IE（识别用） |
| `/data/vehicle/carplay_record_stream` `/carplay_playback_stream` | 同库字符串 | 音频录制/回放流 |

格式：**裸二进制结构数组**（非 JSON/plist —— 库只导入了 `fopen/fread/fwrite`，没有任何序列化库）。

## 对「直改缓存冒充 iPhone」的结论

- **可行前提**：读懂 `carplay_device_info.dat` 的字节布局，并保证写回的
  `deviceID / displayUUID / transportType` 与手机实际握手值一致；否则设备列表能显示、
  但 CarPlay 会话起不来（`Check_Device_SerialNum` 还会校验序列号）。
- **风险**：盲改车机数据分区可能破坏后续配对（没有固件级回滚）。
- **正确顺序**：先只读导出该文件（App「车机一键解锁 → 只读诊断」已实现），
  按实际字节确定布局，再决定是「补一条记录」还是「改现有记录」。

## 读取方式（App 内已实现，只读）

车机一键解锁弹窗 →「只读诊断」：dump
`/data/vehicle/data/` 目录、两个 `.dat` 的 hex+可打印串、CarPlay 属性、
`/data/misc/bluedroid/bt_config.conf` 里本机 MAC 的配对条目。
