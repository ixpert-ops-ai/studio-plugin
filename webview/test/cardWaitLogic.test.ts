// 카드 대기 상단 문구와 탐색 힌트 카드 표시 문구 로직 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/cardWaitLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RUNNING_TEXT, WAITING_TEXT, applyCardStatus, cardPendingAfter, submittedStatusText } from '../src/cardWaitLogic.ts';
import { localNameLabel, toUserWording } from '../src/explorerDisplayLogic.ts';

const loading = { id: 'a', isLoading: true, currentStatus: '🔍 프로젝트 코드 구조를 바탕으로 요구사항을 구체화하고 있습니다...' };
const streaming = { id: 'b', isLoading: false, currentStatus: undefined };

test('카드 4종의 요청이 오면 진행 중 메시지 문구가 대기 문구가 된다', () => {
  for (const sub of ['refinedConfirmation/request', 'scopeSelection/showTree', 'l1Clarification/request', 'stage3Confirmation/request']) {
    const out = applyCardStatus([loading, streaming], sub);
    assert.equal(out[0].currentStatus, WAITING_TEXT);
    assert.equal(out[1], streaming);
  }
});

test('카드 응답 뒤에는 진행 문구로 돌아간다', () => {
  for (const sub of ['refinedConfirmation/hide', 'scopeSelection/hideTree', 'l1Clarification/hide', 'stage3Confirmation/hide', 'refinedConfirmation/expired']) {
    assert.equal(applyCardStatus([{ ...loading, currentStatus: WAITING_TEXT }], sub)[0].currentStatus, RUNNING_TEXT);
  }
});

test('다른 메시지는 문구를 바꾸지 않고 같은 배열을 돌려준다', () => {
  const msgs = [loading];
  assert.equal(applyCardStatus(msgs, 'task_chunk'), msgs);
  assert.equal(applyCardStatus(msgs, undefined), msgs);
});

test('대기 여부와 제출 뒤 문구', () => {
  assert.equal(cardPendingAfter(false, 'scopeSelection/showTree'), true);
  assert.equal(cardPendingAfter(true, 'scopeSelection/hideTree'), false);
  assert.equal(cardPendingAfter(true, 'chat'), true);
  assert.equal(submittedStatusText(true), '✓ 확인이 제출되었습니다. 확인을 기다리고 있습니다...');
  assert.equal(submittedStatusText(false), '✓ 확인이 제출되었습니다. 분석을 진행 중입니다...');
});

test('탐색 힌트 카드: 메타그래프는 프로젝트 코드 구조로, 주석 없음 칸은 비운다', () => {
  assert.equal(toUserWording("'survey'로 메타그래프를 검색하겠습니다. 메타그래프 기준"), "'survey'로 프로젝트 코드 구조를 검색하겠습니다. 프로젝트 코드 구조 기준");
  assert.equal(toUserWording(undefined), '');
  assert.equal(localNameLabel('주석 없음'), '');
  assert.equal(localNameLabel(undefined), '');
  assert.equal(localNameLabel('  '), '');
  assert.equal(localNameLabel('설문클로징 혜택링크'), '(설문클로징 혜택링크)');
});
