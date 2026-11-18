#!/usr/bin/env bash
# 架构守卫：协议层 / 设备层不许反向依赖界面层（com.carbridge.app）。
#
# 为什么要这条：一旦 carplay/* 里出现 import com.carbridge.app.*，改界面就会影响协议代码，
# 「改一个功能只动一个文件」立刻失效。这个脚本进 CI/提交前跑一次就够。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/app/java/com/carbridge"

fail=0

# 1) carplay/** 里不许出现 import com.carbridge.app.
hits=$(grep -rn "import com\.carbridge\.app\." "$SRC/carplay" --include="*.java" 2>/dev/null || true)
if [ -n "$hits" ]; then
    echo "✗ 协议层反向依赖了界面层："
    echo "$hits"
    fail=1
else
    echo "✓ 协议层没有反向依赖界面层"
fi

# 2) 白名单：只有编排层（CarPlayWirelessTx）和 iap2 的回调接口可以提到 app 的类
allowed=$(grep -rln "com\.carbridge\.app\." "$SRC/carplay" --include="*.java" 2>/dev/null || true)
if [ -n "$allowed" ]; then
    echo "  （间接引用：$(echo "$allowed" | xargs -n1 basename | tr '\n' ' ')）"
fi

# 3) util 层不许依赖任何业务包
util_hits=$(grep -rn "import com\.carbridge\.\(app\|carplay\.\(iap2\|core\|net\|bt\)\)" \
    "$SRC/carplay/util" --include="*.java" 2>/dev/null || true)
if [ -n "$util_hits" ]; then
    echo "✗ util 层依赖了业务包："
    echo "$util_hits"
    fail=1
else
    echo "✓ util 层保持独立"
fi

exit $fail
