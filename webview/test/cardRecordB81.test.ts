// B-81 7, 9, 10: 건너뛰기·취소·시간 초과 기록 줄, 결과 뒤 Stage 0 카드 완료 처리, 감지 영역 칩 필터 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/cardRecordB81.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
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
