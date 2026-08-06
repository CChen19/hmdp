#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$ROOT/scripts/nginx-hmdp.conf"
if ! command -v nginx >/dev/null 2>&1; then
  echo "nginx not found. Install with: brew install nginx"
  exit 1
fi
# Reload if already running with our pid, else start
if [ -f /tmp/hmdp-nginx.pid ] && kill -0 "$(cat /tmp/hmdp-nginx.pid)" 2>/dev/null; then
  nginx -s reload -c "$CONF"
  echo "Reloaded nginx with $CONF"
else
  # stop stray masters that may hold :8080
  nginx -s stop 2>/dev/null || true
  sleep 0.5
  nginx -t -c "$CONF"
  nginx -c "$CONF"
  echo "Started nginx with $CONF"
fi
echo "Frontend: http://127.0.0.1:8080/"
echo "API proxy: /api -> http://127.0.0.1:8081"
