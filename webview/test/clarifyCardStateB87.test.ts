// B-87 (다)-2: Stage 0 카드의 종료 상태(분석 진행 중·취소·정지·완료)와 복원 규칙(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/clarifyCardStateB87.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CLARIFY_STATE_TEXT,
  clarifyCardState,
  completeClarifyCards,
  finishRefinedAndSettle,
  restoreMessages,
  settleClarifyCard,
} from '../src/chatRestoreLogic.ts';

type Msg = {
  id: string; role?: string; subType?: string; content?: string; isLoading?: boolean; isStreaming?: boolean;
  clarifyCompleted?: boolean; clarifyOutcome?: 'running' | 'canceled' | 'stopped' | 'done'; cardDone?: boolean;
};

const clarify = (id: string, extra: Partial<Msg> = {}): Msg => ({ id, role: 'ai', subType: 'analyze_clarify', ...extra });
const refinedCard = (): Msg => ({
  id: 'rc_1', role: 'ai', subType: 'refinedConfirmation/request', isLoading: false,
  content: JSON.stringify({ projectId: 'p1', requestId: 'r1:analyze_1', originalRequirement: '원문', refinedRequirement: '정제문' }),
});
const state = (ms: Msg[], id: string) => clarifyCardState(ms.find(m => m.id === id) as Msg);

test('카드 상태 네 가지의 문구', () => {
  assert.equal(CLARIFY_STATE_TEXT.running, '✔ 분석 진행 중 (정제문 확인 끝)');
  assert.equal(CLARIFY_STATE_TEXT.canceled, '⛔ 확인을 취소했습니다. 분석을 시작하지 않았습니다.');
  assert.equal(CLARIFY_STATE_TEXT.stopped, '정지를 요청했습니다 (분석은 끝까지 진행될 수 있습니다)');
  assert.equal(CLARIFY_STATE_TEXT.done, '✔ 완료됨 (확인을 마치고 분석이 이어졌습니다)');
});

test('정제문 카드의 응답·만료는 running, 취소는 canceled로 Stage 0 카드를 끝낸다', () => {
  for (const cause of ['asis', 'edited', 'timeout'] as const) {
    const out = finishRefinedAndSettle([clarify('c1'), refinedCard()] as Msg[], cause, { idBase: 'r' });
    assert.equal(state(out, 'c1'), 'running', cause);
  }
  const canceled = finishRefinedAndSettle([clarify('c1'), refinedCard()] as Msg[], 'cancel', { idBase: 'r' });
  assert.equal(state(canceled, 'c1'), 'canceled');
});

test('정제문 카드가 이미 없으면(종료 신호가 두 번 오거나 오래된 신호) Stage 0 카드를 건드리지 않는다', () => {
  const before = [clarify('c1')];
  assert.equal(finishRefinedAndSettle(before, 'asis', { idBase: 'r' }), before);
  assert.equal(finishRefinedAndSettle(before, 'cancel', { idBase: 'r' }), before);
});

test('done은 running과 stopped를 덮는다', () => {
  assert.equal(state(settleClarifyCard([clarify('c1', { clarifyOutcome: 'running' })], 'done'), 'c1'), 'done');
  assert.equal(state(settleClarifyCard([clarify('c1', { clarifyOutcome: 'stopped' })], 'done'), 'c1'), 'done');
  // 결과 도착(completeClarifyCards)도 같다
  const withResult = completeClarifyCards([clarify('c1', { clarifyOutcome: 'running' }), { id: 'analyze_9' }] as Msg[], 'analyze_9');
  assert.equal(state(withResult, 'c1'), 'done');
});

test('done은 canceled를 덮지 않는다(결과가 나와도, settleClarifyCard로도)', () => {
  const canceled = [clarify('c1', { clarifyOutcome: 'canceled' })];
  assert.equal(state(settleClarifyCard(canceled, 'done'), 'c1'), 'canceled');
  const withResult = completeClarifyCards([clarify('c1', { clarifyOutcome: 'canceled' }), { id: 'analyze_9' }] as Msg[], 'analyze_9');
  assert.equal(state(withResult, 'c1'), 'canceled');
  assert.equal(withResult[0].clarifyCompleted, undefined);
  // 복원 상태 표시 우선순위: canceled > done > running·stopped
  assert.equal(clarifyCardState({ clarifyOutcome: 'canceled', clarifyCompleted: true }), 'canceled');
  assert.equal(clarifyCardState({ clarifyOutcome: 'running', clarifyCompleted: true }), 'done');
});

