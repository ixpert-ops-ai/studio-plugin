// Stage 1 탐색 힌트 카드의 화면 표시 문구 순수 로직(React 없음). 프롬프트·탐색기 입력은 바꾸지 않고 화면 출력만 바꾼다.
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

/** 사용자에게 보이는 문장에서 내부 용어 "메타그래프"를 "프로젝트 코드 구조"로 바꾼다. */
export const toUserWording = (text: string | undefined): string => (text ?? '').split('메타그래프').join('프로젝트 코드 구조');

/** 후보 옆 한글 이름 칸: 값이 없거나 "주석 없음"(주석이 없다는 내부 표시)이면 칸을 비운다. */
export const localNameLabel = (localName: string | undefined): string => {
  const v = (localName ?? '').trim();
  return v === '' || v === '주석 없음' ? '' : `(${v})`;
};
