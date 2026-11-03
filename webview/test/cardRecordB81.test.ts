// B-81 7, 9, 10: 건너뛰기·취소·시간 초과 기록 줄, 결과 뒤 Stage 0 카드 완료 처리, 감지 영역 칩 필터 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/cardRecordB81.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  appendRecord,
  restoreMessages,
  safeRecordPosition,
  splitWithRecords,
  completeClarifyCards,
  hintSkipRecord,
  refinedCancelRecord,
  scopeSkipRecord,
  stage3CandidateCount,
  stage3SkipRecord,
  timeoutRecord,
} from '../src/chatRestoreLogic.ts';
import { visibleDomainChips } from '../src/explorerDisplayLogic.ts';

test('건너뛰기·취소 기록 줄은 선택하지 않았다는 사실을 말한다', () => {
  assert.equal(scopeSkipRecord(), '✔ 읽을 범위 선택: 전체(선택 안 함)');
  assert.equal(hintSkipRecord(), '✔ 탐색 힌트: 건너뜀(자율 탐색)');
  assert.equal(stage3SkipRecord(13), '✔ 최종 검증 대상 선택: 건너뜀(전체 13개 진행)');
  assert.equal(stage3SkipRecord(0), '✔ 최종 검증 대상 선택: 건너뜀(전체 진행)');
  assert.equal(refinedCancelRecord(), '✖ 정제문 확인: 취소(분석을 진행하지 않음)');
});

test('5분 시간 초과 안내는 같은 모양의 기록 줄로 바뀐다', () => {
  assert.equal(timeoutRecord('응답이 없어 전체 프로젝트로 진행했습니다.', 0), '✔ 읽을 범위 선택: 전체(5분 응답 없음, 자동 진행)');
  assert.equal(timeoutRecord('응답이 없어 힌트 없이 진행했습니다.', 0), '✔ 탐색 힌트: 없음(5분 응답 없음, 자동 진행)');
  assert.equal(timeoutRecord('응답이 없어 전체 후보로 진행했습니다.', 13), '✔ 최종 검증 대상 선택: 전체 13개 진행(5분 응답 없음, 자동 진행)');
  assert.equal(timeoutRecord('응답이 없어 전체 후보로 진행했습니다.', 0), '✔ 최종 검증 대상 선택: 전체 진행(5분 응답 없음, 자동 진행)');
  assert.equal(timeoutRecord('분석을 취소했습니다.', 13), null);
  assert.equal(timeoutRecord(undefined, 13), null);
});

test('Stage 3 카드 payload의 후보 수', () => {
  assert.equal(stage3CandidateCount(JSON.stringify({ candidates: [{ path: 'a' }, { path: 'b' }] })), 2);
  assert.equal(stage3CandidateCount('깨진 JSON'), 0);
  assert.equal(stage3CandidateCount(undefined), 0);
});

test('결과가 나오면 그 앞의 Stage 0 카드가 완료됨이 되고 뒤의 카드와 결과 없는 경우는 그대로다', () => {
  const msgs = [
    { id: 'c1', subType: 'analyze_clarify' },
    { id: 'rec', subType: 'choice_record' },
    { id: 'analyze_1', subType: 'task_chunk' },
    { id: 'c2', subType: 'analyze_clarify' },
  ];
  const out = completeClarifyCards(msgs, 'analyze_1');
  assert.equal((out[0] as { clarifyCompleted?: boolean }).clarifyCompleted, true);
  assert.equal((out[3] as { clarifyCompleted?: boolean }).clarifyCompleted, undefined, '결과 뒤의 카드는 그대로');
  assert.equal(completeClarifyCards(msgs, 'nothing'), msgs, '결과 메시지가 없으면 그대로');
  assert.equal(completeClarifyCards(msgs, undefined), msgs);
});

test('감지 영역 칩에서 계층 이름과 같은 단어의 중복을 뺀다', () => {
  assert.deepEqual(
    visibleDomainChips(['net.infobank.iss.survey', 'net.infobank.iss.survey.service', 'net.infobank.iss.survey.dao', 'net.infobank.iss.survey.dto', 'controller', 'impl', 'address', 'a.b.survey']),
    ['net.infobank.iss.survey', 'address'],
  );
  assert.deepEqual(visibleDomainChips(undefined), []);
  assert.deepEqual(visibleDomainChips(['Service', 'DAO', 'user']), ['user']);
});

// ── B-82 3-5: 기록 줄 위치 ──
const progress = '> > **(Stage 1) Discovery 시작**\n> 식별된 Seed 바탕으로 그래프 탐색 중...\n';

test('기록 위치는 직전 줄바꿈이다: 완성된 줄 끝, 완성되지 않은 마지막 줄 앞', () => {
  assert.equal(safeRecordPosition(progress), progress.length);
  assert.equal(safeRecordPosition(progress + '> 탐색된 파일 연관성 평'), progress.length); // 쓰다 만 줄은 그 줄 앞
  assert.equal(safeRecordPosition(''), 0);
  assert.equal(safeRecordPosition('줄바꿈 없는 한 줄'), 0);
});

test('코드 블록 중간에는 끼우지 않고 블록이 시작되는 줄 앞에 둔다', () => {
  const head = '진행 줄\n';
  const open = head + '```kotlin\nval a = 1\nval b = 2\n'; // 닫히지 않은 블록
  assert.equal(safeRecordPosition(open), head.length);
  const closed = open + '```\n'; // 닫힌 블록 뒤는 줄 끝
  assert.equal(safeRecordPosition(closed), closed.length);
});

