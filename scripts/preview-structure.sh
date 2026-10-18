#!/usr/bin/env bash
# 结构预览:不启动 Spring、不连数据库,直接打印一个文件的解析结果。
#
# 两种用法,分别对应两件事:
#   1) 定参数 —— 打印结构块与 token 分布(标题层级、段落分位、最长表格行),
#      切片参数 target / max / overlap 要根据这份数据决定;
#   2) 验收切片 —— 加 --chunks 调用切片器,把每一片打出来,
#      并自动校验"下标能否取回原文"与"index 是否连续"。
#
# 用法:
#   scripts/preview-structure.sh samples/rag-notes.pdf
#   scripts/preview-structure.sh --chunks samples/rag-notes.pdf
#   scripts/preview-structure.sh --chunks --splitter=<全限定类名> <文件>
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
# shellcheck source=scripts/jdk21.sh
. "$ROOT/scripts/jdk21.sh"

if [ "$#" -eq 0 ]; then
  echo "用法: scripts/preview-structure.sh [--chunks] [--splitter=<类名>] <文件...>" >&2
  exit 2
fi

"$SERVER/mvnw" -q -f "$SERVER/pom.xml" compile dependency:build-classpath \
  -Dmdep.outputFile="$SERVER/target/classpath.txt" -Dmdep.includeScope=runtime

# java.exe 是原生 Windows 程序,认不了 Git Bash 的 /d/... 形式路径,这里转成 D:\...
SERVER_WIN="$(cygpath -w "$SERVER" 2>/dev/null || echo "$SERVER")"

exec "$JDK21/bin/java" \
  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$SERVER_WIN\\target\\classes;$(cat "$SERVER/target/classpath.txt")" \
  com.ragkb.preview.StructurePreview "$@"
