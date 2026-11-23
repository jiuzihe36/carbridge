#!/system/bin/sh
# 管理器里的「操作」按钮：立刻写一次，并把结果打到输出里（不用等开机、不用看日志文件）。
MODDIR=${0%/*}
"$MODDIR/bin/iphone_bt" --apply 2>&1

echo "---- 最近日志 ----"
tail -n 6 /data/local/tmp/iphone_bt.log 2>/dev/null
