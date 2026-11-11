import { REFINED_CARD, closeRefinedCard, findRefinedCard, parsePayload } from './refinedConfirmLogic.ts';

// 저장된 채팅을 복원할 때의 화면 상태 정리와, 카드에서 고른 내용을 채팅에 남기는 한 줄 기록 문구(React 없음).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

export type RestorableMessage = {
  id: string;
  role?: string;
  subType?: string;
  isLoading?: boolean;
  isStreaming?: boolean;
  currentStatus?: string;
  stepNotiStatus?: string;
  clarifyCompleted?: boolean;
};

/** Stage 0 카드의 확정이 받아들여져 분석이 이어졌다는 신호로 보는 뒤따른 메시지(clarifySessionLogic의 ACCEPTED와 그 뒤 결과 메시지). */
const AFTER_CONFIRM = [
  'analyze_start', 'analyze_notice', 'refinedConfirmation/request', 'scopeSelection/showTree', 'l1Clarification/request',
  'stage3Confirmation/request', 'analyze_removed', 'analyze_incomplete', 'analyze_restore', 'choice_record',
];

/**
 * 복원한 메시지의 화면 상태를 정리한다.
 * - 저장 시점에 진행 중이던 메시지(isLoading/isStreaming)는 지금 진행 중인 작업이 아니다. 그대로 두면 정지 버튼이 켜지고,
 *   누르면 진행 중인 분석이 없는데도 /cancel이 나간다. 진행 표시를 끄고 시작만 하고 끝나지 않은 단계 알림은 실패로 표시한다.
 * - 확정 뒤 분석이 이어진 Stage 0 카드는 clarifyCompleted를 켠다(복원 화면에서 읽기 전용 "완료됨"). 확정하지 않은 카드는 그대로 둔다.
 */
export const restoreMessages = <T extends RecordableMessage>(messages: T[]): T[] =>
  restoreRefinedCard(messages.map((m, i) => {
    let next: T = m;
    if (m.isLoading || m.isStreaming) {
      next = { ...next, isLoading: false, isStreaming: false, currentStatus: undefined };
    }
    if (m.subType === 'step_noti' && m.stepNotiStatus === 'started') {
      next = { ...next, stepNotiStatus: 'failed' };
    }
    if (m.subType === 'analyze_clarify' && messages.slice(i + 1).some(later => later.subType !== undefined && AFTER_CONFIRM.includes(later.subType))) {
      next = { ...next, clarifyCompleted: true };
    }
    return next;
  }));

/**
 * 실행 중인 화면에서 분석 결과(resultId)가 나오면 그 앞에 있는 Stage 0 카드를 "완료됨"으로 바꾼다(B-81 9: 복원할 때의 [restoreMessages]와 같은 결과).
 * 결과 메시지가 아직 없으면 아무것도 바꾸지 않는다.
 */
export const completeClarifyCards = <T extends RestorableMessage>(messages: T[], resultId: string | undefined): T[] => {
  const idx = resultId === undefined ? -1 : messages.findIndex(m => m.id === resultId);
  if (idx < 0) return messages;
  return messages.map((m, i) => (m.subType === 'analyze_clarify' && i < idx && !m.clarifyCompleted ? { ...m, clarifyCompleted: true } : m));
};

/** 결과 말풍선 안에 끼워 넣는 카드 기록 줄: at은 본문(content)에서 기록이 놓이는 글자 위치(줄 경계로 맞춘 값). */
export type InlineRecord = { at: number; text: string };

export type RecordableMessage = RestorableMessage & { content?: string; inlineRecords?: InlineRecord[] };

/**
 * 카드 기록을 결과 본문의 어디에 끼울지(B-82 3-5): 글자 수 그대로가 아니라 직전 줄바꿈(완성되지 않은 마지막 줄은 그 줄 앞)에 맞춘다.
 * 코드 블록 안이면 그 블록이 시작되는 줄 앞으로, 표 바로 뒤(표가 이어질 수 있음)이면 표가 시작되는 줄 앞으로 옮겨 표·코드 블록 중간에 끼지 않는다.
 */
