#!/system/bin/sh
# late_start 服务模式：等蓝牙起来，然后把身份写进控制器并常驻刷新。
#
# 为什么必须常驻刷新：AOSP 在「可发现状态变化 / 蓝牙开关 / SDP 服务增删」时会重新
# 下发 EIR，把我们写进去的 128 位 UUID 覆盖掉。单次写入 = 车机下一次 inquiry 就
# 看不到 CarPlay 标记了。
MODDIR=${0%/*}
BIN="$MODDIR/bin/iphone_bt"
LOG=/data/local/tmp/iphone_bt.log

# 只留一个实例（模块更新 / 重启脚本时都可能残留）
pkill -f "iphone_bt --loop" 2>/dev/null
sleep 1

echo "---- $(date) service.sh 启动 ----" >> "$LOG"

# 等蓝牙进程起来（最多 4 分钟）：开机阶段 Airplane / 省电策略都可能还没拉起它
i=0
while [ $i -lt 120 ]; do
  if pidof com.android.bluetooth >/dev/null 2>&1; then
    echo "$(date) 蓝牙进程已就绪（等了 ${i} 轮）" >> "$LOG"
    break
  fi
  sleep 2
  i=$((i + 1))
done

"$BIN" --loop 2 >> "$LOG" 2>&1 &
echo $! > /data/local/tmp/iphone_bt.pid
echo "$(date) 已启动 iphone_bt --loop 2 (pid=$!)" >> "$LOG"
