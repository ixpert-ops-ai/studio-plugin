// 분석 시작 전 정제문 확인 카드 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/refinedConfirmLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildCancelText,
  buildSubmitText,
  evaluateEdit,
  closeRefinedCard,
  findRefinedCard,
  makeNoticeBubble,
  parsePayload,
} from '../src/refinedConfirmLogic.ts';
import { IDLE, onBackendMessage, startWait } from '../src/clarifyWaitLogic.ts';

const SHOWN = 'SurveyServiceImpl만 수정하여 브랜드메시지를 추가하세요.';

test('카드 데이터를 읽는다: 필수 값이 없거나 깨지면 null', () => {
  const ok = parsePayload(JSON.stringify({ projectId: 'p1', originalRequirement: '원문', refinedRequirement: SHOWN, message: '안내' }));
  assert.deepEqual(ok, { projectId: 'p1', originalRequirement: '원문', refinedRequirement: SHOWN, message: '안내' });
  assert.equal(parsePayload('깨진 문자열'), null);
  assert.equal(parsePayload(JSON.stringify({ projectId: 'p1' })), null);
  assert.equal(parsePayload(JSON.stringify({ refinedRequirement: 'x' })), null);
  assert.equal(parsePayload('null'), null);
});

test('확인 필요 문장(decisionNote)은 있을 때만 읽고, 편집 상태나 제출 본문에 영향이 없다', () => {
  const note = '응답 테이블(ISSW_SURVEY_R)에 값을 쓰는 쿼리가 읽은 매퍼 1개에 없습니다. 응답자 이메일 저장을 이 프로젝트에서 구현할지, 응답을 저장하는 다른 시스템과 연계할지 정해야 합니다.';
  const withNote = parsePayload(JSON.stringify({ projectId: 'p1', originalRequirement: '원문', refinedRequirement: SHOWN, message: '안내', decisionNote: note }));
  assert.equal(withNote?.decisionNote, note);
  // 정제문은 그대로다(문장을 바꾸거나 덧붙이지 않는다)
  assert.equal(withNote?.refinedRequirement, SHOWN);
  // 없거나 빈 문장이면 키 자체가 없다
  for (const none of [undefined, '', '   ']) {
    const p = parsePayload(JSON.stringify({ projectId: 'p1', originalRequirement: '원문', refinedRequirement: SHOWN, decisionNote: none }));
    assert.equal(p !== null && 'decisionNote' in p, false);
  }
  // 정제문을 고쳐도 제출 본문에는 문장이 들어가지 않는다(문장은 화면 표시 전용)
  assert.equal(buildSubmitText('p1', SHOWN, '고친 정제문', 'edited'), JSON.stringify({ projectId: 'p1', editedText: '고친 정제문' }));
  assert.deepEqual(evaluateEdit(SHOWN, '고친 정제문'), { blank: false, changed: true, canProceedAsIs: true, canProceedEdited: true });
});

test('편집 상태: 그대로면 이대로 분석만, 고치면 수정 버튼도, 비면 둘 다 막는다', () => {
  assert.deepEqual(evaluateEdit(SHOWN, SHOWN), { blank: false, changed: false, canProceedAsIs: true, canProceedEdited: false });
  assert.deepEqual(evaluateEdit(SHOWN, `  ${SHOWN}\n`), { blank: false, changed: false, canProceedAsIs: true, canProceedEdited: false });
  assert.deepEqual(evaluateEdit(SHOWN, `${SHOWN} 알림톡은 유지합니다.`), { blank: false, changed: true, canProceedAsIs: true, canProceedEdited: true });
  for (const blank of ['', '   ', '\n\t']) {
    assert.deepEqual(evaluateEdit(SHOWN, blank), { blank: true, changed: true, canProceedAsIs: false, canProceedEdited: false });
  }
});

test('이대로 분석은 편집 내용을 보내지 않고, 수정한 내용으로 분석은 고친 문장(앞뒤 공백 제거)을 보낸다', () => {
  assert.equal(buildSubmitText('p1', SHOWN, SHOWN, 'asis'), JSON.stringify({ projectId: 'p1' }));
  // 고쳐 놓고 "이대로 분석"을 눌러도 보여 준 정제문으로 진행한다(편집 내용은 보내지 않는다)
  assert.equal(buildSubmitText('p1', SHOWN, '다른 문장', 'asis'), JSON.stringify({ projectId: 'p1' }));
  assert.equal(buildSubmitText('p1', SHOWN, '  고친 문장입니다 \n', 'edited'), JSON.stringify({ projectId: 'p1', editedText: '고친 문장입니다' }));
});

