import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

const html = readFileSync(new URL("../simulator/index.html", import.meta.url), "utf8");
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script); // Validate the complete generated entry point.

// Exercise the real session functions without a microphone, map renderer, or browser TTS.
function drive() {
  const context = vm.createContext({
    window: {}, performance: { now: () => 10000 },
    setTimeout, clearTimeout, heard: [], log: () => {}, render: () => {}
  });
  const run = source => vm.runInContext(source, context);
  run(script.slice(0, script.indexOf("const KOREA =")));
  run(`
    showSign = () => {};
    speak = async (words, flow) => { heard.push(words); return flow === S.flow; };
    speakSeq = async (words, flow) => { heard.push(...words); return flow === S.flow; };
    Object.assign(S, {running:true, parked:false, legIndex:0, busy:true, keepListening:true,
       activeStory:{title:"용인", text:"도착 후 결말", genre:"LOCAL"}});
    setAttention(true);
  `);
  return { run, context };
}

test("마지막 지역이 끝나면 주차로 넘어가고, 주차 중에는 이야기·휴식 안내가 나오지 않는다", () => {
  const {run, context} = drive();
  run(`
    cancelFlow();
    Object.assign(S, {running:false, restHold:false, savedStory:null, attention:false, demo:true});
    var Parking = { start() { heard.push("주차 시작"); S.garage = {gear:"D", speed:8}; } };
    S.legIndex = route().legs.length - 1;
    nextLeg();
  `);
  assert.ok(context.heard.includes("주차 시작"));
  assert.equal(run("S.demo"), false);
  const leg = run("S.legIndex");
  run("nextLeg(); start(); enterRest();");
  assert.equal(run("S.legIndex"), leg);
  assert.equal(run("S.running"), false);
  assert.equal(run("S.restHold"), false);
});

test("경고는 이어 듣기를 중단하고, 거절·무응답이면 경로를 유지한다", async () => {
  for (const reply of [null, {t:"no"}]) {
    const {run, context} = drive();
    assert.equal(run("S.restHold && !S.busy && S.savedStory.title === '용인'"), true);
    run("S.offerAfter = 0");
    const proposal = run("playNext()");
    run('submit({t:"yes"}, "touch")'); // D단 터치로는 동의할 수 없다.
    if (reply) run('submit({t:"no"}, "keyboard")');
    await proposal;
    assert.equal(run("S.waypoint"), false);
    const count = context.heard.length;
    await run("playNext()");
    assert.equal(context.heard.length, count);
  }
});

test("동의 뒤 모의 내비 반영과 P단을 거쳐야 선택한 결말이 나온다", async () => {
  const {run, context} = drive();
  run("S.offerAfter = 0");
  const proposal = run("playNext()");
  run('submit({t:"yes"}, "keyboard")');
  assert.equal(run("S.waypoint"), false);
  await proposal;
  assert.equal(run("S.waypoint && !S.resting"), true);
  assert.ok(!context.heard.includes("도착 후 결말"));
  const parked = run("arrive()");
  assert.equal(run("S.parked && S.resting"), true);
  run('submit({t:"opt",i:0}, "touch")');
  await parked;
  assert.equal(context.heard.filter(s => s === "도착 후 결말").length, 1);
  run("resume()");
  assert.equal(run("!S.resting && !S.parked"), true);
});
