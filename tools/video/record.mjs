// 시뮬레이터 장면을 헤드리스 Chrome에서 녹화합니다. 사용자의 Chrome 프로필은 건드리지 않습니다.
// 사용법: node record.mjs <작업 폴더>   (시뮬레이터가 http://localhost:8791 에서 실행 중이어야 합니다)
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const work = process.argv[2];
const here = dirname(fileURLToPath(import.meta.url));
const framesDir = join(work, "frames");
mkdirSync(framesDir, { recursive: true });
const PORT = 9337;
const chrome = spawn("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", [
  "--headless=new", `--remote-debugging-port=${PORT}`, `--user-data-dir=${join(work, "chrome")}`,
  "--window-size=1920,1080", "--hide-scrollbars", "--no-first-run", "--mute-audio",
  "--autoplay-policy=no-user-gesture-required", "--disable-background-timer-throttling",
  "--disable-renderer-backgrounding", "--disable-backgrounding-occluded-windows", "about:blank",
], { stdio: "ignore" });
const quit = code => { try { chrome.kill(); } catch {} process.exit(code); };
const sleep = ms => new Promise(r => setTimeout(r, ms));

let target;
for (let i = 0; i < 60 && !target; i++) {
  await sleep(250);
  try { target = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find(t => t.type === "page"); } catch {}
}
if (!target) { console.error("Chrome에 연결하지 못했어요"); quit(1); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
let seq = 0;
const pending = new Map(), handlers = {};
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.id) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.fail(new Error(m.error.message)) : p.ok(m.result); }
  else handlers[m.method]?.(m.params);
};
const send = (method, params = {}) => new Promise((ok, fail) => { const id = ++seq; pending.set(id, { ok, fail }); ws.send(JSON.stringify({ id, method, params })); });

const frames = [];
let screencastStarted = false;
handlers["Page.screencastFrame"] = ({ data, metadata, sessionId }) => {
  const f = `${String(frames.length).padStart(6, "0")}.jpg`;
  writeFileSync(join(framesDir, f), Buffer.from(data, "base64"));
  frames.push({ f, t: metadata.timestamp });
  send("Page.screencastFrameAck", { sessionId }).catch(() => {});
};
handlers["Runtime.consoleAPICalled"] = ({ type, args }) => { if (type === "error") console.error("페이지 오류:", args.map(a => a.value ?? a.description).join(" ")); };
handlers["Runtime.exceptionThrown"] = ({ exceptionDetails }) => console.error("페이지 예외:", exceptionDetails.exception?.description || exceptionDetails.text);
handlers["Runtime.bindingCalled"] = async ({ name }) => {
  if (name !== "__startScreencast" || screencastStarted) return;
  screencastStarted = true;
  await send("Page.startScreencast", { format: "jpeg", quality: 88, maxWidth: 1920, maxHeight: 1080, everyNthFrame: 1 });
};

await send("Page.enable");
await send("Runtime.enable");
await send("Emulation.setDeviceMetricsOverride", { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
await send("Runtime.addBinding", { name: "__startScreencast" });
// 음성 인식은 시연 입력으로 대신하므로 빈 객체로 둡니다
await send("Page.addScriptToEvaluateOnNewDocument", { source: `
  class FakeRecognition { start() {} stop() {} abort() {} }
  Object.defineProperty(window, "webkitSpeechRecognition", { value: FakeRecognition, configurable: true });
  Object.defineProperty(window, "SpeechRecognition", { value: FakeRecognition, configurable: true });
` });
await send("Page.navigate", { url: "http://localhost:8791/index.html" });
await sleep(2500);

// 말하는 시각을 기록해, 나중에 원본 녹음 파일을 같은 시각에 넣습니다
await send("Runtime.evaluate", { expression: `
  window.__video = { keeps: [], clips: [], unrecorded: [] };
  { const clipFor0 = clipFor, playClip0 = playClip, sayOne0 = sayOne;
    let last = null;
    clipFor = (voice, text) => { last = { key: audioKey(text), voice, text }; return clipFor0(voice, text); };
    playClip = async (path, flow) => {
      const c = { ...last, t0: Date.now(), t1: null }; window.__video.clips.push(c);
      const how = await playClip0(path, flow); c.t1 = Date.now(); c.how = how; return how;
    };
    sayOne = (text, style, flow) => { window.__video.unrecorded.push(text); return sayOne0(text, style, flow); };
  }
  "ok"` });

const script = readFileSync(join(here, "director.js"), "utf8");
const timer = setTimeout(() => { console.error("녹화 시간 초과"); quit(1); }, 8 * 60 * 1000);
const r = await send("Runtime.evaluate", { expression: script, awaitPromise: true, returnByValue: true, timeout: 7 * 60 * 1000 });
clearTimeout(timer);
await send("Page.stopScreencast").catch(() => {});
await sleep(300);
if (r.exceptionDetails) { console.error("장면 진행 실패:", r.exceptionDetails.exception?.description || r.exceptionDetails.text); quit(1); }
const v = (await send("Runtime.evaluate", { expression: "JSON.stringify(window.__video)", returnByValue: true })).result.value;
const video = JSON.parse(v);
writeFileSync(join(work, "manifest.json"), JSON.stringify({ frames, ...video }, null, 1));
const ranges = [];
for (const [state, t] of video.keeps) { if (state === "on") ranges.push([t, null]); else ranges.at(-1)[1] = t; }
const total = ranges.reduce((a, [s, e]) => a + (e - s), 0) / 1000;
const span = frames.at(-1).t - frames[0].t;
console.log(JSON.stringify({ result: r.result.value, seconds: +total.toFixed(1), frames: frames.length, fps: +(frames.length / span).toFixed(1), clips: video.clips.length, unrecorded: video.unrecorded, ocr: video.ocr }, null, 1));
quit(0);
