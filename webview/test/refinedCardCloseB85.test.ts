// B-85 1: 정제문 확인 카드는 응답·취소·시간 초과 어느 경우에도 즉시 지워지고(대기 타이머와 버튼이 카드와 함께 사라진다) 기록 줄이 남는다. 복원도 같다(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/refinedCardCloseB85.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { finishRefinedCard, refinedCloseRecords, restoreMessages } from '../src/chatRestoreLogic.ts';

const SHOWN = '설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 상세 조회 시 마스킹하여 반환한다.';
const NOTE = '응답 테이블(ISSW_SURVEY_R)에 값을 쓰는 쿼리가 읽은 매퍼 1개(sql_survey.xml)에 없습니다. 응답자 이메일 저장을 이 프로젝트에서 구현할지, 응답을 저장하는 다른 시스템과 연계할지 정해야 합니다.';

type Msg = { id: string; role?: string; subType?: string; content?: string; isLoading?: boolean; isStreaming?: boolean; inlineRecords?: Array<{ at: number; text: string }>; expired?: boolean };

const payload = (withNote: boolean) =>
  JSON.stringify({ projectId: 'p1', requestId: '180f0685:analyze_1', originalRequirement: '원문', refinedRequirement: SHOWN, ...(withNote ? { decisionNote: NOTE } : {}) });
const card = (withNote = true, extra: Partial<Msg> = {}): Msg => ({ id: 'rc_1', role: 'ai', subType: 'refinedConfirmation/request', content: payload(withNote), isLoading: false, ...extra });
const before = (): Msg[] => [{ id: 'u1', role: 'user', content: '/clarify 요구사항' }, card()];
const texts = (ms: Msg[]) => ms.filter(m => m.subType === 'choice_record').map(m => m.content);

test('이대로 분석: 카드가 즉시 지워지고(타이머·버튼 포함) 기록 줄에 정제문과 확인 필요 문장이 남는다', () => {
  const out = finishRefinedCard(before(), 'asis', { idBase: 'r' });
  assert.equal(out.some(m => m.subType === 'refinedConfirmation/request'), false);
  assert.deepEqual(texts(out), ['✔ 정제문 확인: 이대로 분석', `↳ 분석에 쓴 정제문: ${SHOWN}`, `📌 구현 전 확인 필요: ${NOTE}`]);
  assert.equal(out[0].id, 'u1'); // 다른 메시지는 그대로
});

test('수정한 내용으로 분석: 분석에 쓴 정제문은 고친 문장이다', () => {
  const out = finishRefinedCard(before(), 'edited', { editedText: '  사용자가 고친 정제문  ', idBase: 'r' });
  assert.deepEqual(texts(out), ['✔ 정제문 확인: 수정한 내용으로 분석', '↳ 분석에 쓴 정제문: 사용자가 고친 정제문', `📌 구현 전 확인 필요: ${NOTE}`]);
  // 고친 문장이 비어 있으면 보인 정제문을 쓴다
  assert.equal(texts(finishRefinedCard(before(), 'edited', { editedText: '  ', idBase: 'r' }))[1], `↳ 분석에 쓴 정제문: ${SHOWN}`);
});

test('취소: 정제문 인용 줄이 없다(분석을 시작하지 않는다), 확인 필요 문장은 남는다', () => {
  const out = finishRefinedCard(before(), 'cancel', { idBase: 'r' });
  assert.equal(out.some(m => m.subType === 'refinedConfirmation/request'), false);
  assert.deepEqual(texts(out), ['✔ 정제문 확인: 취소', `📌 구현 전 확인 필요: ${NOTE}`]);
});

test('시간 초과(5분 자동 진행): 카드가 지워지고 정제문 그대로 진행했다는 기록이 남는다', () => {
  const out = finishRefinedCard(before(), 'timeout', { idBase: 'r' });
  assert.equal(out.some(m => m.subType === 'refinedConfirmation/request'), false);
  assert.deepEqual(texts(out), ['✔ 정제문 확인: 응답 없음(5분, 정제문 그대로 진행)', `↳ 분석에 쓴 정제문: ${SHOWN}`, `📌 구현 전 확인 필요: ${NOTE}`]);
  // 예전 문구는 쓰지 않는다
  assert.equal(texts(out).some(t => (t ?? '').includes('응답이 없어 위 정제된 문장')), false);
});

