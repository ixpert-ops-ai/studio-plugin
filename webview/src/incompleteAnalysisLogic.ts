// 파일 분석이 대체 경로(탐색기·검증기 실패)로 끝났을 때의 "다시 시도" 순수 로직(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

/** analyze_incomplete 본문({"requirement": "..."})에서 다시 시도에 쓸 요구사항을 읽는다. 읽을 수 없거나 비어 있으면 null(버튼을 보이지 않는다). */
export const parseIncompletePayload = (content: string | null | undefined): string | null => {
  try {
    const o = JSON.parse(content ?? '');
    const r = typeof o?.requirement === 'string' ? o.requirement.trim() : '';
    return r === '' ? null : r;
  } catch {
    return null;
  }
};

/** analyze_incomplete 본문에서 요구사항과 재시도 키(없을 수 있다)를 함께 읽는다. 요구사항을 읽을 수 없으면 null. */
export const parseIncompleteDetail = (content: string | null | undefined): { requirement: string; retryKey?: string } | null => {
  const requirement = parseIncompletePayload(content);
  if (requirement === null) return null;
  try {
    const key = JSON.parse(content ?? '')?.retryKey;
    return typeof key === 'string' && key.trim() !== '' ? { requirement, retryKey: key } : { requirement };
  } catch {
    return { requirement };
  }
};

/**
 * 다시 시도 요청(발화는 다시 보내지 않는다).
 * - 재시도 키가 있으면 /analyze-retry: 백엔드가 보관한 같은 인텐트(제외 파일, 제약, 정제문)로 다시 시작한다.
 * - 키가 없으면(인텐트 없이 시작한 분석) 분석에 쓴 같은 요구사항을 /analyze로 다시 보낸다.
 */
export const retryRequest = (requirement: string, retryKey?: string): { command: string; text: string; retryKey?: string } =>
  retryKey ? { command: '/analyze-retry', text: requirement, retryKey } : { command: '/analyze', text: requirement };

export const RETRY_LABEL = '다시 시도';
export const RETRYING_LABEL = '다시 시도하는 중...';
