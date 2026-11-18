#!/usr/bin/env bash
# 跑假车机测试台
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
[ -x "$JAVA" ] || JAVA="$(command -v java || true)"
exec "$JAVA" -cp "$HERE/out" fakecar.Harness "$@"
