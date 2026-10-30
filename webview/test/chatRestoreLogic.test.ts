// 채팅 복원 정리와 카드 선택 기록 문구 테스트(Node 내장 러너, 타입 제거 실행).
// 실행(저장소 루트): node --experimental-strip-types --test webview/test/chatRestoreLogic.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  finalRefinedText,
  hintRecord,
  historyWithOpenQuestion,
  refinedRecord,
  restoreMessages,
  scopeRecord,
  stage3Record,
} from '../src/chatRestoreLogic.ts';

test('복원하면 진행 중이던 메시지의 로딩 표시가 꺼져 정지 버튼이 켜지지 않는다', () => {
  const restored = restoreMessages([
    { id: '1', role: 'user' },
    { id: '2', role: 'ai', isLoading: true, currentStatus: '분석 중' },
    { id: '3', role: 'ai', isStreaming: true },
  ]);
  assert.equal(restored.some(m => m.isLoading || m.isStreaming), false);
  assert.equal(restored[1].currentStatus, undefined);
});

test('시작만 하고 끝나지 않은 단계 알림은 실패로 표시하고 끝난 알림은 건드리지 않는다', () => {
  const restored = restoreMessages([
    { id: '1', subType: 'step_noti', stepNotiStatus: 'started' },
    { id: '2', subType: 'step_noti', stepNotiStatus: 'completed' },
  ]);
  assert.equal(restored[0].stepNotiStatus, 'failed');
  assert.equal(restored[1].stepNotiStatus, 'completed');
});

test('확정 뒤 분석이 이어진 Stage 0 카드만 완료됨으로 표시한다', () => {
  const restored = restoreMessages([
    { id: 'c1', subType: 'analyze_clarify' },
    { id: 'a1', subType: 'analyze_start' },
    { id: 'c2', subType: 'analyze_clarify' },
  ]);
  assert.equal(restored[0].clarifyCompleted, true);
  assert.equal(restored[2].clarifyCompleted, undefined);
});

test('카드 뒤에 분석 메시지가 없으면(확정하지 않았으면) 완료됨이 아니다', () => {
  const restored = restoreMessages([
    { id: 'u', role: 'user' },
    { id: 'c1', subType: 'analyze_clarify' },
  ]);
  assert.equal(restored[1].clarifyCompleted, undefined);
});

test('복원은 원본 배열과 메시지를 바꾸지 않는다', () => {
  const src = [{ id: '1', isLoading: true }];
  restoreMessages(src);
  assert.equal(src[0].isLoading, true);
});

test('완료된 카드의 대화 기록에 답을 받지 못한 질문이 어시스턴트 발화로 붙는다', () => {
  const h = historyWithOpenQuestion([{ role: 'user', content: '이메일은 마스킹' }], '응답자 이메일은 어디서 가져올까요?');
  assert.equal(h.length, 2);
  assert.deepEqual(h[1], { role: 'assistant', content: '응답자 이메일은 어디서 가져올까요?' });
});

test('질문이 이미 기록에 있거나 비어 있으면 더하지 않는다', () => {
  const base = [{ role: 'assistant', content: '질문입니다' }];
  assert.equal(historyWithOpenQuestion(base, '질문입니다').length, 1);
  assert.equal(historyWithOpenQuestion(base, null).length, 1);
  assert.equal(historyWithOpenQuestion(undefined, '  ').length, 0);
});

test('정제문 카드의 최종 문장은 고쳤으면 고친 문장, 아니면 보여 준 정제문이다', () => {
  assert.equal(finalRefinedText(JSON.stringify({ projectId: 'p', editedText: '고친 문장' }), '보여 준 문장'), '고친 문장');
  assert.equal(finalRefinedText(JSON.stringify({ projectId: 'p' }), '보여 준 문장'), '보여 준 문장');
  assert.equal(finalRefinedText('깨진 본문', '보여 준 문장'), '보여 준 문장');
});

test('카드 선택 기록은 한 줄이다', () => {
  assert.equal(refinedRecord('설문 상세 조회에 응답자 이메일을\n추가'), '✔ 확정한 정제문: 설문 상세 조회에 응답자 이메일을 추가');
  assert.equal(hintRecord('  '), '✔ 탐색 힌트: 없음(그대로 진행)');
  assert.equal(hintRecord('설문 매퍼 위주로'), '✔ 탐색 힌트: 설문 매퍼 위주로');
  assert.equal(scopeRecord(['a/b/SurveyDao.java', 'a/b/SurveyService.java']), '✔ 읽을 범위 선택: 2개(SurveyDao.java, SurveyService.java)');
  assert.equal(stage3Record([]), '✔ 최종 검증 대상 선택: 없음');
  assert.match(stage3Record(['1.java', '2.java', '3.java', '4.java', '5.java', '6.java']), /6개\(1\.java, 2\.java, 3\.java, 4\.java 외 2개\)/);
  assert.equal(/\n/.test(scopeRecord(['x/a\nb.java'])), false);
});
