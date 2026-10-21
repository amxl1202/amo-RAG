#!/usr/bin/env bash
# 全链路跑批:检索 → 门控 → 生成 → 引用 → 自检。
#
# 与 eval-run.sh 的区别:那个只跑检索,不花钱、可反复跑;这个每题要调两次模型
# (生成 + 自检),换来的是"答案对不对"的证据。改检索参数时先跑便宜的那个。
#
# 需要:数据库在跑(脚本会连 5433)、.env 里有 DASHSCOPE_API_KEY。
#
# 用法:
#   scripts/answer-eval.sh
#   scripts/answer-eval.sh samples/eval/cases.json
#   scripts/answer-eval.sh --thresholds=0.5,0.6
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
# shellcheck source=scripts/jdk25.sh
. "$ROOT/scripts/jdk25.sh"

"$SERVER/mvnw" -q -f "$SERVER/pom.xml" compile dependency:build-classpath \
  -Dmdep.outputFile="$SERVER/target/classpath.txt" -Dmdep.includeScope=runtime

SERVER_WIN="$(cygpath -w "$SERVER" 2>/dev/null || echo "$SERVER")"

cd "$ROOT"
exec "$JDK25/bin/java" \
  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$SERVER_WIN\\target\\classes;$(cat "$SERVER/target/classpath.txt")" \
  com.ragkb.eval.AnswerEval "$@"
