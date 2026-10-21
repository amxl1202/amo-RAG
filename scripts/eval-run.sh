#!/usr/bin/env bash
# 评估跑批:不启动 Spring、不连数据库,读数据集 -> 调检索 -> 输出指标 JSON。
#
# 用法:
#   scripts/eval-run.sh                      # 默认用 samples/eval/cases.json
#   scripts/eval-run.sh <cases.json>
#
# 现在检索还没实现,三个检索指标恒为 0 —— 这一步验证的是"数据集能读、校验能过、
# 指标能算、输出格式稳定"。检索接上之后只换 EvalRunner 里的 Retriever 实现。
#
# 输出:stdout 是 JSON,stderr 是指标口径说明,可以直接重定向:
#   scripts/eval-run.sh > report.json
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
# shellcheck source=scripts/jdk25.sh
. "$ROOT/scripts/jdk25.sh"

"$SERVER/mvnw" -q -f "$SERVER/pom.xml" compile dependency:build-classpath \
  -Dmdep.outputFile="$SERVER/target/classpath.txt" -Dmdep.includeScope=runtime

# java.exe 是原生 Windows 程序,认不了 Git Bash 的 /d/... 形式路径,这里转成 D:\...
SERVER_WIN="$(cygpath -w "$SERVER" 2>/dev/null || echo "$SERVER")"

# 切到仓库根目录,让默认数据集路径 samples/eval/cases.json 能按相对路径找到
cd "$ROOT"
exec "$JDK25/bin/java" \
  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$SERVER_WIN\\target\\classes;$(cat "$SERVER/target/classpath.txt")" \
  com.ragkb.eval.EvalMain "$@"
