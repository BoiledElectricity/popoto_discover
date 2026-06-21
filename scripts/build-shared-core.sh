#!/bin/sh

set -eu

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" = "YES" ]; then
    echo "Skipping Gradle build because OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES"
    exit 0
fi

PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"

cd "$PROJECT_ROOT"

java_is_usable() {
    command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1
}

if [ -z "${JAVA_HOME:-}" ]; then
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
        if [ -n "$JAVA_HOME" ]; then
            export JAVA_HOME
            export PATH="$JAVA_HOME/bin:$PATH"
        fi
    fi
fi

if ! java_is_usable && command -v brew >/dev/null 2>&1; then
    BREW_OPENJDK_PREFIX="$(brew --prefix openjdk@17 2>/dev/null || true)"
    if [ -n "$BREW_OPENJDK_PREFIX" ] && [ -x "$BREW_OPENJDK_PREFIX/bin/java" ]; then
        export JAVA_HOME="$BREW_OPENJDK_PREFIX/libexec/openjdk.jdk/Contents/Home"
        export PATH="$BREW_OPENJDK_PREFIX/bin:$JAVA_HOME/bin:$PATH"
    fi
fi

if ! java_is_usable; then
    echo "Java runtime not found. Install a JDK before building SharedCore."
    exit 1
fi

export GRADLE_USER_HOME="${PROJECT_ROOT}/.gradle-xcode"
export KONAN_DATA_DIR="${PROJECT_ROOT}/.konan-local"
export GRADLE_OPTS="${GRADLE_OPTS:-} --enable-native-access=ALL-UNNAMED"

mkdir -p "$GRADLE_USER_HOME" "$KONAN_DATA_DIR"

./gradlew \
  --no-daemon \
  --stacktrace \
  -Dkonan.data.dir="$KONAN_DATA_DIR" \
  :shared-core:embedAndSignAppleFrameworkForXcode
