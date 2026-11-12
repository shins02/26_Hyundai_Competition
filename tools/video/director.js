// 시뮬레이터 안에서 소개 영상 장면을 진행합니다. record.mjs가 페이지에 넣어 실행합니다.
// 시뮬레이터의 실제 함수만 호출합니다. 운전자 대답과 차량 신호는 시연 입력이고, 영상 자막에 그렇게 표시합니다.
(async () => {
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const until = async (fn, label, timeout = 90000) => {
    const end = performance.now() + timeout;
    while (!fn()) {
      if (performance.now() > end) throw new Error(`시간 초과: ${label} · ${JSON.stringify({ leg: S.legIndex, busy: S.busy, status: S.status, title: S.title })}`);
      await wait(50);
    }
  };
  const V = window.__video;
  const keep = on => V.keeps.push([on ? "on" : "off", Date.now()]);

  const style = document.createElement("style");
  style.textContent = `
    #panelBtn, #zoomBtn, #panel, #soundWarn { display: none !important; }
    #vCaption { position: fixed; left: 0; right: 0; bottom: 0; height: 98px; z-index: 50; background: rgba(4,6,10,.88); color: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; font-family: "Apple SD Gothic Neo", sans-serif; transition: opacity .25s; opacity: 0; }
    #vCaption.show { opacity: 1; }
    #vCaption b { font-size: 36px; font-weight: 700; letter-spacing: -.3px; }
    #vCaption span { font-size: 22px; color: #aeb9c9; margin-top: 3px; }
    #vFade { position: fixed; inset: 0; z-index: 60; background: #000; opacity: 0; pointer-events: none; transition: opacity .28s; }
    #vCard { position: fixed; inset: 0; z-index: 70; background: radial-gradient(90% 90% at 50% 40%, #16233b, #06080d 75%); color: #f3f5f8; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; font-family: "Apple SD Gothic Neo", sans-serif; transition: opacity .45s; }
    #vCard h1 { margin: 0; font-size: 112px; font-weight: 800; letter-spacing: -2px; }
    #vCard h2 { margin: 24px 0 0; font-size: 46px; font-weight: 600; line-height: 1.45; letter-spacing: -.5px; }
    #vCard p { margin: 46px 0 0; font-size: 27px; color: #94a3b8; line-height: 1.6; }
  `;
  document.head.appendChild(style);
  const box = (id) => { const d = document.createElement("div"); d.id = id; document.body.appendChild(d); return d; };
  const cap = box("vCaption"), fader = box("vFade"), cardEl = box("vCard");
  const caption = (main, sub = "") => { cap.innerHTML = `<b>${main}</b>${sub ? `<span>${sub}</span>` : ""}`; cap.classList.add("show"); };
  const noCaption = () => cap.classList.remove("show");
  const card = (title, line, note = "") => { cardEl.innerHTML = `${title ? `<h1>${title}</h1>` : ""}<h2>${line}</h2>${note ? `<p>${note}</p>` : ""}`; cardEl.style.opacity = 1; };
  // 뒤쪽 진행은 영상에서 빼고 건너뜁니다
  const jump = async (work) => {
    fader.style.opacity = 1; await wait(320);
    keep(false); await work(); keep(true);
    fader.style.opacity = 0; await wait(320);
  };
  const answer = async (words, ans) => {
    await until(() => S.pending && S.pending.listening, `대답 대기: ${words}`);
    await wait(500);
    S.heard = words; render();
    await wait(1000);
    submit(ans, "keyboard");
  };

  // ---------- 제목 ----------
  card("S J Story", "출발부터 주차까지 함께하는 차량용 음성 길동무", "Pleos Connect 앱 시연 · 실제 차량이 아닌 시뮬레이터 화면입니다");
  await wait(600); // 첫 화면이 그려진 뒤 녹화를 시작합니다
  window.__startScreencast("go");
  await wait(400);
  keep(true);
  await wait(3300);
  card("", "길 위에서는 지나는 고장의 이야기와 휴식 안내를,<br>주차 뒤에는 내 차 위치를.", "운전자는 화면 대신 말로 대답합니다");
  await wait(3000);

  // ---------- 출발 · 지역 이야기 ----------
  caption("출발하면 음성으로 인사합니다");
  P.greeting.splice(1); // 짧은 인사 문장으로 고정합니다
  $("goYeongdong").click();
  cardEl.style.opacity = 0;
  await until(() => V.clips.length >= 2, "안내 문장 시작");
  await jump(async () => {
    await until(() => S.activeStory && S.partIndex === 0, "용인 이야기 시작");
    caption("지나는 고장의 이야기를 들려줍니다", "이야기 43편 · 개그·오싹·생각·지역 · 녹음 목소리 4종");
  });
  await until(() => S.partIndex === 1, "용인 이야기 둘째 문단");
  await wait(2500);

  // ---------- 말로 대답하는 퀴즈 ----------
  await jump(async () => { await until(() => S.pending, "퀴즈 질문"); caption("주행 중에는 터치 대신 말로 대답합니다", "영상의 대답은 시연 입력입니다 · 시뮬레이터는 Chrome 음성 인식으로 동작"); });
  await answer("1번", { t: "opt", i: 0 });
  await until(() => !S.busy, "퀴즈 대답 끝");
  await wait(300);

  // ---------- 다음 지역 → 졸음 경고 → 쉼터 ----------
  await jump(async () => {
    if (S.legIndex < 1) nextLeg();
    await until(() => S.activeStory && S.legIndex >= 1, "이천 이야기 시작");
    caption("다음 지역에 들어서면 그 고장 이야기로 이어집니다");
  });
  await wait(2200);
  caption("차의 졸음 경고가 켜지면 이야기를 멈추고 쉼터를 제안합니다", "경고 신호는 시연 입력입니다");
  setAttention(true);
  await until(() => S.title && S.title.startsWith("🅿"), "쉼터 안내 시작");
  await answer("네", { t: "yes" });
  await until(() => S.waypoint, "경유지 반영");
  caption("운전자가 동의하면 경유지로 넣습니다", "내비 연동은 모의 동작입니다");
  await until(() => !S.busy, "경유지 안내 끝");
  await wait(1200);

  // ---------- 쉼터 도착 → 결말 ----------
  await jump(async () => {
    clearTimeout(arriveTimer);
    arrive();
    await until(() => S.pending, "결말 질문");
    caption("쉼터에 세우면(P단) 멈췄던 이야기의 결말을 들려줍니다");
  });
  await answer("네", { t: "yes" });
  await until(() => S.status && S.pending === null && S.speaking, "결말 시작");
  await wait(3500);

  // ---------- 밤: 장르 제안 ----------
  await jump(async () => {
    resume();
    setNight(true); tick();
    S.lastEnd = 0;
    await until(() => S.pending, "밤 모드 질문");
    caption("밤이 되면 분위기에 맞는 이야기를 제안합니다", "개그 · 오싹 · 생각 · 지역, 뒷좌석 동승자가 있으면 먼저 확인");
  });
  await answer("네", { t: "yes" });
  await until(() => S.activeStory && S.activeStory.genre === "SPOOKY", "오싹한 이야기 시작");
  await wait(4600);

  // ---------- 주차 ----------
  await jump(async () => {
    setNight(false); tick();
    S.demo = false; clearTimeout(legTimer);
    S.legIndex = route().legs.length - 1;
    Parking.start();
    caption("목적지에 도착하면, 이제 주차 위치를 기억합니다", "앞으로 가는 동안에는 글자를 읽지 않습니다");
  });
  await until(() => S.garage.gear === "R", "R단");
  caption("후진(R단)을 시작하면 빌트인캠이 기둥 글자를 읽습니다", "시연용 장면을 실제 문자 인식으로 읽습니다 · 차량 카메라 연동은 API 확인이 필요합니다");
  await until(() => S.garage.gear === "P", "P단");
  caption("주차(P단)를 마치면 층과 기둥 번호를 앱으로 알립니다", "기어와 영상은 모의 입력 · 휴대폰은 마이현대 연동을 가정한 화면입니다");
  await until(() => $("pkNoti").classList.contains("show"), "휴대폰 알림");
  V.ocr = { est: $("pkEst").textContent, log: $("pkLog").innerText };
  await wait(2700);
  $("pkNoti").click();
  await wait(3000);

  // ---------- 마무리 ----------
  noCaption();
  card("S J Story", "이야기로 함께 달리고, 쉬어 갈 곳을 권하고,<br>내려서는 내 차를 찾아 줍니다.", "현대자동차그룹 차량용 앱 개발 공모전 출품용 시연<br>목소리 Supertone Supertonic 2 · 지도 데이터 Natural Earth");
  await wait(4800);
  keep(false);
  return "done";
})();
