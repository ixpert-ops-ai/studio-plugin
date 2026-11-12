// B-86 c: 살아 있는 확인 카드 표. 저장된 채팅을 복원할 때(IDE 재시작·채팅 전환 뒤 복귀) 지금 백엔드가 기다리는 카드만 활성으로 두고 나머지는 결과 기록 줄로 바꾼다(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/liveCardsB86.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { restoreMessages } from '../src/chatRestoreLogic.ts';
import {
  CARD_INFO, MAX_ENDED, cardKeyFor, createLiveCards, endCard, endedLines, isLive, kindOfSubType, kindOfTimeoutNotice, neutralLine, registerCard,
  type CardKind,
} from '../src/liveCardsLogic.ts';

type Msg = { id: string; role?: string; subType?: string; content?: string; isLoading?: boolean; cardKey?: string; cardDone?: boolean; expired?: boolean };
const KINDS: CardKind[] = ['refined', 'package', 'hint', 'stage3'];
const NOTE = '응답 테이블(ISSW_SURVEY_R)에 값을 쓰는 쿼리가 읽은 매퍼 1개(sql_survey.xml)에 없습니다.';
const content = (kind: CardKind) => kind === 'refined'
  ? JSON.stringify({ projectId: 'p1', requestId: 'rid1:analyze_1', originalRequirement: '원문', refinedRequirement: '정제문', decisionNote: NOTE })
  : JSON.stringify({ projectId: 'p1', turn: 2 });
const open = (cards: ReturnType<typeof createLiveCards>, kind: CardKind, seq = 1): Msg => {
  const c = content(kind);
  const key = cardKeyFor(kind, c, seq, 1000 + seq);
  registerCard(cards, kind, key, c);
  return { id: `${kind}_${seq}`, role: 'ai', subType: CARD_INFO[kind].subType, content: c, isLoading: false, cardKey: key };
};
const subTypes = (ms: Msg[]) => ms.map(m => m.subType ?? 'plain');
const recs = (ms: Msg[]) => ms.filter(m => m.subType === 'choice_record').map(m => m.content);

test('번호: 정제문은 payload의 requestId, 나머지는 종류:순번:시각이고 같은 종류의 새 카드가 오면 이전 번호는 무효다', () => {
  assert.equal(cardKeyFor('refined', content('refined'), 5, 9), 'rid1:analyze_1');
  assert.equal(cardKeyFor('refined', JSON.stringify({ projectId: 'p1', refinedRequirement: 'x' }), 5, 9), 'refined:5:9'); // requestId 없는 이전 카드
  assert.equal(cardKeyFor('hint', content('hint'), 5, 9), 'hint:5:9');
  const cards = createLiveCards();
  const first = open(cards, 'hint', 1);
  assert.equal(isLive(cards, 'hint', first.cardKey), true);
  const second = open(cards, 'hint', 2);
  assert.equal(isLive(cards, 'hint', first.cardKey), false, '같은 종류 새 카드가 오면 이전 번호는 무효');
  assert.equal(isLive(cards, 'hint', second.cardKey), true);
  // 다른 종류는 서로 영향이 없다
  const pkg = open(cards, 'package', 3);
  assert.equal(isLive(cards, 'hint', second.cardKey), true);
  assert.equal(isLive(cards, 'package', pkg.cardKey), true);
  // 번호가 같아도 종류가 다르면 살아 있는 카드가 아니다
  assert.equal(isLive(cards, 'stage3', pkg.cardKey), false);
  assert.equal(isLive(cards, 'hint', undefined), false);
});

