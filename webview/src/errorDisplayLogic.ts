// 짝이 되는 메시지가 없어 버려지던 백엔드 오류(error)를 대화창에 오류 말풍선으로 보이는 순수 로직(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

/** 내부 정보가 섞일 수 있는 오류 본문 대신 보이는 고정 문구. */
export const FIXED_ERROR_TEXT = '요청을 처리하지 못했습니다. 다시 시도해 주세요.';

/**
 * 본문에 내부 정보(예외 메시지, 서버 주소, 경로, 클래스명, 스택)가 섞였을 수 있는가.
 * 라우터는 예외를 "…오류가 발생했습니다: ${e.message}" 형식으로 보내므로(WebviewActionRouter.kt:184, :339, :449 등) 그 형식과,
 * 주소·경로·예외 이름 같은 표지가 있으면 섞인 것으로 본다. 고정 안내 문장("분석 세션이 만료되었거나…")은 섞이지 않은 것으로 본다.
 */
export const mayContainInternalDetail = (raw: string): boolean =>
  /오류가 발생했습니다:\s*\S/.test(raw) ||
  /https?:\/\//.test(raw) ||
  /\b[A-Za-z_$][\w$]*(Exception|Error)\b/.test(raw) ||
  /\bat\s+[\w.$]+\(/.test(raw) ||
  /[A-Za-z]:[\\/]/.test(raw) ||
  /(^|\s)\/[\w.-]+\/[\w.-]+/.test(raw);

/** 화면에 보일 오류 문구: 내부 정보가 섞였을 수 있으면 고정 문구, 아니면 원문. 원문은 화면에 내지 않는다. */
export const displayErrorText = (raw: string | null | undefined): string => {
  const text = (raw ?? '').trim();
  if (text === '' || mayContainInternalDetail(text)) return FIXED_ERROR_TEXT;
  return text;
};

export type ErrorBubble = {
  id: string;
  role: 'ai';
  subType: 'error';
  content: string;
  isError: true;
  isLoading: false;
};

/** 오류 말풍선 하나. id는 시각과 난수로 만들어 연속 오류가 서로 덮지 않는다. */
export const makeErrorBubble = (raw: string | null | undefined, now: number, rand: string): ErrorBubble => ({
  id: `error_${now}_${rand}`,
  role: 'ai',
  subType: 'error',
  content: displayErrorText(raw),
  isError: true,
  isLoading: false,
});

/** 메시지 목록 끝에 오류 말풍선을 붙인다(기존 메시지는 건드리지 않는다). */
export const appendErrorBubble = <T extends { id: string }>(messages: T[], raw: string | null | undefined, now: number, rand: string): Array<T | ErrorBubble> =>
  [...messages, makeErrorBubble(raw, now, rand)];
