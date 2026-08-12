#!/usr/bin/env bash
#
# Idempotent Cloud Agent setup for TowerScope AR.
#
# Prepares both runnable pieces of the repo:
#   * app/          – Android app built with the Gradle wrapper + Android SDK
#   * elevation-api/ – FastAPI LOS elevation service (Python venv)
#
# Safe to run repeatedly: every step checks for existing state before doing work.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="13114758"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"

log() { printf '\n=== %s ===\n' "$1"; }

log "System packages (python venv support)"
if ! dpkg -s python3-venv >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq python3-venv
else
  echo "python3-venv already installed"
fi

log "Android SDK command-line tools"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extracted"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mv "$tmp/extracted/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
else
  echo "cmdline-tools already present"
fi
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

log "Android SDK packages + licenses"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null
echo "installed platform-tools, platforms;android-36, build-tools;36.0.0"

log "local.properties (Gradle SDK location)"
if [ ! -f local.properties ] || ! grep -q '^sdk.dir=' local.properties; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" >> local.properties
  echo "wrote sdk.dir=$ANDROID_SDK_ROOT"
else
  echo "sdk.dir already configured"
fi

log "Build Android debug APK (warms Gradle + dependency caches)"
./gradlew :app:assembleDebug

log "elevation-api Python environment"
cd elevation-api
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
fi
# shellcheck disable=SC1091
. .venv/bin/activate
python -m pip install --upgrade pip -q
pip install -q -r requirements.txt
echo "elevation-api dependencies ready"

log "Setup complete"
