"""녹음용 대본 목록을 만듭니다.

시뮬레이터가 말할 수 있는 모든 문장을 뽑아, 문장마다 녹음 파일 이름(해시)을 붙입니다.
녹음한 파일을 그 이름으로 simulator/audio/ 에 넣고 build_simulator.py를 다시 실행하면
시뮬레이터가 컴퓨터 음성 대신 녹음 파일을 재생합니다.

사용법: python3 simulator/make_script.py
결과:
  simulator/대본_전체.csv    모든 문장
  simulator/대본_시연용.csv  추천 시연 순서(영동고속도로 용인→이천, 장화홍련전, 쉼터 안내)에 나오는 문장만
"""
import csv
import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SIM = ROOT / "simulator"
stories = json.loads((ROOT / "app/src/main/assets/stories.json").read_text(encoding="utf-8"))
P = json.loads((SIM / "phrases.json").read_text(encoding="utf-8"))
routes = json.loads((SIM / "routes.json").read_text(encoding="utf-8"))
routes.pop("_comment", None)


def key(text: str) -> str:
    """시뮬레이터의 audioKey()와 같은 FNV-1a 32비트 해시"""
    h = 0x811C9DC5
    for ch in unicodedata.normalize("NFC", text).strip():
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return f"{h:08x}"


def js_str(x) -> str:
    """시뮬레이터(JavaScript)가 값을 글자로 바꾸는 방식과 똑같이: 6.0 → "6", 3.5 → "3.5" """
    if isinstance(x, float) and x.is_integer():
        return str(int(x))
    return str(x)


def fill(t: str, **v) -> str:
    return re.sub(r"\{(\w+)\}", lambda m: js_str(v.get(m.group(1), "")), t)


rows = {}  # text -> row


def add(text, scene, style="SYSTEM", demo=False):
    text = (text or "").strip()
    if not text:
        return
    row = rows.setdefault(text, {"file": key(text) + ".mp3", "scene": scene, "style": style, "demo": False, "text": text})
    row["demo"] = row["demo"] or demo


DEMO_STORIES = {"local-yongin-history", "local-icheon-history", "spooky-janghwa"}
DEMO_REGIONS = {"용인", "이천"}
DEMO_RESTS = {"덕평자연휴게소", "용인휴게소"}

# 1) 이야기 본문, 질문, 대답 해설
for s in stories:
    demo = s["id"] in DEMO_STORIES
    style = s["genre"]
    for i, part in enumerate(s.get("parts") or [s["text"]]):
        add(part, f"{s['title']} ({i + 1})", style, demo)
    it = s.get("interaction")
    if it:
        add(fill(P["question"], q=it["question"], o1=it["options"][0], o2=it["options"][1]), f"{s['title']} · 질문",
            "SPOOKY" if style == "SPOOKY" else "SYSTEM", demo)
        for i, r in enumerate(it["responses"]):
            add(r, f"{s['title']} · 대답 {i + 1}", style, demo)

# 2) 지역 소개 문구 (이야기가 있는 지역 + 경로의 지역)
regions = {re.sub(r"(시|군|구)$", "", s["region"]) for s in stories if s.get("region")}
for r in routes.values():
    regions |= {re.sub(r"(시|군|구)$", "", l["region"]) for l in r["legs"]}
for r in sorted(regions):
    for t in P["regionIntro"]:
        add(fill(t, r=r), "지역 소개", "SYSTEM", r in DEMO_REGIONS)

# 3) 쉼터 안내 (경로마다)
for r in routes.values():
    for l in r["legs"]:
        kind = "졸음쉼터" if "졸음쉼터" in l["rest"] else "휴게소"
        demo = l["rest"] in DEMO_RESTS
        v = dict(name=l["rest"], kind=kind, km=l["km"])
        add(fill(P["restDistance"], **v), "쉼터 안내", "SYSTEM", demo)
        add(fill(P["restYes"], **v), "쉼터 안내", "SYSTEM", demo)
        add(fill(P["restSilent"], **v), "쉼터 안내", "SYSTEM", demo)
        add(fill(P["arrive"], **v), "쉼터 도착", "SYSTEM", demo)
add(fill(P["arrive"], name="쉼터"), "쉼터 도착")

# 4) 고정 안내 문구
for name, value in P.items():
    if name.startswith("_") or name in ("regionIntro", "question", "restDistance", "restYes", "restSilent", "arrive"):
        continue
    lists = value.values() if isinstance(value, dict) else [value]
    for lst in lists:
        for t in lst:
            add(t, f"안내 · {name}", "SYSTEM", True)

ordered = sorted(rows.values(), key=lambda r: (not r["demo"], r["scene"]))
header = ["파일명", "장면", "말투", "시연용", "문장"]
for path, only_demo in [(SIM / "대본_전체.csv", False), (SIM / "대본_시연용.csv", True)]:
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        for r in ordered:
            if only_demo and not r["demo"]:
                continue
            w.writerow([r["file"], r["scene"], r["style"], "O" if r["demo"] else "", r["text"]])
total = len(rows)
demo = sum(1 for r in rows.values() if r["demo"])
chars = sum(len(r["text"]) for r in rows.values() if r["demo"])
print(f"전체 {total}문장 → 대본_전체.csv")
print(f"시연용 {demo}문장 ({chars}자) → 대본_시연용.csv")