test('상태가 있으면 isSubmitted 문구보다 우선한다: 상태가 정해진 카드는 읽기 전용(completed)이고 상태가 없을 때만 undefined', () => {
  // App.tsx ClarifyForm: completed = clarifyCardState(msg) !== undefined 이고 제출 문구는 !completed일 때만 보인다
  for (const o of ['running', 'canceled', 'stopped', 'done'] as const) assert.notEqual(clarifyCardState({ clarifyOutcome: o }), undefined, o);
  assert.equal(clarifyCardState({}), undefined);
});

test('정지: 정제문 확인이 끝난(running) 카드에만 붙고, 제출 전 카드는 잠그지 않는다. running은 stopped를 되돌리지 않는다', () => {
  assert.equal(state(settleClarifyCard([clarify('c1', { clarifyOutcome: 'running' })], 'stopped'), 'c1'), 'stopped');
  const before = [clarify('c1')];
  assert.equal(settleClarifyCard(before, 'stopped'), before);
  assert.equal(state(settleClarifyCard([clarify('c1', { clarifyOutcome: 'stopped' })], 'running'), 'c1'), 'stopped');
  const doneCard = [clarify('c1', { clarifyCompleted: true })];
  assert.equal(settleClarifyCard(doneCard, 'stopped'), doneCard);
});

test('카드가 두 장이면 가장 최근 카드만 바뀐다', () => {
  const out = settleClarifyCard([clarify('c1'), { id: 'u', role: 'user' }, clarify('c2')] as Msg[], 'running');
  assert.equal(state(out, 'c1'), undefined);
  assert.equal(state(out, 'c2'), 'running');
  assert.equal(settleClarifyCard([{ id: 'u', role: 'user' }] as Msg[], 'running').length, 1);
});

test('복원: clarifyOutcome이 저장돼 있으면 확정 뒤 메시지로 완료됨을 추정하지 않는다', () => {
  for (const o of ['running', 'stopped', 'canceled'] as const) {
    const restored = restoreMessages([clarify('c1', { clarifyOutcome: o }), { id: 'a1', subType: 'analyze_start' }] as Msg[]);
    assert.equal(restored[0].clarifyCompleted, undefined, o);
    assert.equal(restored[0].clarifyOutcome, o);
  }
});

test('복원: 필드가 없는 옛 채팅에 "✔ 정제문 확인: 취소" 기록 줄이 있으면 취소로 추정하고 완료됨으로 보지 않는다', () => {
  const restored = restoreMessages([
    clarify('c1'),
    { id: 'rec1', role: 'ai', subType: 'choice_record', content: '✔ 정제문 확인: 취소' },
    { id: 'n1', subType: 'analyze_notice' },
  ] as Msg[]);
  assert.equal(restored[0].clarifyOutcome, 'canceled');
  assert.equal(restored[0].clarifyCompleted, undefined);
});

test('복원: 취소 기록은 그 카드 뒤 다음 카드 앞까지만 그 카드의 것이다', () => {
  const restored = restoreMessages([
    clarify('c1'),
    { id: 'rec1', role: 'ai', subType: 'choice_record', content: '✔ 정제문 확인: 취소' },
    clarify('c2'),
    { id: 'a1', subType: 'analyze_start' },
  ] as Msg[]);
  assert.equal(restored[0].clarifyOutcome, 'canceled');
  assert.equal(restored[2].clarifyOutcome, undefined);
  assert.equal(restored[2].clarifyCompleted, true);
});

test('복원: 그 밖의 옛 채팅은 현행대로(확정 뒤 분석 메시지가 있으면 완료됨, 없으면 그대로, 다른 정제문 기록은 취소가 아니다)', () => {
  const done = restoreMessages([clarify('c1'), { id: 'a1', subType: 'analyze_start' }] as Msg[]);
  assert.equal(done[0].clarifyCompleted, true);
  assert.equal(done[0].clarifyOutcome, undefined);
  const untouched = restoreMessages([{ id: 'u', role: 'user' }, clarify('c1')] as Msg[]);
  assert.equal(untouched[1].clarifyCompleted, undefined);
  assert.equal(untouched[1].clarifyOutcome, undefined);
  const asis = restoreMessages([
    clarify('c1'),
    { id: 'rec1', role: 'ai', subType: 'choice_record', content: '✔ 정제문 확인: 이대로 분석' },
    { id: 'a1', subType: 'analyze_start' },
  ] as Msg[]);
  assert.equal(asis[0].clarifyCompleted, true);
  assert.equal(asis[0].clarifyOutcome, undefined);
});
