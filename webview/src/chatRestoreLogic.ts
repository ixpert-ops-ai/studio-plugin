// 저장된 채팅을 복원할 때의 화면 상태 정리와, 카드에서 고른 내용을 채팅에 남기는 한 줄 기록 문구(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

export type RestorableMessage = {
  id: string;
  role?: string;
  subType?: string;
  isLoading?: boolean;
  isStreaming?: boolean;
  currentStatus?: string;
  stepNotiStatus?: string;
  clarifyCompleted?: boolean;
};

/** Stage 0 카드의 확정이 받아들여져 분석이 이어졌다는 신호로 보는 뒤따른 메시지(clarifySessionLogic의 ACCEPTED와 그 뒤 결과 메시지). */
const AFTER_CONFIRM = [
  'analyze_start', 'analyze_notice', 'refinedConfirmation/request', 'scopeSelection/showTree', 'l1Clarification/request',
  'stage3Confirmation/request', 'analyze_removed', 'analyze_incomplete', 'analyze_restore', 'choice_record',
];

/**
 * 복원한 메시지의 화면 상태를 정리한다.
 * - 저장 시점에 진행 중이던 메시지(isLoading/isStreaming)는 지금 진행 중인 작업이 아니다. 그대로 두면 정지 버튼이 켜지고,
 *   누르면 진행 중인 분석이 없는데도 /cancel이 나간다. 진행 표시를 끄고 시작만 하고 끝나지 않은 단계 알림은 실패로 표시한다.
 * - 확정 뒤 분석이 이어진 Stage 0 카드는 clarifyCompleted를 켠다(복원 화면에서 읽기 전용 "완료됨"). 확정하지 않은 카드는 그대로 둔다.
 */
export const restoreMessages = <T extends RestorableMessage>(messages: T[]): T[] =>
  messages.map((m, i) => {
    let next: T = m;
    if (m.isLoading || m.isStreaming) {
      next = { ...next, isLoading: false, isStreaming: false, currentStatus: undefined };
    }
    if (m.subType === 'step_noti' && m.stepNotiStatus === 'started') {
      next = { ...next, stepNotiStatus: 'failed' };
    }
    if (m.subType === 'analyze_clarify' && messages.slice(i + 1).some(later => later.subType !== undefined && AFTER_CONFIRM.includes(later.subType))) {
      next = { ...next, clarifyCompleted: true };
    }
    return next;
  });

/** 완료된 카드의 대화 기록: 답을 받지 못한 마지막 질문도 어시스턴트 발화로 남긴다(이미 기록에 있으면 더하지 않는다). */
export const historyWithOpenQuestion = (
  history: Array<{ role: string; content: string }> | null | undefined,
  openQuestion: string | null | undefined,
): Array<{ role: string; content: string }> => {
  const base = history ?? [];
  const q = (openQuestion ?? '').trim();
  if (q === '' || base.some(t => t.role === 'assistant' && t.content.trim() === q)) return base;
  return [...base, { role: 'assistant', content: q }];
};

const oneLine = (s: string): string => s.replace(/\s+/g, ' ').trim();

const names = (paths: string[], max: number): string => {
  const base = paths.map(p => oneLine(p.replace(/\\/g, '/').split('/').filter(x => x !== '').pop() ?? p));
  return base.length <= max ? base.join(', ') : base.slice(0, max).join(', ') + ` 외 ${base.length - max}개`;
};

/** 정제문 카드의 제출 본문(JSON)에서 최종 문장을 고른다: 고쳤으면 editedText, 아니면 카드가 보여 준 정제문. */
export const finalRefinedText = (submitText: string, shownRefined: string): string => {
  try {
    const d = JSON.parse(submitText);
    if (d && typeof d.editedText === 'string' && d.editedText.trim() !== '') return d.editedText;
  } catch { /* 본문이 깨졌으면 보여 준 정제문을 쓴다 */ }
  return shownRefined;
};

/** 정제문 확인 카드에서 확정한 문장. */
export const refinedRecord = (finalText: string): string => `✔ 확정한 정제문: ${oneLine(finalText)}`;

/** 탐색 힌트 카드에서 보낸 힌트(비어 있으면 힌트 없이 진행). */
export const hintRecord = (userHint: string | null | undefined): string => {
  const h = oneLine(userHint ?? '');
  return h === '' ? '✔ 탐색 힌트: 없음(그대로 진행)' : `✔ 탐색 힌트: ${h}`;
};

/** 읽을 범위(폴더·파일) 선택 카드에서 고른 경로. */
export const scopeRecord = (selectedPaths: string[]): string =>
  selectedPaths.length === 0 ? '✔ 읽을 범위 선택: 없음' : `✔ 읽을 범위 선택: ${selectedPaths.length}개(${names(selectedPaths, 4)})`;

/** Stage 3 최종 검증 대상 선택 카드에서 고른 파일. */
export const stage3Record = (selectedPaths: string[]): string =>
  selectedPaths.length === 0 ? '✔ 최종 검증 대상 선택: 없음' : `✔ 최종 검증 대상 선택: ${selectedPaths.length}개(${names(selectedPaths, 4)})`;