export const safeRecordPosition = (content: string): number => {
  let at = content.lastIndexOf('\n') + 1;
  const lines = content.slice(0, at).split('\n'); // 마지막 원소는 빈 문자열(at이 줄 시작이므로)
  lines.pop();
  // 코드 블록 안: 닫히지 않은 ``` 줄을 찾아 그 앞으로
  let openFenceLine = -1;
  lines.forEach((l, i) => { if (l.trimStart().startsWith('```')) openFenceLine = openFenceLine === -1 ? i : -1; });
  if (openFenceLine !== -1) return lines.slice(0, openFenceLine).reduce((n, l) => n + l.length + 1, 0);
  // 표 바로 뒤: 이어지는 '|' 줄 묶음의 시작 앞으로
  let i = lines.length - 1;
  if (i >= 0 && lines[i].trimStart().startsWith('|')) {
    while (i > 0 && lines[i - 1].trimStart().startsWith('|')) i--;
    at = lines.slice(0, i).reduce((n, l) => n + l.length + 1, 0);
  }
  return at;
};

export type RecordSegment = { kind: 'md'; text: string } | { kind: 'rec'; text: string };

/** 본문을 기록 위치에서 잘라 [본문, 기록, 본문…] 순서로 돌려준다. 위치가 겹치면 들어온 순서, 범위를 벗어나면 끝으로 본다. 빈 본문 조각은 만들지 않는다. */
export const splitWithRecords = (content: string, records: InlineRecord[] | undefined): RecordSegment[] => {
  const sorted = (records ?? []).map((r, i) => ({ ...r, i, at: Math.max(0, Math.min(r.at, content.length)) })).sort((a, b) => a.at - b.at || a.i - b.i);
  const out: RecordSegment[] = [];
  let pos = 0;
  for (const r of sorted) {
    if (r.at > pos) out.push({ kind: 'md', text: content.slice(pos, r.at) });
    out.push({ kind: 'rec', text: r.text });
    pos = Math.max(pos, r.at);
  }
  if (pos < content.length || out.length === 0) out.push({ kind: 'md', text: content.slice(pos) });
  return out;
};

/**
 * 카드 기록 한 줄을 대화에 더한다: 분석 결과가 스트리밍 중이면 그 말풍선 본문의 안전한 위치에 끼우고(그래서 결과 아래에 몰리지 않고 카드를 처리한 시점의
 * 진행 줄 바로 뒤에 놓인다), 아니면 따로 한 줄 메시지로 끝에 붙인다(결과가 만들어지기 전의 기록).
 */
export const appendRecord = <T extends RecordableMessage>(messages: T[], id: string, text: string): T[] => {
  let idx = -1;
  messages.forEach((m, i) => {
    if (m.role === 'ai' && m.id.startsWith('analyze_') && m.subType === 'task_chunk' && (m.isStreaming === true || m.isLoading === true)) idx = i;
  });
  if (idx < 0) return [...messages, { id, role: 'ai', subType: 'choice_record', content: text, isLoading: false } as unknown as T];
  const target = messages[idx];
  const at = safeRecordPosition(target.content ?? '');
  return messages.map((m, i) => (i === idx ? { ...m, inlineRecords: [...(m.inlineRecords ?? []), { at, text }] } : m));
};

/** 완료된 카드의 대화 기록: 답을 받지 못한 마지막 질문도 어시스턴트 발화로 남긴다(이미 기록에 있으면 더하지 않는다). */
export const historyWithOpenQuestion = (
  history: Array<{ role: string; content: string }> | null | undefined,
  openQuestion: string | null | undefined,
): Array<{ role: string; content: string }> => {
  const base = history ?? [];
  const q = (openQuestion ?? '').trim();
  if (q === '' || base.some(t => t.role === 'assistant' && t.content.trim() === q)) return base;
  return [...base, { role: 'assistant', content: q }];
};

const oneLine = (s: string): string => s.replace(/\s+/g, ' ').trim();

const names = (paths: string[], max: number): string => {
  const base = paths.map(p => oneLine(p.replace(/\\/g, '/').split('/').filter(x => x !== '').pop() ?? p));
  return base.length <= max ? base.join(', ') : base.slice(0, max).join(', ') + ` 외 ${base.length - max}개`;
};

/** 정제문 카드의 제출 본문(JSON)에서 최종 문장을 고른다: 고쳤으면 editedText, 아니면 카드가 보여 준 정제문. */
export const finalRefinedText = (submitText: string, shownRefined: string): string => {
  try {
    const d = JSON.parse(submitText);
    if (d && typeof d.editedText === 'string' && d.editedText.trim() !== '') return d.editedText;
  } catch { /* 본문이 깨졌으면 보여 준 정제문을 쓴다 */ }
  return shownRefined;
};

/** 정제문 확인 카드가 끝난 이유. orphan은 응답 전에 저장된 채팅을 복원했을 때(IDE 재시작 등으로 응답을 기다리던 요청이 사라진 경우). */
export type RefinedCloseCause = 'asis' | 'edited' | 'cancel' | 'timeout' | 'orphan';