test('빈 문장과 고치지 않은 상태에서는 아무것도 보내지 않는다', () => {
  assert.equal(buildSubmitText('p1', SHOWN, '', 'asis'), null);
  assert.equal(buildSubmitText('p1', SHOWN, '   ', 'edited'), null);
  assert.equal(buildSubmitText('p1', SHOWN, SHOWN, 'edited'), null);
});

test('취소는 projectId만 보낸다', () => {
  assert.equal(buildCancelText('p1'), JSON.stringify({ projectId: 'p1' }));
});

test('안내 말풍선은 오류가 아니다: isError가 없고 subType이 analyze_notice다', () => {
  const b = makeNoticeBubble(' 분석을 취소했습니다. ', 1000, 'ab');
  assert.deepEqual(b, { id: 'notice_1000_ab', role: 'ai', subType: 'analyze_notice', content: '분석을 취소했습니다.', isLoading: false });
  assert.ok(!('isError' in b));
  assert.equal(makeNoticeBubble(null, 1, 'x').content, '');
});

test('확인 카드나 취소 안내가 오면 분석 대기 표시가 내려간다', () => {
  let wait = startWait(IDLE, 'confirming', 1000);
  assert.deepEqual(onBackendMessage(wait, 'refinedConfirmation/request'), IDLE);
  wait = startWait(IDLE, 'confirming', 2000);
  assert.deepEqual(onBackendMessage(wait, 'analyze_notice'), IDLE);
  // 확인 카드 숨김은 대기를 건드리지 않는다
  wait = startWait(IDLE, 'confirming', 3000);
  assert.equal(onBackendMessage(wait, 'refinedConfirmation/hide'), wait);
});

// B-85 1에서 바뀐 기대값(전: 시간 초과 때 markExpired가 카드를 지우지 않고 expired 표시만 달아 대기 타이머가 계속 늘었다 / 후: closeRefinedCard가 카드를 목록에서 뺀다)
test('카드 닫기: 정제문 확인 카드만 빠지고 다른 메시지는 그대로이며, 카드가 없으면 같은 목록을 돌려준다', () => {
  const list = [
    { id: 'a', subType: 'analyze_clarify' },
    { id: 'b', subType: 'refinedConfirmation/request' },
    { id: 'c' },
  ];
  const out = closeRefinedCard(list);
  assert.deepEqual(out.map(m => m.id), ['a', 'c']);
  assert.equal(list.length, 3); // 입력을 바꾸지 않는다
  // 같은 종료 신호가 두 번 와도(응답 뒤 백엔드 hide) 안전하다: 카드가 없으면 같은 배열
  assert.equal(closeRefinedCard(out), out);
  assert.equal(findRefinedCard(list)?.id, 'b');
  assert.equal(findRefinedCard(out), undefined);
});

test('requestId: 카드 payload에서 읽고, 제출·취소 본문에 같이 싣는다(없으면 이전 본문 그대로)', () => {
  const withId = parsePayload(JSON.stringify({ projectId: 'p1', originalRequirement: '원문', refinedRequirement: SHOWN, requestId: '180f0685:analyze_17' }));
  assert.equal(withId?.requestId, '180f0685:analyze_17');
  for (const none of [undefined, '', '  ']) {
    const p = parsePayload(JSON.stringify({ projectId: 'p1', refinedRequirement: SHOWN, requestId: none }));
    assert.equal(p !== null && 'requestId' in p, false);
  }
  assert.equal(buildSubmitText('p1', SHOWN, SHOWN, 'asis', 'rid:m1'), JSON.stringify({ projectId: 'p1', requestId: 'rid:m1' }));
  assert.equal(buildSubmitText('p1', SHOWN, '고친 문장', 'edited', 'rid:m1'), JSON.stringify({ projectId: 'p1', requestId: 'rid:m1', editedText: '고친 문장' }));
  assert.equal(buildCancelText('p1', 'rid:m1'), JSON.stringify({ projectId: 'p1', requestId: 'rid:m1' }));
  // requestId가 없던 이전 카드는 이전과 같은 본문
  assert.equal(buildSubmitText('p1', SHOWN, SHOWN, 'asis'), JSON.stringify({ projectId: 'p1' }));
  assert.equal(buildCancelText('p1'), JSON.stringify({ projectId: 'p1' }));
});
