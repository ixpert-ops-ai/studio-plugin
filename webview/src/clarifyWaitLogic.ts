// clarify 대기 중 진행 표시의 순수 로직(React 없음). 사실만 보인다: 대기 중이라는 것과 경과 초뿐이고 단계, 퍼센트, 예상 남은 시간은 만들지 않는다.
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

/** idle: 대기 없음, interpreting: 발화를 보내고 해석 응답을 기다림, confirming: 확인을 보내고 분석이 시작되길 기다림. */
export type WaitPhase = 'idle' | 'interpreting' | 'confirming';

export type WaitState = { phase: WaitPhase; startedAt: number | null };

export const IDLE: WaitState = { phase: 'idle', startedAt: null };

/** 백엔드가 보내는 메시지 중 대기를 끝내는 것: 새 대화 카드(해석 응답), 분석 시작, 오류, 취소, 정제문 확인 카드(사용자 응답을 기다리는 중이라 분석 대기가 아니다), 취소 안내. */
export const END_SUBTYPES = ['analyze_clarify', 'analyze_start', 'error', 'task_cancelled', 'refinedConfirmation/request', 'analyze_notice'];

/** 응답이 끝내 오지 않을 때 표시가 영영 남지 않게 하는 안전 상한(운영 타임아웃의 두 배, 기본 300초). 이 값은 경과를 알리는 용도가 아니다. */
export const MAX_WAIT_MS = 600_000;

const isWaiting = (s: WaitState): boolean => s.phase !== 'idle';

/** 대기를 시작한다. 이미 대기 중이면 처음 시작 시각을 유지한다(연속 전송이 시계를 되감지 않는다). */
export const startWait = (state: WaitState, phase: Exclude<WaitPhase, 'idle'>, now: number): WaitState =>
  isWaiting(state) ? state : { phase, startedAt: now };

/** 새 발화를 보낼 수 있는가: 해석을 기다리는 중이거나 확인을 이미 보냈다면 보내지 않는다. */
export const canSendUtterance = (state: WaitState, text: string, isSubmitted: boolean): boolean =>
  text.trim().length > 0 && !isWaiting(state) && !isSubmitted;

/** 백엔드 메시지에 따른 상태 변화: 대기를 끝내는 subType이면 idle, 아니면 그대로(요약 도착 등은 대기를 끝내지 않는다). */
export const onBackendMessage = (state: WaitState, subType: string | undefined): WaitState =>
  isWaiting(state) && subType !== undefined && END_SUBTYPES.includes(subType) ? IDLE : state;

/** 경과 초(내림). 대기 중이 아니면 0. */
export const elapsedSeconds = (state: WaitState, now: number): number =>
  isWaiting(state) && state.startedAt !== null ? Math.max(0, Math.floor((now - state.startedAt) / 1000)) : 0;

/** 안전 상한을 넘으면 표시를 내린다. */
export const expireIfStale = (state: WaitState, now: number): WaitState =>
  isWaiting(state) && state.startedAt !== null && now - state.startedAt >= MAX_WAIT_MS ? IDLE : state;

export const INTERPRETING_TEXT = '요청을 해석하는 중입니다';

/** 확인 뒤 표시: 기존 문구는 그대로 두고 경과 초만 붙인다. */
export const confirmingElapsedText = (seconds: number): string => `경과 ${seconds}초`;
