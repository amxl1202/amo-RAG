#!/usr/bin/env bash
# 用 JDK 21 跑 Maven(推荐入口,替代直接调用 mvn / mvnw)。
#
# 用法:
#   scripts/mvn21.sh compile
#   scripts/mvn21.sh spring-boot:run
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/jdk21.sh
. "$ROOT/scripts/jdk21.sh"

cd "$ROOT/server"
exec ./mvnw "$@"
