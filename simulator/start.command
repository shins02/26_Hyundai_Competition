#!/bin/bash
# 더블클릭하면 시뮬레이터를 Chrome에서 http://localhost:8791 로 열어 줍니다.
# (모든 문장의 녹음 목소리 재생과 마이크 음성 대답이 가장 안정적으로 동작하는 방법)

# 터미널 설정(PATH)이 깨져 있어도 기본 명령을 찾을 수 있게 직접 지정합니다
export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
PORT=8791
URL="http://localhost:$PORT/index.html"

# 이 파일이 있는 폴더(simulator)로 이동합니다. 실패하면 엉뚱한 폴더를 열지 않고 멈춥니다
cd "${0%/*}" || { echo "❌ 시뮬레이터 폴더로 이동하지 못했어요: ${0%/*}"; read -r -p "엔터를 누르면 닫혀요"; exit 1; }
[ -f build_simulator.py ] || { echo "❌ $(pwd) 에 build_simulator.py가 없어요"; read -r -p "엔터를 누르면 닫혀요"; exit 1; }

python3 build_simulator.py || echo "⚠️ 새로 만들기에 실패해서, 이전에 만든 시뮬레이터로 엽니다"

# 이미 켜져 있던 서버가 있으면 정리하고 새로 켭니다
lsof -ti tcp:$PORT | xargs kill 2>/dev/null
python3 -m http.server $PORT --bind 127.0.0.1 --directory "$(pwd)" >/dev/null 2>&1 &
SERVER=$!

# 서버가 시뮬레이터를 제대로 내보낼 때까지 기다렸다가 브라우저를 엽니다
OK=""
for i in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL")" = "200" ] && OK=1 && break
  sleep 0.3
done
if [ -z "$OK" ]; then
  echo "❌ 서버가 시뮬레이터를 열지 못했어요. 이 창의 내용을 알려 주세요."
  kill $SERVER 2>/dev/null
  read -r -p "엔터를 누르면 닫혀요"
  exit 1
fi

open -a "Google Chrome" "$URL" 2>/dev/null || open "$URL"
echo "✅ 시뮬레이터 실행 중: $URL  (이 창을 닫으면 종료)"
wait $SERVER
