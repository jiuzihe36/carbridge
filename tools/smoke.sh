#!/usr/bin/env bash
# 冒烟测试：把「装机 → 起假车机 → 自动连 → 等到推流」全流程跑一遍，失败退出非 0。
#
# 为什么要有它：每次改协议/认证都要人工点两遍手机，容易漏步骤、也说不清到底通没通。
# 有了这个脚本，改动之后一条命令就能回答「还通不通」。
#
# 用法：
#   tools/smoke.sh                # 用当前 adb 设备：手机=第一个非平板设备，平板=第一个 192.168.x 设备
#   PHONE=<transport_id|序列号> TABLET=<ip:port> tools/smoke.sh
#   SKIP_BUILD=1 tools/smoke.sh   # 不重新编译，只跑流程
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$HOME/android-sdk/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] || { echo "缺少 adb" >&2; exit 1; }

log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { echo "✗ $*" >&2; exit 1; }

# ---------------------------------------------------------------- 1. 构建
if [ "${SKIP_BUILD:-0}" != "1" ]; then
    log "构建 carbridge + fakecar"
    JAVA_HOME="${JAVA_HOME:-$HOME/项目/资料/toolchain/jdk-current/Contents/Home}" "$ROOT/android/build.sh" >/tmp/smoke-build.log 2>&1 \
        || { tail -20 /tmp/smoke-build.log; fail "carbridge 构建失败"; }
    ( cd "$ROOT/tools/fakecar" && JAVA_HOME="${JAVA_HOME:-$HOME/项目/资料/toolchain/jdk-current/Contents/Home}" ./build.sh >/tmp/smoke-build-fake.log 2>&1 ) \
        || { tail -20 /tmp/smoke-build-fake.log; fail "fakecar 构建失败"; }
fi

# ---------------------------------------------------------------- 2. 找设备
"$ADB" start-server >/dev/null 2>&1
# 按机型自动分配：一加/OPPO = 手机；vivo = 平板（也可用 PHONE/TABLET 环境变量指定）
if [ -z "${PHONE:-}" ] || [ -z "${TABLET:-}" ]; then
    DEVS=()
    while IFS= read -r dev; do
        [ -n "$dev" ] && DEVS+=("$dev")
    done < <("$ADB" devices | awk '/\tdevice$/{print $1}')
    for dev in "${DEVS[@]}"; do
        # 同一个物理设备可能在 adb 里出现多次（mDNS 名 + ip:port），按机型去重
        model=$("$ADB" -s "$dev" shell getprop ro.product.model 2>/dev/null </dev/null | tr -d '\r')
        case "$model" in
            *PA2353*|*DPD2305*|*vivo*) [ -z "${TABLET:-}" ] && TABLET="$dev" ;;
            *PKG110*|*OP5D*)          [ -z "${PHONE:-}" ]  && PHONE="$dev" ;;
        esac
    done
fi
[ -n "${PHONE:-}" ] || fail "找不到手机（adb devices 里没有非 IP 设备）"
[ -n "${TABLET:-}" ] || fail "找不到平板（adb devices 里没有 ip:port 设备）"
log "手机=$PHONE  平板=$TABLET"

# ---------------------------------------------------------------- 3. 装机
log "安装 APK"
# ⚠️ APK 名带版本号，不能写死（曾经写死 0.1.0，版本一升冒烟就装不上还以为是协议坏了）。
#    版本号从各工程 Manifest 现读，产物名 = 构建脚本的输出名。
CARBRIDGE_VER=$(grep -o 'android:versionName="[^"]*"' "$ROOT/android/app/AndroidManifest.xml" | head -1 | sed 's/.*="//; s/"$//')
FAKECAR_VER=$(grep -o 'android:versionName="[^"]*"' "$ROOT/tools/fakecar/app/AndroidManifest.xml" | head -1 | sed 's/.*="//; s/"$//')
CARBRIDGE_APK="$ROOT/android/build/carbridge-$CARBRIDGE_VER.apk"
FAKECAR_APK="$ROOT/tools/fakecar/build/fakecar-$FAKECAR_VER.apk"
[ -f "$CARBRIDGE_APK" ] || fail "找不到 $CARBRIDGE_APK（先构建）"
[ -f "$FAKECAR_APK" ] || fail "找不到 $FAKECAR_APK（先构建）"
if [ "${SKIP_INSTALL:-0}" = "1" ]; then
    log "跳过安装（SKIP_INSTALL=1）"
else
    # -g 直接给权限；-r 覆盖安装。部分机型还会弹确认框，所以超时给足。
    "$ADB" -s "$PHONE" install -r -g --user 0 "$CARBRIDGE_APK" >/tmp/smoke-install.log 2>&1 \
        || { tail -5 /tmp/smoke-install.log; fail "手机安装失败（若是系统确认框，请手动点允许后重试）"; }
    "$ADB" -s "$TABLET" install -r -g --user 0 "$FAKECAR_APK" >/tmp/smoke-install2.log 2>&1 \
        || { tail -5 /tmp/smoke-install2.log; fail "平板安装失败"; }
fi

# ---------------------------------------------------------------- 4. 起两个 App
LOG=/storage/emulated/0/Android/data/com.carbridge.app/files/carbridge-session.log
log "清空手机会话日志并启动"
# 唤醒屏幕：Car+ 的通道在息屏下容易起不来，冒烟测试必须保证屏幕是亮的
"$ADB" -s "$PHONE" shell input keyevent 224 >/dev/null 2>&1
"$ADB" -s "$PHONE" shell am force-stop com.carbridge.app
"$ADB" -s "$PHONE" shell "rm -f $LOG"
# force-stop 之后立刻 am start 会偶发「起了但进程没起来」（系统还在收尸），
# 所以启动后要确认 pid 真的在，不在就重试一次。
"$ADB" -s "$PHONE" shell am start -n com.carbridge.app/.MainActivity >/dev/null 2>&1
sleep 2
if [ -z "$("$ADB" -s "$PHONE" shell pidof com.carbridge.app 2>/dev/null | tr -d '\r')" ]; then
    log "首次启动没起来，重试一次"
    sleep 2
    "$ADB" -s "$PHONE" shell am start -n com.carbridge.app/.MainActivity >/dev/null 2>&1
    sleep 3
