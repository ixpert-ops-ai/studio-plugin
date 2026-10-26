// clarify 확정 뒤 오류(특히 세션 만료)를 다루는 순수 로직(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

/** 백엔드가 세션을 찾지 못했을 때 보내는 고정 오류 문구의 일부(WebviewActionRouter의 /clarify-confirm, /clarify-utterance). */
export const SESSION_EXPIRED_KEY = '분석 세션이 만료되었거나 유효하지 않습니다';

export const isSessionExpiredError = (content: string | null | undefined): boolean =>
  (content ?? '').includes(SESSION_EXPIRED_KEY);

export type ClarifyFormState = {
  /** 확정을 보냈고 아직 분석 시작·오류·확인 카드 중 어느 것도 받지 못했다. */
  confirmPending: boolean;
  /** 화면이 "확인이 제출되었습니다" 상태다. */
  submitted: boolean;
};

export type ClarifyMessageOutcome = {
  /** 확정 대기를 끝낸다. */
  endConfirmPending: boolean;
  /** "확인이 제출되었습니다" 문구를 걷고 다시 확정할 수 있게 한다. */
  revertSubmitted: boolean;
  /** 세션이 끝났으니 "같은 요구사항으로 다시 시작" 안내를 보인다. */
  sessionExpired: boolean;
};

const NO_CHANGE: ClarifyMessageOutcome = { endConfirmPending: false, revertSubmitted: false, sessionExpired: false };

/** 확정이 받아들여졌다는 신호: 분석 시작, 취소 안내, 정제문 확인 카드. */
const ACCEPTED = ['analyze_start', 'analyze_notice', 'refinedConfirmation/request'];

/**
 * 백엔드 메시지가 clarify 카드에 주는 영향.
 * - 확정 뒤 받은 오류: 제출 문구를 걷는다. 세션 만료 오류면 다시 시작 안내를 보인다.
 * - 발화를 보내다 받은 세션 만료 오류(아직 제출 전): 다시 시작 안내를 보인다.
 * - 분석이 이미 시작된 뒤의 오류(확정 대기가 끝난 뒤)는 건드리지 않는다.
 */
export const onClarifyBackendMessage = (state: ClarifyFormState, subType: string | undefined, content?: string | null): ClarifyMessageOutcome => {
  if (subType !== undefined && ACCEPTED.includes(subType)) {
    return state.confirmPending ? { endConfirmPending: true, revertSubmitted: false, sessionExpired: false } : NO_CHANGE;
  }
  if (subType === 'error') {
    const expired = isSessionExpiredError(content);
    const interactive = state.confirmPending || !state.submitted;
    return {
      endConfirmPending: state.confirmPending,
      revertSubmitted: state.confirmPending,
      sessionExpired: expired && interactive,
    };
  }
  return NO_CHANGE;
};

/** 다시 시작할 때 /clarify로 보내는 요청: 원 요구사항 그대로(대화 발화는 다시 보내지 않는다). */
export const restartRequest = (originalRequirement: string): { command: string; text: string } => ({
  command: '/clarify',
  text: originalRequirement,
});

/** 안내 문장에 보여 줄 이전 발화 목록: 서버가 기록한 사용자 발화와 이 화면에서 보낸 발화를 합치고 중복과 원 요구사항은 뺀다. */
export const previousStatements = (
  originalRequirement: string,
  dialogueHistory: Array<{ role: string; content: string }> | null | undefined,
  sentUtterances: string[],
): string[] => {
  const seen = new Set<string>([originalRequirement.trim()]);
  const out: string[] = [];
  const add = (s: string) => {
    const t = s.trim();
    if (t === '' || seen.has(t)) return;
    seen.add(t);
    out.push(t);
  };
  (dialogueHistory ?? []).filter(d => d.role === 'user').forEach(d => add(d.content));
  sentUtterances.forEach(add);
  return out;
};
