#!/usr/bin/env bash
# Checks that an APK carries the fork release identity and no MBTA Sentry DSN.
# CI runs this before signing. Run it locally the same way:
#   fork/android/release/check-apk.sh androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk
# Optional second argument: the expected versionName (CI passes the X.Y.Z from the tag).
set -euo pipefail

expected_package='com.mbta.tid.mbta_app.mbestavros'
expected_label='MBTA Go (mbestavros)'

apk="${1:?usage: $0 <apk> [expected versionName]}"
expected_version="${2:-}"
[ -f "${apk}" ] || { echo "check-apk: no such file: ${apk}" >&2; exit 2; }
: "${ANDROID_HOME:?check-apk: set ANDROID_HOME}"
build_tools="$(ls -d "${ANDROID_HOME}"/build-tools/* | sort -V | tail -n 1)"
aapt2="${build_tools}/aapt2"

failures=0
fail() {
  echo "check-apk: FAIL: $*" >&2
  failures=$((failures + 1))
}

badging="$("${aapt2}" dump badging "${apk}")"
package_line="$(printf '%s\n' "${badging}" | grep -m 1 '^package:')"
package="$(printf '%s\n' "${package_line}" | sed -n "s/.* name='\([^']*\)'.*/\1/p")"
version_name="$(printf '%s\n' "${package_line}" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
label="$(printf '%s\n' "${badging}" | sed -n "s/^application-label:'\(.*\)'$/\1/p")"

[ "${package}" = "${expected_package}" ] || fail "package is '${package}', expected '${expected_package}'"
[ "${label}" = "${expected_label}" ] || fail "label is '${label}', expected '${expected_label}'"
# AppVersion.parse() accepts only X.Y.Z. The app crashes at launch on anything else.
printf '%s' "${version_name}" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || fail "versionName is '${version_name}', expected X.Y.Z"
if [ -n "${expected_version}" ] && [ "${version_name}" != "${expected_version}" ]; then
  fail "versionName is '${version_name}', but the tag says '${expected_version}'"
fi

# The meta-data value line follows its name line in the xmltree dump.
manifest="$("${aapt2}" dump xmltree --file AndroidManifest.xml "${apk}")"
printf '%s\n' "${manifest}" \
  | grep -A 1 '="firebase_analytics_collection_deactivated"' \
  | grep -Eq ':value\([^)]*\)=(true|"true"|\(type 0x12\)0xffffffff)' \
  || fail "firebase_analytics_collection_deactivated is not true"

# A Sentry DSN is https://<32 hex>@host/project. None may be compiled in.
if unzip -p "${apk}" 'classes*.dex' | LC_ALL=C grep -aEq 'https://[0-9a-f]{32}@'; then
  fail "the dex files contain a Sentry DSN"
fi

if [ "${failures}" -gt 0 ]; then
  exit 1
fi
echo "check-apk: OK: ${package} '${label}' ${version_name}"