test('확인 필요 문장이 없는 카드는 그 줄이 없다', () => {
  const out = finishRefinedCard([card(false)], 'asis', { idBase: 'r' });
  assert.deepEqual(texts(out), ['✔ 정제문 확인: 이대로 분석', `↳ 분석에 쓴 정제문: ${SHOWN}`]);
});

test('같은 종료 신호가 두 번 와도 기록은 한 번이다(응답 뒤에 오는 백엔드 hide·expired)', () => {
  const once = finishRefinedCard(before(), 'asis', { idBase: 'r1' });
  const twice = finishRefinedCard(once, 'timeout', { idBase: 'r2' });
  assert.equal(twice, once);
  assert.equal(texts(twice).length, 3);
});

test('결과가 스트리밍 중이면 기록은 그 말풍선 본문에 끼운다', () => {
  const stream: Msg = { id: 'analyze_9', role: 'ai', subType: 'task_chunk', content: '진행 줄\n', isStreaming: true };
  const out = finishRefinedCard([stream, card()], 'asis', { idBase: 'r' });
  assert.equal(out.some(m => m.subType === 'refinedConfirmation/request'), false);
  assert.deepEqual(out[0].inlineRecords?.map(r => r.text), ['✔ 정제문 확인: 이대로 분석', `↳ 분석에 쓴 정제문: ${SHOWN}`, `📌 구현 전 확인 필요: ${NOTE}`]);
});

test('복원: 예전 형식으로 expired가 저장된 카드는 그 자리에서 시간 초과 기록 줄이 된다', () => {
  const saved: Msg[] = [{ id: 'u1', role: 'user', content: '/clarify' }, card(true, { expired: true }), { id: 'analyze_1', role: 'ai', subType: 'task_chunk', content: '결과' }];
  const out = restoreMessages(saved);
  assert.equal(out.some(m => m.subType === 'refinedConfirmation/request'), false);
  assert.deepEqual(out.map(m => m.id === 'u1' ? 'u1' : m.id === 'analyze_1' ? 'analyze_1' : 'rec'), ['u1', 'rec', 'rec', 'rec', 'analyze_1']);
  assert.deepEqual(texts(out), ['✔ 정제문 확인: 응답 없음(5분, 정제문 그대로 진행)', `↳ 분석에 쓴 정제문: ${SHOWN}`, `📌 구현 전 확인 필요: ${NOTE}`]);
});

test('복원: 응답 전에 저장된 활성 카드는 "응답 전 종료됨(IDE 재시작)" 기록 줄이 되고 정제문 인용은 없다', () => {
  const saved: Msg[] = [{ id: 'u1', role: 'user', content: '/clarify' }, card()];
  const out = restoreMessages(saved);
  assert.equal(out.some(m => m.subType === 'refinedConfirmation/request'), false);
  assert.deepEqual(texts(out), ['✔ 정제문 확인: 응답 전 종료됨(IDE 재시작)', `📌 구현 전 확인 필요: ${NOTE}`]);
  // 카드가 없는 저장 채팅은 그대로(같은 내용)
  const plain: Msg[] = [{ id: 'u1', role: 'user', content: '/clarify' }, { id: 'rec_1', role: 'ai', subType: 'choice_record', content: '✔ 정제문 확인: 이대로 분석' }];
  assert.deepEqual(restoreMessages(plain), plain);
});

test('기록 줄 문구: 이유 5가지', () => {
  assert.equal(refinedCloseRecords('asis', 'x', undefined)[0], '✔ 정제문 확인: 이대로 분석');
  assert.equal(refinedCloseRecords('edited', 'x', undefined)[0], '✔ 정제문 확인: 수정한 내용으로 분석');
  assert.equal(refinedCloseRecords('cancel', 'x', undefined)[0], '✔ 정제문 확인: 취소');
  assert.equal(refinedCloseRecords('timeout', 'x', undefined)[0], '✔ 정제문 확인: 응답 없음(5분, 정제문 그대로 진행)');
  assert.equal(refinedCloseRecords('orphan', 'x', undefined)[0], '✔ 정제문 확인: 응답 전 종료됨(IDE 재시작)');
});
