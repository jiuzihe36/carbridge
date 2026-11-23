#!/system/bin/sh
# late_start 服务模式：先探一次「本机到底有没有 HCI 设备」，再决定要不要常驻。
#
# 为什么要先探：很多高通机型的蓝牙是**用户态直驱 UART**（hci_uart 模块 refcount=0、
# /sys/class/bluetooth 为空），内核根本没有 hci0 —— 这种情况下 raw HCI 注入不可用，
# 再循环重试也只是每分钟刷一行日志，必须一次性说清楚并退出。
MODDIR=${0%/*}
BIN="$MODDIR/bin/iphone_bt"
LOG=/data/local/tmp/iphone_bt.log

pkill -f "iphone_bt --loop" 2>/dev/null
sleep 1
echo "---- $(date) service.sh 启动 ----" >> "$LOG"

# 等蓝牙进程起来（最多 4 分钟）
i=0
while [ $i -lt 120 ]; do
  if pidof com.android.bluetooth >/dev/null 2>&1; then break; fi
  sleep 2
  i=$((i + 1))
done

"$BIN" --probe >> "$LOG" 2>&1
if [ $? -ne 0 ]; then
  echo "$(date) 本机没有可用的 HCI 设备 → 模块不启动（EIR 注入需要 hook 蓝牙进程，不是本模块能做的）" >> "$LOG"
  exit 0
fi

"$BIN" --loop 2 >> "$LOG" 2>&1 &
echo $! > /data/local/tmp/iphone_bt.pid
echo "$(date) 已启动 iphone_bt --loop 2 (pid=$!)" >> "$LOG"
