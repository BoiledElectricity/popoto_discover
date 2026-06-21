#!/bin/sh

set -eu

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" = "YES" ]; then
    echo "Skipping Gradle build because OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES"
    exit 0
fi

PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"

cd "$PROJECT_ROOT"

java_major_for_home() {
    "$1/bin/java" -version 2>&1 | awk -F'[".]' '/version/ { print ($2 == "1" ? $3 : $2); exit }'
}

java_is_usable() {
    if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
        return 1
    fi

    major="$(java -version 2>&1 | awk -F'[".]' '/version/ { print ($2 == "1" ? $3 : $2); exit }')"
    case "$major" in
        17|21) return 0 ;;
        *) return 1 ;;
    esac
}

use_java_home() {
    if [ -n "$1" ] && [ -x "$1/bin/java" ]; then
        major="$(java_major_for_home "$1")"
        case "$major" in
            17|21) ;;
            *) return 1 ;;
        esac
        export JAVA_HOME="$1"
        export PATH="$JAVA_HOME/bin:$PATH"
        return 0
    fi
    return 1
}

find_brew() {
    for candidate in /opt/homebrew/bin/brew /usr/local/bin/brew brew; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
    done
    return 1
}

BREW_BIN="$(find_brew || true)"
if [ -n "$BREW_BIN" ]; then
    BREW_OPENJDK_PREFIX="$("$BREW_BIN" --prefix openjdk@17 2>/dev/null || true)"
    if [ -n "$BREW_OPENJDK_PREFIX" ]; then
        use_java_home "$BREW_OPENJDK_PREFIX" || true
        use_java_home "$BREW_OPENJDK_PREFIX/libexec/openjdk.jdk/Contents/Home" || true
    fi
fi

if { [ -z "${JAVA_HOME:-}" ] || ! java_is_usable; } && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    use_java_home "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" || \
        use_java_home "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" || \
        use_java_home "$(/usr/libexec/java_home 2>/dev/null || true)" || true
fi

if ! java_is_usable; then
    echo "Java 17 or 21 runtime not found. Install a supported JDK before building SharedCore."
    exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME is not set. Install a supported JDK before building SharedCore."
    exit 1
fi

export GRADLE_USER_HOME="${PROJECT_ROOT}/.gradle-xcode"
export KONAN_DATA_DIR="${PROJECT_ROOT}/.konan-local"
export GRADLE_OPTS="${GRADLE_OPTS:-} --enable-native-access=ALL-UNNAMED"

mkdir -p "$GRADLE_USER_HOME" "$KONAN_DATA_DIR"

./gradlew \
  --no-daemon \
  --stacktrace \
  -Dorg.gradle.java.home="$JAVA_HOME" \
  -Dkonan.data.dir="$KONAN_DATA_DIR" \
  :shared-core:embedAndSignAppleFrameworkForXcode
