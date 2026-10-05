#!/bin/bash
# Build the root app_process jar: javac against the public android.jar (hidden APIs via reflection), d8 to dex.
# Needs a JDK 17+ and the Android SDK (build-tools + one platform). Override the paths with env vars if yours differ.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
BT=${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)}
AJ=${ANDROID_JAR:-$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)}
rm -rf out && mkdir -p out/cls
javac -nowarn --release 17 -cp "$AJ" -d out/cls src/*.java
"$BT/d8" --min-api 34 --lib "$AJ" --output out $(find out/cls -name '*.class')
(cd out && zip -q stwake.jar classes.dex)
echo "built out/stwake.jar"
