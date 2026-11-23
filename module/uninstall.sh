#!/system/bin/sh
# 卸载 = 恢复：停掉循环，再让系统蓝牙重新接管控制器。
#
# 我们从头到尾没有改任何系统文件（模块里没有 system/ 目录），写的只是控制器里的
# 运行时状态（EIR / CoD / 名字 / scan enable）。这些状态在蓝牙重启或关开一次之后
# 就会被 AOSP 自己重写回来，所以这里主动关开一次，用户立刻看到原样。
pkill -f "iphone_bt --loop" 2>/dev/null
rm -f /data/local/tmp/iphone_bt.pid
echo "$(date) 模块卸载：已停循环，重启蓝牙恢复原身份" >> /data/local/tmp/iphone_bt.log

svc bluetooth disable
sleep 2
svc bluetooth enable
