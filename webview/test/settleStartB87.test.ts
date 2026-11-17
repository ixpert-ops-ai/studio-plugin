// B-87 (다)-1: Stage 0 카드가 도착하면 같은 id의 진행 줄(analyze_start) 로딩을 끈다(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/settleStartB87.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { settleStart } from '../src/chatRestoreLogic.ts';
import { applyCardStatus } from '../src/cardWaitLogic.ts';
import { makeErrorBubble } from '../src/errorDisplayLogic.ts';

type M = { id: string; role?: string; subType?: string; isLoading?: boolean; isStreaming?: boolean; currentStatus?: string };
const stopVisible = (ms: M[]) => ms.some(m => m.isLoading || m.isStreaming); // App.tsx 정지 버튼 표시 조건과 같다

const start = (id: string): M => ({ id, role: 'ai', subType: 'analyze_start', isLoading: true, currentStatus: '요구사항을 구체화하고 있습니다...' });

test('같은 id의 analyze_start 로딩이 꺼지고 문구도 지워진다', () => {
  const after = settleStart([{ id: 'u', role: 'user' }, start('clarify_1')], 'clarify_1');
  assert.equal(after[1].isLoading, false);
  assert.equal(after[1].currentStatus, undefined);
  assert.equal(stopVisible(after), false);
});

test('다른 id의 로딩(진행 중인 분석)과 다른 subType은 건드리지 않는다', () => {
  const running = start('analyze_9');
  const other: M = { id: 'clarify_1', subType: 'task_chunk', isStreaming: true };
  const before = [start('clarify_1'), running, other];
  const after = settleStart(before, 'clarify_1');
  assert.equal(after[0].isLoading, false);
  assert.deepEqual(after[1], running);
  assert.deepEqual(after[2], other);
  assert.equal(stopVisible(after), true); // 진행 중인 분석의 정지 버튼은 그대로 켜진다
});

test('id가 없거나 맞는 메시지가 없으면 변화가 없다(같은 배열)', () => {
  const before = [start('clarify_1')];
  assert.equal(settleStart(before, undefined), before);
  assert.deepEqual(settleStart(before, 'clarify_2'), before);
});

test('이미 풀린 메시지는 같은 값으로 남고, 카드 상태 문구 갱신이 풀린 메시지를 되살리지 않는다', () => {
  const settled = settleStart([start('clarify_1')], 'clarify_1');
  assert.deepEqual(settleStart(settled, 'clarify_1'), settled);
  const cardUp = applyCardStatus(settled, 'scopeSelection/showTree');
  assert.equal(cardUp[0].currentStatus, undefined);
  assert.equal(cardUp[0].isLoading, false);
});

test('카드 뒤 확정 실패·세션 만료 error는 오류 말풍선만 더하고 로딩이 남지 않는다', () => {
  const afterCard = settleStart([{ id: 'u', role: 'user' }, start('clarify_1'), { id: 'clarify_1', subType: 'analyze_clarify' }], 'clarify_1');
  for (const msg of ['분석 세션이 만료되었거나 유효하지 않습니다. `/clarify`를 다시 실행해주세요.', '분석을 시작하지 못했습니다: 연결 실패']) {
    const withError = [...afterCard, makeErrorBubble(msg, 1, 'abcde') as unknown as M];
    assert.equal(stopVisible(withError), false, msg);
  }
});
