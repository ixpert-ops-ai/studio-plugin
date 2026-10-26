// clarify 확정 뒤 오류(세션 만료) 처리 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/clarifySessionLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  confirmButtonLabel,
  isRetryableConfirmFailure,
  isSessionExpiredError,
  onClarifyBackendMessage,
  previousStatements,
  restartRequest,
} from '../src/clarifySessionLogic.ts';

const EXPIRED = '분석 세션이 만료되었거나 유효하지 않습니다. `/clarify`를 다시 실행해주세요.';

test('세션 만료 오류는 고정 문구로 알아본다', () => {
  assert.equal(isSessionExpiredError(EXPIRED), true);
  assert.equal(isSessionExpiredError('요청을 처리하지 못했습니다. 다시 시도해 주세요.'), false);
  assert.equal(isSessionExpiredError(null), false);
  assert.equal(isSessionExpiredError(undefined), false);
});

test('확정 뒤 세션 만료 오류: 제출 문구를 걷고 다시 시작 안내를 보인다', () => {
  const r = onClarifyBackendMessage({ confirmPending: true, submitted: true }, 'error', EXPIRED);
  assert.deepEqual(r, { endConfirmPending: true, revertSubmitted: true, sessionExpired: true });
});

test('확정 뒤 다른 오류: 제출 문구만 걷고 다시 시작 안내는 없다', () => {
  const r = onClarifyBackendMessage({ confirmPending: true, submitted: true }, 'error', '발화 처리 중 오류가 발생했습니다: x');
  assert.deepEqual(r, { endConfirmPending: true, revertSubmitted: true, sessionExpired: false });
});

test('발화를 보내다 받은 세션 만료 오류(제출 전): 다시 시작 안내만 보인다', () => {
  const r = onClarifyBackendMessage({ confirmPending: false, submitted: false }, 'error', EXPIRED);
  assert.deepEqual(r, { endConfirmPending: false, revertSubmitted: false, sessionExpired: true });
});

test('분석이 이미 시작된 뒤의 오류는 건드리지 않는다', () => {
  const afterStart = { confirmPending: false, submitted: true };
  assert.deepEqual(onClarifyBackendMessage(afterStart, 'error', EXPIRED), { endConfirmPending: false, revertSubmitted: false, sessionExpired: false });
  assert.deepEqual(onClarifyBackendMessage(afterStart, 'error', 'x'), { endConfirmPending: false, revertSubmitted: false, sessionExpired: false });
});

test('확정이 받아들여지면(분석 시작, 취소 안내, 확인 카드) 대기만 끝나고 제출 문구는 그대로다', () => {
  for (const t of ['analyze_start', 'analyze_notice', 'refinedConfirmation/request']) {
    assert.deepEqual(onClarifyBackendMessage({ confirmPending: true, submitted: true }, t), { endConfirmPending: true, revertSubmitted: false, sessionExpired: false }, t);
    assert.deepEqual(onClarifyBackendMessage({ confirmPending: false, submitted: true }, t), { endConfirmPending: false, revertSubmitted: false, sessionExpired: false }, t);
  }
  // 그 밖의 메시지(요약 도착 등)는 영향이 없다
  assert.deepEqual(onClarifyBackendMessage({ confirmPending: true, submitted: true }, 'analyze_clarify_summary'), { endConfirmPending: false, revertSubmitted: false, sessionExpired: false });
  assert.deepEqual(onClarifyBackendMessage({ confirmPending: true, submitted: true }, undefined), { endConfirmPending: false, revertSubmitted: false, sessionExpired: false });
});

test('다시 시작 요청은 /clarify에 원 요구사항을 그대로 싣는다(발화는 싣지 않는다)', () => {
  const sr = '기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요';
  assert.deepEqual(restartRequest(sr), { command: '/clarify', text: sr });
});

test('이전 발화 목록: 서버 기록과 화면에서 보낸 발화를 합치고 중복과 원 요구사항은 뺀다', () => {
  const sr = '원 요구사항';
  const history = [
    { role: 'user', content: '원 요구사항' },
    { role: 'assistant', content: '질문입니다' },
    { role: 'user', content: '관리자 화면만 해당이에요' },
  ];
  assert.deepEqual(previousStatements(sr, history, ['관리자 화면만 해당이에요', '  이메일은 마스킹해서 ', '']), ['관리자 화면만 해당이에요', '이메일은 마스킹해서']);
  assert.deepEqual(previousStatements(sr, undefined, []), []);
});

test('확정 뒤 일반 오류는 다시 시도할 수 있고 세션 만료 오류는 다시 시작 안내다', () => {
  const state = { confirmPending: true, submitted: true };
  const failed = onClarifyBackendMessage(state, 'error', '분석을 시작하지 못했습니다: 연결 실패');
  assert.equal(isRetryableConfirmFailure(failed), true);
  const expired = onClarifyBackendMessage(state, 'error', '분석 세션이 만료되었거나 유효하지 않습니다. /clarify를 다시 실행해 주세요.');
  assert.equal(isRetryableConfirmFailure(expired), false);
  // 확정을 보내지 않은 상태의 오류, 분석이 이미 시작된 뒤의 오류는 다시 시도 대상이 아니다
  assert.equal(isRetryableConfirmFailure(onClarifyBackendMessage({ confirmPending: false, submitted: true }, 'error', '분석 중 오류')), false);
  assert.equal(isRetryableConfirmFailure(onClarifyBackendMessage({ confirmPending: false, submitted: false }, 'error', 'x')), false);
});

test('확정 버튼 문구: 실패 뒤에는 다시 시도', () => {
  assert.equal(confirmButtonLabel(true), '다시 시도');
  assert.equal(confirmButtonLabel(false), '확인 완료 및 분석 시작 →');
});
