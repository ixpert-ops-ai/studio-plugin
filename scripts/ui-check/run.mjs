// B-58 보충: 접힌 "제외된 후보 N개" 패널을 headless Chrome + CDP로 확인한다(의존성 추가 없음, Node 내장 http/WebSocket만 사용).
// 운영 코드와 산출물은 바꾸지 않는다. 모의 브리지는 페이지 로드 전에 주입하고, 백엔드가 /restore에 주는 응답 형식(restored, ignored, removedCandidates)을 그대로 따른다.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

// B-78: 스크립트는 git이 추적하는 scripts/ui-check에 둔다. 실행 산출물(스크린샷, 결과, Chrome 프로필)만 build/ui-check(git 제외)에 쓴다.
const HERE = import.meta.dirname;
const ROOT = path.resolve(HERE, '../..').replace(/\\/g, '/');
const OUT = ROOT + '/build/ui-check';
fs.mkdirSync(OUT, { recursive: true });
const WEB = path.resolve(ROOT + '/src/main/resources/webview');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PORT_WEB = 8765, PORT_CDP = 9333;
const data = JSON.parse(fs.readFileSync(HERE + '/data.json', 'utf8'));
const sleep = ms => new Promise(r => setTimeout(r, ms));

// 1) 정적 서버(Node 내장 http)
const server = http.createServer((req, res) => {
  const file = path.resolve(WEB, '.' + (req.url === '/' ? '/index.html' : req.url.split('?')[0]));
  if (!file.startsWith(WEB) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': file.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream' });
  res.end(fs.readFileSync(file));
});
await new Promise(r => server.listen(PORT_WEB, '127.0.0.1', r));

