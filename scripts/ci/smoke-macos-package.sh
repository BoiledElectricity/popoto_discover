#!/usr/bin/env bash
set -euo pipefail

work_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/popoto-package.XXXXXX")"
mount_dir="${work_dir}/mounted"
cleanup() {
  hdiutil detach "${mount_dir}" -quiet >/dev/null 2>&1 || true
  rm -rf "${work_dir}"
}
trap cleanup EXIT

shopt -s nullglob
packages=(build/jpackage/installer/*.dmg)
[[ ${#packages[@]} == 1 ]] || { echo 'Expected exactly one DMG.' >&2; exit 1; }
mkdir "${mount_dir}"
hdiutil verify "${packages[0]}"
hdiutil attach "${packages[0]}" -mountpoint "${mount_dir}" -nobrowse -quiet
app="${work_dir}/Popoto Discover.app"
ditto "${mount_dir}/Popoto Discover.app" "${app}"
hdiutil detach "${mount_dir}" -quiet

if [[ "${MACOS_SIGNING_ENABLED:-false}" == true ]]; then
  codesign --verify --deep --strict --verbose=2 "${app}"
  spctl -a -vv -t exec "${app}"
fi

cli="${app}/Contents/MacOS/popoto-discover"
version="$("${cli}" version)"
printf '%s\n' "${version}"
grep -Fx "package: ${PACKAGE_VERSION:?}" <<< "${version}"
"${cli}" discover --transport udp --timeout 0.2 --retries 1
echo 'DMG verification, copied application launcher, bundled runtime, and UDP discovery pass.'
