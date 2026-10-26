// 분석 시작 전 정제문 확인 카드 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/refinedConfirmLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildCancelText,
  buildSubmitText,
  evaluateEdit,
  EXPIRED_TEXT,
  makeNoticeBubble,
  markExpired,
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

test('만료 표시: 확인 카드에만 달리고 다른 메시지는 그대로이며 카드는 지워지지 않는다', () => {
  const list = [
    { id: 'a', subType: 'analyze_clarify' },
    { id: 'b', subType: 'refinedConfirmation/request' },
    { id: 'c' },
  ];
  const out = markExpired(list);
  assert.equal(out.length, 3);
  assert.deepEqual(out[0], list[0]);
  assert.deepEqual(out[1], { id: 'b', subType: 'refinedConfirmation/request', expired: true });
  assert.deepEqual(out[2], list[2]);
  assert.equal(list[1].expired, undefined); // 입력을 바꾸지 않는다
  assert.equal(EXPIRED_TEXT, '응답이 없어 원래 문장으로 분석을 진행했습니다.');
  // 이미 만료된 카드는 같은 값으로 남는다
  assert.deepEqual(markExpired(out)[1], out[1]);
});
