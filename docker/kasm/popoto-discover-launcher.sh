#!/usr/bin/env bash
set -euo pipefail

for _ in $(seq 1 120); do
  [ -f "$POPOTO_JAR" ] && break
  sleep 1
done

if [ ! -f "$POPOTO_JAR" ]; then
  xmessage -center "Popoto Discover jar was not built. Check container logs."
  exit 1
fi

cd /config
exec java -jar "$POPOTO_JAR" gui