test('채팅 전환 후 복귀: 대기 중인 카드는 네 종류 모두 활성으로 남고, 표는 복원을 거쳐도 그대로다', () => {
  const cards = createLiveCards();
  const saved: Msg[] = [{ id: 'u1', role: 'user', content: '/clarify' }, ...KINDS.map((k, i) => open(cards, k, i + 1))];
  // 채팅 B로 전환(표는 앱 수준 상태라 B를 불러와도 그대로)
  const other = restoreMessages<Msg>([{ id: 'ub', role: 'user', content: '다른 채팅' }], cards);
  assert.deepEqual(subTypes(other), ['plain']);
  // 채팅 A로 복귀: 카드 네 장이 그대로 활성(기록 줄로 바뀌지 않는다)
  const back = restoreMessages<Msg>(saved, cards);
  assert.deepEqual(subTypes(back), ['plain', ...KINDS.map(k => CARD_INFO[k].subType)]);
  assert.deepEqual(recs(back), []);
  // 한 번 더 복원해도(왔다 갔다) 같다
  assert.deepEqual(restoreMessages<Msg>(back, cards), back);
  // 정제문 카드는 cardKey가 없어도 payload의 requestId로 같은 카드임을 안다
  const noKey = saved.map(m => (m.subType === CARD_INFO.refined.subType ? { ...m, cardKey: undefined } : m));
  assert.equal(restoreMessages<Msg>(noKey, cards).some(m => m.subType === CARD_INFO.refined.subType), true);
});

test('앱 수준 상태: 표는 App 컴포넌트 밖(모듈 수준)에 하나만 있고 채팅을 불러올 때 그 표로 복원한다', () => {
  const app = fs.readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  const lines = app.split('\n');
  const decl = lines.findIndex(l => l === 'const liveCards = createLiveCards();');
  assert.ok(decl >= 0, '들여쓰기 없이 모듈 수준에 선언');
  const componentStart = lines.findIndex(l => /^(const|function) App\b|^export default function App/.test(l));
  assert.ok(componentStart < 0 || decl < componentStart, 'App 컴포넌트보다 앞(모듈 수준)');
  assert.equal(lines.filter(l => l.includes('createLiveCards()')).length, 1);
  assert.ok(app.includes('setMessages(restoreMessages(loadedMessages, liveCards));'));
});

test('끝난 카드 복원: 기억한 실제 결과 줄이 있으면 그것, 없으면 중립 문구, 카드는 사라진다', () => {
  const cards = createLiveCards();
  const pkg = open(cards, 'package', 1);
  const hint = open(cards, 'hint', 2);
  const s3 = open(cards, 'stage3', 3);
  const refined = open(cards, 'refined', 4);
  const saved: Msg[] = [{ id: 'u1', role: 'user', content: '/clarify' }, pkg, hint, s3, refined];
  // 채팅을 전환해 둔 사이에: 패키지는 시간 초과(결과 줄 기억), 힌트는 hide만(결과 모름), Stage 3은 시간 초과(줄 기억), 정제문은 만료(줄 기억)
  endCard(cards, 'package', ['✔ 읽을 범위 선택: 전체(5분 응답 없음, 자동 진행)']);
  endCard(cards, 'hint');
  endCard(cards, 'stage3', ['✔ 최종 검증 대상 선택: 전체 13개 진행(5분 응답 없음, 자동 진행)']);
  endCard(cards, 'refined', ['✔ 정제문 확인: 응답 없음(5분, 정제문 그대로 진행)', '↳ 분석에 쓴 정제문: 정제문', `📌 구현 전 확인 필요: ${NOTE}`]);
  const out = restoreMessages<Msg>(saved, cards);
  assert.deepEqual(subTypes(out).filter(s => s !== 'choice_record' && s !== 'plain'), [], '카드는 하나도 남지 않는다');
  assert.deepEqual(recs(out), [
    '✔ 읽을 범위 선택: 전체(5분 응답 없음, 자동 진행)',
    '✔ 탐색 힌트: 이전에 끝난 카드(채팅 복원)',
    '✔ 최종 검증 대상 선택: 전체 13개 진행(5분 응답 없음, 자동 진행)',
    '✔ 정제문 확인: 응답 없음(5분, 정제문 그대로 진행)', '↳ 분석에 쓴 정제문: 정제문', `📌 구현 전 확인 필요: ${NOTE}`,
  ]);
  // 카드 자리에서 기록 줄이 되므로 순서가 유지된다
  assert.equal(out[0].id, 'u1');
  assert.deepEqual(KINDS.map(k => neutralLine(k)), [
    '✔ 정제문 확인: 이전에 끝난 카드(채팅 복원)', '✔ 읽을 범위 선택: 이전에 끝난 카드(채팅 복원)', '✔ 탐색 힌트: 이전에 끝난 카드(채팅 복원)', '✔ 최종 검증 대상 선택: 이전에 끝난 카드(채팅 복원)',
  ]);
});

