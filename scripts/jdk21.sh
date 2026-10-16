#!/usr/bin/env bash
# 被 source 使用:定位并导出 JDK 21。
#
# 为什么不直接用系统的 JAVA_HOME:它指向 IntelliJ 自带的 JBR 25。
# 那是 IDE 的运行时,不是工程的构建/运行 JDK。用它编译虽然靠 --release 21 也能过,
# 但"项目跑在 Java 21 上"就变成一句没验证过的话,面试追问时站不住。
#
# 覆盖方式:JDK21_HOME='D:\path\to\jdk-21' source scripts/jdk21.sh

JDK21_HOME="${JDK21_HOME:-D:\\java\\jdk-21.0.12.1+1}"
JDK21="${JDK21_HOME//\\//}"

if [ ! -x "$JDK21/bin/java.exe" ] && [ ! -x "$JDK21/bin/java" ]; then
  echo "[jdk21] 找不到 JDK 21: $JDK21_HOME" >&2
  echo "[jdk21] 用 JDK21_HOME=... 覆盖,或修改本文件中的默认路径" >&2
  return 1 2>/dev/null || exit 1
fi

export JDK21
export JAVA_HOME="$JDK21_HOME"
