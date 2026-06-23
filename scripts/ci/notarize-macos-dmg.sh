#!/usr/bin/env bash
set -euo pipefail

if [[ "${MACOS_SIGNING_ENABLED:-false}" != "true" ]]; then
  echo "macOS signing was not enabled; skipping notarization."
  exit 0
fi

required=(
  APPLE_ID
  APPLE_TEAM_ID
  APPLE_APP_SPECIFIC_PASSWORD
  MACOS_SIGNING_KEY_USER_NAME
)

for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing ${name}; cannot notarize signed macOS DMG." >&2
    exit 1
  fi
done

dmg_count="$(find build/jpackage/installer -maxdepth 1 -name '*.dmg' -type f | wc -l | tr -d ' ')"
if [[ "${dmg_count}" != "1" ]]; then
  echo "Expected exactly one DMG in build/jpackage/installer, found ${dmg_count}." >&2
  find build/jpackage/installer -maxdepth 1 -type f -print >&2
  exit 1
fi

dmg="$(find build/jpackage/installer -maxdepth 1 -name '*.dmg' -type f -print -quit)"
identity="Developer ID Application: ${MACOS_SIGNING_KEY_USER_NAME}"

echo "Signing DMG: ${dmg}"
codesign --force --timestamp --sign "${identity}" "${dmg}"
codesign -dv --verbose=4 "${dmg}"

echo "Submitting DMG for Apple notarization."
xcrun notarytool submit "${dmg}" \
  --apple-id "${APPLE_ID}" \
  --team-id "${APPLE_TEAM_ID}" \
  --password "${APPLE_APP_SPECIFIC_PASSWORD}" \
  --wait

echo "Stapling notarization ticket."
xcrun stapler staple "${dmg}"
xcrun stapler validate "${dmg}"

echo "Verifying Gatekeeper assessment."
spctl -a -vv -t open --context context:primary-signature "${dmg}"

mount_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/popoto-dmg.XXXXXX")"
cleanup() {
  hdiutil detach "${mount_dir}" -quiet >/dev/null 2>&1 || true
  rmdir "${mount_dir}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

hdiutil attach "${dmg}" -mountpoint "${mount_dir}" -nobrowse -quiet
app="$(find "${mount_dir}" -maxdepth 1 -name '*.app' -type d -print -quit)"
if [[ -z "${app}" ]]; then
  echo "No .app found inside notarized DMG." >&2
  exit 1
fi

codesign -dv --verbose=4 "${app}"
spctl -a -vv -t exec "${app}"
