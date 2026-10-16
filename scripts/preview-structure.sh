#!/usr/bin/env bash
# 结构预览:不启动 Spring、不连数据库,直接打印一个文件解析后的结构块与 token 统计。
#
# 用途:切片参数(target / max / overlap)必须由语料实测决定,
# 这个脚本给出决定这三个数字所需的数据 —— 标题层级分布、段落 token 分位、最长表格行。
#
# 用法:
#   scripts/preview-structure.sh PLAN.md
#   scripts/preview-structure.sh samples/*.pdf
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
# shellcheck source=scripts/jdk21.sh
. "$ROOT/scripts/jdk21.sh"

if [ "$#" -eq 0 ]; then
  echo "用法: scripts/preview-structure.sh <文件...>" >&2
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
