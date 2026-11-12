#!/usr/bin/env bash
# APK 体积门禁。
#
# 「更轻量」是产品承诺，必须有自动化守门 —— 不能靠人肉检查。
# 超出预算直接让 CI 失败。

set -euo pipefail

APK="${1:?用法: size-gate.sh <apk 路径>}"

if [ ! -f "$APK" ]; then
  echo "找不到 APK: $APK" >&2
  exit 1
fi

# ── 预算（KB）─────────────────────────────────────────────────────────────
# 说明：这些是**上限**，随每次有意识的体积权衡调整，并记录在 docs/08。
BUDGET_LITE_KB=15360   # 15 MB —— iqtvlite-lite（无 libmpv）
BUDGET_FULL_KB=51200  # 50 MB —— iqtvlite 完整版（含 libmpv + FFmpeg）

SIZE_KB=$(unzip -l "$APK" | awk 'NR>3 && $1 ~ /^[0-9]+$/ {sum+=$1} END {print int(sum/1024)}')
echo "APK 体积: ${SIZE_KB} KB"

# libmpv 变体的判定：是否包含 libmpv.so
if unzip -l "$APK" | grep -q "libmpv.so"; then
  BUDGET=$BUDGET_FULL_KB
  VARIANT="full (含 libmpv)"
else
  BUDGET=$BUDGET_LITE_KB
  VARIANT="lite (无 libmpv)"
fi

echo "变体: $VARIANT  预算: ${BUDGET} KB"

if [ "$SIZE_KB" -gt "$BUDGET" ]; then
  echo "::error::APK 超出体积预算 ${SIZE_KB} > ${BUDGET} KB"
  exit 1
fi

echo "::notice::体积门禁通过 (${SIZE_KB}/${BUDGET} KB)"
