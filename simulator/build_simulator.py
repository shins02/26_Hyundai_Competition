"""앱의 stories.json과 문구·경로·녹음 파일 목록을 시뮬레이터 HTML에 넣어 한 파일짜리 시뮬레이터를 만듭니다.

사용법: python3 simulator/build_simulator.py
결과:   simulator/SJStory_차량화면_시뮬레이터.html

직접 녹음한 음성을 쓰려면 simulator/audio/ 에 "<해시>.mp3" 파일을 넣고 다시 실행하세요.
파일 이름(해시)은 make_script.py가 만드는 대본 목록에 나옵니다.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SIM = ROOT / "simulator"

stories = json.loads((ROOT / "app/src/main/assets/stories.json").read_text(encoding="utf-8"))
phrases = json.loads((SIM / "phrases.json").read_text(encoding="utf-8"))
routes = json.loads((SIM / "routes.json").read_text(encoding="utf-8"))
routes.pop("_comment", None)
# 지도: Natural Earth(퍼블릭 도메인) 시·도 경계를 줄인 파일 (경도·위도)
korea = json.loads((SIM / "korea_map.json").read_text(encoding="utf-8"))

# 녹음 목소리: simulator/audio/<목소리>/<해시>.m4a → {목소리: {해시: 경로}}
EXT = (".mp3", ".wav", ".m4a", ".ogg")
voice_info = {v["id"]: v for v in json.loads((SIM / "voices.json").read_text(encoding="utf-8"))["voices"]}
audio = {}
audio_dir = SIM / "audio"
if audio_dir.exists():
    for d in sorted(p for p in audio_dir.iterdir() if p.is_dir()):
        clips = {f.stem.lower(): f"audio/{d.name}/{f.name}" for f in sorted(d.iterdir())
                 if f.suffix.lower() in EXT and len(f.stem) == 8}
        if clips:
            audio[d.name] = clips
    # 예전 방식(폴더 없이 audio/에 바로 넣은 파일, 예: CLOVA 더빙 수동 녹음)은 "녹음" 목소리로 묶습니다
    loose = {f.stem.lower(): f"audio/{f.name}" for f in sorted(audio_dir.iterdir())
             if f.is_file() and f.suffix.lower() in EXT and len(f.stem) == 8}
    if loose:
        audio["REC"] = loose
order = [v for v in voice_info if v in audio] + [v for v in audio if v not in voice_info]
voices = [{"id": v, "name": voice_info.get(v, {}).get("name", "직접 녹음한 목소리" if v == "REC" else v),
           "desc": voice_info.get(v, {}).get("desc", ""), "clips": len(audio[v])} for v in order]

# 시연용 문장은 용량을 줄여(48kbps) HTML 안에 직접 넣습니다 → 파일을 어디서 열어도 소리가 납니다
import base64
import csv
import subprocess

demo_keys = set()
demo_csv = SIM / "대본_시연용.csv"
if demo_csv.exists():
    demo_keys = {r["파일명"].split(".")[0] for r in csv.DictReader(demo_csv.open(encoding="utf-8-sig"))}
cache = SIM / ".embed_cache"
# 기본 목소리(첫 번째)는 시연용 문장 전체, 나머지 목소리는 미리 듣기 문장만 넣어 HTML 크기를 줄입니다
phr = json.loads((SIM / "phrases.json").read_text(encoding="utf-8"))


def fnv(text):
    import unicodedata
    h = 0x811C9DC5
    for ch in unicodedata.normalize("NFC", text).strip():
        h = ((h ^ ord(ch)) * 0x01000193) & 0xFFFFFFFF
    return f"{h:08x}"


preview_keys = {fnv(t) for t in phr["voiceTest"]}
embed = {}
for i, v in enumerate(order):
    for k in sorted((demo_keys if i == 0 else preview_keys) & set(audio[v])):
        src = SIM / audio[v][k]
        small = cache / v / f"{k}.m4a"
        if not small.exists() or small.stat().st_mtime < src.stat().st_mtime:
            small.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["afconvert", "-f", "m4af", "-d", "aac", "-b", "48000", str(src), str(small)], check=True)
        embed.setdefault(v, {})[k] = "data:audio/mp4;base64," + base64.b64encode(small.read_bytes()).decode()


def js(obj):
    return json.dumps(obj, ensure_ascii=False).replace("</", "<\\/")


out = (SIM / "template.html").read_text(encoding="utf-8")
for marker, value in [("/*__STORIES__*/[]", stories), ("/*__ROUTES__*/{}", routes),
                      ("/*__PHRASES__*/{}", phrases), ("/*__AUDIO__*/{}", audio), ("/*__VOICES__*/[]", voices), ("/*__EMBED__*/{}", embed), ("/*__KOREA__*/{}", korea)]:
    assert marker in out, marker
    out = out.replace(marker, js(value))
target = SIM / "SJStory_차량화면_시뮬레이터.html"
target.write_text(out, encoding="utf-8")
# localhost로 열 때 쓰는 영문 이름 사본 (음성 인식·마이크 권한은 http://localhost 에서 안정적으로 동작)
(SIM / "index.html").write_text(out, encoding="utf-8")
size = target.stat().st_size / 1e6
print(f"HTML {size:.1f}MB (시연용 녹음 {sum(len(x) for x in embed.values())}개 포함)")
# 대본의 모든 문장에 목소리마다 녹음이 있는지 확인합니다 (없으면 그 문장만 컴퓨터 음성으로 바뀌어 들림)
full_csv = SIM / "대본_전체.csv"
if full_csv.exists():
    rows = list(csv.DictReader(full_csv.open(encoding="utf-8-sig")))
    for v in (x for x in order if x in voice_info):
        miss = [r["문장"] for r in rows if r["파일명"].split(".")[0] not in audio[v]]
        if miss:
            print(f"⚠ {v}: 녹음 없는 문장 {len(miss)}개 (컴퓨터 음성으로 읽힘) → make_voice.py --voice {v} --all")
            for t in miss[:5]:
                print("   ·", t)
print(f"이야기 {len(stories)}편, 녹음 목소리 " + (", ".join(f"{v['id']}({v['clips']})" for v in voices) or "없음") + f" → {target}")
