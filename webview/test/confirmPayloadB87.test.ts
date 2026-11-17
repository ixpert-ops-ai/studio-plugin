// B-87 (가): 확정 payload에 입력창의 미전송 글을 싣는 순수 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/confirmPayloadB87.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { confirmPayload } from '../src/clarifySessionLogic.ts';

const verdicts = { a: 'CONFIRMED', b: 'REJECTED' };
const reasons = { b: 'FILE_MISMATCH' };

test('빈 글, 공백뿐인 글, 없는 글은 이전과 같은 payload(userStatement null)', () => {
  const before = { verdictUpdates: verdicts, rejectionReasonUpdates: reasons, userStatement: null, isCompletionDeclared: true };
  for (const text of ['', '   ', '\n\t ', null, undefined]) {
    assert.deepEqual(confirmPayload(verdicts, reasons, text), before);
    assert.equal(JSON.stringify(confirmPayload(verdicts, reasons, text)), JSON.stringify(before));
  }
});

test('입력창에 남은 글은 앞뒤 공백만 떼고 userStatement로 보낸다', () => {
  const p = confirmPayload(verdicts, reasons, '  응답자 이메일은 설문 테이블에서 가져와  \n');
  assert.equal(p.userStatement, '응답자 이메일은 설문 테이블에서 가져와');
  assert.equal(p.isCompletionDeclared, true);
  assert.deepEqual(p.verdictUpdates, verdicts);
  assert.deepEqual(p.rejectionReasonUpdates, reasons);
});

test('같은 입력은 다시 보내도 같은 payload다(실패 뒤 다시 시도: 백엔드가 확정 전 상태로 되돌린다)', () => {
  const text = '이메일은 새로 저장해줘';
  assert.deepEqual(confirmPayload(verdicts, reasons, text), confirmPayload(verdicts, reasons, text));
});

test('완결 선언 인자는 그대로 전달한다', () => {
  assert.equal(confirmPayload(verdicts, reasons, '글', false).isCompletionDeclared, false);
});
