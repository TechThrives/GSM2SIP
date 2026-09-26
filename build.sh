#!/bin/bash
#
# Build script for SIP-GSM Gateway APK + Magisk module.
#
# Prerequisites:
#   sudo ./setup.sh    # run once to install JDK, Android SDK, etc.
#
# Usage:
#   ./build.sh          # Build debug APK + Magisk module
#   ./build.sh release  # Build release APK + Magisk module
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Source environment if available ──────────────────

if [ -f "$SCRIPT_DIR/.env.build" ]; then
    source "$SCRIPT_DIR/.env.build"
fi

# ── Check prerequisites ──────────────────────────────

check_java() {
    if ! command -v java &>/dev/null; then
        echo "ERROR: Java not found. Run: sudo ./setup.sh"
        exit 1
    fi
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
        echo "ERROR: JDK 17+ required (found: $JAVA_VER). Run: sudo ./setup.sh"
        exit 1
    fi
    echo "Java: $(java -version 2>&1 | head -1)"
}

check_android_sdk() {
    # Find Android SDK
    if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
        for dir in "/opt/android-sdk" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
            if [ -d "$dir" ]; then
                export ANDROID_HOME="$dir"
                break
            fi
        done
    fi
    ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

    if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
        echo "ERROR: Android SDK not found. Run: sudo ./setup.sh"
        exit 1
    fi

    # Ensure local.properties exists
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    echo "Android SDK: $ANDROID_HOME"
}

check_gradle_wrapper() {
    local JAR="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
    local NEED_DOWNLOAD=false

    if [ ! -f "$JAR" ]; then
        NEED_DOWNLOAD=true
    elif ! unzip -t "$JAR" >/dev/null 2>&1; then
        echo "WARNING: gradle-wrapper.jar is corrupt, re-downloading..."
        rm -f "$JAR"
        NEED_DOWNLOAD=true
    fi

    if [ "$NEED_DOWNLOAD" = true ]; then
        echo "Downloading Gradle wrapper..."
        mkdir -p "$SCRIPT_DIR/gradle/wrapper"

        # Read the version from the wrapper properties: AGP 9.0 needs Gradle
        # 9.1+, so a hardcoded one could rebuild a wrapper this build rejects.
        local GRADLE_VER
        GRADLE_VER=$(sed -n 's|.*gradle-\([0-9][0-9.]*\)-bin\.zip.*|\1|p' \
            "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.properties" | head -1)
        if [ -z "$GRADLE_VER" ]; then
            echo "ERROR: cannot determine the Gradle version from gradle-wrapper.properties" >&2
            exit 1
        fi
        local GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip"

        curl -fsSL "$GRADLE_URL" -o /tmp/gradle-dist.zip
        rm -rf /tmp/gradle-extract
        mkdir -p /tmp/gradle-extract
        unzip -qo /tmp/gradle-dist.zip -d /tmp/gradle-extract

        local GRADLE_BIN="/tmp/gradle-extract/gradle-${GRADLE_VER}/bin/gradle"
        if [ -x "$GRADLE_BIN" ]; then
            cd "$SCRIPT_DIR"
            "$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VER"
        fi

        rm -f /tmp/gradle-dist.zip
        rm -rf /tmp/gradle-extract
    fi

    if [ ! -f "$JAR" ] || ! unzip -t "$JAR" >/dev/null 2>&1; then
        echo "ERROR: Valid gradle-wrapper.jar not found. Run: sudo ./setup.sh"
        exit 1
    fi

    chmod +x "$SCRIPT_DIR/gradlew" 2>/dev/null || true
}

# ── Build APK ────────────────────────────────────────

