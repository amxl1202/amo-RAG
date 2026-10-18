#!/usr/bin/env bash
# 被 source 使用:定位并导出 JDK 25。
#
# 为什么不用系统的 JAVA_HOME:它指向 IntelliJ 自带的 JBR —— 那是 IDE 的运行时,
# 换个编辑器就没了。项目必须有自己固定、可复现的构建 JDK。
#
# 覆盖方式:JDK25_HOME='D:\path\to\jdk-25' source scripts/jdk25.sh

JDK25_HOME="${JDK25_HOME:-D:\\java\\jdk-25.0.4.1+1}"
JDK25="${JDK25_HOME//\\//}"

if [ ! -x "$JDK25/bin/java.exe" ] && [ ! -x "$JDK25/bin/java" ]; then
  echo "[jdk25] 找不到 JDK 25: $JDK25_HOME" >&2
  echo "[jdk25] 用 JDK25_HOME=... 覆盖,或修改本文件中的默认路径" >&2
  return 1 2>/dev/null || exit 1
fi

export JDK25
export JAVA_HOME="$JDK25_HOME"
