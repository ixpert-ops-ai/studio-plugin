// 분석 시작 전 정제문 확인 카드의 순수 로직(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

export type RefinedConfirmPayload = {
  projectId: string;
  originalRequirement: string;
  refinedRequirement: string;
  message?: string;
};

/** 카드 데이터를 읽는다. 깨졌거나 필수 값이 없으면 null(카드는 불러오기 실패 안내를 보인다). */
export const parsePayload = (raw: string): RefinedConfirmPayload | null => {
  try {
    const d = JSON.parse(raw);
    if (!d || typeof d.projectId !== 'string' || typeof d.refinedRequirement !== 'string') return null;
    return {
      projectId: d.projectId,
      originalRequirement: typeof d.originalRequirement === 'string' ? d.originalRequirement : '',
      refinedRequirement: d.refinedRequirement,
      message: typeof d.message === 'string' ? d.message : undefined,
    };
  } catch {
    return null;
  }
};

export type EditState = {
  /** 편집 칸이 비어 있다(공백만 있어도 빈 것). 비어 있으면 어떤 진행 버튼도 쓸 수 없다. */
  blank: boolean;
  /** 보여 준 정제문과 다르다(앞뒤 공백 차이는 같은 것으로 본다). */
  changed: boolean;
  /** "이대로 분석": 비어 있지 않을 때만. 편집한 내용은 쓰지 않고 보여 준 정제문으로 진행한다. */
  canProceedAsIs: boolean;
  /** "수정한 내용으로 분석": 비어 있지 않고 고쳤을 때만. */
  canProceedEdited: boolean;
};

export const evaluateEdit = (shown: string, edited: string): EditState => {
  const blank = edited.trim().length === 0;
  const changed = edited.trim() !== shown.trim();
  return { blank, changed, canProceedAsIs: !blank, canProceedEdited: !blank && changed };
};

export type ConfirmAction = 'asis' | 'edited';

/**
 * 백엔드로 보내는 응답 본문. "이대로 분석"은 editedText가 없고, "수정한 내용으로 분석"은 앞뒤 공백을 뗀 고친 문장이 들어간다.
 * 진행할 수 없는 상태(빈 칸, 고치지 않았는데 edited)면 null이라 아무것도 보내지 않는다.
 */
export const buildSubmitText = (
  projectId: string,
  shown: string,
  edited: string,
  action: ConfirmAction,
): string | null => {
  const s = evaluateEdit(shown, edited);
  if (action === 'asis') return s.canProceedAsIs ? JSON.stringify({ projectId }) : null;
  return s.canProceedEdited ? JSON.stringify({ projectId, editedText: edited.trim() }) : null;
};

export const buildCancelText = (projectId: string): string => JSON.stringify({ projectId });

/** 취소 안내 등 오류가 아닌 안내 메시지(analyze_notice)를 대화창에 붙이는 말풍선. error 형식과 달리 isError가 없다. */
export type NoticeBubble = {
  id: string;
  role: 'ai';
  subType: 'analyze_notice';
  content: string;
  isLoading: false;
};

export const makeNoticeBubble = (content: string | null | undefined, now: number, rand: string): NoticeBubble => ({
  id: `notice_${now}_${rand}`,
  role: 'ai',
  subType: 'analyze_notice',
  content: (content ?? '').trim(),
  isLoading: false,
});