test('이미 기록 줄이 있는 카드는 줄을 더하지 않는다: 사용자가 답한 카드(cardDone)는 카드만 지우고, 복원을 되풀이해도 줄은 늘지 않는다', () => {
  const cards = createLiveCards();
  const hint = open(cards, 'hint', 1);
  endCard(cards, 'hint'); // 사용자가 답함(App의 cardAnswered가 표에서 뺀다)
  const saved: Msg[] = [
    { id: 'u1', role: 'user', content: '/clarify' },
    { ...hint, cardDone: true },
    { id: 'rec_1', role: 'ai', subType: 'choice_record', content: '✔ 탐색 힌트: 없음(그대로 진행)' },
  ];
  const once = restoreMessages<Msg>(saved, cards);
  assert.deepEqual(subTypes(once), ['plain', 'choice_record']);
  assert.deepEqual(recs(once), ['✔ 탐색 힌트: 없음(그대로 진행)']);
  assert.deepEqual(restoreMessages<Msg>(once, cards), once);
  // 기록 줄만 있고 카드가 없는 저장 채팅은 그대로
  const plain: Msg[] = [{ id: 'u1', role: 'user', content: '/clarify' }, { id: 'rec_1', role: 'ai', subType: 'choice_record', content: '✔ 정제문 확인: 이대로 분석' }];
  assert.deepEqual(restoreMessages<Msg>(plain, cards), plain);
});

test('같은 종류 새 카드 도착 시 이전 카드의 번호는 무효: 저장된 이전 카드는 닫히고 새 카드만 활성이다', () => {
  const cards = createLiveCards();
  const old = open(cards, 'hint', 1);
  const fresh = open(cards, 'hint', 2);
  const out = restoreMessages<Msg>([old, { ...fresh, id: 'hint_fresh' }], cards);
  assert.deepEqual(subTypes(out), ['choice_record', 'l1Clarification/request']);
  assert.equal(out[1].id, 'hint_fresh');
  assert.deepEqual(recs(out), ['✔ 탐색 힌트: 이전에 끝난 카드(채팅 복원)']);
});

test('예전 저장 채팅(카드 번호 없음)과 웹뷰 새로고침 뒤(표가 비어 있음) 복원: 카드는 닫히고 중립 문구가 된다', () => {
  const noKeyCards: Msg[] = (['package', 'hint', 'stage3'] as CardKind[]).map((k, i) => ({ id: `old_${i}`, role: 'ai', subType: CARD_INFO[k].subType, content: content(k), isLoading: false }));
  // 표에 같은 종류의 살아 있는 카드가 있어도 번호가 없는 카드는 활성이 아니다
  const cards = createLiveCards();
  for (const k of ['package', 'hint', 'stage3'] as CardKind[]) open(cards, k, 9);
  const old = restoreMessages<Msg>(noKeyCards, cards);
  assert.deepEqual(subTypes(old), ['choice_record', 'choice_record', 'choice_record']);
  assert.deepEqual(recs(old), ['✔ 읽을 범위 선택: 이전에 끝난 카드(채팅 복원)', '✔ 탐색 힌트: 이전에 끝난 카드(채팅 복원)', '✔ 최종 검증 대상 선택: 이전에 끝난 카드(채팅 복원)']);
  // 새로고침: 번호가 있는 카드라도 표가 비어 있으면 닫힌다(백엔드의 5분 자동 진행에 맡긴다)
  const before = createLiveCards();
  const saved: Msg[] = KINDS.map((k, i) => open(before, k, i + 1));
  const afterReload = restoreMessages<Msg>(saved, createLiveCards());
  assert.deepEqual(subTypes(afterReload), ['choice_record', 'choice_record', 'choice_record', 'choice_record', 'choice_record']);
  // 인자를 주지 않아도 같다(표가 없는 것과 같다)
  assert.deepEqual(restoreMessages<Msg>(saved), afterReload);
  // 예전 형식으로 만료(expired)가 저장된 정제문 카드는 시간 초과 기록(B-85 1 D 그대로)
  const expired = restoreMessages<Msg>([{ id: 'rc', role: 'ai', subType: CARD_INFO.refined.subType, content: content('refined'), expired: true }], createLiveCards());
  assert.deepEqual(recs(expired), ['✔ 정제문 확인: 응답 없음(5분, 정제문 그대로 진행)', '↳ 분석에 쓴 정제문: 정제문', `📌 구현 전 확인 필요: ${NOTE}`]);
});

