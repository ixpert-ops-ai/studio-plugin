// B-84: 탐색 힌트 건너뜀 줄의 Turn 번호, 저장 채팅 복원이 카드 payload(decisionNote)와 이전 기록 문구를 건드리지 않는지 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/cardRecordB84.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hintSkipRecord, hintTurnOf, restoreMessages } from '../src/chatRestoreLogic.ts';

test('탐색 힌트 건너뜀 줄: Turn 번호가 있으면 붙이고, 없으면 이전 문구 그대로', () => {
  assert.equal(hintSkipRecord(2), '✔ Turn 2 탐색 힌트: 건너뜀(자율 탐색)');
  assert.equal(hintSkipRecord(1), '✔ Turn 1 탐색 힌트: 건너뜀(자율 탐색)');
  for (const none of [undefined, 0, -1, 1.5, NaN]) {
    assert.equal(hintSkipRecord(none as number | undefined), '✔ 탐색 힌트: 건너뜀(자율 탐색)');
  }
  assert.equal(hintSkipRecord(), '✔ 탐색 힌트: 건너뜀(자율 탐색)');
});

test('카드 payload에서 Turn 번호를 읽는다: 정수만, 읽을 수 없으면 undefined', () => {
  assert.equal(hintTurnOf(JSON.stringify({ projectId: 'p', turn: 3, query: 'Survey' })), 3);
  assert.equal(hintTurnOf(JSON.stringify({ projectId: 'p' })), undefined);
  assert.equal(hintTurnOf(JSON.stringify({ turn: '3' })), undefined);
  assert.equal(hintTurnOf(JSON.stringify({ turn: 0 })), undefined);
  assert.equal(hintTurnOf('깨진 문자열'), undefined);
  assert.equal(hintTurnOf(undefined), undefined);
  assert.equal(hintTurnOf(null), undefined);
});

test('저장 채팅 복원: Turn 번호가 없는 이전 기록 줄과 카드 payload(decisionNote)는 그대로 남는다', () => {
  const note = '응답 테이블(ISSW_SURVEY_R)에 값을 쓰는 쿼리가 읽은 매퍼 1개에 없습니다. 응답자 이메일 저장을 이 프로젝트에서 구현할지, 응답을 저장하는 다른 시스템과 연계할지 정해야 합니다.';
  const payload = JSON.stringify({ projectId: 'p1', originalRequirement: '원문', refinedRequirement: '정제문', decisionNote: note });
  const result = {
    id: 'analyze_1',
    role: 'ai',
    subType: 'analyze_start',
    content: '결과 본문',
    // 11-03 17:46 저장 채팅의 기록 줄(Turn 번호가 없던 시절의 문구)
    inlineRecords: [{ at: 203, text: '✔ 탐색 힌트: 건너뜀(자율 탐색)' }, { at: 203, text: '✔ 탐색 힌트: 건너뜀(자율 탐색)' }],
  };
  const card = { id: 'rc_1', role: 'ai', subType: 'refinedConfirmation/request', content: payload, isLoading: false };
  const restored = restoreMessages([result, card]);
  assert.deepEqual(restored[0].inlineRecords, result.inlineRecords);
  assert.equal(restored[1].content, payload);
  assert.equal(JSON.parse(restored[1].content).decisionNote, note);
});
