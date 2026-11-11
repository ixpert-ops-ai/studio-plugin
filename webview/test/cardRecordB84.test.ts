// B-84: 탐색 힌트 건너뜀 줄의 Turn 번호, 저장 채팅 복원이 카드 payload(decisionNote)와 이전 기록 문구를 건드리지 않는지 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/cardRecordB84.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hintSkipRecord, hintTurnLabel, hintTurnOf, noticeTurnOf, restoreMessages, timeoutRecord } from '../src/chatRestoreLogic.ts';

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

test('저장 채팅 복원: Turn 번호가 없는 이전 기록 줄은 그대로 남고, 카드의 확인 필요 문장은 기록 줄로 남는다', () => {
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
  // B-85 1 D에서 바뀐 기대값(전: 응답 전에 저장된 카드가 payload 그대로 복원됨 / 후: 눌러도 아무 일도 못 하는 카드 대신 기록 줄로 바뀌고, 확인 필요 문장은 그 기록 줄에 남는다)
  assert.equal(restored.some(m => m.subType === 'refinedConfirmation/request'), false);
  assert.equal(restored[1].content, '✔ 정제문 확인: 응답 전 종료됨(IDE 재시작)');
  assert.equal(restored[2].content, `📌 구현 전 확인 필요: ${note}`);
});

// B-85 2: 시간 초과 기록에도 Turn 번호(건너뛰기와 같은 방식). 값은 백엔드가 안내에 실은 turn → 마지막 힌트 카드의 turn → 없으면 이전 문구.
test('힌트 시간 초과 기록: Turn 번호가 있으면 붙이고, 없으면 이전 문구, 다른 카드의 기록은 turn과 무관하다', () => {
  const hintNotice = '응답이 없어 힌트 없이 진행했습니다.';
  assert.equal(timeoutRecord(hintNotice, 0, 2), '✔ Turn 2 탐색 힌트: 없음(5분 응답 없음, 자동 진행)');
  for (const none of [undefined, 0, -1, 1.5, NaN]) {
    assert.equal(timeoutRecord(hintNotice, 0, none as number | undefined), '✔ 탐색 힌트: 없음(5분 응답 없음, 자동 진행)');
  }
  assert.equal(timeoutRecord(hintNotice, 0), '✔ 탐색 힌트: 없음(5분 응답 없음, 자동 진행)');
  // 건너뛰기 기록과 같은 형식: 시간 초과만 "없음(5분 응답 없음, 자동 진행)"이 다르다
  assert.equal(hintSkipRecord(2).replace('건너뜀(자율 탐색)', ''), timeoutRecord(hintNotice, 0, 2)!.replace('없음(5분 응답 없음, 자동 진행)', ''));
  // 범위·Stage 3 기록에는 turn이 붙지 않는다
  assert.equal(timeoutRecord('응답이 없어 전체 프로젝트로 진행했습니다.', 0, 2), '✔ 읽을 범위 선택: 전체(5분 응답 없음, 자동 진행)');
  assert.equal(timeoutRecord('응답이 없어 전체 후보로 진행했습니다.', 13, 2), '✔ 최종 검증 대상 선택: 전체 13개 진행(5분 응답 없음, 자동 진행)');
});

test('시간 초과 안내의 turn 필드: 숫자 또는 숫자 문자열(메타 필드는 문자열), 양의 정수만 읽는다', () => {
  assert.equal(noticeTurnOf(3), 3);
  assert.equal(noticeTurnOf('3'), 3);
  assert.equal(noticeTurnOf(' 3 '), 3);
  for (const none of ['3.5', '0', 0, -1, '-1', '', '  ', 'abc', undefined, null, NaN, {}]) {
    assert.equal(noticeTurnOf(none), undefined);
  }
  assert.equal(hintTurnLabel(2), 'Turn 2 ');
  assert.equal(hintTurnLabel(undefined), '');
});

test('복원: Turn이 붙은 시간 초과 기록 줄과 붙지 않은 이전 기록 줄은 복원 때 바뀌지 않는다', () => {
  const result = {
    id: 'analyze_1', role: 'ai', subType: 'analyze_start', content: '결과',
    inlineRecords: [
      { at: 10, text: '✔ Turn 2 탐색 힌트: 없음(5분 응답 없음, 자동 진행)' },
      { at: 10, text: '✔ 탐색 힌트: 없음(5분 응답 없음, 자동 진행)' },
    ],
  };
  assert.deepEqual(restoreMessages([result])[0].inlineRecords, result.inlineRecords);
});
