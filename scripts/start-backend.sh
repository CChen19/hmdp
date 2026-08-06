#!/bin/bash
set -e
export PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
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