test('끝내기: 살아 있는 카드가 없으면 아무 일도 없고(두 번 와도 안전), 기억하는 결과는 최근 MAX_ENDED개다', () => {
  const cards = createLiveCards();
  assert.equal(endCard(cards, 'hint', ['x']), undefined);
  assert.deepEqual(cards.ended, []);
  const a = open(cards, 'hint', 1);
  assert.equal(endCard(cards, 'hint', ['✔ 탐색 힌트: 건너뜀']), a.cardKey);
  assert.equal(endCard(cards, 'hint', ['중복']), undefined);
  assert.deepEqual(endedLines(cards, a.cardKey), ['✔ 탐색 힌트: 건너뜀']);
  for (let i = 2; i < 2 + MAX_ENDED + 3; i++) { open(cards, 'hint', i); endCard(cards, 'hint', [`줄${i}`]); }
  assert.equal(cards.ended.length, MAX_ENDED);
  assert.equal(endedLines(cards, a.cardKey), undefined, '오래된 것은 밀려난다');
});

test('시간 초과 안내 문구와 종류', () => {
  assert.equal(kindOfTimeoutNotice('응답이 없어 전체 프로젝트로 진행했습니다.'), 'package');
  assert.equal(kindOfTimeoutNotice('응답이 없어 힌트 없이 진행했습니다.'), 'hint');
  assert.equal(kindOfTimeoutNotice(' 응답이 없어 전체 후보로 진행했습니다. '), 'stage3');
  assert.equal(kindOfTimeoutNotice('분석을 취소했습니다.'), undefined);
  assert.equal(kindOfTimeoutNotice(undefined), undefined);
  assert.equal(kindOfSubType('l1Clarification/request'), 'hint');
  assert.equal(kindOfSubType('analyze_notice'), undefined);
});

test('App의 카드 처리가 표를 쓴다: 요청 4종은 번호를 붙이고, 응답·취소·만료·hide·시간 초과는 표에서 뺀다', () => {
  const app = fs.readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  for (const [kind, sub] of [['package', 'scopeSelection/showTree'], ['hint', 'l1Clarification/request'], ['stage3', 'stage3Confirmation/request'], ['refined', 'refinedConfirmation/request']]) {
    assert.ok(app.includes(`openCard('${kind}', data.content)`), `${sub} 요청은 openCard('${kind}')`);
  }
  for (const [kind, sub] of [['package', 'scopeSelection/hideTree'], ['hint', 'l1Clarification/hide'], ['stage3', 'stage3Confirmation/hide'], ['refined', 'refinedConfirmation/hide']]) {
    const body = app.slice(app.indexOf(`data.subType === '${sub}'`)).split('\n').slice(0, 6).join('\n');
    assert.ok(body.includes(`endCard(liveCards, '${kind}')`), `${sub}는 endCard('${kind}')`);
  }
  assert.ok(app.includes("endCard(liveCards, 'refined', refinedCloseRecords('timeout'"), '만료는 결과 줄을 기억한다');
  assert.ok(app.includes('if (timedOutKind) endCard(liveCards, timedOutKind, rec ? [rec] : undefined);'), '시간 초과 안내는 종류별로 결과 줄을 기억한다');
  assert.ok(app.includes("data.subType === 'cardAnswered'"));
  // 힌트·Stage 3 카드에 답하면 cardAnswered를 보낸다(제출·건너뛰기 4곳)
  assert.equal(app.split("cardAnswered('hint');").length - 1, 2);
  assert.equal(app.split("cardAnswered('stage3');").length - 1, 2);
});
