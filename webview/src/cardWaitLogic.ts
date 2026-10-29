// 확인 카드(정제문, 패키지 선택, 탐색 힌트, Stage 3)가 사용자 응답을 기다리는 동안의 상단 문구 순수 로직(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

export const WAITING_TEXT = '확인을 기다리고 있습니다';
export const RUNNING_TEXT = '분석을 진행 중입니다';

/** 사용자 응답을 기다리는 카드가 뜨는 메시지. */
export const CARD_REQUEST_SUBTYPES = ['refinedConfirmation/request', 'scopeSelection/showTree', 'l1Clarification/request', 'stage3Confirmation/request'];

/** 카드 응답(또는 만료)으로 카드가 내려가는 메시지. */
export const CARD_HIDE_SUBTYPES = ['refinedConfirmation/hide', 'refinedConfirmation/expired', 'scopeSelection/hideTree', 'l1Clarification/hide', 'stage3Confirmation/hide'];

export const isCardRequest = (subType: string | undefined): boolean => subType !== undefined && CARD_REQUEST_SUBTYPES.includes(subType);
export const isCardHide = (subType: string | undefined): boolean => subType !== undefined && CARD_HIDE_SUBTYPES.includes(subType);

type StatusMessage = { isLoading?: boolean; currentStatus?: string };

/** 카드가 뜨면 진행 중인 메시지의 상단 문구를 대기 문구로, 카드 응답 뒤에는 진행 문구로 바꾼다. 문구가 없는(스트리밍이 시작된) 메시지와 다른 subType은 그대로 둔다. */
export const applyCardStatus = <T extends StatusMessage>(messages: T[], subType: string | undefined): T[] => {
  const text = isCardRequest(subType) ? WAITING_TEXT : isCardHide(subType) ? RUNNING_TEXT : null;
  if (text === null) return messages;
  return messages.map(m => (m.isLoading && m.currentStatus !== undefined ? { ...m, currentStatus: text } : m));
};

/** 카드 대기 여부: 요청이면 true, 내려가면 false, 다른 메시지는 그대로. */
export const cardPendingAfter = (pending: boolean, subType: string | undefined): boolean =>
  isCardRequest(subType) ? true : isCardHide(subType) ? false : pending;

/** 확인 제출 뒤 문구(ClarifyForm): 카드를 기다리는 중이면 대기 문구, 아니면 진행 문구. */
// B-79: 카드를 기다리는 동안에는 대기 문구를 여기서 되풀이하지 않는다(진행 말풍선 상단 문구와 카드 위 대기 줄이 이미 "확인을 기다리고 있습니다"를 말한다).
export const submittedStatusText = (pending: boolean): string => pending ? '✓ 확인이 제출되었습니다.' : `✓ 확인이 제출되었습니다. ${RUNNING_TEXT}...`;

/** 카드 종류별 대기 안내: 사용자가 응답하지 않을 때 백엔드가 몇 초 뒤 무엇으로 진행하는지(백엔드 타임아웃과 같은 값). */
export const CARD_WAIT_INFO: Record<string, { timeoutSec: number; fallback: string }> = {
  scope: { timeoutSec: 300, fallback: '전체 프로젝트로' },
  l1: { timeoutSec: 300, fallback: '힌트 없이' },
  stage3: { timeoutSec: 300, fallback: '전체 후보로' },
  refined: { timeoutSec: 300, fallback: '위 정제된 문장 그대로' },
};

/** 경과 시간 표시: 60초 미만은 "N초", 이상은 "M분 S초". */
export const cardElapsedText = (seconds: number): string => {
  const s = Math.max(0, Math.floor(seconds));
  return s < 60 ? `${s}초` : `${Math.floor(s / 60)}분 ${s % 60}초`;
};

/** 카드 상단 대기 줄: 대기 상태, 경과 시간, 응답이 없을 때의 진행 방식. */
export const cardWaitLine = (kind: string, seconds: number): string => {
  const info = CARD_WAIT_INFO[kind];
  const tail = info ? ` (${Math.round(info.timeoutSec / 60)}분 안에 응답이 없으면 ${info.fallback} 진행합니다)` : '';
  return `확인을 기다리고 있습니다 · 경과 ${cardElapsedText(seconds)}${tail}`;
};

/** 카드가 화면(뷰포트) 안에 보이는가: 위쪽이 화면 아래쪽보다 위이고 아래쪽이 화면 위쪽보다 아래면 보인다. */
export const isCardInViewport = (top: number, bottom: number, viewportHeight: number): boolean => top < viewportHeight && bottom > 0;
