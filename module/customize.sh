#!/system/bin/sh
# KernelSU / SukiSU 安装时执行（模块文件已解压到 $MODPATH）
ui_print "- iPhone 蓝牙身份：在射频层写 EIR / CoD / 名字，不改任何系统文件"
ui_print "- 卸载模块会自动停循环并重启蓝牙，身份恢复原样"
set_perm "$MODPATH/bin/iphone_bt" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
