#!/usr/bin/env bash
# Signs an APK with the key in a keystore, as CI's release job does with the
# release key, with the Android SDK's apksigner (from its newest build-tools),
# then checks it's signed by that key and by nothing else. Every build runs it
# too, with a key made for the run, so a release never finds it broken. Prints
# the key's certificate SHA-256.
#
#   KEYSTORE=release.p12 KEYSTORE_PASSWORD=… [KEY_PASSWORD=…] [KEY_ALIAS=…] \
#     .github/sign-apk.sh in.apk out.apk
#
# KEY_PASSWORD is the keystore's where it isn't set (keytool makes PKCS12
# keystores with one password), and KEY_ALIAS is only needed where the keystore
# has more than one key.
set -euo pipefail

in="$1"
out="$2"
: "${KEYSTORE:?}" "${KEYSTORE_PASSWORD:?}"
export KEYSTORE_PASSWORD
export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
sign_alias=()
list_alias=()
if [ -n "${KEY_ALIAS:-}" ]; then
  sign_alias=(--ks-key-alias "$KEY_ALIAS")
  list_alias=(-alias "$KEY_ALIAS")
fi

tools="$(ls -d "${ANDROID_HOME:?}"/build-tools/*/ | sort -V | tail -n 1)"
tools="${tools%/}"
[ -x "$tools/apksigner" ] || { echo "No apksigner in $ANDROID_HOME/build-tools" >&2; exit 1; }
echo "apksigner from build-tools ${tools##*/}" >&2

# (What they say goes to the log, not into what this prints.)
"$tools/apksigner" sign --ks "$KEYSTORE" "${sign_alias[@]}" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD --out "$out" "$in" >&2
"$tools/zipalign" -c 4 "$out" >&2

key="$(keytool -list -v -keystore "$KEYSTORE" -storepass:env KEYSTORE_PASSWORD "${list_alias[@]}" |
  sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1 | tr -d ':' | tr 'A-F' 'a-f')"
signers="$("$tools/apksigner" verify --print-certs "$out" | sed -n 's/.*certificate SHA-256 digest: //p' | sort -u)"
if [ -z "$key" ] || [ "$signers" != "$key" ]; then
  echo "$out isn't signed by that key alone (it's signed by: ${signers:-nothing})." >&2
  exit 1
fi
echo "$key"
