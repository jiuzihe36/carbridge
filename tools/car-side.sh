#!/usr/bin/env bash
# 车机侧诊断 / 后门 —— 在 Mac 连上车机热点（xiaohu）之后跑。
#
#   tools/car-side.sh            只读：车机身份、已配对列表、CarPlay 能力判定日志
#   tools/car-side.sh --force    再写 persist.sys.wirelesscarplay.address（车机自带后门）
#
# 为什么要这个后门：车机 CarPlay 应用把「手机出现在列表里」判两件事
#   ① device.getUuids() 含 2d8d2466（配对那一刻的 SDP 缓存）
#   ② device.getAddress() == persist.sys.wirelesscarplay.address（不检查 UUID）
# 第 ② 条是车机自己的逻辑，写进去之后即使 Settings 那行还是灰的，CarPlay 列表里
# 也会出现这台手机，点它就会 connectBT → 手机的 CADE iAP2。
set -uo pipefail

CAR="${CAR:-192.168.33.92:5555}"
PHONE_MAC="${PHONE_MAC:-4C:92:D2:94:86:5E}"
CARPLAY_PKG="com.desaysv.vehicle.carplayapp"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

ADB="${ADB:-$HOME/android-sdk/platform-tools/adb}"
say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

say "连接车机 $CAR"
"$ADB" connect "$CAR" | tail -1
"$ADB" -s "$CAR" wait-for-device
"$ADB" -s "$CAR" shell getprop ro.product.model
"$ADB" -s "$CAR" shell getprop ro.build.version.release

say "车机蓝牙：适配器 + 已配对列表"
"$ADB" -s "$CAR" shell dumpsys bluetooth_manager \
  | sed -n '/AdapterProperties/,/mSnoopLogSettingAtEnable/p'

say "后门属性当前值"
"$ADB" -s "$CAR" shell getprop persist.sys.wirelesscarplay.address

say "车机侧 CarPlay 判定日志（isSupportCarplay）"
"$ADB" -s "$CAR" logcat -d -v time 2>/dev/null \
  | grep -iE "isSupportCarplay|WirelessCarplayManager|updateBondedDeviceListView|connectBT|New Iphone" \
  | tail -25

if [ "$FORCE" = "1" ]; then
  say "写入后门属性 → $PHONE_MAC"
  "$ADB" -s "$CAR" shell setprop persist.sys.wirelesscarplay.address "$PHONE_MAC"
  "$ADB" -s "$CAR" shell getprop persist.sys.wirelesscarplay.address

  say "重启车机 CarPlay 应用（让它重读设备列表）"
  "$ADB" -s "$CAR" shell am force-stop "$CARPLAY_PKG"
  sleep 1
  "$ADB" -s "$CAR" shell am start -n "$CARPLAY_PKG/.MainActivity"
  echo "已重启。到车机屏幕上打开 Apple CarPlay，列表里应该出现这台手机，点它。"

  say "盯车机日志 40 秒（看它有没有 connectBT / New Iphone）"
  timeout 40 "$ADB" -s "$CAR" logcat -v time 2>/dev/null \
    | grep -iE "isSupportCarplay|connectBT|New Iphone|CarPlay.*connect|iAP" \
    | tail -40
else
  say "只读模式。要写后门再加 --force"
fi
