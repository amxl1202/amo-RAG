#!/usr/bin/env bash
# 固定用 JDK 21 构建/运行。
#
# 为什么需要这个脚本:系统 JAVA_HOME 指向 IntelliJ 自带的 JBR 25,那是 IDE 运行时,
# 不是项目构建 JDK。直接跑 mvnw 会拿到 25 —— 编译虽能过(靠 --release 21),
# 但「项目跑在 21 上」这句话就变成未经证实的断言,面试时经不起追问。
# 这里显式指定,并把 Maven 启动 JDK 与编译目标 JDK 统一到 21。
set -e

JDK21="${JDK21_HOME:-D:\\java\\jdk-21.0.12.1+1}"

if [ ! -x "${JDK21//\\//}/bin/java" ] && [ ! -f "${JDK21//\\//}/bin/java.exe" ]; then
  echo "[mvn21] 找不到 JDK 21: $JDK21" >&2
  echo "[mvn21] 用 JDK21_HOME=/path/to/jdk-21 覆盖" >&2
  exit 1
fi

export JAVA_HOME="$JDK21"
cd "$(dirname "$0")/../server"
exec ./mvnw "$@"
