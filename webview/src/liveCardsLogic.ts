// 살아 있는 확인 카드 표(B-86 c, React 없음). 저장된 채팅을 복원할 때(IDE 재시작뿐 아니라 채팅 전환·복귀) 저장된 카드가 "지금 백엔드가 기다리는 카드"인지 가린다.
// 키: 웹뷰가 카드 요청을 받을 때 붙이는 카드 번호(nonce)를 메시지의 cardKey에 저장한다. 정제문 카드는 payload의 requestId(rid:messageId)를 번호로 쓴다.
// 이유: 카드 메시지에는 분석 messageId가 없고(백엔드는 "system"을 보낸다) 힌트에만 turn이 있어, "종류 + 분석 id + turn"으로는 다른 분석의 같은 종류 카드가 구분되지 않는다.
// 표는 앱 수준 상태(App.tsx 모듈 수준)라 채팅 전환에도 유지되고, 웹뷰를 새로고침하면 사라진다(그때 복원한 카드는 닫힌다. 백엔드의 5분 자동 진행에 맡긴다).
// node의 타입 제거 실행(--experimental-strip-types)으로 webview/test에서 테스트하므로 타입 제거만으로 실행되는 문법만 쓴다(enum 등 금지).

import { parsePayload } from './refinedConfirmLogic.ts';

export type CardKind = 'refined' | 'package' | 'hint' | 'stage3';

export const CARD_INFO: Record<CardKind, { subType: string; name: string }> = {
  refined: { subType: 'refinedConfirmation/request', name: '정제문 확인' },
  package: { subType: 'scopeSelection/showTree', name: '읽을 범위 선택' },
  hint: { subType: 'l1Clarification/request', name: '탐색 힌트' },
  stage3: { subType: 'stage3Confirmation/request', name: '최종 검증 대상 선택' },
};

export const CARD_KINDS: CardKind[] = ['refined', 'package', 'hint', 'stage3'];

export const kindOfSubType = (subType: string | undefined): CardKind | undefined =>
  CARD_KINDS.find(k => CARD_INFO[k].subType === subType);

/** 시간 초과 안내(analyze_notice) 문구가 어느 카드의 것인지. 모르는 문구는 undefined. */
export const kindOfTimeoutNotice = (noticeText: string | null | undefined): CardKind | undefined => {
  const t = (noticeText ?? '').trim();
  if (t === '응답이 없어 전체 프로젝트로 진행했습니다.') return 'package';
  if (t === '응답이 없어 힌트 없이 진행했습니다.') return 'hint';
  if (t === '응답이 없어 전체 후보로 진행했습니다.') return 'stage3';
  return undefined;
};

/** 끝난 카드의 결과 문구를 기억하는 개수(최근 순). 오래된 것은 밀려난다. */
export const MAX_ENDED = 8;

export type LiveCards = {
  /** 종류별로 지금 살아 있는 카드(백엔드가 응답을 기다리는 카드): 번호와 카드 payload(끝날 때 기록 줄을 만들 재료). */
  live: Partial<Record<CardKind, { key: string; content: string }>>;
  /** 끝난 카드의 번호와 그때 남긴 결과 기록 줄(채팅 전환 중에 끝나 화면에 못 남긴 결과를 복원 때 쓴다). */
  ended: Array<{ key: string; kind: CardKind; lines: string[] }>;
};

export const createLiveCards = (): LiveCards => ({ live: {}, ended: [] });

export const makeCardKey = (kind: CardKind, seq: number, now: number): string => `${kind}:${seq}:${now}`;

/** 카드 요청의 번호: 정제문은 payload의 requestId, 없거나 다른 종류는 새 번호. */
export const cardKeyFor = (kind: CardKind, content: string, seq: number, now: number): string => {
  if (kind === 'refined') {
    const id = parsePayload(content)?.requestId;
    if (id) return id;
  }
  return makeCardKey(kind, seq, now);
};

/** 새 카드가 도착: 같은 종류의 이전 번호는 무효가 된다(웹뷰도 같은 종류의 이전 카드를 지운다). */
export const registerCard = (state: LiveCards, kind: CardKind, key: string, content: string): void => {
  state.live[kind] = { key, content };
};

/**
 * 그 종류의 카드가 끝났다(응답·취소·만료·hide, 표시 중인 채팅과 무관): 살아 있는 번호를 지운다. lines가 있으면 결과 기록 줄로 기억한다.
 * 살아 있는 카드가 없으면 아무 일도 하지 않는다(같은 종료 신호가 두 번 와도 안전). 끝난 번호를 돌려준다.
 */
export const endCard = (state: LiveCards, kind: CardKind, lines?: string[]): string | undefined => {
  const cur = state.live[kind];
  if (!cur) return undefined;
  delete state.live[kind];
  if (lines && lines.length > 0) {
    state.ended = [{ key: cur.key, kind, lines }, ...state.ended.filter(e => e.key !== cur.key)].slice(0, MAX_ENDED);
  }
  return cur.key;
};

export const isLive = (state: LiveCards, kind: CardKind, key: string | undefined): boolean =>
  key !== undefined && state.live[kind]?.key === key;

export const endedLines = (state: LiveCards, key: string | undefined): string[] | undefined =>
  key === undefined ? undefined : state.ended.find(e => e.key === key)?.lines;

/** 결과를 모르는 끝난 카드의 중립 기록 줄. */
export const neutralLine = (kind: CardKind): string => `✔ ${CARD_INFO[kind].name}: 이전에 끝난 카드(채팅 복원)`;
