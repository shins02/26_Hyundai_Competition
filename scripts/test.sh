#!/bin/bash
# 단위 테스트 실행.
# 프로젝트 경로에 한글이 있으면 Kotlin 테스트 컴파일이 앱 코드를 못 찾는 문제가 있어,
# 영문 경로의 임시 사본에서 테스트를 돌립니다. (APK 빌드는 원래 폴더에서 그대로 됩니다)
set -e
SRC="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/gilstory-test"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
rsync -a --delete --exclude build --exclude .gradle "$SRC/" "$TMP/"
cd "$TMP" && ./gradlew :app:testDebugUnitTest --console=plain
grep -h "<testsuite" app/build/test-results/testDebugUnitTest/*.xml | sed -E 's/.*name="([^"]+)" tests="([0-9]+)" skipped="[0-9]+" failures="([0-9]+)" errors="([0-9]+)".*/\1  테스트 \2개, 실패 \3, 오류 \4/'
