// clarify 대기 중 진행 표시 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/clarifyWaitLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  IDLE,
  MAX_WAIT_MS,
  canSendUtterance,
  confirmingElapsedText,
  elapsedSeconds,
  expireIfStale,
  onBackendMessage,
  startWait,
} from '../src/clarifyWaitLogic.ts';

test('발화를 보내면 표시가 시작되고 시작 시각이 기록된다', () => {
  const s = startWait(IDLE, 'interpreting', 1000);
  assert.deepEqual(s, { phase: 'interpreting', startedAt: 1000 });
});

test('해석 응답(새 대화 카드)이 오면 표시가 끝난다', () => {
  const s = startWait(IDLE, 'interpreting', 1000);
  assert.deepEqual(onBackendMessage(s, 'analyze_clarify'), IDLE);
});

test('오류와 취소가 오면 표시가 끝난다', () => {
  const s = startWait(IDLE, 'interpreting', 1000);
  assert.deepEqual(onBackendMessage(s, 'error'), IDLE);
  assert.deepEqual(onBackendMessage(s, 'task_cancelled'), IDLE);
});

test('작업 요약 도착 등 대기를 끝내지 않는 메시지는 표시를 유지한다', () => {
  const s = startWait(IDLE, 'interpreting', 1000);
  assert.equal(onBackendMessage(s, 'analyze_clarify_summary'), s);
  assert.equal(onBackendMessage(s, 'step_noti'), s);
  assert.equal(onBackendMessage(s, undefined), s);
});

test('발화를 연속으로 보낼 수 없다: 대기 중에는 거절하고 시작 시각을 되감지 않는다', () => {
  const s = startWait(IDLE, 'interpreting', 1000);
  assert.equal(canSendUtterance(s, '두 번째 발화', false), false);
  assert.equal(startWait(s, 'interpreting', 9000), s); // 같은 상태 객체(시작 시각 1000 유지)
  // 응답이 와서 끝난 뒤에는 다시 보낼 수 있다
  const done = onBackendMessage(s, 'analyze_clarify');
  assert.equal(canSendUtterance(done, '두 번째 발화', false), true);
  assert.deepEqual(startWait(done, 'interpreting', 9000), { phase: 'interpreting', startedAt: 9000 });
});

test('빈 발화이거나 이미 확인을 제출했다면 보내지 않는다', () => {
  assert.equal(canSendUtterance(IDLE, '   ', false), false);
  assert.equal(canSendUtterance(IDLE, '발화', true), false);
  assert.equal(canSendUtterance(IDLE, '발화', false), true);
});

test('확인 뒤 대기: 경과 초가 늘고 분석 시작 메시지가 오면 끝난다', () => {
  const s = startWait(IDLE, 'confirming', 5000);
  assert.equal(elapsedSeconds(s, 5000), 0);
  assert.equal(elapsedSeconds(s, 5999), 0);
  assert.equal(elapsedSeconds(s, 34000), 29);
  assert.equal(confirmingElapsedText(29), '경과 29초');
  assert.deepEqual(onBackendMessage(s, 'analyze_start'), IDLE);
});

test('대기 중이 아니면 경과는 0이고 메시지는 상태를 바꾸지 않는다', () => {
  assert.equal(elapsedSeconds(IDLE, 99999), 0);
  assert.equal(onBackendMessage(IDLE, 'analyze_clarify'), IDLE);
});

test('응답이 끝내 오지 않으면 안전 상한에서 표시를 내린다', () => {
  const s = startWait(IDLE, 'interpreting', 0);
  assert.equal(expireIfStale(s, MAX_WAIT_MS - 1), s);
  assert.deepEqual(expireIfStale(s, MAX_WAIT_MS), IDLE);
  assert.equal(expireIfStale(IDLE, MAX_WAIT_MS * 2), IDLE);
});
