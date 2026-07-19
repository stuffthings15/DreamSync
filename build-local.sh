#!/usr/bin/env sh
# Builds both debug APKs.
set -e
cd "$(dirname "$0")"

if [ ! -f local.properties ]; then
    echo "Copy local.properties.example to local.properties and set sdk.dir first." >&2
    exit 1
fi

./gradlew :phone:assembleDebug :wear:assembleDebug

echo
echo "APKs:"
echo "  phone/build/outputs/apk/debug/phone-debug.apk"
echo "  wear/build/outputs/apk/debug/wear-debug.apk"
