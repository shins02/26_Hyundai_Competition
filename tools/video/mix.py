"""녹화 기록(manifest.json)대로 원본 녹음 파일을 넣어 영상의 음성 트랙을 만듭니다.

사용법 (gilstory 폴더에서): tools/voice/.venv/bin/python tools/video/mix.py <작업 폴더>
결과: <작업 폴더>/voice.m4a
"""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

work = Path(sys.argv[1])
root = Path(__file__).resolve().parents[2]
m = json.loads((work / "manifest.json").read_text(encoding="utf-8"))
RATE = 44100

ranges, off = [], 0.0  # (원본 시작, 원본 끝, 영상 시작) 초
start = None
for state, t in m["keeps"]:
    t /= 1000
    if state == "on":
        start = t
    else:
        ranges.append((start, t, off))
        off += t - start
total = off
out = np.zeros(int(total * RATE) + RATE, dtype=np.float32)
cache = work / "wav"
cache.mkdir(exist_ok=True)

used = 0
for c in m["clips"]:
    src = root / "simulator/audio" / c["voice"] / f"{c['key']}.m4a"
    wav = cache / f"{c['voice']}_{c['key']}.wav"
    if not wav.exists():
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@44100", str(src), str(wav)], check=True)
    data, rate = sf.read(wav, dtype="float32")
    assert rate == RATE, rate
    if data.ndim > 1:
        data = data.mean(axis=1)
    t0 = c["t0"] / 1000
    t1 = c["t1"] / 1000 if c.get("t1") else t0 + len(data) / RATE
    if c.get("how") == "stop":
        data = data[: int((t1 - t0) * RATE)]
    clip_end = t0 + len(data) / RATE
    for a, b, o in ranges:
        s, e = max(a, t0), min(b, clip_end)
        if e <= s:
            continue
        seg = data[int((s - t0) * RATE): int((e - t0) * RATE)]
        at = int((o + s - a) * RATE)
        out[at: at + len(seg)] += seg
        used += 1

# 장면이 바뀌는 곳에서 소리가 툭 끊기지 않게 줄였다가 올립니다
fade = int(0.28 * RATE)
ramp = np.linspace(0, 1, fade, dtype=np.float32)
for a, b, o in ranges:
    i0, i1 = int(o * RATE), int((o + b - a) * RATE)
    if o > 0:
        out[i0: i0 + fade] *= ramp[: len(out[i0: i0 + fade])]
    tail = out[max(0, i1 - fade): i1]
    tail *= ramp[::-1][-len(tail):]

out = out[: int(total * RATE)]
peak = float(np.abs(out).max()) or 1.0
out *= 0.89 / peak
sf.write(work / "voice.wav", out, RATE, subtype="PCM_16")
subprocess.run(["afconvert", "-f", "m4af", "-d", "aac", "-b", "160000", str(work / "voice.wav"), str(work / "voice.m4a")], check=True)
print(f"음성 트랙 {total:.1f}초, 녹음 조각 {used}개, 원래 최대 음량 {peak:.2f}")
