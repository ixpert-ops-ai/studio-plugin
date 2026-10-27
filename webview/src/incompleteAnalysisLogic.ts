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

/** 다시 시도 요청: 분석에 쓴 같은 요구사항을 /analyze로 다시 보낸다(발화는 다시 보내지 않는다). */
export const retryRequest = (requirement: string): { command: string; text: string } => ({ command: '/analyze', text: requirement });

export const RETRY_LABEL = '다시 시도';
export const RETRYING_LABEL = '다시 시도하는 중...';