test('표 중간(또는 표 바로 뒤)에는 끼우지 않고 표가 시작되는 줄 앞에 둔다', () => {
  const head = progress;
  const table = '| 순번 | 파일 |\n|:---:|:---|\n| 1 | A.java |\n';
  assert.equal(safeRecordPosition(head + table), head.length, '표가 이어질 수 있으므로 표 앞');
  assert.equal(safeRecordPosition(head + table + '| 2 | B.jav'), head.length, '쓰다 만 표 행도 표 앞');
  assert.equal(safeRecordPosition(head + table + '\n'), (head + table + '\n').length, '표가 끝난 뒤 빈 줄이면 줄 끝');
});

test('본문을 기록 위치에서 잘라 본문-기록-본문 순서로 돌려주고, 기록이 표·코드 블록을 가르지 않는다', () => {
  const body = progress + '> 최종 13개 선정 완료.\n' + '\n| 순번 | 파일 |\n|:---:|:---|\n| 1 | A.java |\n';
  const at1 = safeRecordPosition(progress);
  const at2 = safeRecordPosition(progress + '> 최종 13개 선정 완료.\n');
  const segs = splitWithRecords(body, [{ at: at2, text: 'R2' }, { at: at1, text: 'R1' }]); // 순서가 뒤바뀌어 들어와도 위치순
  assert.deepEqual(segs.map(s => s.kind + ':' + s.text.slice(0, 6)), ['md:> > **', 'rec:R1', 'md:> 최종 1', 'rec:R2', 'md:\n| 순번 ']);
  assert.equal(segs.filter(s => s.kind === 'md').map(s => s.text).join(''), body, '본문은 잘리거나 빠지지 않는다');
  for (const s of segs.filter(s => s.kind === 'md')) {
    const fences = s.text.split('\n').filter(l => l.startsWith('```')).length;
    assert.equal(fences % 2, 0);
  }
  // 같은 위치는 들어온 순서, 범위 밖은 끝으로, 기록 없으면 본문 한 조각
  assert.deepEqual(splitWithRecords('abc', [{ at: 1, text: 'A' }, { at: 1, text: 'B' }]).map(s => s.text), ['a', 'A', 'B', 'bc']);
  assert.deepEqual(splitWithRecords('abc', [{ at: 99, text: 'Z' }]).map(s => s.kind + s.text), ['mdabc', 'recZ']);
  assert.deepEqual(splitWithRecords('abc', undefined).map(s => s.text), ['abc']);
});

test('결과가 스트리밍 중이면 본문에 끼우고, 아니면 한 줄 메시지로 끝에 붙는다', () => {
  type M = { id: string; role: string; subType?: string; content?: string; isStreaming?: boolean; inlineRecords?: Array<{ at: number; text: string }> };
  const streaming: M[] = [{ id: 'u', role: 'user', content: 'x' }, { id: 'analyze_1', role: 'ai', subType: 'task_chunk', isStreaming: true, content: progress }];
  const out = appendRecord(streaming, 'rec1', '✔ 읽을 범위 선택: 전체(선택 안 함)');
  assert.equal(out.length, 2, '별도 메시지를 만들지 않는다');
  assert.deepEqual(out[1].inlineRecords, [{ at: progress.length, text: '✔ 읽을 범위 선택: 전체(선택 안 함)' }]);
  // 결과가 아직 없거나(분석 시작 줄) 끝난 뒤면 한 줄 메시지
  const before: M[] = [{ id: 'analyze_1', role: 'ai', subType: 'analyze_start', content: '' }];
  const out2 = appendRecord(before, 'rec2', '✔ 확정한 정제문: x');
  assert.equal(out2.length, 2);
  assert.equal(out2[1].subType, 'choice_record');
  const done: M[] = [{ id: 'analyze_1', role: 'ai', subType: 'task_chunk', isStreaming: false, content: progress }];
  assert.equal(appendRecord(done, 'rec3', 'x').length, 2);
});

test('복원 순서: 저장된 메시지를 복원해도 기록은 같은 자리에 남는다', () => {
  type M = { id: string; role: string; subType?: string; content?: string; isStreaming?: boolean; inlineRecords?: Array<{ at: number; text: string }> };
  let msgs: M[] = [{ id: 'analyze_1', role: 'ai', subType: 'task_chunk', isStreaming: true, content: progress }];
  msgs = appendRecord(msgs, 'r1', 'R1');
  msgs = msgs.map(m => ({ ...m, content: (m.content ?? '') + '> 최종 13개 선정 완료.\n' }));
  msgs = appendRecord(msgs, 'r2', 'R2');
  const live = splitWithRecords(msgs[0].content ?? '', msgs[0].inlineRecords).map(s => s.kind + ':' + s.text.slice(0, 5));
  const saved: M[] = JSON.parse(JSON.stringify(msgs)); // 채팅 저장·불러오기
  const restored = restoreMessages(saved);
  assert.equal(restored[0].isStreaming, false, '복원하면 진행 중 표시는 꺼진다');
  assert.deepEqual(splitWithRecords(restored[0].content ?? '', restored[0].inlineRecords).map(s => s.kind + ':' + s.text.slice(0, 5)), live);
  assert.deepEqual(live, ['md:> > *', 'rec:R1', 'md:> 최종 ', 'rec:R2']);
});
