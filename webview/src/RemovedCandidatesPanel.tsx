import React, { useState } from 'react';
import { ChevronDown, ChevronRight, CheckSquare, Square, RotateCcw } from 'lucide-react';
import {
  type RemovedCandidate,
  type RestoreNotice,
  buildRestoreRequest,
  canRestore,
  fileName,
  hasUserExcludedBadge,
  needsScroll,
  panelTitle,
  pruneSelection,
  reasonLine,
  restoreNoticeLines,
  toggleSelection,
} from './removedCandidatesLogic';

interface RemovedCandidatesPanelProps {
  /** 결과 메시지 id. /restore 응답을 같은 메시지에 붙이는 요청 id로 쓴다. */
  messageId: string;
  /** 백엔드가 준 순서 그대로의 목록(다시 정렬하지 않는다). */
  candidates: RemovedCandidate[];
  notice?: RestoreNotice;
}

/**
 * 분석 결과 아래의 접힌 "제외된 후보 N개". 파일 1개를 되살리는 데 펼침, 체크, 확정(선택한 파일 되살리기) 3번의 클릭이 든다.
 * 되살리기는 LLM을 부르지 않고 /restore로 구현 대상(최종 목록)에 바로 추가한다.
 */
export const RemovedCandidatesPanel: React.FC<RemovedCandidatesPanelProps> = ({ messageId, candidates, notice }) => {
  const [expanded, setExpanded] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [pending, setPending] = useState(false);

  const noticeLines = notice ? restoreNoticeLines(notice) : [];
  const noticeBlock = noticeLines.length > 0 && (
    <div style={{ fontSize: '12px', color: '#94a3b8', marginTop: '6px' }}>
      {noticeLines.map(l => <div key={l}>{l}</div>)}
    </div>
  );

  // 다 비면 패널을 숨긴다(되살리기 결과 안내만 남긴다)
  if (candidates.length === 0) {
    return noticeBlock ? <div className="removed-candidates-notice">{noticeBlock}</div> : null;
  }

  const live = pruneSelection(selected, candidates);
  const enabled = canRestore(live, candidates) && !pending;

  const handleRestore = () => {
    if (!enabled || !window.sendToIde) return;
    setPending(true);
    window.sendToIde(JSON.stringify(buildRestoreRequest(live, candidates, messageId)));
    // 응답(analyze_restore)이 오면 결과 메시지의 목록과 안내가 바뀌어 다시 그려진다.
    setTimeout(() => { setPending(false); setSelected([]); }, 300);
  };

  return (
    <div className="removed-candidates" style={{
      marginTop: '10px', border: '1px solid rgba(148, 163, 184, 0.35)', borderRadius: '6px', padding: '8px 10px',
      background: 'rgba(17, 24, 39, 0.35)', fontSize: '12px'
    }}>
      <button
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        style={{ display: 'flex', alignItems: 'center', gap: '6px', background: 'transparent', border: 'none', color: '#cbd5e1', cursor: 'pointer', padding: 0, fontSize: '12px' }}
      >
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span>{panelTitle(candidates.length)}</span>
      </button>

      {expanded && (
        <>
          <div
            className="removed-candidates-list"
            style={{ marginTop: '8px', ...(needsScroll(candidates.length) ? { maxHeight: '240px', overflowY: 'auto' } : {}) }}
          >
            {candidates.map(c => {
              const checked = live.includes(c.path);
              const reason = reasonLine(c);
              return (
                <div
                  key={c.path}
                  onClick={() => setSelected(prev => toggleSelection(prev, c.path))}
                  style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', padding: '4px 2px', cursor: 'pointer' }}
                  title={c.path}
                >
                  {checked ? <CheckSquare size={14} color="#60a5fa" /> : <Square size={14} color="#64748b" />}
                  <div style={{ minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flexWrap: 'wrap' }}>
                      <span style={{ color: '#e2e8f0', wordBreak: 'break-all' }}>{fileName(c.path)}</span>
                      {hasUserExcludedBadge(c) && (
                        <span style={{ fontSize: '10px', padding: '0 6px', borderRadius: '8px', background: 'rgba(245, 158, 11, 0.2)', color: '#fbbf24' }}>제외 요청</span>
                      )}
                    </div>
                    {reason && <div style={{ color: '#94a3b8', marginTop: '2px' }}>{reason}</div>}
                  </div>
                </div>
              );
            })}
          </div>
          <div style={{ marginTop: '8px', display: 'flex', alignItems: 'center', gap: '10px' }}>
            <button
              onClick={handleRestore}
              disabled={!enabled}
              style={{
                display: 'flex', alignItems: 'center', gap: '6px', padding: '4px 10px', borderRadius: '4px', fontSize: '12px',
                border: '1px solid #3b82f6', color: enabled ? '#eff6ff' : '#64748b',
                background: enabled ? '#2563eb' : 'transparent', cursor: enabled ? 'pointer' : 'not-allowed'
              }}
            >
              <RotateCcw size={12} />
              선택한 파일 되살리기
            </button>
            <span style={{ color: '#64748b' }}>LLM 호출 없이 구현 대상에 바로 추가됩니다.</span>
          </div>
        </>
      )}
      {noticeBlock}
    </div>
  );
};
