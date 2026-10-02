#!/bin/sh
# Собирает debug-APK и ставит на подключённый по adb телефон.
# Использует окружение из .tools/ (см. setup.sh), если оно есть.
set -e
cd "$(dirname "$0")"
[ -d .tools/jdk ] && export JAVA_HOME="$PWD/.tools/jdk"
[ -d .tools/sdk ] && export ANDROID_HOME="$PWD/.tools/sdk"
[ -d .tools ] && export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
./gradlew --console=plain assembleDebug "$@"
adb=adb
[ -n "$ANDROID_HOME" ] && adb="$ANDROID_HOME/platform-tools/adb"
"$adb" install -r app/build/outputs/apk/debug/app-debug.apk