const REFINED_CAUSE_LABEL: Record<RefinedCloseCause, string> = {
  asis: '이대로 분석',
  edited: '수정한 내용으로 분석',
  cancel: '취소',
  timeout: '응답 없음(5분, 정제문 그대로 진행)',
  orphan: '응답 전 종료됨(IDE 재시작)',
};

/**
 * 정제문 확인 카드가 끝났을 때 남기는 기록 줄(B-85 1): "✔ 정제문 확인: <이유>", 다음 줄에 실제 분석에 쓴 정제문(취소·응답 전 종료는 분석에 쓴 문장이 없어 생략),
 * "구현 전 확인 필요" 문장이 있었으면 그 줄(카드를 지워도 문장이 남게 한다). 결과 화면의 📌 블록과 같은 문장이다.
 */
export const refinedCloseRecords = (cause: RefinedCloseCause, finalText: string, decisionNote: string | undefined): string[] => {
  const lines = [`✔ 정제문 확인: ${REFINED_CAUSE_LABEL[cause]}`];
  if (cause !== 'cancel' && cause !== 'orphan' && finalText.trim() !== '') lines.push(`↳ 분석에 쓴 정제문: ${oneLine(finalText)}`);
  if (decisionNote !== undefined && decisionNote.trim() !== '') lines.push(`📌 구현 전 확인 필요: ${oneLine(decisionNote)}`);
  return lines;
};

const cardContent = (card: { content?: string } | undefined): string => card?.content ?? '';

/**
 * 정제문 확인 카드를 즉시 지우고 기록 줄을 남긴다(응답·취소·시간 초과 공통). 기록은 다른 카드 기록과 같이 [appendRecord]로 붙는다.
 * edited일 때만 editedText가 분석에 쓴 문장이고, 그 밖에는 카드가 보인 정제문이다. 카드가 이미 없으면 목록을 그대로 돌려준다(종료 신호가 두 번 와도 기록은 한 번).
 */
export const finishRefinedCard = <T extends RecordableMessage>(messages: T[], cause: RefinedCloseCause, opts: { editedText?: string; idBase: string }): T[] => {
  const card = findRefinedCard(messages);
  if (!card) return messages;
  const payload = parsePayload(cardContent(card));
  const shown = payload?.refinedRequirement ?? '';
  const edited = (opts.editedText ?? '').trim();
  const finalText = cause === 'edited' && edited !== '' ? edited : shown;
  let next = closeRefinedCard(messages);
  refinedCloseRecords(cause, finalText, payload?.decisionNote).forEach((text, i) => {
    next = appendRecord(next, `${opts.idBase}_${i}`, text);
  });
  return next;
};

/**
 * 저장된 채팅을 복원할 때 정제문 확인 카드를 그 자리에서 기록 줄로 바꾼다(B-85 1 D): 예전 형식으로 만료 표시(expired)가 저장된 카드는 시간 초과 기록,
 * 응답 전에 저장된 활성 카드는 "응답 전 종료됨(IDE 재시작)". 복원한 카드는 기다리는 요청이 이미 없어 눌러도 아무 일도 하지 못한다.
 */
export const restoreRefinedCard = <T extends RecordableMessage>(messages: T[]): T[] => {
  const idx = messages.findIndex(m => m.subType === REFINED_CARD);
  if (idx < 0) return messages;
  const card = messages[idx];
  const payload = parsePayload(cardContent(card));
  const expired = (card as unknown as { expired?: boolean }).expired === true;
  const cause: RefinedCloseCause = expired ? 'timeout' : 'orphan';
  const records = refinedCloseRecords(cause, payload?.refinedRequirement ?? '', payload?.decisionNote)
    .map((text, i) => ({ id: `rec_restore_refined_${i}`, role: 'ai', subType: 'choice_record', content: text, isLoading: false }) as unknown as T);
  return [...messages.slice(0, idx), ...records, ...messages.slice(idx + 1).filter(m => m.subType !== REFINED_CARD)];
};

/** 탐색 힌트 카드에서 보낸 힌트(비어 있으면 힌트 없이 진행). */
export const hintRecord = (userHint: string | null | undefined): string => {
  const h = oneLine(userHint ?? '');
  return h === '' ? '✔ 탐색 힌트: 없음(그대로 진행)' : `✔ 탐색 힌트: ${h}`;
};

/** 읽을 범위(폴더·파일) 선택 카드에서 고른 경로. */
export const scopeRecord = (selectedPaths: string[]): string =>
  selectedPaths.length === 0 ? '✔ 읽을 범위 선택: 없음' : `✔ 읽을 범위 선택: ${selectedPaths.length}개(${names(selectedPaths, 4)})`;

