// 짝이 되는 메시지가 없는 오류를 오류 말풍선으로 보이는 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/errorDisplayLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  FIXED_ERROR_TEXT,
  appendErrorBubble,
  displayErrorText,
  makeErrorBubble,
  mayContainInternalDetail,
} from '../src/errorDisplayLogic.ts';
import { IDLE, canSendUtterance, onBackendMessage, startWait } from '../src/clarifyWaitLogic.ts';

test('고정 안내 문장은 원문 그대로 보인다', () => {
  const raw = '분석 세션이 만료되었거나 유효하지 않습니다. `/clarify`를 다시 실행해주세요.';
  assert.equal(mayContainInternalDetail(raw), false);
  assert.equal(displayErrorText(raw), raw);
  assert.equal(displayErrorText('선택한 디렉토리에 분석 가능한 소스 파일이 없습니다.'), '선택한 디렉토리에 분석 가능한 소스 파일이 없습니다.');
  assert.equal(displayErrorText('분석이 취소되었습니다.'), '분석이 취소되었습니다.');
});

test('예외 메시지가 붙은 오류와 내부 표지(주소, 예외 이름, 경로, 스택)는 고정 문구로 바꾼다', () => {
  for (const raw of [
    '발화 처리 중 오류가 발생했습니다: USER_UTTERED PENDING 항목(id=ref:src/a/B.java)에 utteredIdentifier가 누락되었습니다.',
    '발화 처리 중 오류가 발생했습니다: null',
    '요구사항 구체화 중 오류가 발생했습니다: [Error] OpenAI 서버 통신 실패 (http://vllm.example/v1/chat/completions)',
    'java.lang.IllegalStateException: boom',
    '    at net.ib.ixpert.Foo.bar(Foo.kt:12)',
    'C:\\Users\\x\\proj\\a.txt 를 열 수 없습니다',
    '/home/u/proj/a.txt 를 열 수 없습니다',
  ]) {
    assert.equal(mayContainInternalDetail(raw), true, raw);
    assert.equal(displayErrorText(raw), FIXED_ERROR_TEXT, raw);
    assert.ok(!displayErrorText(raw).includes('IllegalState') && !displayErrorText(raw).includes('http'));
  }
  assert.equal(FIXED_ERROR_TEXT, '요청을 처리하지 못했습니다. 다시 시도해 주세요.');
});

test('빈 오류 본문도 고정 문구로 보인다', () => {
  assert.equal(displayErrorText(''), FIXED_ERROR_TEXT);
  assert.equal(displayErrorText(undefined), FIXED_ERROR_TEXT);
  assert.equal(displayErrorText('   '), FIXED_ERROR_TEXT);
});

test('오류 말풍선은 내용이 있는 AI 오류 메시지이고 기존 메시지는 그대로 둔다', () => {
  const before = [{ id: 'm1', content: '기존' }];
  const after = appendErrorBubble(before, '분석 세션이 만료되었거나 유효하지 않습니다.', 1000, 'a1');
  assert.equal(after.length, 2);
  assert.deepEqual(after[0], before[0]);
  assert.deepEqual(after[1], { id: 'error_1000_a1', role: 'ai', subType: 'error', content: '분석 세션이 만료되었거나 유효하지 않습니다.', isError: true, isLoading: false });
  assert.equal(before.length, 1); // 입력 배열을 바꾸지 않는다
});

test('연속 오류는 서로 덮지 않고 각각 말풍선이 된다(같은 시각이어도 id가 다르다)', () => {
  let list: Array<{ id: string }> = [];
  list = appendErrorBubble(list, '첫 오류', 5000, 'x1');
  list = appendErrorBubble(list, '둘째 오류', 5000, 'y2');
  assert.equal(list.length, 2);
  assert.notEqual(list[0].id, list[1].id);
  assert.deepEqual(list.map(m => (m as { content?: string }).content), ['첫 오류', '둘째 오류']);
  assert.equal(makeErrorBubble('a', 1, 'r').id, 'error_1_r');
});

test('오류 뒤 정상 발화: 오류가 대기를 끝내고, 다시 보낸 발화는 정상 흐름(해석 응답)으로 끝난다', () => {
  let wait = startWait(IDLE, 'interpreting', 1000);
  assert.equal(canSendUtterance(wait, '두 번째 발화', false), false);
  wait = onBackendMessage(wait, 'error');            // 오류가 와서 표시가 내려간다
  assert.deepEqual(wait, IDLE);
  assert.equal(canSendUtterance(wait, '다시 보내는 발화', false), true);
  wait = startWait(wait, 'interpreting', 9000);       // 정상 발화
  assert.deepEqual(wait, { phase: 'interpreting', startedAt: 9000 });
  wait = onBackendMessage(wait, 'analyze_clarify');   // 정상 응답으로 끝난다
  assert.deepEqual(wait, IDLE);
});
