#!/bin/bash
# Signs an unsigned release APK with Stillwave's release key.
#
# Usage: tools/sign-release.sh [--lineage] <unsigned.apk> <signed.apk>
#
# Signing material lives outside the repository, in $STILLWAVE_SIGNING_DIR
# (default ~/.stillwave). It must contain signing.properties with:
#   storeFile, storePassword, keyAlias      release key (PKCS12)
# and, for --lineage only:
#   legacyStoreFile, legacyStorePassword, legacyKeyAlias, lineage
#
# --lineage adds an APK Signature Scheme v3 rotation proof from the earlier
# pre-release key, so a device with a pre-release build installed can update
# in place (Android 9+). Published releases do not use it.
set -euo pipefail

LINEAGE=0
if [ "${1:-}" = "--lineage" ]; then LINEAGE=1; shift; fi
if [ $# -ne 2 ]; then
  echo "usage: $0 [--lineage] <unsigned.apk> <signed.apk>" >&2
  exit 2
fi
IN=$1
OUT=$2
DIR=${STILLWAVE_SIGNING_DIR:-$HOME/.stillwave}
PROPS=$DIR/signing.properties
[ -f "$PROPS" ] || { echo "missing $PROPS" >&2; exit 1; }

prop() { sed -n "s/^$1=//p" "$PROPS" | tail -1; }
resolve() { case $1 in /*) echo "$1" ;; *) echo "$DIR/$1" ;; esac; }

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)}

export STILLWAVE_RELEASE_PASS STILLWAVE_LEGACY_PASS
STILLWAVE_RELEASE_PASS=$(prop storePassword)
RELEASE=(--ks "$(resolve "$(prop storeFile)")" --ks-key-alias "$(prop keyAlias)"
  --ks-pass env:STILLWAVE_RELEASE_PASS --key-pass env:STILLWAVE_RELEASE_PASS)

# Sign Gradle's output as is: F-Droid copies this signature onto its own rebuild, which only
# verifies if the ZIP layout is untouched. No zipalign pass; apksigner keeps the existing alignment.
"$BT/zipalign" -c -p 4 "$IN" >/dev/null || { echo "$IN is not page-aligned; use Gradle's unsigned output" >&2; exit 1; }
ALIGNED=$IN

if [ $LINEAGE = 1 ]; then
  STILLWAVE_LEGACY_PASS=$(prop legacyStorePassword)
  "$BT/apksigner" sign \
    --ks "$(resolve "$(prop legacyStoreFile)")" --ks-key-alias "$(prop legacyKeyAlias)" \
    --ks-pass env:STILLWAVE_LEGACY_PASS --key-pass env:STILLWAVE_LEGACY_PASS \
    --next-signer "${RELEASE[@]}" \
    --lineage "$(resolve "$(prop lineage)")" --rotation-min-sdk-version 28 \
    --alignment-preserved true --out "$OUT" "$ALIGNED"
else
  "$BT/apksigner" sign "${RELEASE[@]}" --alignment-preserved true --out "$OUT" "$ALIGNED"
fi

"$BT/apksigner" verify --verbose --print-certs "$OUT" \
  | grep -E "Verified using v[23] |Signer .*SHA-256|Number of signers"
