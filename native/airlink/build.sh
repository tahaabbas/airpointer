#!/usr/bin/env bash
# Builds native/airlink (Rust + iroh) and copies the libraries where the apps pick them up:
#   desktop/AirPointer/native/<rid>/   (Windows DLL; Linux .so when run under Linux/WSL)
#   android/app/src/main/jniLibs/<abi>/ (needs: rustup targets + `cargo install cargo-ndk`, ANDROID_NDK_HOME)
# Usage: ./build.sh [windows|linux|android|all]   (default: all that this machine can build)
set -euo pipefail
cd "$(dirname "$0")"
root=../..
what=${1:-all}

# Keep build-machine paths (home folder, checkout location) out of the libraries' panic messages.
home=$HOME
here=$PWD
if command -v cygpath >/dev/null; then home=$(cygpath -w "$HOME"); here=$(cygpath -w "$PWD"); fi
export RUSTFLAGS="${RUSTFLAGS:-} --remap-path-prefix=$here=airlink --remap-path-prefix=$home=~"

if [[ $what == windows || $what == all ]] && [[ ${OS:-} == Windows_NT ]]; then
  cargo build --release
  mkdir -p $root/desktop/AirPointer/native/win-x64
  cp target/release/airlink.dll $root/desktop/AirPointer/native/win-x64/
fi

if [[ $what == linux || ( $what == all && $(uname -s) == Linux ) ]]; then
  cargo build --release --target x86_64-unknown-linux-gnu
  mkdir -p $root/desktop/AirPointer/native/linux-x64
  cp target/x86_64-unknown-linux-gnu/release/libairlink.so $root/desktop/AirPointer/native/linux-x64/
fi

if [[ $what == android || $what == all ]]; then
  out=$(mktemp -d)
  cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 24 -o "$out" build --release
  for abi in arm64-v8a armeabi-v7a x86_64; do
    mkdir -p $root/android/app/src/main/jniLibs/$abi
    cp "$out/$abi/libairlink.so" $root/android/app/src/main/jniLibs/$abi/
  done
  rm -rf "$out"
fi
