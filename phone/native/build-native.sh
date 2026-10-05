#!/bin/bash
# Build the native helpers for the phone (aarch64, API 34) with the Android NDK.
# NDK defaults to the newest one under $ANDROID_SDK/ndk (or ~/Android/Sdk/ndk); set NDK= to override.
set -e; cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
NDK=${NDK:-$(ls -d "$SDK"/ndk/*/ | sort -V | tail -1)}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang"
"$CC" -O2 -o udkgen udkgen.c -ldl
"$CC" -O2 -o magread magread.c -landroid -lm