// 2) headless Chrome
const chrome = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--user-data-dir=${OUT}/profile`, `--remote-debugging-port=${PORT_CDP}`, '--window-size=1100,1000', 'about:blank'], { stdio: 'ignore' });
const results = [];
const observations = [];
const shots = [];
let ws, nextId = 1; const pending = new Map();
function cleanup() { try { ws?.close(); } catch {} try { chrome.kill(); } catch {} try { server.close(); } catch {} }

try {
  let target;
  for (let i = 0; i < 60 && !target; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT_CDP}/json/list`)).json();
      target = list.find(t => t.type === 'page');
    } catch {}
    if (!target) await sleep(250);
  }
  if (!target) throw new Error('Chrome DevTools 대상 없음');
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
  const send = (method, params = {}) => new Promise(res => { const id = nextId++; pending.set(id, res); ws.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expr => { const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 1200)); return r.result?.result?.value; };
  const shot = async name => { const r = await send('Page.captureScreenshot', { format: 'png' }); const f = `${OUT}/${name}.png`; fs.writeFileSync(f, Buffer.from(r.result.data, 'base64')); shots.push(f); return f; };
  let clicks = 0;
  const click = async (selectorExpr, label) => {
    const pos = await evaluate(`(()=>{const el=(${selectorExpr}); if(!el) return null; el.scrollIntoView({block:'center'}); const r=el.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    if (!pos) throw new Error('클릭 대상 없음: ' + label);
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: pos.x, y: pos.y });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: pos.x, y: pos.y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: pos.x, y: pos.y, button: 'left', clickCount: 1 });
    clicks++;
    await sleep(120);
  };
  const post = obj => evaluate(`window.postMessage(${JSON.stringify(obj)}, '*'), true`);
  const record = (id, item, ok, evidence) => { results.push({ id, item, ok, evidence }); console.log(ok ? '됨  ' : '안 됨', id, item, '|', evidence); };

  // 3) 모의 브리지(페이지 로드 전 주입): 웹뷰가 보낸 요청을 기록하고 /restore에는 백엔드와 같은 형식으로 응답한다.
  const bridge = `
    window.__sent = []; window.__backendList = []; window.__extraIgnored = [];
    window.sendToIde = function (data) {
      window.__sent.push(data);
      try {
        const o = JSON.parse(data);
        if (o.command === '/restore') {
          const paths = JSON.parse(o.text).paths || [];
          const list = window.__backendList;
          const restored = paths.filter(p => list.some(c => c.path === p));
          const ignored = paths.filter(p => !list.some(c => c.path === p)).concat(window.__extraIgnored);
          window.__backendList = list.filter(c => !restored.includes(c.path));
          setTimeout(() => window.postMessage({ type: 'ai_message', subType: 'analyze_restore', messageId: o.requestId,
            content: JSON.stringify({ restored, ignored, removedCandidates: window.__backendList }) }, '*'), 40);
        }
        if (o.command === '/clarify-utterance') {
          window.__utteranceCount = (window.__utteranceCount || 0) + 1;
          setTimeout(() => {
            if (window.__utteranceMode === 'error') window.postMessage({ type: 'ai_message', subType: 'error', messageId: 'clarify_utt_err', content: '발화 처리 중 오류가 발생했습니다: 모의 오류' }, '*');
            else window.postMessage({ type: 'ai_message', subType: 'analyze_clarify', messageId: 'clarify_utt_' + Date.now(), content: JSON.stringify(window.__formPayload) }, '*');
          }, window.__delayMs || 0);
        }
        if (o.command === '/analyze-confirm') {
          if (window.__confirmMode === 'failOnce' && !window.__failedOnce) {
            window.__failedOnce = true;
            setTimeout(() => window.postMessage({ type: 'ai_message', subType: 'error', messageId: 'analyze_fail1', content: '분석을 시작하지 못했습니다: 연결 실패' }, '*'), 50);
          } else if (window.__confirmMode === 'expired') {
            setTimeout(() => window.postMessage({ type: 'ai_message', subType: 'error', messageId: 'analyze_exp', content: '분석 세션이 만료되었거나 유효하지 않습니다. /clarify를 다시 실행해주세요.' }, '*'), 50);
          } else {
            setTimeout(() => window.postMessage({ type: 'ai_message', subType: 'analyze_start', messageId: 'analyze_C1', content: '분석을 시작합니다' }, '*'), window.__confirmDelayMs || 0);
          }
        }
      } catch (e) {}
    };`;
  await send('Page.enable'); await send('Runtime.enable');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: bridge });
  await send('Page.navigate', { url: `http://127.0.0.1:${PORT_WEB}/` });
  for (let i = 0; i < 80; i++) { if (await evaluate(`document.readyState==='complete' && (document.querySelector('#root')?.children.length||0)>0`)) break; await sleep(250); }
  await sleep(500);
  await shot('00-loaded');

  // 4) 결과 메시지: Kotlin 브리지가 보내는 순서 그대로(analyze_start -> task_chunk -> chat -> analyze_removed)
  const ID = 'analyze_UI1';
  const deliver = async (id, withRemoved) => {
    await post({ type: 'ai_message', subType: 'analyze_start', messageId: id, content: '분석을 시작합니다' });
    await post({ type: 'ai_message', subType: 'task_chunk', messageId: id, content: '### 요구사항 분석 요약\n\n- 분석 결과 본문(자동 확인용)\n', isFirst: true });
    await post({ type: 'ai_message', subType: 'chat', messageId: id, content: '' });
    await sleep(200);
    if (withRemoved) await post({ type: 'ai_message', subType: 'analyze_removed', messageId: id, content: JSON.stringify({ candidates: data }) });
    await sleep(300);
  };
  await evaluate(`window.__backendList = ${JSON.stringify(data)}, true`);
  await deliver(ID, true);
  const panel = `document.querySelector('[data-message-id="${ID}"] .removed-candidates')`;
  const titleBtn = n => `[...document.querySelectorAll('[data-message-id="${ID}"] .removed-candidates button')].find(b=>b.textContent.includes('제외된 후보 ${n}개'))`;
  const restoreBtn = `[...document.querySelectorAll('[data-message-id="${ID}"] .removed-candidates button')].find(b=>b.textContent.includes('선택한 파일 되살리기'))`;
  const items = `[...document.querySelectorAll('[data-message-id="${ID}"] .removed-candidates-list > div')]`;
  const textOf = sel => evaluate(`(${sel})?.innerText ?? null`);

  // 시나리오 1: 접힌 상태
  const s1 = await evaluate(`(()=>{const b=${titleBtn(14)}; return {has:!!b, expanded:b?.getAttribute('aria-expanded'), list:!!document.querySelector('[data-message-id="${ID}"] .removed-candidates-list'), text:b?.textContent};})()`);
  await shot('01-collapsed');
  record(1, '"제외된 후보 14개"가 접힌 상태로 표시', s1.has && s1.expanded === 'false' && !s1.list, JSON.stringify(s1));

  // 클릭 1: 펼침
  clicks = 0;
  await click(titleBtn(14), '펼침');
  await shot('02-expanded');
  const s2 = await evaluate(`(()=>{const it=${items}; return {n:it.length, titles:it.map(d=>d.title), badges:it.map(d=>d.innerText.includes('제외 요청')), icons:it.map(d=>!!d.querySelector('svg')), names:it.map(d=>d.innerText.split('\\n')[0])};})()`);
  const orderOk = JSON.stringify(s2.titles) === JSON.stringify(data.map(d => d.path));
  const badgeOk = s2.badges.slice(0, 2).every(Boolean) && s2.badges.slice(2).every(b => !b);
  record(2, '체크박스·파일명이 받은 순서대로, USER_EXCLUDED 2개가 맨 앞에서 "제외 요청" 배지', s2.n === 14 && orderOk && badgeOk && s2.icons.every(Boolean), `항목 ${s2.n}개, 순서 일치=${orderOk}, 배지=${JSON.stringify(s2.badges)}, 앞 3개 이름=${s2.names.slice(0, 3)}`);

  // 시나리오 3: 스크롤
  const s3 = await evaluate(`(()=>{const l=document.querySelector('[data-message-id="${ID}"] .removed-candidates-list'); return {sh:l.scrollHeight, ch:l.clientHeight, ov:getComputedStyle(l).overflowY};})()`);
  record(3, '10개를 넘어 목록 영역이 스크롤됨(scrollHeight > clientHeight)', s3.sh > s3.ch && s3.ov === 'auto', JSON.stringify(s3));

  // 시나리오 4: 버튼 비활성 -> 체크 후 활성
  const before = await evaluate(`${restoreBtn}.disabled`);
  const pickIdx = 4; // 5번째 항목(STAGE3)
  await click(`${items}[${pickIdx}]`, '체크');
  await shot('03-checked');
  const after = await evaluate(`(()=>{const d=${items}[${pickIdx}]; return {disabled:${restoreBtn}.disabled, svg:d.querySelector('svg').getAttribute('class')};})()`);
  record(4, '체크 전 버튼 비활성, 1개 체크하면 활성', before === true && after.disabled === false, `체크 전 disabled=${before}, 체크 후 disabled=${after.disabled}, 아이콘 class=${after.svg}`);

  // 시나리오 5: 확정 -> /restore 요청 내용
  await click(restoreBtn, '확정');
  await sleep(500);
  await shot('04-restored-1');
  const sent = await evaluate(`window.__sent.filter(s=>s.includes('/restore')).map(s=>JSON.parse(s))`);
  const req = sent[sent.length - 1];
  const reqPaths = req ? JSON.parse(req.text).paths : null;
  record(5, '/restore 요청에 경로 1개와 결과 메시지 id', !!req && req.command === '/restore' && req.requestId === ID && JSON.stringify(reqPaths) === JSON.stringify([data[pickIdx].path]), JSON.stringify(req));

  // 시나리오 6: 응답 후 안내와 제목 13개
  const s6 = await evaluate(`(()=>{const p=${panel}; return {text:p?.innerText, title:${titleBtn(13)}?.textContent, stillListed:${items}.some(d=>d.title===${JSON.stringify(data[pickIdx].path)}), n:${items}.length};})()`);
  record(6, '"1개를 되살렸습니다. 구현 대상에 포함됩니다." 안내와 제목 13개', !!s6.text && s6.text.includes('1개를 되살렸습니다. 구현 대상에 포함됩니다.') && !!s6.title && !s6.stillListed && s6.n === 13, `제목=${s6.title}, 항목 ${s6.n}개, 되살린 항목 목록에 남음=${s6.stillListed}`);

  // 시나리오 7: 클릭 3회
  record(7, '펼침·체크·확정 3번의 클릭으로 끝남', clicks === 3, `접힌 상태에서 되살림까지 dispatch한 클릭 ${clicks}회`);

  // 시나리오 8: 무시된 경로 안내
  await evaluate(`window.__extraIgnored = ['src/gone/Old.java'], true`);
  await click(`${items}[3]`, '체크2');
  await click(restoreBtn, '확정2');
  await sleep(500);
  await shot('05-ignored-notice');
  const s8 = await textOf(panel);
  record(8, '"<파일명>은(는) 되살릴 수 없었습니다" 안내', !!s8 && s8.includes('Old.java은(는) 되살릴 수 없었습니다') && s8.includes('1개를 되살렸습니다'), JSON.stringify(s8?.split('\n').slice(-3)));
  await evaluate(`window.__extraIgnored = [], true`);

  // 시나리오 9: 모두 되살리면 패널이 사라지고 안내만 남음
  const remaining = await evaluate(`${items}.length`);
  for (let i = 0; i < remaining; i++) await click(`${items}[${i}]`, '전체 체크 ' + i);
  await shot('06-all-checked');
  await click(restoreBtn, '전체 확정');
  await sleep(600);
  await shot('07-all-restored');
  const s9 = await evaluate(`(()=>{const m=document.querySelector('[data-message-id="${ID}"]'); return {panel:!!m.querySelector('.removed-candidates'), notice:m.querySelector('.removed-candidates-notice')?.innerText ?? null};})()`);
  record(9, '모두 되살리면 패널이 사라지고 안내 줄만 남음', !s9.panel && !!s9.notice && s9.notice.includes(`${remaining}개를 되살렸습니다`), `패널 있음=${s9.panel}, 안내=${JSON.stringify(s9.notice)} (마지막 되살림 ${remaining}개)`);

  // 시나리오 10: analyze_removed가 없는 예전 결과에는 패널이 없음
  await deliver('analyze_OLD', false);
  await shot('08-old-result');
  const s10 = await evaluate(`(()=>{const m=document.querySelector('[data-message-id="analyze_OLD"]'); return {msg:!!m, text:m?.innerText?.slice(0,60), panel:m ? m.querySelectorAll('.removed-candidates, .removed-candidates-notice').length : -1};})()`);
  record(10, 'analyze_removed가 없는 예전 결과에는 패널이 없음', s10.msg && s10.panel === 0, JSON.stringify(s10));

  // ── 대기 구간 확인(11~16): 새 페이지에 대화 카드를 띄우고 응답을 지연시킨다 ──
  await send('Page.navigate', { url: `http://127.0.0.1:${PORT_WEB}/` });
  for (let i = 0; i < 80; i++) { if (await evaluate(`document.readyState==='complete' && (document.querySelector('#root')?.children.length||0)>0`)) break; await sleep(250); }
  await sleep(500);
  const formPayload = { originalRequirement: '금칙어 등록 시 비슷한 단어도 막아 주세요', summaryTurn: 1, items: [], openQuestion: null, isExhausted: false, isReadyForStage1: false, taskSummary: null, excludedFiles: [], pendingExclusions: [], dialogueHistory: [] };
  await evaluate(`window.__formPayload = ${JSON.stringify(formPayload)}, window.__delayMs = 2500, window.__utteranceMode = 'ok', true`);
  await post({ type: 'ai_message', subType: 'analyze_clarify', messageId: 'clarify_1', content: JSON.stringify(formPayload) });
  await sleep(400);
  const form = `document.querySelector('[data-message-id="clarify_1"]')`;
  const inputSel = `${form}.querySelector('input[type="text"]')`;
  const setText = async t => { await evaluate(`(()=>{const i=${inputSel}; const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set; set.call(i, ${JSON.stringify(t)}); i.dispatchEvent(new Event('input',{bubbles:true})); return true})()`); await sleep(100); };
  const sendBtn = `[...${form}.querySelectorAll('button')].find(b=>b.textContent.includes('지시 전송')||b.textContent.includes('해석 중'))`;
  const waitText = `(${form}?.querySelector('.clarify-wait')?.innerText ?? null)`;
  const idle0 = await evaluate(`${waitText}`);
  await shot('09-form-idle');

  // 11) 발화 전송 후 응답이 올 때까지 표시가 유지된다(기존 600ms 상태가 아님: 전송 1.2초 뒤에도 보인다)
  await setText('띄어쓰기를 없앤 뒤 같으면 같은 단어로 보세요');
  await click(sendBtn, '지시 전송');
  await sleep(1200);
  const s11 = await evaluate(`(()=>{const f=${form}; return {wait:${waitText}, disabled:f.querySelector('input[type="text"]').disabled, btn:[...f.querySelectorAll('button')].map(b=>b.textContent).filter(t=>t.includes('해석')||t.includes('지시')).join('|'), sent:window.__utteranceCount};})()`);
  await shot('10-interpreting');
  record(11, '발화 전송 뒤 응답 전까지 "요청을 해석하는 중입니다"와 로딩 표시(1.2초 뒤에도 유지), 입력 비활성', idle0 === null && !!s11.wait && s11.wait.includes('요청을 해석하는 중입니다') && s11.disabled === true && s11.sent === 1, JSON.stringify({ 전송전: idle0, ...s11 }));

  // 12) 표시 중 연속 전송은 막힌다(요청이 늘지 않는다)
  await evaluate(`(()=>{const i=${inputSel}; i.removeAttribute('disabled'); return true})()`).catch(() => {});
  const blocked = await evaluate(`(()=>{const f=${form}; const b=${sendBtn}; return {btnDisabled:b.disabled, sent:window.__utteranceCount};})()`);
  record(12, '대기 중 연속 전송은 막힘(버튼 비활성, 서버로 간 요청 1건 유지)', blocked.btnDisabled === true && blocked.sent === 1, JSON.stringify(blocked));

  // 13) 응답(analyze_clarify)이 오면 표시가 내려간다(새 대화 카드에는 대기 표시가 없다)
  await sleep(1800);
  const s13 = await evaluate(`(()=>{const forms=[...document.querySelectorAll('[data-message-id^="clarify_"]')]; return {forms:forms.length, waits:document.querySelectorAll('.clarify-wait').length, text:document.body.innerText.includes('요청을 해석하는 중입니다')};})()`);
  await shot('11-interpreted');
  record(13, '해석 응답이 오면 표시가 내려감', s13.waits === 0 && s13.text === false && s13.forms >= 1, JSON.stringify(s13));

  // 14) 오류가 오면 표시가 내려가고 입력이 다시 켜진다(발화 카드가 그대로 남는 경우)
  await evaluate(`window.__utteranceMode = 'error', window.__delayMs = 1500, true`);
  const form2 = `document.querySelector('[data-message-id^="clarify_utt_"]')`;
  const input2 = `${form2}.querySelector('input[type="text"]')`;
  const setText2 = async t => { await evaluate(`(()=>{const i=${input2}; const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set; set.call(i, ${JSON.stringify(t)}); i.dispatchEvent(new Event('input',{bubbles:true})); return true})()`); await sleep(100); };
  const sendBtn2 = `[...${form2}.querySelectorAll('button')].find(b=>b.textContent.includes('지시 전송')||b.textContent.includes('해석 중'))`;
  await setText2('오류 경로 확인');
  await click(sendBtn2, '지시 전송(오류 경로)');
  await sleep(500);
  const mid = await evaluate(`(()=>{const f=${form2}; return f.querySelectorAll('.clarify-wait').length;})()`);
  await sleep(1500);
  const s14 = await evaluate(`(()=>{const f=${form2}; return {waits:f.querySelectorAll('.clarify-wait').length, inputDisabled:f.querySelector('input[type="text"]').disabled, fixed:document.body.innerText.includes('요청을 처리하지 못했습니다. 다시 시도해 주세요.'), leak:document.body.innerText.includes('모의 오류'), bubbles:document.querySelectorAll('[data-message-id^="error_"]').length};})()`);
  await shot('12-error');
  record(14, '오류가 오면 표시가 내려가고 입력이 다시 켜지며, 오류 문구(고정 문구)가 보이고 예외 원문은 안 보임', mid === 1 && s14.waits === 0 && s14.inputDisabled === false && s14.fixed === true && s14.leak === false && s14.bubbles >= 1, JSON.stringify({ 대기중: mid, ...s14 }));

  // 15) 확인 뒤 기존 문구는 그대로이고 로딩 표시와 경과 초가 붙는다
  await evaluate(`window.__confirmDelayMs = 4000, true`);
  const confirmBtn = `[...${form2}.querySelectorAll('button')].find(b=>b.textContent.includes('확인 완료'))`;
  await click(confirmBtn, '확인 완료');
  await sleep(1300);
  const e1 = await evaluate(`(()=>{const f=${form2}; return {text:f.innerText.includes('✓ 확인이 제출되었습니다. 분석을 진행 중입니다...'), wait:f.querySelector('.clarify-wait')?.innerText ?? null};})()`);
  await sleep(2000);
  const e2 = await evaluate(`(()=>{const f=${form2}; return f.querySelector('.clarify-wait')?.innerText ?? null;})()`);
  await shot('13-confirming');
  const n1 = e1.wait ? parseInt((e1.wait.match(/경과 (\d+)초/) || [])[1]) : NaN;
  const n2 = e2 ? parseInt((e2.match(/경과 (\d+)초/) || [])[1]) : NaN;
  const factOnly = !/%|남은|예상|단계/.test((e1.wait || '') + (e2 || ''));
  record(15, '확인 뒤 기존 문구 유지 + 로딩 표시와 경과 초 증가(퍼센트·예상 시간 없음)', e1.text && n1 >= 1 && n2 > n1 && factOnly, JSON.stringify({ 문구유지: e1.text, 첫표시: e1.wait, 둘째표시: e2, 사실만: factOnly }));

  // 16) 분석 시작 메시지가 오면 표시가 내려가고 제출 문구는 남는다
  await sleep(1500);
  const s16 = await evaluate(`(()=>{const f=${form2}; return {waits:f.querySelectorAll('.clarify-wait').length, text:f.innerText.includes('✓ 확인이 제출되었습니다.'), analysisMsg:!!document.querySelector('[data-message-id="analyze_C1"]')};})()`);
  await shot('14-analysis-started');
  record(16, '분석 시작 메시지가 오면 표시가 내려가고 제출 문구는 남음', s16.waits === 0 && s16.text === true && s16.analysisMsg === true, JSON.stringify(s16));

  // 17) 고정 안내 오류(세션 만료)는 원문 그대로, messageId 없는 오류도 보인다
  const bodyText = () => evaluate(`document.body.innerText`);
  const bubblesBefore = await evaluate(`document.querySelectorAll('[data-message-id^="error_"]').length`);
  await post({ type: 'ai_message', subType: 'error', messageId: 'clarify_utt_1799999999', content: '분석 세션이 만료되었거나 유효하지 않습니다. `/clarify`를 다시 실행해주세요.' });
  await sleep(300);
  await post({ type: 'ai_message', subType: 'error', content: '선택한 디렉토리에 분석 가능한 소스 파일이 없습니다.' });
  await sleep(300);
  const t17 = await bodyText();
  await shot('15-errors-dropped-before');
  const bubblesAfter = await evaluate(`document.querySelectorAll('[data-message-id^="error_"]').length`);
  record(17, '짝 없는 고정 안내 오류(세션 만료)와 messageId 없는 오류가 원문 그대로 보임(오류 말풍선 2개 증가)', t17.includes('분석 세션이 만료되었거나 유효하지 않습니다.') && t17.includes('선택한 디렉토리에 분석 가능한 소스 파일이 없습니다.') && bubblesAfter - bubblesBefore === 2, JSON.stringify({ 증가: bubblesAfter - bubblesBefore }));

  // 18) 연속 오류는 각각 말풍선이 되고 ERROR 표시가 붙는다. 내부 정보가 섞인 오류는 고정 문구만 보인다
  await post({ type: 'ai_message', subType: 'error', messageId: 'clarify_utt_1799999998', content: '발화 처리 중 오류가 발생했습니다: java.lang.IllegalStateException: 내부 상세 http://internal.example/x' });
  await post({ type: 'ai_message', subType: 'error', messageId: 'clarify_utt_1799999997', content: '발화 처리 중 오류가 발생했습니다: 또 다른 내부 상세' });
  await sleep(400);
  const t18 = await bodyText();
  const bubbles18 = await evaluate(`document.querySelectorAll('[data-message-id^="error_"]').length`);
  const badges = await evaluate(`document.querySelectorAll('[data-message-id^="error_"] .error-badge').length`);
  await shot('16-errors-consecutive');
  record(18, '연속 오류 2건이 각각 말풍선(ERROR 표시)이 되고 내부 상세(예외 이름, 주소)는 화면에 안 나옴', bubbles18 - bubblesAfter === 2 && badges >= 2 && !t18.includes('IllegalStateException') && !t18.includes('internal.example') && !t18.includes('또 다른 내부 상세'), JSON.stringify({ 증가: bubbles18 - bubblesAfter, ERROR표시: badges }));

  // 19) 오류가 아닌 메시지 처리는 그대로: 짝 없는 task_success는 말풍선을 만들지 않는다
  const msgCount = await evaluate(`document.querySelectorAll('[data-message-role]').length`);
  await post({ type: 'ai_message', subType: 'task_success', messageId: 'nomatch_1', content: '짝 없는 완료' });
  await sleep(300);
  const msgCount2 = await evaluate(`document.querySelectorAll('[data-message-role]').length`);
  record(19, '오류가 아닌 짝 없는 메시지(task_success)는 여전히 말풍선을 만들지 않음', msgCount2 === msgCount, JSON.stringify({ 전: msgCount, 후: msgCount2 }));

  // ───────── 분석 시작 전 정제문 확인 카드(20~25) ─────────
  const CARD = `document.querySelector('[data-refined-confirm="true"]')`;
  const ACT = a => `document.querySelector('[data-refined-confirm-action="${a}"]')`;
  const EDIT = `document.querySelector('[data-refined-confirm-edit="true"]')`;
  const RC_ORIG = '설문 발송 채널에 브랜드메시지 추가';
  const RC_REFINED = 'SurveyServiceImpl만 수정하여 설문 발송 채널에 브랜드메시지를 추가하세요.';
  const rcRequest = () => post({ type: 'ai_message', subType: 'refinedConfirmation/request', messageId: 'system', content: JSON.stringify({ projectId: 'proj1', originalRequirement: RC_ORIG, refinedRequirement: RC_REFINED, message: '분석을 시작하기 전에 정제된 요구사항을 확인해 주세요.' }) });
  const rcSent = () => evaluate(`window.__sent.map(s=>JSON.parse(s)).filter(o=>typeof o.command==='string'&&o.command.startsWith('refinedConfirmation/'))`);
  const typeInto = async text => {
    await evaluate(`(()=>{const el=${EDIT}; el.focus(); el.select(); return true})()`);
    await send('Input.insertText', { text });
    await sleep(150);
  };
  const setBlank = () => evaluate(`(()=>{const el=${EDIT}; const set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set; set.call(el,''); el.dispatchEvent(new Event('input',{bubbles:true})); return true})()`);
  const sentBase = (await rcSent()).length;

  // 20) 카드 표시: 원문과 정제문이 나란히 보이고, 정제문은 편집 칸이며, 버튼 세 개가 있고 고치기 전에는 "수정한 내용으로 분석"이 막힘
  await rcRequest();
  await sleep(300);
  const s20 = await evaluate(`(()=>{const c=${CARD}; if(!c) return {card:false}; const q=a=>c.querySelector('[data-refined-confirm-action="'+a+'"]'); return {card:true, original:c.querySelector('[data-refined-confirm-original="true"]').innerText, edit:c.querySelector('[data-refined-confirm-edit="true"]').value, cancelLabel:q('cancel').innerText.trim(), asisLabel:q('asis').innerText.trim(), editedLabel:q('edited').innerText.trim(), asisDisabled:q('asis').disabled, editedDisabled:q('edited').disabled, cancelDisabled:q('cancel').disabled, count:document.querySelectorAll('[data-refined-confirm="true"]').length};})()`);
  await shot('20-refined-card');
  record(20, '확인 카드: 원문과 정제문 나란히, 정제문은 편집 칸, 버튼 3개(취소/이대로 분석/수정한 내용으로 분석), 고치기 전에는 수정 버튼 비활성', s20.card === true && s20.original === RC_ORIG && s20.edit === RC_REFINED && s20.cancelLabel === '취소' && s20.asisLabel === '이대로 분석' && s20.editedLabel === '수정한 내용으로 분석' && s20.asisDisabled === false && s20.editedDisabled === true && s20.cancelDisabled === false && s20.count === 1, JSON.stringify(s20));

  // 21) 수정 후 진행: 고친 문장을 submit의 editedText로 보내고 카드는 잠긴다
  const typed = 'SurveyServiceImpl만 수정하여 브랜드메시지 발송을 추가하세요. 알림톡 동작은 그대로 둡니다.';
  await typeInto(typed);
  const s21a = await evaluate(`({edited:${ACT('edited')}.disabled, asis:${ACT('asis')}.disabled, value:${EDIT}.value})`);
  await click(ACT('edited'), '수정한 내용으로 분석');
  await sleep(200);
  const sent21 = (await rcSent()).slice(sentBase);
  const s21b = await evaluate(`({edited:${ACT('edited')}.disabled, asis:${ACT('asis')}.disabled, cancel:${ACT('cancel')}.disabled, area:${EDIT}.disabled, label:${ACT('edited')}.innerText.trim()})`);
  await shot('21-refined-edited-submit');
  const submit21 = sent21.find(o => o.command === 'refinedConfirmation/submit');
  const body21 = submit21 ? JSON.parse(submit21.text) : null;
  record(21, '수정 후 진행: 고치면 수정 버튼이 켜지고, 누르면 고친 문장이 editedText로 한 번만 전송되며 카드가 잠김', s21a.edited === false && s21a.value === typed && sent21.length === 1 && body21 && body21.projectId === 'proj1' && body21.editedText === typed && s21b.edited && s21b.asis && s21b.cancel && s21b.area, JSON.stringify({ 전송: sent21, 잠금: s21b }));

  // 22) 이대로 분석: 편집 내용 없이 projectId만 전송
  await rcRequest();
  await sleep(300);
  await click(ACT('asis'), '이대로 분석');
  await sleep(200);
  const sent22 = (await rcSent()).slice(sentBase + 1);
  const body22 = sent22[0] && sent22[0].command === 'refinedConfirmation/submit' ? JSON.parse(sent22[0].text) : null;
  record(22, '이대로 분석: submit에 projectId만 있고 editedText가 없음', sent22.length === 1 && body22 && body22.projectId === 'proj1' && !('editedText' in body22), JSON.stringify(sent22));

  // 23) 취소: cancel만 전송하고, 이어서 오는 안내는 오류가 아닌 일반 말풍선으로 보임
  await rcRequest();
  await sleep(300);
  const errBefore = await evaluate(`document.querySelectorAll('[data-message-id^="error_"]').length`);
  const errClassBefore = await evaluate(`document.querySelectorAll('.msg-ai.error').length`);
  await click(ACT('cancel'), '취소');
  await sleep(200);
  const sent23 = (await rcSent()).slice(sentBase + 2);
  await post({ type: 'ai_message', subType: 'refinedConfirmation/hide', messageId: 'system', content: '' });
  await post({ type: 'ai_message', subType: 'analyze_notice', messageId: 'analyze_notice_1', content: '분석을 취소했습니다.' });
  await sleep(300);
  const s23 = await evaluate(`(()=>{const t=document.body.innerText; const bubbles=[...document.querySelectorAll('[data-message-id^="notice_"]')]; return {text:t.includes('분석을 취소했습니다.'), notice:bubbles.length, noticeIsError:bubbles.some(b=>b.classList.contains('error')), errBubbles:document.querySelectorAll('[data-message-id^="error_"]').length, errClass:document.querySelectorAll('.msg-ai.error').length, card:document.querySelectorAll('[data-refined-confirm="true"]').length};})()`);
  await shot('23-refined-cancel-notice');
  record(23, '취소: cancel만 전송, 카드는 내려가고 "분석을 취소했습니다."가 오류가 아닌 안내 말풍선으로 보임(오류 말풍선 증가 없음)', sent23.length === 1 && sent23[0].command === 'refinedConfirmation/cancel' && s23.text && s23.notice === 1 && !s23.noticeIsError && s23.errBubbles === errBefore && s23.errClass === errClassBefore && s23.card === 0, JSON.stringify({ 전송: sent23, ...s23 }));

  // 24) 빈 문장 차단: 두 진행 버튼이 모두 막히고 경고가 보이며 눌러도 아무것도 전송되지 않음. 되돌리면 다시 켜짐
  await rcRequest();
  await sleep(300);
  const base24 = (await rcSent()).length;
  await setBlank();
  await sleep(150);
  const s24a = await evaluate(`({edited:${ACT('edited')}.disabled, asis:${ACT('asis')}.disabled, cancel:${ACT('cancel')}.disabled, warn:!!document.querySelector('[data-refined-confirm-blank="true"]')})`);
  await evaluate(`${ACT('edited')}.click(); ${ACT('asis')}.click(); true`);
  await sleep(200);
  const sent24 = (await rcSent()).slice(base24);
  await shot('24-refined-blank-blocked');
  await click(`[...document.querySelectorAll('[data-refined-confirm="true"] button')].find(b=>b.innerText.includes('원래대로 되돌리기'))`, '원래대로 되돌리기');
  const s24b = await evaluate(`({value:${EDIT}.value, asis:${ACT('asis')}.disabled, warn:!!document.querySelector('[data-refined-confirm-blank="true"]')})`);
  record(24, '빈 문장: 진행 버튼 둘 다 비활성+경고, 눌러도 전송 0건, 취소는 가능, 되돌리면 복구', s24a.edited && s24a.asis && !s24a.cancel && s24a.warn && sent24.length === 0 && s24b.value === RC_REFINED && !s24b.asis && !s24b.warn, JSON.stringify({ 빈칸: s24a, 전송: sent24.length, 되돌린뒤: s24b }));

  // 25) 대기 시간 초과 등으로 백엔드가 카드를 내리면(hide) 카드가 사라진다
  await post({ type: 'ai_message', subType: 'refinedConfirmation/hide', messageId: 'system', content: '' });
  await sleep(250);
  const s25 = await evaluate(`document.querySelectorAll('[data-refined-confirm="true"]').length`);
  record(25, '백엔드가 hide를 보내면 확인 카드가 사라짐(대기 시간 초과 경로)', s25 === 0, JSON.stringify({ 카드: s25 }));

  // 26) 응답 없이 시간이 지나 위 정제된 문장 그대로 분석이 진행되면(expired) 카드는 지워지지 않고 비활성이 되며 안내가 보인다
  await rcRequest();
  await sleep(300);
  const base26 = (await rcSent()).length;
  await post({ type: 'ai_message', subType: 'refinedConfirmation/expired', messageId: 'system', content: '' });
  await sleep(300);
  const s26 = await evaluate(`(()=>{const c=${CARD}; if(!c) return {card:false}; const q=a=>c.querySelector('[data-refined-confirm-action="'+a+'"]'); return {card:true, text:c.innerText.includes('응답이 없어 위 정제된 문장 그대로 분석을 진행했습니다.'), expiredEl:!!c.querySelector('[data-refined-confirm-expired="true"]'), cancel:q('cancel').disabled, asis:q('asis').disabled, edited:q('edited').disabled, area:c.querySelector('[data-refined-confirm-edit="true"]').disabled};})()`);
  await evaluate(`${ACT('asis')}.click(); ${ACT('cancel')}.click(); true`);
  await sleep(200);
  const sent26 = (await rcSent()).slice(base26);
  await shot('26-refined-expired');
  record(26, '시간 초과로 진행되면 카드가 남아 비활성이 되고 안내 문구가 보이며, 이후 눌러도 전송 0건', s26.card && s26.text && s26.expiredEl && s26.cancel && s26.asis && s26.edited && s26.area && sent26.length === 0, JSON.stringify({ ...s26, 전송: sent26.length }));
  await post({ type: 'ai_message', subType: 'refinedConfirmation/hide', messageId: 'system', content: '' });

  // ───────── clarify 세션 만료(27~30) ─────────
  const SR_EXP = '기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요';
  const expPayload = { originalRequirement: SR_EXP, summaryTurn: 1, items: [], openQuestion: null, isExhausted: false, isReadyForStage1: false, taskSummary: null, excludedFiles: [], pendingExclusions: [],
    dialogueHistory: [{ role: 'user', content: SR_EXP }, { role: 'assistant', content: '질문입니다' }, { role: 'user', content: '관리자 화면만 해당이에요' }] };
  await evaluate(`window.__confirmMode = 'expired', true`);
  await post({ type: 'ai_message', subType: 'analyze_clarify', messageId: 'clarify_exp1', content: JSON.stringify(expPayload) });
  await sleep(400);
  const fExp = `document.querySelector('[data-message-id="clarify_exp1"]')`;
  const expConfirm = `[...${fExp}.querySelectorAll('button')].find(b=>b.textContent.includes('확인 완료'))`;
  const sentBeforeExp = await evaluate(`window.__sent.length`);

  // 27) 확정 뒤 세션 만료 오류: 제출 문구가 없고(되돌림), 확정 버튼 대신 "같은 요구사항으로 다시 시작" 버튼이 정확히 1개
  await click(expConfirm, '확인 완료(만료 모의)');
  await sleep(600);
  const s27 = await evaluate(`(()=>{const f=${fExp}; const t=f.innerText; return {submittedText:t.includes('확인이 제출되었습니다'), restartBtns:f.querySelectorAll('[data-clarify-restart="true"]').length, restartLabel:f.querySelector('[data-clarify-restart="true"]')?.innerText.trim(), confirmBtn:[...f.querySelectorAll('button')].some(b=>b.textContent.includes('확인 완료')), waits:f.querySelectorAll('.clarify-wait').length, notice:t.includes('자동으로 다시 보내지 않습니다'), errorShown:document.body.innerText.includes('분석 세션이 만료되었거나 유효하지 않습니다.')};})()`);
  await shot('27-session-expired-restart');
  record(27, '확정 뒤 세션 만료 오류: "확인이 제출되었습니다" 문구 없음, 다시 시작 버튼 1개, 확정 버튼 없음, 기다림 표시 없음, 오류 문구 보임', s27.submittedText === false && s27.restartBtns === 1 && s27.restartLabel === '같은 요구사항으로 다시 시작' && s27.confirmBtn === false && s27.waits === 0 && s27.notice === true && s27.errorShown === true, JSON.stringify(s27));

  // 28) 안내 문장에 이전 발화 목록이 보이고(원 요구사항은 제외) 발화는 자동 재전송되지 않았다
  const s28 = await evaluate(`(()=>{const f=${fExp}; const li=[...f.querySelectorAll('[data-session-expired-statements="true"] li')].map(x=>x.innerText.trim()); return {li, resentUtterance:window.__sent.slice(${sentBeforeExp}).map(x=>JSON.parse(x).command).filter(c=>c==='/clarify-utterance').length};})()`);
  record(28, '이전 발화 목록 안내(관리자 화면만 해당이에요만, 원 요구사항 제외)와 발화 자동 재전송 없음', s28.li.length === 1 && s28.li[0] === '관리자 화면만 해당이에요' && s28.resentUtterance === 0, JSON.stringify(s28));

  // 29) 다시 시작 버튼: /clarify에 원 요구사항과 같은 문자열을 보내고, 사용자 말풍선 "/clarify 원문"이 붙는다. 두 번 눌러도 1회
  const userBefore = await evaluate(`document.querySelectorAll('.msg-user').length`);
  await click(`${fExp}.querySelector('[data-clarify-restart="true"]')`, '같은 요구사항으로 다시 시작');
  await sleep(300);
  await evaluate(`${fExp}.querySelector('[data-clarify-restart="true"]').click(); true`);
  await sleep(300);
  const sentRestart = (await evaluate(`window.__sent.map(x=>JSON.parse(x)).filter(o=>o.command==='/clarify')`));
  const userAfter = await evaluate(`[...document.querySelectorAll('.msg-user')].map(x=>x.innerText.trim())`);
  record(29, '다시 시작: /clarify에 원문과 같은 문자열 1회 전송, 사용자 말풍선 "/clarify 원문" 추가, 버튼은 눌린 뒤 비활성', sentRestart.length === 1 && sentRestart[0].text === SR_EXP && userAfter.length === userBefore + 1 && userAfter[userAfter.length - 1] === '/clarify ' + SR_EXP, JSON.stringify({ 전송: sentRestart, 말풍선수: [userBefore, userAfter.length] }));

  // 30) 정상 확정 흐름은 그대로: 만료 모드를 끄면 확정 뒤 제출 문구가 남고 분석 시작이 오며 다시 시작 버튼이 없다
  await evaluate(`window.__confirmMode = 'ok', window.__confirmDelayMs = 100, true`);
  const okPayload = { ...expPayload, originalRequirement: '정상 흐름 확인' };
  await post({ type: 'ai_message', subType: 'analyze_clarify', messageId: 'clarify_ok1', content: JSON.stringify(okPayload) });
  await sleep(400);
  const fOk = `document.querySelector('[data-message-id="clarify_ok1"]')`;
  await click(`[...${fOk}.querySelectorAll('button')].find(b=>b.textContent.includes('확인 완료'))`, '확인 완료(정상)');
  await sleep(700);
  const s30 = await evaluate(`(()=>{const f=${fOk}; return {submittedText:f.innerText.includes('✓ 확인이 제출되었습니다. 분석을 진행 중입니다...'), restartBtns:f.querySelectorAll('[data-clarify-restart="true"]').length, started:!!document.querySelector('[data-message-id="analyze_C1"]')};})()`);
  record(30, '정상 확정: 제출 문구 유지, 분석 시작 메시지 도착, 다시 시작 버튼 없음', s30.submittedText === true && s30.restartBtns === 0 && s30.started === true, JSON.stringify(s30));

  // 31) 카드 대기 문구: 탐색 힌트 카드가 뜨면 진행 중 메시지 상단 문구와 제출 뒤 문구가 "확인을 기다리고 있습니다", 카드가 내려가면 "분석을 진행 중입니다"
  // 32) 탐색 힌트 카드 표시: "메타그래프"는 "프로젝트 코드 구조"로, "주석 없음" 칸은 비운다
  const l1Payload = { projectId: 'p1', turn: 1, query: 'survey response email', topCandidates: [{ className: 'SurveyDao', fileType: 'INTERFACE', localName: '주석 없음' }, { className: 'ClosingLinkDto', fileType: 'DTO', localName: '설문클로징 혜택링크' }], domains: [], message: "'survey'로 메타그래프를 검색하겠습니다." };
  await post({ type: 'ai_message', subType: 'l1Clarification/request', messageId: 'system', content: JSON.stringify(l1Payload) });
  await sleep(500);
  const s31a = await evaluate(`(()=>{const st=[...document.querySelectorAll('.status-text')].map(x=>x.innerText.trim()); const f=document.querySelector('[data-message-id="clarify_ok1"]'); return {status:st, submitted:f? (f.innerText.includes('✓ 확인이 제출되었습니다.') && !f.innerText.includes('확인이 제출되었습니다. 확인을 기다리고 있습니다')) : null}})()`);
  await shot('31-card-wait');
  const s32 = await evaluate(`(()=>{const t=document.body.innerText; return {meta:t.includes('메타그래프'), newWord:t.includes('프로젝트 코드 구조를 검색하겠습니다'), noComment:t.includes('주석 없음'), localName:t.includes('(설문클로징 혜택링크)')}})()`);
  record(32, '탐색 힌트 카드: 메타그래프 문구 없음, 프로젝트 코드 구조로 표시, 주석 없음 칸 비움, 있는 한글 이름은 그대로', s32.meta === false && s32.newWord === true && s32.noComment === false && s32.localName === true, JSON.stringify(s32));
  await post({ type: 'ai_message', subType: 'l1Clarification/hide', messageId: 'system', content: '' });
  await sleep(500);
  const s31b = await evaluate(`(()=>{const st=[...document.querySelectorAll('.status-text')].map(x=>x.innerText.trim()); const f=document.querySelector('[data-message-id="clarify_ok1"]'); return {status:st, submitted:f? f.innerText.includes('✓ 확인이 제출되었습니다. 분석을 진행 중입니다...') : null}})()`);
  record(31, '카드 대기 중 상단 문구 "확인을 기다리고 있습니다"(제출 뒤 문구 포함), 카드 응답 뒤 "분석을 진행 중입니다"', s31a.status.includes('확인을 기다리고 있습니다') && s31a.submitted === true && s31b.status.includes('분석을 진행 중입니다') && !s31b.status.includes('확인을 기다리고 있습니다') && s31b.submitted === true, JSON.stringify({ wait: s31a, after: s31b }));

  // 33) 분석 시작 실패 뒤 "다시 시도": 같은 정제문으로 재확정(/analyze-confirm 2회), 발화 자동 재전송 없음, 두 번째에 분석 시작
  await evaluate("window.__confirmMode = 'failOnce', window.__failedOnce = false, window.__confirmDelayMs = 100, true");
  const retryPayload = { ...expPayload, originalRequirement: '재시도 확인' };
  await post({ type: 'ai_message', subType: 'analyze_clarify', messageId: 'clarify_retry1', content: JSON.stringify(retryPayload) });
  await sleep(400);
  const fRetry = "document.querySelector('[data-message-id=\"clarify_retry1\"]')";
  const sentBeforeRetry = await evaluate("window.__sent.length");
  await click("[..." + fRetry + ".querySelectorAll('button')].find(b=>b.textContent.includes('확인 완료'))", '확인 완료(실패할 확정)');
  await sleep(700);
  const s33a = await evaluate("(()=>{const f=" + fRetry + "; const b=f.querySelector('[data-clarify-retry=\"true\"]'); return {retryBtn: !!b, label: b ? b.textContent.trim() : null, submittedText: f.innerText.includes('확인이 제출되었습니다'), restartBtns: f.querySelectorAll('[data-clarify-restart=\"true\"]').length}})()");
  await click(fRetry + ".querySelector('[data-clarify-retry=\"true\"]')", '다시 시도');
  await sleep(700);
  const s33b = await evaluate("(()=>{const sent=window.__sent.slice(" + sentBeforeRetry + ").map(x=>JSON.parse(x).command); return {confirms: sent.filter(c=>c==='/analyze-confirm').length, utterances: sent.filter(c=>c==='/clarify-utterance').length, submittedText: " + fRetry + ".innerText.includes('확인이 제출되었습니다')}})()");
  record(33, '분석 시작 실패 뒤: 제출 문구 없음, "다시 시도" 버튼 1개(다시 시작 버튼 없음), 누르면 재확정 /analyze-confirm 2회째 전송, 발화 자동 재전송 0, 다시 제출 문구', s33a.retryBtn === true && s33a.label === '다시 시도' && s33a.submittedText === false && s33a.restartBtns === 0 && s33b.confirms === 2 && s33b.utterances === 0 && s33b.submittedText === true, JSON.stringify({ afterFail: s33a, afterRetry: s33b }));

  // 34) 대체 경로로 끝난 분석: 결과 표·/implement 안내·내부 문구 없이 안내와 "다시 시도"만, 누르면 같은 요구사항을 /analyze로 1회 전송, 정상 결과에는 버튼 없음
  const incReq = '관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능의 백엔드를 수정하여, 응답자 이메일을 마스킹해 반환하세요.';
  await post({ type: 'ai_message', subType: 'analyze_start', messageId: 'analyze_inc1', content: '분석을 시작합니다' });
  await post({ type: 'ai_message', subType: 'task_chunk', messageId: 'analyze_inc1', content: '### 파일 분석을 끝까지 하지 못했습니다\n서버 응답이 중간에 끊겨 대상 파일을 확정하지 못했습니다. 아래 "다시 시도"를 누르면 같은 요구사항으로 다시 분석합니다.\n', isFirst: true });
  await post({ type: 'ai_message', subType: 'chat', messageId: 'analyze_inc1', content: '' });
  await post({ type: 'ai_message', subType: 'analyze_incomplete', messageId: 'analyze_inc1', content: JSON.stringify({ requirement: incReq }) });
  await sleep(500);
  const fInc = "document.querySelector('[data-message-id=\"analyze_inc1\"]')";
  const sentBeforeInc = await evaluate("window.__sent.length");
  const s34a = await evaluate("(()=>{const f=" + fInc + "; const t=f.innerText; const b=f.querySelectorAll('[data-analyze-retry=\"true\"]'); return {notice:t.includes('파일 분석을 끝까지 하지 못했습니다'), table:f.querySelectorAll('table').length, implement:t.includes('/implement'), internal:/Score:|Fallback|Bypass/.test(t), btns:b.length, label:b[0]?b[0].textContent.trim():null, normalBtns:document.querySelectorAll('[data-message-id=\"analyze_UI1\"] [data-analyze-retry=\"true\"]').length}})()");
  await shot('34-analysis-incomplete');
  await click(fInc + ".querySelector('[data-analyze-retry=\"true\"]')", '다시 시도(분석 미완료)');
  await sleep(300);
  await evaluate(fInc + ".querySelector('[data-analyze-retry=\"true\"]').click(); true");
  await sleep(300);
  const s34b = await evaluate("(()=>{const sent=window.__sent.slice(" + sentBeforeInc + ").map(x=>JSON.parse(x)); const b=" + fInc + ".querySelector('[data-analyze-retry=\"true\"]'); return {analyze: sent.filter(o=>o.command==='/analyze'), others: sent.filter(o=>o.command!=='/analyze' && o.command!=='/saveChat').length, disabled:b.disabled, label:b.textContent.trim()}})()");
  record(34, '분석 미완료: 안내만(결과 표·/implement·Score/Fallback/Bypass 없음), 다시 시도 1개, 눌러도 /analyze는 같은 요구사항으로 1회, 비활성, 정상 결과에는 버튼 없음', s34a.notice === true && s34a.table === 0 && s34a.implement === false && s34a.internal === false && s34a.btns === 1 && s34a.label === '다시 시도' && s34a.normalBtns === 0 && s34b.analyze.length === 1 && s34b.analyze[0].text === incReq && s34b.others === 0 && s34b.disabled === true, JSON.stringify({ before: s34a, after: s34b }));

  // 35) 인텐트가 있는 분석 미완료: 재시도 키가 /analyze-retry로 같은 정제문과 함께 1회 전송된다(제외 파일·제약은 백엔드가 보관한 인텐트로 유지, 발화 재전송 없음)
  const keyReq = '관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능의 백엔드를 수정하여, 응답자 이메일을 마스킹해 반환하세요.';
  await post({ type: 'ai_message', subType: 'analyze_start', messageId: 'analyze_inc2', content: '분석을 시작합니다' });
  await post({ type: 'ai_message', subType: 'task_chunk', messageId: 'analyze_inc2', content: '### 파일 분석을 끝까지 하지 못했습니다', isFirst: true });
  await post({ type: 'ai_message', subType: 'chat', messageId: 'analyze_inc2', content: '' });
  await post({ type: 'ai_message', subType: 'analyze_incomplete', messageId: 'analyze_inc2', content: JSON.stringify({ requirement: keyReq, retryKey: 'retry_7_123456' }) });
  await sleep(500);
  const fInc2 = "document.querySelector('[data-message-id=\"analyze_inc2\"]')";
  const sentBefore35 = await evaluate("window.__sent.length");
  await click(fInc2 + ".querySelector('[data-analyze-retry=\"true\"]')", '다시 시도(인텐트 보존)');
  await sleep(300);
  await evaluate(fInc2 + ".querySelector('[data-analyze-retry=\"true\"]').click(); true");
  await sleep(300);
  const s35 = await evaluate("(()=>{const sent=window.__sent.slice(" + sentBefore35 + ").map(x=>JSON.parse(x)).filter(o=>o.command!=='/saveChat'); return {sent, disabled:" + fInc2 + ".querySelector('[data-analyze-retry=\"true\"]').disabled}})()");
  record(35, '인텐트가 있는 분석 미완료: 다시 시도가 /analyze-retry에 같은 정제문과 재시도 키를 1회 보내고(다른 전송 없음) 비활성', s35.sent.length === 1 && s35.sent[0].command === '/analyze-retry' && s35.sent[0].text === keyReq && s35.sent[0].retryKey === 'retry_7_123456' && s35.disabled === true, JSON.stringify(s35));

  // 36) 카드 요청 후 표시 확인: Stage 3 카드가 화면(뷰포트) 안에 보이고, 대기 줄(대기 상태, 경과 시간, 응답 없을 때 진행 방식)이 있고, 수신·렌더 진단 로그(/uiLog)가 백엔드로 간다
  for (let i = 0; i < 12; i++) await post({ type: 'ai_message', subType: 'chat_start', messageId: 'filler_' + i, content: '긴 대화 채우기 ' + i });
  await evaluate("(()=>{const l=document.querySelector('.chat-list'); if(l) l.scrollTop = 0; return true})()");
  const sentBefore36 = await evaluate("window.__sent.length");
  const stage3Payload = { projectId: 'p1', candidates: [1, 2, 3, 4, 5, 6].map(n => ({ order: n, path: 'src/main/java/a/B' + n + '.java', type: 'MODIFY', description: 'Score: ' + n })), message: 'Stage 3 후보 확인' };
  await post({ type: 'ai_message', subType: 'stage3Confirmation/request', messageId: 'system', content: JSON.stringify(stage3Payload) });
  await sleep(2400);
  await shot('36-card-wait');
  const s36 = await evaluate("(()=>{const c=document.querySelector('[data-card-kind=\"stage3\"]'); if(!c) return {card:false}; const r=c.getBoundingClientRect(); const b=c.querySelector('[data-card-wait=\"true\"]'); const logs=window.__sent.slice(" + sentBefore36 + ").map(x=>JSON.parse(x)).filter(o=>o.command==='/uiLog').map(o=>o.text); return {card:true, inViewport: r.top < window.innerHeight && r.bottom > 0, banner:b?b.innerText:null, logs}})()");
  const elapsedOk = !!(s36.banner && /경과 [2-9]초/.test(s36.banner));
  record(36, '카드 요청 후 표시 확인: 화면 밖으로 스크롤돼 있던 상태에서도 Stage 3 카드가 뷰포트 안에 보이고, 대기 줄(확인을 기다리고 있습니다, 경과 시간, 5분 뒤 전체 후보로 진행)이 있고, 수신·렌더 진단 로그가 간다', s36.card === true && s36.inViewport === true && elapsedOk && /5분 안에 응답이 없으면 전체 후보로 진행합니다/.test(s36.banner || '') && s36.logs.some(t => t.startsWith('카드 수신')) && s36.logs.some(t => t.startsWith('카드 렌더') && t.includes('kind=stage3')), JSON.stringify(s36));
  await post({ type: 'ai_message', subType: 'stage3Confirmation/hide', messageId: 'system', content: '' });
  await sleep(300);

  // 37) 타임아웃 안내 표시: 백엔드가 응답 없이 넘어가며 보낸 안내(analyze_notice)가 대화창에 보인다
  await post({ type: 'ai_message', subType: 'analyze_notice', messageId: 'analyze_notice_t', content: '응답이 없어 전체 후보로 진행했습니다.' });
  await sleep(400);
  const s37 = await evaluate("(()=>{const t=document.body.innerText; return {notice:t.includes('응답이 없어 전체 후보로 진행했습니다.'), cardGone: document.querySelectorAll('[data-card-kind=\"stage3\"]').length}})()");
  record(37, '타임아웃 안내 표시: "응답이 없어 전체 후보로 진행했습니다." 안내가 보이고 카드는 내려가 있다', s37.notice === true && s37.cardGone === 0, JSON.stringify(s37));

  // 38) 질문 표시(B-77): 소스 확인 질문(근거 줄 포함, 여러 줄)이 "추가 확인 필요" 영역에 줄바꿈 그대로 보인다
  const Q38 = '응답자 이메일을 저장하거나 조회하는 코드를 찾지 못했습니다 (후보 범위 파일 30개를 읽어 확인).\n다른 업무 코드에서만 발견됩니다: sql_survey.xml:670, sql_survey.xml:841, sql_survey.xml:857 (테이블 ISSW_ADMIN_USER)\n어디서 가져올까요? (예: 다른 테이블 조인, 새로 저장, 잘 모르겠음)';
  const qPayload = { originalRequirement: '기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요', summaryTurn: 1, items: [], openQuestion: Q38, isExhausted: false, isReadyForStage1: false, taskSummary: null, excludedFiles: [], pendingExclusions: [], dialogueHistory: [] };
  await post({ type: 'ai_message', subType: 'analyze_clarify', messageId: 'clarify_q1', content: JSON.stringify(qPayload) });
  await sleep(500);
  const fQ = `document.querySelector('[data-message-id="clarify_q1"]')`;
  const s38 = await evaluate(`(()=>{const f=${fQ}; if(!f) return {card:false}; const box=[...f.querySelectorAll('div')].find(d=>d.innerText && d.innerText.startsWith(${JSON.stringify(Q38.split('\n')[0])}) && d.children.length===0); const r=box?.getBoundingClientRect(); return {card:true, hasQuestion:!!box, lines:box? box.innerText.split('\\n').length:0, evidence:box? /sql_survey\.xml:670/.test(box.innerText):false, preWrap:box? getComputedStyle(box).whiteSpace:null, heightOk: r? r.height > 45 : false, header:f.innerText.includes('추가 확인 필요')};})()`);
  await shot('38-field-question');
  record(38, '질문 표시: 소스 확인 질문 1개가 "추가 확인 필요" 영역에 3줄로 보이고 근거 줄(sql_survey.xml:670 등)이 포함된다(줄바꿈 유지)', s38.card && s38.hasQuestion && s38.lines === 3 && s38.evidence && s38.preWrap === 'pre-wrap' && s38.header === true, JSON.stringify(s38));

  // 39) 답이 정제문에 반영됨(B-77): 질문에 답하면 /clarify-utterance로 답이 그대로 가고, 정제문 확인 카드에 그 답이 들어간 정제문이 보인다(백엔드 응답은 모의: 정제문에 답이 들어가는 것은 Kotlin 테스트 ClarifyFieldQuestionB77Test.r2가 확인)
  const ANS39 = '다른 테이블에서 조인해서 가져오세요';
  const REF39 = '기존 설문조사 상세 조회 기능에 응답자 이메일 정보를 다른 테이블에서 조인해서 함께 내려주도록 백엔드를 수정한다.';
  await evaluate(`window.__formPayload = ${JSON.stringify({ ...qPayload, openQuestion: null, echoBackMessage: '응답자 이메일 출처에 대한 답을 제약으로 기록했습니다.' })}, window.__delayMs = 0, window.__utteranceMode = 'ok', true`);
  const sentBefore39 = await evaluate('window.__sent.length');
  await evaluate(`(()=>{const i=${fQ}.querySelector('input[type="text"]'); const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set; set.call(i, ${JSON.stringify(ANS39)}); i.dispatchEvent(new Event('input',{bubbles:true})); return true})()`);
  await sleep(100);
  await click(`[...${fQ}.querySelectorAll('button')].find(b=>b.textContent.includes('지시 전송'))`, '지시 전송(답변)');
  await sleep(600);
  await post({ type: 'ai_message', subType: 'refinedConfirmation/request', messageId: 'system', content: JSON.stringify({ projectId: 'proj1', originalRequirement: qPayload.originalRequirement, refinedRequirement: REF39, message: '분석을 시작하기 전에 정제된 요구사항을 확인해 주세요.' }) });
  await sleep(500);
  const s39 = await evaluate(`(()=>{const sent=window.__sent.slice(${sentBefore39}).map(x=>JSON.parse(x)).filter(o=>o.command==='/clarify-utterance'); const t=document.body.innerText; return {utterances:sent.length, answerSent: sent[0]?.text===${JSON.stringify(ANS39)}, questionGone: !t.includes('어디서 가져올까요'), refinedHasAnswer: [...document.querySelectorAll('[data-card-kind="refined"] textarea, [data-card-kind="refined"]')].some(e=>((e.value||'')+(e.innerText||'')).includes('다른 테이블에서 조인해서 함께 내려주도록')), card: document.querySelectorAll('[data-card-kind="refined"]').length};})()`);
  await shot('39-answer-in-refined');
  record(39, '답이 정제문에 반영됨: 답이 /clarify-utterance로 1회 그대로 전송되고 질문은 사라지며, 정제문 확인 카드에 답이 들어간 정제문이 보인다(백엔드 응답은 모의)', s39.utterances === 1 && s39.answerSent && s39.questionGone && s39.refinedHasAnswer, JSON.stringify(s39));

  const errors = await evaluate(`window.__errors || null`);
  fs.writeFileSync(OUT + '/results.json', JSON.stringify({ results, observations, shots, clicks, errors }, null, 1));
  const md = ['| # | 항목 | 결과 | 근거 |', '|---|---|---|---|', ...results.map(r => `| ${r.id} | ${r.item} | ${r.ok ? '됨' : '**안 됨**'} | ${String(r.evidence).replace(/\|/g, '\\|').slice(0, 300)} |`)].join('\n');
  fs.writeFileSync(OUT + '/results.md', md + '\n');
  console.log(`\n됨 ${results.filter(r => r.ok).length}/${results.length}`);
} catch (e) {
  console.error('실행 오류:', e.message);
  fs.writeFileSync(OUT + '/results.json', JSON.stringify({ results, error: e.message }, null, 1));
  process.exitCode = 1;
} finally {
  cleanup();
}
