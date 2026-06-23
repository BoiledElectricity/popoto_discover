#!/usr/bin/env bash
set -euo pipefail

required=(
  MACOS_DEVELOPER_ID_APPLICATION_P12_B64
  MACOS_DEVELOPER_ID_APPLICATION_P12_PASSWORD
  MACOS_SIGNING_KEY_USER_NAME
)

for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "macOS signing is disabled: ${name} is not set."
    echo "MACOS_SIGNING_ENABLED=false" >> "${GITHUB_ENV:?}"
    exit 0
  fi
done

keychain="${RUNNER_TEMP:?}/popoto-discover-signing.keychain-db"
certificate="${RUNNER_TEMP:?}/developer-id-application.p12"
password="$(uuidgen)"

printf '%s' "${MACOS_DEVELOPER_ID_APPLICATION_P12_B64}" | base64 -D > "${certificate}"

security create-keychain -p "${password}" "${keychain}"
security set-keychain-settings -lut 21600 "${keychain}"
security unlock-keychain -p "${password}" "${keychain}"
security import "${certificate}" \
  -k "${keychain}" \
  -P "${MACOS_DEVELOPER_ID_APPLICATION_P12_PASSWORD}" \
  -T /usr/bin/codesign \
  -T /usr/bin/productsign
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "${password}" "${keychain}"
security list-keychains -d user -s "${keychain}" $(security list-keychains -d user | sed 's/[ "]//g')

echo "Configured macOS signing keychain: ${keychain}"
security find-identity -v -p codesigning "${keychain}"

{
  echo "MACOS_SIGNING_ENABLED=true"
  echo "MACOS_SIGNING_KEYCHAIN=${keychain}"
} >> "${GITHUB_ENV:?}"