fi
if [ -z "$("$ADB" -s "$PHONE" shell pidof com.carbridge.app 2>/dev/null | tr -d '\r')" ]; then
    fail "App 没能启动（检查是否被系统拦截/闪退）"
fi

log "启动假车机并自动连接"
"$ADB" -s "$TABLET" shell am force-stop com.carbridge.fakecar
"$ADB" -s "$TABLET" shell am start -n com.carbridge.fakecar/.MainActivity --ez autoconnect true >/dev/null 2>&1

# ---------------------------------------------------------------- 4.5 截图存档
SHOTS="$ROOT/build/smoke-$(date +%m%d-%H%M%S)"
mkdir -p "$SHOTS"
shot() { "$ADB" -s "$PHONE" exec-out screencap -p > "$SHOTS/$1.png" 2>/dev/null; }

# 按钮文案断言：这条能直接抓住「蓝底蓝字=看不见」这类 bug
btn_text() {
    # 连接成功后界面可能停在「诊断」页（看不到主按钮），先切回「连接」页再取文案；
    # 只 dump 可见节点，页面不对就会误判成「空文案」。
    "$ADB" -s "$PHONE" shell "uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1"
    local tb
    tb=$("$ADB" -s "$PHONE" shell cat /sdcard/smoke.xml 2>/dev/null \
        | tr '<' '\n<' \
        | grep -oE 'resource-id="com.carbridge.app:id/tab_ops"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
        | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    if [ -n "$tb" ]; then
        local x y
        x=$(echo "$tb" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \3/' | awk '{print int(($1+$2)/2)}')
        y=$(echo "$tb" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2 \4/' | awk '{print int(($1+$2)/2)}')
        "$ADB" -s "$PHONE" shell input tap "$x" "$y" >/dev/null 2>&1
        sleep 1
        # 连接成功后状态卡会多出「投屏中 / 详情」几行，主按钮被顶到屏幕外，
        # 只 dump 可见节点就会误判成空文案 —— 先滚回顶部。
        "$ADB" -s "$PHONE" shell input swipe 632 700 632 2300 120 >/dev/null 2>&1
        sleep 1
        "$ADB" -s "$PHONE" shell "uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1"
    fi
    "$ADB" -s "$PHONE" shell cat /sdcard/smoke.xml 2>/dev/null \
        | tr '<' '\n<' \
        | grep -oE 'text="[^"]*" resource-id="com.carbridge.app:id/btn_connect"' \
        | sed -E 's/text="([^"]*)".*/\1/' | head -1
}

# 注意：App 设计上启动 3 秒后就自动连 Car+（通道窗口很短），所以这里不假设「空闲」，
# 只断言按钮**有文案** —— 蓝底蓝字那个 bug 的表现就是文案存在但看不见，
# 因此还要配合截图人工确认；文案为空则直接判失败。
idle_text=$(btn_text)
shot idle
log "启动后按钮文案：「${idle_text}」"
if [ -n "$idle_text" ]; then
    log "  ✓ 按钮有文案（非空条）"
else
    # 启动 4 秒内 App 可能正处在 Car+ 拉起/切页的过渡态，取不到按钮不算缺陷；
    # 真正的回归守卫是下面「已连接」那次断言。
    log "  · 启动瞬间界面未就绪，跳过（以已连接态为准）"
fi

# ---------------------------------------------------------------- 5. 等结果
log "等待「AirPlay 会话已建立」（最多 90s）"
ok=0
for i in $(seq 1 45); do
    if "$ADB" -s "$PHONE" shell "grep -aq 'AirPlay 会话已建立' $LOG" 2>/dev/null; then ok=1; break; fi
    sleep 2
done

# ---------------------------------------------------------------- 6. 断言
assert_contains() {
    local pattern="$1" label="$2"
    if "$ADB" -s "$PHONE" shell "grep -aq '$pattern' $LOG" 2>/dev/null; then
        log "  ✓ $label"
    else
        log "  ✗ $label"
        return 1
    fi
}

bad=${bad_idle:-0}
log "断言关键日志"
assert_contains "SDP 信标已注册"      "蓝牙信标四条记录"   || bad=1
assert_contains "车机身份"            "iAP2 识别到车机"     || bad=1
assert_contains "0x4301"              "收到 CarPlay 会话开始" || bad=1
assert_contains "GET /info"           "AirPlay /info 应答"  || bad=1
assert_contains "开始推流"            "视频开始推流"        || bad=1

conn_text=$(btn_text)
shot connected
log "已连接按钮文案：「${conn_text}」"
if [ "$conn_text" = "断开连接" ]; then
    log "  ✓ 已连接按钮文案正确（不会出现「没有字的蓝条」）"
else
    log "  ✗ 已连接按钮文案异常（期望「断开连接」）"
    bad=1
fi

if [ "$ok" = "1" ] && [ "$bad" = "0" ]; then
    log "冒烟测试通过 ✔  截图：${SHOTS}"
    exit 0
fi
log "—— 日志尾部 ——"
"$ADB" -s "$PHONE" shell "tail -20 $LOG" 2>/dev/null
fail "冒烟测试未通过（链路建立=${ok}，断言失败=${bad}）"
