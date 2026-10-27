// 분석 미완료 "다시 시도" 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/incompleteAnalysisLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseIncompletePayload, retryRequest } from '../src/incompleteAnalysisLogic.ts';

test('본문에서 같은 요구사항을 읽는다', () => {
  assert.equal(parseIncompletePayload(JSON.stringify({ requirement: '  응답자 이메일을 마스킹해 주세요  ' })), '응답자 이메일을 마스킹해 주세요');
});

test('읽을 수 없거나 비어 있으면 null(버튼 없음)', () => {
  assert.equal(parseIncompletePayload(''), null);
  assert.equal(parseIncompletePayload(undefined), null);
  assert.equal(parseIncompletePayload('not json'), null);
  assert.equal(parseIncompletePayload(JSON.stringify({ requirement: '   ' })), null);
  assert.equal(parseIncompletePayload(JSON.stringify({ other: 1 })), null);
});

test('다시 시도는 같은 문장을 /analyze로 보낸다', () => {
  assert.deepEqual(retryRequest('같은 정제문'), { command: '/analyze', text: '같은 정제문' });
});
