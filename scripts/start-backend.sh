#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 优先使用环境变量；否则尝试 Homebrew openjdk@17（路径由 brew 解析，不写死用户目录）
if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  BREW_JDK="$(brew --prefix openjdk@17 2>/dev/null || true)"
  if [ -n "$BREW_JDK" ] && [ -d "$BREW_JDK/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="$BREW_JDK/libexec/openjdk.jdk/Contents/Home"
  fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if curl -sf -o /dev/null http://127.0.0.1:8081/shop-type/list; then
  echo "Backend already running on 8081"
  exit 0
fi
if [ ! -f target/hmdp-1.0-SNAPSHOT.jar ]; then
  echo "Jar not found. Run: mvn -DskipTests package"
  exit 1
fi
echo "Starting backend on 8081 (keep this terminal open)..."
exec java -jar target/hmdp-1.0-SNAPSHOT.jar
