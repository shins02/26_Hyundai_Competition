"""무료 공개 음성 모델(Supertone 'Supertonic 2', OpenRAIL-M)로 대본을 녹음 파일로 만듭니다.

- 모델: https://huggingface.co/Supertone/supertonic-2 (한국어 지원, 무료·상업적 이용 가능, 사칭 등 금지 용도만 제한)
- 컴퓨터 안에서만 돌아갑니다 (인터넷 전송 없음). 결과는 simulator/audio/<목소리>/<해시>.m4a (목소리마다 폴더가 따로 있어, 시뮬레이터에서 고를 수 있습니다)

사용법 (gilstory 폴더에서):
  tools/voice/.venv/bin/python tools/voice/make_voice.py --samples          # 목소리 10종 비교 샘플 → tools/voice/samples/
  tools/voice/.venv/bin/python tools/voice/make_voice.py --voice F2         # 시연용 대본 녹음
  tools/voice/.venv/bin/python tools/voice/make_voice.py --voice F2 --all   # 전체 대본 녹음
  tools/voice/.venv/bin/python tools/voice/make_voice.py --voice F1 M1 M2 --all   # 여러 목소리 (시뮬레이터에서 골라 듣기)
  python3 simulator/build_simulator.py                                      # 시뮬레이터에 반영
"""
import argparse
import csv
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
from supertonic_helper import load_text_to_speech, load_voice_style  # noqa: E402

# 장르별 말투: 빠르기(1.0 기준, 클수록 빠름)
SPEED = {"LOCAL": 0.98, "COMEDY": 1.06, "SPOOKY": 0.88, "THINK": 0.92, "SYSTEM": 1.0}
VOICES = ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"]

DIGITS = "영일이삼사오육칠팔구"
UNITS = [(10000, "만"), (1000, "천"), (100, "백"), (10, "십")]


def sino(n: int) -> str:
    """정수를 한자어 수사로 읽습니다. 1232 → 천이백삼십이"""
    if n == 0:
        return "영"
    out = ""
    for value, name in UNITS:
        q, n = divmod(n, value)
        if q:
            out += ("" if q == 1 and value != 10000 else sino(q) if value == 10000 else DIGITS[q]) + name
    if n:
        out += DIGITS[n]
    return out


def normalize_numbers(text: str) -> str:
    """음성 모델이 숫자를 자연스럽게 읽도록 한글로 풀어 씁니다. (파일 이름 해시는 원문 기준)"""
    def decimal(m):
        a, b = m.group(1), m.group(2)
        return sino(int(a)) + " 점 " + " ".join(DIGITS[int(d)] for d in b)

    text = re.sub(r"(\d+)\.(\d+)", decimal, text)
    text = re.sub(r"\d+", lambda m: sino(int(m.group())), text)
    return text


def synth(tts, style, text: str, speed: float) -> np.ndarray:
    # 앱 이름은 글자 대신 소리로 읽게 바꿉니다 (파일 이름 해시는 원문 기준)
    text = text.replace("S J Story", "에스제이 스토리")
    wav, dur = tts(normalize_numbers(text), "ko", style, 10, speed, silence_duration=0.35)
    return wav[0, : int(tts.sample_rate * float(np.squeeze(dur)))]


def save_m4a(wav: np.ndarray, rate: int, target: Path, bitrate: int = 64000):
    """wav로 저장한 뒤 macOS 기본 도구(afconvert)로 m4a(AAC)로 줄입니다."""
    tmp = target.with_suffix(".wav")
    # 앞뒤에 짧은 여백을 주어 문장이 이어질 때 숨 쉬는 느낌을 냅니다
    pad = np.zeros(int(rate * 0.08), dtype=np.float32)
    sf.write(tmp, np.concatenate([pad, wav.astype(np.float32), pad]), rate)
    subprocess.run(["afconvert", "-f", "m4af", "-d", "aac", "-b", str(bitrate), str(tmp), str(target)], check=True)
    tmp.unlink()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voice", nargs="+", default=["F2"], choices=VOICES, help="목소리 (F=여성, M=남성), 여러 개 가능")
    ap.add_argument("--all", action="store_true", help="전체 대본 녹음 (기본: 시연용 대본)")
    ap.add_argument("--samples", action="store_true", help="목소리 10종 비교 샘플만 만들기")
    ap.add_argument("--force", action="store_true", help="이미 있는 파일도 다시 만들기")
    args = ap.parse_args()

    tts = load_text_to_speech(str(HERE / "model/onnx"))

    if args.samples:
        out = HERE / "samples"
        out.mkdir(exist_ok=True)
        text = "안녕하세요, S J Story예요. 지금 지나는 용인에는 작은 토성 하나가 남아 있어요. 1232년, 몽골 제국의 군대가 고려로 쳐들어왔어요. 자, 하나 물어볼게요."
        for v in VOICES:
            style = load_voice_style([str(HERE / f"model/voice_styles/{v}.json")])
            save_m4a(synth(tts, style, text, 1.0), tts.sample_rate, out / f"{v}.m4a")
            print(f"✔ 샘플 {v} → {out / (v + '.m4a')}")
        return

    script = ROOT / "simulator" / ("대본_전체.csv" if args.all else "대본_시연용.csv")
    rows = list(csv.DictReader(script.open(encoding="utf-8-sig")))
    for voice in args.voice:
        style = load_voice_style([str(HERE / f"model/voice_styles/{voice}.json")])
        audio = ROOT / "simulator/audio" / voice
        audio.mkdir(parents=True, exist_ok=True)
        made = 0
        for i, row in enumerate(rows, 1):
            target = audio / row["파일명"].replace(".mp3", ".m4a")
            if target.exists() and not args.force:
                continue
            save_m4a(synth(tts, style, row["문장"], SPEED.get(row["말투"], 1.0)), tts.sample_rate, target)
            made += 1
            if made % 25 == 0:
                print(f"  {voice} [{i}/{len(rows)}]")
        (audio / "VOICE.txt").write_text(
            f"목소리: Supertone Supertonic 2 ({voice}), OpenRAIL-M 라이선스\nhttps://huggingface.co/Supertone/supertonic-2\n",
            encoding="utf-8")
        print(f"✔ {voice}: 새로 만든 파일 {made}개 → {audio}")


if __name__ == "__main__":
    main()