/** 읽을 범위 카드에서 "취소 (전체 선택)"으로 넘긴 경우(B-81 7: 제출뿐 아니라 건너뛰기도 기록한다). */
export const scopeSkipRecord = (): string => '✔ 읽을 범위 선택: 전체(선택 안 함)';

/**
 * 탐색 힌트 카드에서 "건너뛰기 (자율 탐색)"으로 넘긴 경우(B-84 e). 탐색이 여러 턴이면 같은 줄이 겹쳐 보이므로 Turn 번호를 붙인다.
 * 번호를 모르면(Turn이 없는 저장 채팅, 읽을 수 없는 payload) 이전 문구 그대로다.
 */
export const hintSkipRecord = (turn?: number): string => `✔ ${hintTurnLabel(turn)}탐색 힌트: 건너뜀(자율 탐색)`;

/** "Turn 2 " (끝에 공백 포함). 번호를 모르거나 양의 정수가 아니면 빈 문자열이라 이전 문구가 된다. */
export const hintTurnLabel = (turn: number | undefined): string =>
  typeof turn === 'number' && Number.isInteger(turn) && turn > 0 ? `Turn ${turn} ` : '';

/** 백엔드 안내(analyze_notice)의 turn 필드(숫자 또는 숫자 문자열, 메타 필드는 문자열로 온다). 양의 정수가 아니면 undefined. */
export const noticeTurnOf = (raw: unknown): number | undefined => {
  const n = typeof raw === 'number' ? raw : typeof raw === 'string' && /^\d+$/.test(raw.trim()) ? Number(raw.trim()) : NaN;
  return Number.isInteger(n) && n > 0 ? n : undefined;
};

/** 탐색 힌트 카드 payload(JSON 문자열)의 Turn 번호. 읽을 수 없거나 정수가 아니면 undefined. */
export const hintTurnOf = (payload: unknown): number | undefined => {
  if (typeof payload !== 'string') return undefined;
  try {
    const t = JSON.parse(payload)?.turn;
    return typeof t === 'number' && Number.isInteger(t) && t > 0 ? t : undefined;
  } catch {
    return undefined;
  }
};

/** Stage 3 카드에서 "건너뛰기 (전체 N개 진행)"으로 넘긴 경우. */
export const stage3SkipRecord = (total: number): string =>
  total > 0 ? `✔ 최종 검증 대상 선택: 건너뜀(전체 ${total}개 진행)` : '✔ 최종 검증 대상 선택: 건너뜀(전체 진행)';


/** 백엔드가 5분 시간 초과로 보낸 안내(CardTimeoutNotice)를 같은 모양의 기록 줄로 바꾼다. 해당 안내가 아니면 null. */
export const timeoutRecord = (noticeText: string | null | undefined, stage3Total: number, hintTurn?: number): string | null => {
  const t = (noticeText ?? '').trim();
  if (t === '응답이 없어 전체 프로젝트로 진행했습니다.') return '✔ 읽을 범위 선택: 전체(5분 응답 없음, 자동 진행)';
  // B-85 2: 힌트 카드 시간 초과도 건너뛰기([hintSkipRecord])처럼 Turn 번호를 붙인다. 번호를 모르면 이전 문구 그대로다.
  if (t === '응답이 없어 힌트 없이 진행했습니다.') return `✔ ${hintTurnLabel(hintTurn)}탐색 힌트: 없음(5분 응답 없음, 자동 진행)`;
  if (t === '응답이 없어 전체 후보로 진행했습니다.') return stage3Total > 0 ? `✔ 최종 검증 대상 선택: 전체 ${stage3Total}개 진행(5분 응답 없음, 자동 진행)` : '✔ 최종 검증 대상 선택: 전체 진행(5분 응답 없음, 자동 진행)';
  return null;
};

/** Stage 3 카드 payload(JSON 문자열)의 후보 수. 읽을 수 없으면 0. */
export const stage3CandidateCount = (payload: string | null | undefined): number => {
  try {
    const d = JSON.parse(payload ?? '');
    return Array.isArray(d?.candidates) ? d.candidates.length : 0;
  } catch { return 0; }
};

/** Stage 3 최종 검증 대상 선택 카드에서 고른 파일. */
export const stage3Record = (selectedPaths: string[]): string =>
  selectedPaths.length === 0 ? '✔ 최종 검증 대상 선택: 없음' : `✔ 최종 검증 대상 선택: ${selectedPaths.length}개(${names(selectedPaths, 4)})`;