build_apk() {
    local BUILD_TYPE="${1:-debug}"
    echo ""
    echo "=== Building $BUILD_TYPE APK ==="
    echo ""

    if [ "$BUILD_TYPE" = "release" ]; then
        ./gradlew assembleRelease --no-daemon
        # Signed via the "shared" signingConfig, so the output is app-release.apk
        # rather than app-release-unsigned.apk.  An unsigned APK is rejected by
        # PackageManager with INSTALL_PARSE_FAILED_NO_CERTIFICATES and is
        # useless in the priv-app module, which is what the old path produced.
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
        [ -f "$APK_PATH" ] || APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    else
        ./gradlew assembleDebug --no-daemon
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    fi

    if [ -f "$APK_PATH" ]; then
        echo ""
        echo "APK built: $APK_PATH"
        cp "$APK_PATH" "$SCRIPT_DIR/gateway.apk"
        echo "Copied to: $SCRIPT_DIR/gateway.apk"
    else
        echo "ERROR: APK not found at $APK_PATH"
        exit 1
    fi
}

# ── Build Magisk module ─────────────────────────────

# Build one tinymix for one ABI.  $1 = GOARCH, $2 = expected `file` signature,
# $3 = output path.
build_tinymix_arch() {
    local GOARCH_="$1" SIG="$2" OUT="$3"
    local TINYMIX_SRC="$SCRIPT_DIR/tools/tinymix"

    # A pre-built binary of the right architecture is good enough
    if [ -f "$OUT" ] && file "$OUT" 2>/dev/null | grep -q "$SIG"; then
        echo "Using existing tinymix: $OUT"
        return 0
    fi

    if command -v go &>/dev/null && [ -f "$TINYMIX_SRC/main.go" ]; then
        echo "Building tinymix for $GOARCH_..."
        (cd "$TINYMIX_SRC" && GOOS=linux GOARCH="$GOARCH_" GOARM=7 CGO_ENABLED=0 \
            go build -ldflags='-s -w' -o "$OUT" .)
        if [ -f "$OUT" ]; then
            chmod 755 "$OUT"
            echo "tinymix built: $OUT ($(du -h "$OUT" | cut -f1))"
            return 0
        fi
    fi

    echo "WARNING: could not build tinymix for $GOARCH_ — mixer controls will"
    echo "         not work on $GOARCH_ devices.  Install Go, or drop a"
    echo "         pre-built binary at: $OUT"
    return 1
}

build_tinymix() {
    echo ""
    echo "=== Building tinymix (static, one per ABI) ==="
    echo ""

    # Two builds, because the ALSA control ioctls encode the size of structs
    # that contain `long`: an arm64 binary talks a different ioctl ABI than an
    # armeabi-v7a one, and neither works on the other's kernel.  install.sh
    # picks the matching one at flash time.
    #
    # `|| true`: a tinymix that will not build is survivable — the module still
    # flashes, only the mixer controls go unused — but `set -e` would abort.
    build_tinymix_arch arm64 "ARM aarch64" "$SCRIPT_DIR/magisk/tinymix" || true
    build_tinymix_arch arm   "ELF 32-bit.*ARM" "$SCRIPT_DIR/magisk/tinymix32" || true
}

# ── Stamp module.prop from the app version ───────────
#
# Magisk Manager compares module.prop's versionCode against the installed
# module's to decide if a zip is an update, and never reads the APK's own
# versionCode -- so a stale one here silently ships the old priv-app.
stamp_module_prop() {
    local gradle="$SCRIPT_DIR/app/build.gradle.kts"
    local prop="$SCRIPT_DIR/magisk/module.prop"

    local vc vn
    vc=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$gradle" | head -1)
    vn=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$gradle" | head -1)

    # Loud failure: a wrong versionCode is the exact bug this prevents.
    if [ -z "$vc" ] || [ -z "$vn" ]; then
        echo "ERROR: could not read versionCode/versionName from $gradle" >&2
        exit 1
    fi

    # Line by line, not `sed -i` (needs an arg on BSD).  Re-emitting every line
    # keeps the file uniformly LF with a trailing newline.
    local tmp line
    tmp=$(mktemp)
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        line=${line#$'\ufeff'}   # match Stamp-ModuleProp, which drops any BOM
        case "$line" in
            versionCode=*) printf 'versionCode=%s\n' "$vc" ;;
            version=*)     printf 'version=v%s\n' "$vn" ;;
            *)              printf '%s\n' "$line" ;;
        esac
    done < "$prop" > "$tmp"
    mv "$tmp" "$prop"
    echo "module.prop: version=v$vn versionCode=$vc (from build.gradle.kts)"
}

