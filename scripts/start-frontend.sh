#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$ROOT/scripts/nginx-hmdp.conf"
PID_FILE="$ROOT/logs/nginx.pid"

if ! command -v nginx >/dev/null 2>&1; then
  echo "nginx not found. Install with: brew install nginx"
  exit 1
fi

mkdir -p "$ROOT/logs"

# mime.types：从本机 nginx 安装目录复制到仓库 scripts/（不硬编码用户路径）
MIME_DST="$ROOT/scripts/mime.types"
if [ ! -f "$MIME_DST" ]; then
  if command -v brew >/dev/null 2>&1 && [ -f "$(brew --prefix)/etc/nginx/mime.types" ]; then
    cp "$(brew --prefix)/etc/nginx/mime.types" "$MIME_DST"
  elif [ -f /etc/nginx/mime.types ]; then
    cp /etc/nginx/mime.types "$MIME_DST"
  else
    echo "mime.types not found. Install nginx or copy mime.types to scripts/mime.types"
    exit 1
  fi
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  nginx -p "$ROOT" -c "$CONF" -s reload
  echo "Reloaded nginx (prefix=$ROOT)"
else
  nginx -s stop 2>/dev/null || true
  sleep 0.5
  nginx -t -p "$ROOT" -c "$CONF"
  nginx -p "$ROOT" -c "$CONF"
  echo "Started nginx (prefix=$ROOT)"
fi
echo "Frontend: http://127.0.0.1:8080/"
echo "API proxy: /api -> http://127.0.0.1:8081"
