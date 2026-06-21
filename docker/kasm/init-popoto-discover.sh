#!/usr/bin/with-contenv bash
set -euo pipefail

mkdir -p "$POPOTO_CACHE" "$POPOTO_WORK"
mkdir -p /downloads /config/Desktop
ln -sfn /downloads /config/Desktop/Downloads
git config --global --add safe.directory "$POPOTO_WORK" || true

if [ ! -d "$POPOTO_WORK/.git" ]; then
  find "$POPOTO_WORK" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  git clone --branch "$POPOTO_BRANCH" --single-branch "$POPOTO_REPO" "$POPOTO_WORK"
fi

cd "$POPOTO_WORK"
git fetch origin "$POPOTO_BRANCH"
LATEST_SHA="$(git rev-parse "origin/$POPOTO_BRANCH")"
CACHED_SHA="$(cat "$POPOTO_CACHE/source.sha" 2>/dev/null || true)"

if [ ! -f "$POPOTO_JAR" ] || [ "$LATEST_SHA" != "$CACHED_SHA" ]; then
  echo "Popoto Discover source changed: ${CACHED_SHA:-none} -> $LATEST_SHA"
  git checkout -f "$POPOTO_BRANCH"
  git reset --hard "origin/$POPOTO_BRANCH"
  ./gradlew --no-daemon clean shadowJar
  JAR="$(find build/libs -maxdepth 1 -type f -name "*.jar" | sort | tail -n 1)"
  test -n "$JAR"
  cp "$JAR" "$POPOTO_JAR"
  printf "%s\n" "$LATEST_SHA" > "$POPOTO_CACHE/source.sha"
else
  echo "Popoto Discover jar is current at $LATEST_SHA"
fi

chmod -R a+rX "$POPOTO_CACHE" "$POPOTO_WORK" || true
