#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${PROJECT_ROOT}/tool/CheckReleaseDebug.java"

fail() {
  printf '[FAIL] %s\n' "$*" >&2
  exit 1
}

run_checker() {
  relative_path="$1"
  java "$CHECKER" \
    --source "${PROJECT_ROOT}/${relative_path}" \
    --display "$relative_path"
}

false_fixture='app/src/testFixtures/release-debug-false.kt'
run_checker "$false_fixture" >/dev/null \
  || fail '_debug = false 时 Release 门禁应通过'

true_fixture='app/src/testFixtures/release-debug-true.kt'
if actual="$(run_checker "$true_fixture" 2>&1)"; then
  fail '_debug = true 时 Release 门禁必须失败'
fi
[[ "$actual" == *"${true_fixture}"* ]] \
  || fail 'true 诊断必须包含源文件路径'
[[ "$actual" == *'_debug = true，请改为 _debug = false 后重新构建'* ]] \
  || fail 'true 诊断必须保留精确修复动作'

for fixture_name in duplicate mutable computed nested missing; do
  fixture_path="app/src/testFixtures/release-debug-${fixture_name}.kt"
  if actual="$(run_checker "$fixture_path" 2>&1)"; then
    fail "${fixture_name} 反例必须失败"
  fi
  [[ "$actual" == *"${fixture_path}"* ]] \
    || fail "${fixture_name} 诊断必须包含源文件路径"
  [[ "$actual" == *'请保留唯一顶层字面量并改为 _debug = false 后重试'* ]] \
    || fail "${fixture_name} 诊断必须给出 false 修复动作"
done

run_checker 'app/src/testFixtures/release-debug-ignored-text.kt' >/dev/null \
  || fail '注释和字符串中的伪声明不应影响唯一 false 声明'

string_bypass_fixture='app/src/testFixtures/release-debug-string-bypass.kt'
if actual="$(run_checker "$string_bypass_fixture" 2>&1)"; then
  fail '字符串中的注释标记不得隐藏 true 声明'
fi
[[ "$actual" == *"${string_bypass_fixture}"* ]] \
  || fail '字符串绕过诊断必须包含源文件路径'
[[ "$actual" == *'_debug = true，请改为 _debug = false 后重新构建'* ]] \
  || fail '字符串绕过必须命中真实 true 声明'

printf '%s\n' '[PASS] Android Release _debug 门禁 fixture'