build_magisk() {
    echo ""
    echo "=== Building Magisk module ==="
    echo ""

    # Build tinymix binary for ABOX mixer control (required on Samsung Exynos)
    build_tinymix

    # Copy the APK into the Magisk module as a system priv-app.
    # This makes the app a privileged system app, enabling permissions
    # like CAPTURE_AUDIO_OUTPUT that are required for telephony audio capture.
    mkdir -p "$SCRIPT_DIR/magisk/system/priv-app/Gateway"
    cp "$SCRIPT_DIR/gateway.apk" "$SCRIPT_DIR/magisk/system/priv-app/Gateway/Gateway.apk"
    echo "Included APK as priv-app in Magisk module"

    stamp_module_prop

    cd "$SCRIPT_DIR/magisk"
    rm -f "$SCRIPT_DIR/gateway-magisk.zip"
    zip -r "$SCRIPT_DIR/gateway-magisk.zip" . \
        -x "*.DS_Store" -x "__MACOSX/*"
    echo "Magisk module: $SCRIPT_DIR/gateway-magisk.zip"
    cd "$SCRIPT_DIR"
}

# ── Install to device (if connected via ADB) ────────

install_to_device() {
    # Installing to "whatever is plugged in" is the wrong default once there is
    # more than one phone on the bus — the gateway has been installed onto the
    # wrong handset this way.  SKIP_INSTALL=1 builds the artifacts and stops.
    if [ -n "${SKIP_INSTALL:-}" ]; then
        echo ""
        echo "SKIP_INSTALL set — built artifacts only, nothing installed."
        return 0
    fi

    local COUNT
    # `|| true`: grep -c exits 1 on zero matches and a bare assignment inherits
    # that, so `set -e` would abort here on a machine with no adb — after a
    # perfectly good build.
    COUNT=$(adb devices 2>/dev/null | grep -c "device$" || true)
    if [ "${COUNT:-0}" -gt 1 ]; then
        echo ""
        echo "$COUNT devices connected — refusing to guess which one to install to."
        echo "Install explicitly:  adb -s <serial> install -r gateway.apk"
        return 0
    fi

    if command -v adb &>/dev/null && adb devices 2>/dev/null | grep -q "device$"; then
        echo ""
        echo "=== Device detected — installing ==="
        adb install -r "$SCRIPT_DIR/gateway.apk"
        echo "APK installed."
        echo ""
        echo "To install Magisk module:"
        echo "  adb push gateway-magisk.zip /sdcard/"
        echo "  Then install via Magisk Manager on the device."
    else
        echo ""
        echo "No ADB device connected. To install manually:"
        echo "  adb install gateway.apk"
        echo "  adb push gateway-magisk.zip /sdcard/"
    fi
}

# ── Main ─────────────────────────────────────────────

echo "=== SIP-GSM Gateway Build ==="
echo ""

check_java
check_android_sdk
check_gradle_wrapper

BUILD_TYPE="${1:-debug}"
build_apk "$BUILD_TYPE"
build_magisk
install_to_device

echo ""
echo "=== Build complete ==="
echo "  APK:    $SCRIPT_DIR/gateway.apk"
echo "  Magisk: $SCRIPT_DIR/gateway-magisk.zip"
echo ""
echo "Deploy to device:"
echo "  1. adb push gateway-magisk.zip /sdcard/"
echo "     Install via Magisk Manager -> Modules, then reboot"
echo "     (APK is included in the module as a priv-app)"
echo "  2. After reboot: open app, grant permissions, set as default phone app"
echo "  3. Enter SIP credentials, tap START"
echo ""
echo "NOTE: Do NOT also 'adb install' — the Magisk module installs the APK"
echo "      as a privileged system app with CAPTURE_AUDIO_OUTPUT permission."
