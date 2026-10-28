// 분석 미완료 "다시 시도" 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/incompleteAnalysisLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseIncompleteDetail, parseIncompletePayload, retryRequest } from '../src/incompleteAnalysisLogic.ts';

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

test("재시도 키가 있으면 /analyze-retry로 같은 인텐트를 가리킨다(발화는 보내지 않는다)", () => {
  assert.deepEqual(retryRequest("같은 정제문", "retry_1_123"), { command: "/analyze-retry", text: "같은 정제문", retryKey: "retry_1_123" });
  assert.deepEqual(retryRequest("같은 정제문"), { command: "/analyze", text: "같은 정제문" });
});

test("본문에서 재시도 키를 함께 읽는다(없으면 요구사항만)", () => {
  assert.deepEqual(parseIncompleteDetail(JSON.stringify({ requirement: "r", retryKey: "k1" })), { requirement: "r", retryKey: "k1" });
  assert.deepEqual(parseIncompleteDetail(JSON.stringify({ requirement: "r" })), { requirement: "r" });
  assert.deepEqual(parseIncompleteDetail(JSON.stringify({ requirement: "r", retryKey: "  " })), { requirement: "r" });
  assert.equal(parseIncompleteDetail("not json"), null);
});
