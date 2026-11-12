#!/bin/bash
set -eu
cd "$(dirname "$0")"
app="주차메모.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources/Samples"
cp Samples/*.jpg "$app/Contents/Resources/Samples/"
cp Info.plist "$app/Contents/Info.plist"
/usr/bin/xcrun swiftc -O -parse-as-library -module-cache-path "${TMPDIR:-/tmp}/gil-parking-modules" ParkingReader.swift App.swift -o "$app/Contents/MacOS/ParkingDemo"
/usr/bin/codesign --force --sign - "$app"
