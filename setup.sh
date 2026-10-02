#!/bin/sh
# Скачивает JDK 21 и Android SDK в .tools/ — ничего не ставится в систему.
set -e
cd "$(dirname "$0")"
mkdir -p .tools && cd .tools

if [ ! -x jdk/bin/javac ]; then
    echo "→ JDK 21"
    curl -fsSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
    tar xzf jdk.tgz && rm jdk.tgz && mv jdk-21* jdk
fi

if [ ! -x sdk/cmdline-tools/latest/bin/sdkmanager ]; then
    echo "→ Android command-line tools"
    curl -fsSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
    unzip -q clt.zip && rm clt.zip
    mkdir -p sdk/cmdline-tools && mv cmdline-tools sdk/cmdline-tools/latest
fi

export JAVA_HOME="$PWD/jdk"
SDKM="sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$PWD/sdk"
yes | $SDKM --licenses >/dev/null
$SDKM "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"

echo "sdk.dir=$PWD/sdk" > ../local.properties
echo "Готово. Сборка и установка на телефон: ./build.sh"
