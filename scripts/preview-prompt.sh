#!/usr/bin/env bash
# 提示词预览:把文件的切片拼成提示词并打印出来。
#
# 为什么单独一个脚本:提示词只能靠人读一遍才算验收。单测能证明编号与正文的先后、
# 预算没超、没有截断,但证明不了读起来像不像话。
#
# 排序说明:这里按"问题词命中的个数"粗排,只为挑出相关的几条给人看,
# 不复刻检索层(向量 + 关键词 + RRF),也不参与评估。
#
# 用法:
#   scripts/preview-prompt.sh samples/rag-notes.pdf "固定长度切分的问题是什么?"
#   scripts/preview-prompt.sh --question="..." samples/a.pdf samples/b.md
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
# shellcheck source=scripts/jdk25.sh
. "$ROOT/scripts/jdk25.sh"

if [ "$#" -lt 2 ]; then
  echo "用法: scripts/preview-prompt.sh <文件...> \"问题\"" >&2
  exit 2
fi

"$SERVER/mvnw" -q -f "$SERVER/pom.xml" compile dependency:build-classpath \
  -Dmdep.outputFile="$SERVER/target/classpath.txt" -Dmdep.includeScope=runtime

SERVER_WIN="$(cygpath -w "$SERVER" 2>/dev/null || echo "$SERVER")"

exec "$JDK25/bin/java" \
  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$SERVER_WIN\\target\\classes;$(cat "$SERVER/target/classpath.txt")" \
  com.ragkb.generation.PromptPreview "$@"
