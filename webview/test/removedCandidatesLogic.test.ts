// 접힌 "제외된 후보 N개" 패널의 로직 테스트. 웹뷰에는 테스트 도구가 없어 의존성을 추가하지 않고 node 내장 테스트 러너를 쓴다(타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/removedCandidatesLogic.test.ts
// 컴포넌트의 "접힘 기본값"과 클릭 흐름은 React 렌더가 필요해 여기서 다루지 않는다(IDE 체크리스트, docs B-58).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  applyRestoreReply,
  buildRestoreRequest,
  canRestore,
  fileName,
  hasUserExcludedBadge,
  needsScroll,
  panelTitle,
  parseRemovedPayload,
  pruneSelection,
  reasonLine,
  restoreNoticeLines,
  toggleSelection,
  type RemovedCandidate,
} from '../src/removedCandidatesLogic.ts';

const list: RemovedCandidate[] = [
  { path: 'src/admin/AdminService.java', source: 'USER_EXCLUDED', stageRank: null },
  { path: 'src/org/OrganizationRepository.java', source: 'STAGE3_EXPLICIT', stageRank: 2, reason: '조회 조건만 바뀌므로 수정 불필요' },
  { path: 'src/org/OrganizationDto.java', source: 'STAGE3_IMPLICIT', stageRank: 5 },
];

test('제목은 "제외된 후보 N개"', () => {
  assert.equal(panelTitle(3), '제외된 후보 3개');
  assert.equal(panelTitle(1), '제외된 후보 1개');
});

test('"제외 요청" 배지는 USER_EXCLUDED에만', () => {
  assert.deepEqual(list.map(hasUserExcludedBadge), [true, false, false]);
});

test('reason은 있을 때만 한 줄', () => {
  assert.deepEqual(list.map(reasonLine), [null, '조회 조건만 바뀌므로 수정 불필요', null]);
  assert.equal(reasonLine({ path: 'a', source: 'STAGE3_EXPLICIT', reason: '   ' }), null);
});

test('파일명은 경로의 마지막 부분', () => {
  assert.equal(fileName('src/admin/AdminService.java'), 'AdminService.java');
  assert.equal(fileName('C:\\x\\Y.java'), 'Y.java');
});

test('10개를 넘으면 스크롤', () => {
  assert.equal(needsScroll(10), false);
  assert.equal(needsScroll(11), true);
});

test('체크 전환과 버튼 활성 조건: 하나 이상 체크했을 때만 켠다', () => {
  assert.equal(canRestore([], list), false);
  const one = toggleSelection([], list[1].path);
  assert.equal(canRestore(one, list), true);
  assert.equal(canRestore(toggleSelection(one, list[1].path), list), false);
  // 목록에 없는 경로만 체크된 상태(되살린 뒤 사라진 항목)는 활성이 아니다
  assert.equal(canRestore(['src/gone/X.java'], list), false);
  assert.deepEqual(pruneSelection(['src/gone/X.java', list[2].path], list), [list[2].path]);
});

test('/restore 요청: 명령, requestId, 경로는 체크한 순서가 아니라 목록 순서(다시 정렬하지 않음)', () => {
  const req = buildRestoreRequest([list[2].path, list[0].path], list, 'analyze_1');
  assert.equal(req.command, '/restore');
  assert.equal(req.requestId, 'analyze_1');
  assert.deepEqual(JSON.parse(req.text), { paths: [list[0].path, list[2].path] });
});

test('되살린 뒤 N 감소와 결과 안내', () => {
  const reply = { restored: [list[1].path], ignored: [], removedCandidates: [list[0], list[2]] };
  const next = applyRestoreReply(list, reply);
  assert.equal(next.candidates.length, 2);
  assert.equal(panelTitle(next.candidates.length), '제외된 후보 2개');
  assert.deepEqual(next.candidates.map(c => c.path), [list[0].path, list[2].path]); // 받은 순서 그대로
  assert.deepEqual(restoreNoticeLines(next.notice), ['1개를 되살렸습니다. 구현 대상에 포함됩니다.']);
});

test('응답에 남은 목록이 없으면 되살린 경로를 뺀 현재 목록을 쓴다', () => {
  const next = applyRestoreReply(list, { restored: [list[0].path, list[1].path], ignored: [] });
  assert.deepEqual(next.candidates.map(c => c.path), [list[2].path]);
});

test('다 비면 목록이 비고 안내는 남는다(패널은 숨기고 안내만 보인다)', () => {
  const next = applyRestoreReply(list, { restored: list.map(c => c.path), ignored: [], removedCandidates: [] });
  assert.equal(next.candidates.length, 0);
  assert.deepEqual(restoreNoticeLines(next.notice), ['3개를 되살렸습니다. 구현 대상에 포함됩니다.']);
});

test('무시된 경로 안내 한 줄', () => {
  const next = applyRestoreReply(list, { restored: [list[1].path], ignored: ['src/gone/Old.java', 'src/gone/Older.java'], removedCandidates: [list[0], list[2]] });
  assert.deepEqual(restoreNoticeLines(next.notice), [
    '1개를 되살렸습니다. 구현 대상에 포함됩니다.',
    'Old.java, Older.java은(는) 되살릴 수 없었습니다',
  ]);
  // 되살린 것이 없고 무시만 있는 경우에도 무시 안내는 나온다
  assert.deepEqual(restoreNoticeLines({ restored: 0, ignored: ['src/gone/Old.java'] }), ['Old.java은(는) 되살릴 수 없었습니다']);
});

test('analyze_removed 본문 해석: 목록 순서 유지, 비었거나 없거나 깨진 본문은 null(예전 결과는 패널 없음)', () => {
  const parsed = parseRemovedPayload(JSON.stringify({ candidates: list }));
  assert.deepEqual(parsed?.map(c => c.path), list.map(c => c.path));
  assert.equal(parseRemovedPayload(JSON.stringify({ candidates: [] })), null);
  assert.equal(parseRemovedPayload('{}'), null);
  assert.equal(parseRemovedPayload('not json'), null);
});
