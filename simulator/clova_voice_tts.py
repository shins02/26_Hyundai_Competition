"""네이버 클라우드 CLOVA Voice(Premium) API로 대본을 한꺼번에 녹음 파일로 만듭니다.

※ '유나(vyuna)', '아라(vara)' 같은 CLOVA Premium 화자로 자연스러운 음성을 만듭니다.
   연예인(셀럽) 목소리는 별도 계약 없이 쓰지 않습니다.

준비:
  1. 네이버 클라우드 플랫폼 콘솔에서 CLOVA Voice - Premium 이용 신청, 애플리케이션 등록
  2. 발급받은 Client ID / Client Secret을 환경 변수로 지정
       export NCP_CLIENT_ID=...
       export NCP_CLIENT_SECRET=...
사용법:
  python3 simulator/clova_voice_tts.py                 # 시연용 대본만 (요금 절약)
  python3 simulator/clova_voice_tts.py --all           # 전체 대본
  python3 simulator/clova_voice_tts.py --speaker vara  # 화자 바꾸기
그다음 python3 simulator/build_simulator.py

[확인 필요] 엔드포인트와 파라미터는 NCP 문서(CLOVA Voice Premium TTS API) 기준입니다. 요금은 글자 수 기준이니 콘솔에서 확인하세요.
"""
import argparse
import csv
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

SIM = Path(__file__).resolve().parent
URL = "https://naveropenapi.apigw.ntruss.com/tts-premium/v1/tts"

# 장르별 말투: speed는 -5(빠름) ~ 5(느림), emotion 0 중립 · 1 슬픔 · 2 기쁨 · 3 화남
STYLE = {
    "LOCAL": dict(speed=0, emotion=0),
    "COMEDY": dict(speed=-1, emotion=2),
    "SPOOKY": dict(speed=1, emotion=0),
    "THINK": dict(speed=1, emotion=0),
    "SYSTEM": dict(speed=0, emotion=0),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="전체 대본 생성")
    ap.add_argument("--speaker", default="vyuna", help="CLOVA Premium 화자 (기본 vyuna: 유나)")
    args = ap.parse_args()

    cid, secret = os.environ.get("NCP_CLIENT_ID"), os.environ.get("NCP_CLIENT_SECRET")
    if not cid or not secret:
        sys.exit("NCP_CLIENT_ID / NCP_CLIENT_SECRET 환경 변수를 지정해 주세요.")

    script = SIM / ("대본_전체.csv" if args.all else "대본_시연용.csv")
    out_dir = SIM / "audio"
    out_dir.mkdir(exist_ok=True)
    rows = list(csv.DictReader(script.open(encoding="utf-8-sig")))
    made = skipped = 0
    for row in rows:
        target = out_dir / row["파일명"]
        if target.exists():
            skipped += 1
            continue
        style = STYLE.get(row["말투"], STYLE["SYSTEM"])
        form = {"speaker": args.speaker, "volume": "0", "speed": str(style["speed"]), "pitch": "0",
                "format": "mp3", "text": row["문장"]}
        if args.speaker in ("vara", "vmikyung", "vdain", "vyuna", "vgoeun", "vdaeseong"):
            form["emotion"] = str(style["emotion"])
            form["emotion-strength"] = "1"
        req = urllib.request.Request(URL, data=urllib.parse.urlencode(form).encode(), headers={
            "X-NCP-APIGW-API-KEY-ID": cid, "X-NCP-APIGW-API-KEY": secret,
            "Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                target.write_bytes(res.read())
            made += 1
            print(f"✔ {row['파일명']}  {row['문장'][:30]}")
        except urllib.error.HTTPError as e:
            print(f"✘ {row['파일명']}  HTTP {e.code}: {e.read()[:200]!r}")
        time.sleep(0.2)
    print(f"새로 만든 파일 {made}개, 이미 있던 파일 {skipped}개 → {out_dir}")


if __name__ == "__main__":
    main()
