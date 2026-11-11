// 분석 시작 전 정제문 확인 카드의 순수 로직(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

export type RefinedConfirmPayload = {
  projectId: string;
  originalRequirement: string;
  refinedRequirement: string;
  message?: string;
  /** 정제문과 따로 보이는 "확인 필요" 문장(B-84). 정제문 칸을 고쳐도 카드에 그대로 남는다. 없으면 이 키가 없다. */
  decisionNote?: string;
  /** 이 카드를 만든 요청의 식별자(백엔드 rid + messageId). 응답에 같이 실어 보내고, 백엔드가 지금 기다리는 요청과 다르면 무시한다(B-85 1 F). 없으면 이 키가 없다. */
  requestId?: string;
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
      ...(typeof d.decisionNote === 'string' && d.decisionNote.trim().length > 0 ? { decisionNote: d.decisionNote } : {}),
      ...(typeof d.requestId === 'string' && d.requestId.trim().length > 0 ? { requestId: d.requestId } : {}),
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

/** 정제문 확인 카드 메시지의 subType. */
export const REFINED_CARD = 'refinedConfirmation/request';

/**
 * 정제문 확인 카드를 목록에서 뺀다(B-85 1). 카드를 빼면 카드를 감싼 대기 줄(타이머)과 버튼도 함께 사라진다.
 * 응답·취소·시간 초과 어느 경우든 같은 함수를 쓰고, 카드가 없으면 목록을 그대로 돌려준다(같은 종료 신호가 두 번 와도 안전).
 */
export const closeRefinedCard = <T extends { subType?: string }>(messages: T[]): T[] =>
  messages.some(m => m.subType === REFINED_CARD) ? messages.filter(m => m.subType !== REFINED_CARD) : messages;

/** 목록에 있는 정제문 확인 카드 한 장(없으면 undefined). */
export const findRefinedCard = <T extends { subType?: string }>(messages: T[]): T | undefined =>
  messages.find(m => m.subType === REFINED_CARD);

/**
 * 백엔드로 보내는 응답 본문. "이대로 분석"은 editedText가 없고, "수정한 내용으로 분석"은 앞뒤 공백을 뗀 고친 문장이 들어간다.
 * 진행할 수 없는 상태(빈 칸, 고치지 않았는데 edited)면 null이라 아무것도 보내지 않는다.
 */
export const buildSubmitText = (
  projectId: string,
  shown: string,
  edited: string,
  action: ConfirmAction,
  requestId?: string,
): string | null => {
  const s = evaluateEdit(shown, edited);
  // requestId는 카드 payload에 있었을 때만 같이 보낸다(없던 이전 카드는 이전 본문 그대로)
  const base = requestId ? { projectId, requestId } : { projectId };
  if (action === 'asis') return s.canProceedAsIs ? JSON.stringify(base) : null;
  return s.canProceedEdited ? JSON.stringify({ ...base, editedText: edited.trim() }) : null;
};

export const buildCancelText = (projectId: string, requestId?: string): string =>
  JSON.stringify(requestId ? { projectId, requestId } : { projectId });

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
