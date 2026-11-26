#!/bin/sh
# Builds PocketAgent.apk (public, no key) and PocketAgent-personal.apk (owner's key baked in),
# then proves the key is ONLY in the personal one. Never upload the personal APK anywhere.
set -u
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-C:/Users/varsa/.jdk/jdk-17.0.21.1+2}"
O=app/build/outputs/apk/release/app-release.apk
KEYFILE="$HOME/.pocket-agent-keys/personal-gemini-key.txt"
rm -f PocketAgent.apk PocketAgent-personal.apk
rm -f $O; ./gradlew.bat assembleRelease -Ppersonal --console=plain -q || exit 1; cp $O PocketAgent-personal.apk
rm -f $O; ./gradlew.bat assembleRelease --console=plain -q || exit 1; cp $O PocketAgent.apk
K="$(cat "$KEYFILE")"
if unzip -p PocketAgent.apk 'classes*.dex' | grep -q -F -a "$K"; then
  echo "DANGER: key found in the public APK. Deleting it."; rm -f PocketAgent.apk; exit 1
fi
unzip -p PocketAgent-personal.apk 'classes*.dex' | grep -q -F -a "$K" || { echo "personal APK has no key"; exit 1; }
echo "OK: PocketAgent.apk (public, no key) + PocketAgent-personal.apk (key inside, phone only)"
