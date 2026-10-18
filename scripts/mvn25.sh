#!/usr/bin/env bash
# 用 JDK 25 跑 Maven(推荐入口,替代直接调用 mvn / mvnw)。
#
# 用法:
#   scripts/mvn25.sh compile
#   scripts/mvn25.sh test
#   scripts/mvn25.sh spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080"
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/jdk25.sh
. "$ROOT/scripts/jdk25.sh"

cd "$ROOT/server"
exec ./mvnw "$@"
