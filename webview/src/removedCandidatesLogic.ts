// 접힌 "제외된 후보 N개" 패널의 순수 로직(React 없음). 순서는 백엔드가 준 그대로 쓰고 화면에서 다시 정렬하지 않는다.
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

export type RemovedSource = 'STAGE3_EXPLICIT' | 'STAGE3_IMPLICIT' | 'USER_EXCLUDED';

export type RemovedCandidate = {
  path: string;
  source: RemovedSource;
  stageRank?: number | null;
  reason?: string | null;
};

/** 되살리기 결과 안내에 쓰는 값. restored는 되살린 개수, ignored는 되살릴 수 없었던 경로(목록에 없던 것). */
export type RestoreNotice = { restored: number; ignored: string[] };

/** /restore 응답(analyze_restore 메시지 본문). */
export type RestoreReply = { restored?: string[]; ignored?: string[]; removedCandidates?: RemovedCandidate[] };

/** 목록이 이 개수를 넘으면 목록 영역을 스크롤로 처리한다. */
export const SCROLL_THRESHOLD = 10;

export const fileName = (path: string): string => path.split(/[\\/]/).pop() || path;

export const panelTitle = (n: number): string => `제외된 후보 ${n}개`;

export const needsScroll = (n: number): boolean => n > SCROLL_THRESHOLD;

/** 항목마다 reason이 있을 때만 한 줄을 보인다. */
export const reasonLine = (c: RemovedCandidate): string | null => (c.reason && c.reason.trim() ? c.reason.trim() : null);

/** "제외 요청" 배지는 USER_EXCLUDED에만 붙인다. */
export const hasUserExcludedBadge = (c: RemovedCandidate): boolean => c.source === 'USER_EXCLUDED';

/** 체크 상태 전환(선택 순서와 무관하게 목록 순서로 보내므로 집합처럼 다룬다). */
export const toggleSelection = (selected: string[], path: string): string[] =>
  selected.includes(path) ? selected.filter(p => p !== path) : [...selected, path];

/** 목록에 있는 경로만 남긴다(목록이 바뀐 뒤 사라진 항목의 체크를 정리). */
export const pruneSelection = (selected: string[], candidates: RemovedCandidate[]): string[] =>
  selected.filter(p => candidates.some(c => c.path === p));

/** "선택한 파일 되살리기" 버튼은 하나 이상 체크했을 때만 켠다. */
export const canRestore = (selected: string[], candidates: RemovedCandidate[]): boolean =>
  pruneSelection(selected, candidates).length > 0;

/** /restore 요청: 경로는 목록 순서대로 보낸다. requestId는 응답을 같은 결과 메시지에 붙이는 데 쓴다. */
export const buildRestoreRequest = (selected: string[], candidates: RemovedCandidate[], requestId: string) => ({
  command: '/restore',
  text: JSON.stringify({ paths: candidates.filter(c => selected.includes(c.path)).map(c => c.path) }),
  requestId,
});

/** analyze_removed 메시지 본문({"candidates": [...]})을 해석한다. 목록이 없거나 비면 null(패널을 표시하지 않는다). */
export const parseRemovedPayload = (content: string): RemovedCandidate[] | null => {
  try {
    const list = JSON.parse(content)?.candidates;
    return Array.isArray(list) && list.length > 0 ? (list as RemovedCandidate[]) : null;
  } catch {
    return null;
  }
};

/**
 * /restore 응답을 반영한다. 남은 목록은 응답의 removedCandidates를 그대로 쓰고(없으면 되살린 경로를 뺀 현재 목록), 안내 값을 만든다.
 * 되살린 것이 없고 무시된 경로만 있어도 안내(무시 안내)는 남긴다.
 */
export const applyRestoreReply = (
  current: RemovedCandidate[],
  reply: RestoreReply
): { candidates: RemovedCandidate[]; notice: RestoreNotice } => {
  const restored = reply.restored ?? [];
  const remaining = Array.isArray(reply.removedCandidates)
    ? reply.removedCandidates
    : current.filter(c => !restored.includes(c.path));
  return { candidates: remaining, notice: { restored: restored.length, ignored: reply.ignored ?? [] } };
};

/** 되살리기 결과 안내 줄. 되살린 것이 있으면 첫 줄, 무시된 경로가 있으면 한 줄을 더 붙인다. */
export const restoreNoticeLines = (notice: RestoreNotice): string[] => {
  const lines: string[] = [];
  if (notice.restored > 0) lines.push(`${notice.restored}개를 되살렸습니다. 구현 대상에 포함됩니다.`);
  if (notice.ignored.length > 0) lines.push(`${notice.ignored.map(fileName).join(', ')}은(는) 되살릴 수 없었습니다`);
  return lines;
};
