import React, { useState, useMemo } from 'react';
import { Compass, Check, SkipForward, Layers, FileCode, Tag } from 'lucide-react';

export interface L1Candidate {
  className?: string;
  path?: string;
  fileType?: string;
  localName?: string;
  keyMethods?: string[];
  matchScore?: number;
  apiEndpoints?: string[];
}

export interface L1ClarificationPayload {
  projectId: string;
  turn?: number;
  query: string;
  topCandidates: L1Candidate[];
  domains: string[];
  explanation?: string;
  message: string;
}

interface L1ClarificationFormProps {
  payload: string; // JSON string from msg.content
  onCancel: (projectId: string) => void;
  onSubmit: (projectId: string, userHint: string) => void;
}

export const L1ClarificationForm: React.FC<L1ClarificationFormProps> = ({ payload, onCancel, onSubmit }) => {
  const data = useMemo<L1ClarificationPayload | null>(() => {
    try {
      return JSON.parse(payload) as L1ClarificationPayload;
    } catch (e) {
      console.error("Failed to parse l1Clarification payload", e);
      return null;
    }
  }, [payload]);

  const [hintText, setHintText] = useState('');
  const [isSubmitted, setIsSubmitted] = useState(false);
  const [submittedAction, setSubmittedAction] = useState<'submit' | 'cancel' | null>(null);

  if (!data) {
    return (
      <div className="msg-ai analysis" style={{ padding: '12px', background: 'rgba(239, 68, 68, 0.1)', borderRadius: '6px', border: '1px solid #ef4444', color: '#fca5a5' }}>
        L1 힌트 요청 데이터를 불러오는 데 실패했습니다.
      </div>
    );
  }

  const { projectId, turn, query, topCandidates = [], domains = [], explanation, message } = data;

  const handleChipClick = (domain: string) => {
    if (isSubmitted) return;
    // 도메인 패키지에서 마지막 단어 또는 의미 있는 토큰 추출
    const segment = domain.split('.').pop() || domain;
    setHintText(prev => {
      const trimmed = prev.trim();
      if (!trimmed) return segment;
      if (trimmed.includes(segment)) return trimmed;
      return `${trimmed} ${segment}`;
    });
  };

  const handleSubmit = () => {
    if (isSubmitted) return;
    const finalHint = hintText.trim();
    if (!finalHint) {
      alert("업무 도메인 또는 화면 힌트를 입력해주세요. (또는 '건너뛰기'를 선택하세요)");
      return;
    }
    setIsSubmitted(true);
    setSubmittedAction('submit');
    onSubmit(projectId, finalHint);
  };

  const handleCancel = () => {
    if (isSubmitted) return;
    setIsSubmitted(true);
    setSubmittedAction('cancel');
    onCancel(projectId);
  };

  return (
    <div className="msg-ai analysis l1-clarification-card" style={{
      background: 'rgba(30, 41, 59, 0.7)',
      border: '1px solid #3b82f6',
      borderRadius: '8px',
      padding: '16px',
      margin: '12px 0',
      boxShadow: '0 4px 12px rgba(0, 0, 0, 0.3)'
    }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px', borderBottom: '1px solid rgba(255, 255, 255, 0.1)', paddingBottom: '10px' }}>
        <Compass size={20} style={{ color: '#60a5fa' }} />
        <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 600, color: '#93c5fd' }}>
          도메인 힌트 및 탐색 피드백 {turn ? `(Turn ${turn})` : ''}
        </h3>
      </div>

      {/* AI Explanation / Plan */}
      {explanation && (
        <div style={{ background: 'rgba(59, 130, 246, 0.1)', padding: '10px 12px', borderRadius: '6px', borderLeft: '3px solid #3b82f6', marginBottom: '14px', fontSize: '12px', color: '#bfdbfe' }}>
          <strong style={{ color: '#93c5fd' }}>🤖 AI 탐색 해석 및 다음 계획:</strong>
          <div style={{ marginTop: '4px', whiteSpace: 'pre-wrap', lineHeight: '1.4' }}>{explanation}</div>
        </div>
      )}

      {/* Message & Query */}
      <div style={{ fontSize: '13px', lineHeight: '1.5', color: '#e2e8f0', marginBottom: '14px' }}>
        <p style={{ margin: '0 0 6px 0' }}>{message}</p>
        <div style={{ background: 'rgba(0, 0, 0, 0.3)', padding: '6px 10px', borderRadius: '4px', borderLeft: '3px solid #60a5fa', fontSize: '12px', color: '#cbd5e1' }}>
          <strong>현재 검색어:</strong> {query}
        </div>
      </div>

      {/* Detected Domains Chips */}
      {domains.length > 0 && (
        <div style={{ marginBottom: '14px' }}>
          <div style={{ fontSize: '12px', fontWeight: 600, color: '#94a3b8', marginBottom: '6px', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <Tag size={13} /> 감지된 패키지/도메인 영역 (클릭하여 힌트에 추가):
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
            {domains.map((dom, idx) => (
              <button
                key={idx}
                type="button"
                onClick={() => handleChipClick(dom)}
                disabled={isSubmitted}
                style={{
                  fontSize: '11px',
                  padding: '3px 8px',
                  borderRadius: '12px',
                  background: 'rgba(59, 130, 246, 0.15)',
                  border: '1px solid rgba(96, 165, 250, 0.4)',
                  color: '#93c5fd',
                  cursor: isSubmitted ? 'default' : 'pointer',
                  transition: 'all 0.15s ease'
                }}
              >
                + {dom}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Candidates Preview */}
      {topCandidates.length > 0 && (
        <div style={{ marginBottom: '14px' }}>
          <div style={{ fontSize: '12px', fontWeight: 600, color: '#94a3b8', marginBottom: '6px', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <Layers size={13} /> 1턴 상위 동점/유력 후보군 (최대 5건):
          </div>
          <div style={{ background: 'rgba(0, 0, 0, 0.25)', borderRadius: '4px', overflow: 'hidden', border: '1px solid rgba(255, 255, 255, 0.05)' }}>
            {topCandidates.map((cand, idx) => (
              <div
                key={idx}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: '6px 10px',
                  borderBottom: idx < topCandidates.length - 1 ? '1px solid rgba(255, 255, 255, 0.05)' : 'none',
                  fontSize: '12px'
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', minWidth: 0, flex: 1 }}>
                  <FileCode size={14} style={{ color: '#818cf8', flexShrink: 0 }} />
                  <span style={{ fontWeight: 600, color: '#f8fafc', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {cand.className}
                  </span>
                  <span style={{ color: '#94a3b8', fontSize: '11px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    ({cand.localName || '주석 없음'})
                  </span>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0, marginLeft: '8px' }}>
                  <span style={{ fontSize: '10px', padding: '1px 5px', borderRadius: '3px', background: 'rgba(255, 255, 255, 0.1)', color: '#cbd5e1' }}>
                    {cand.fileType || 'JAVA'}
                  </span>
                  <span style={{ fontSize: '11px', color: '#38bdf8', fontWeight: 600 }}>
                    {cand.matchScore ?? 0}점
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* User Input Form or Completed Status */}
      {!isSubmitted ? (
        <div>
          <div style={{ marginBottom: '10px' }}>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: '#e2e8f0', marginBottom: '4px' }}>
              🎯 대상 도메인 / 업무 화면 힌트 입력:
            </label>
            <input
              type="text"
              value={hintText}
              onChange={(e) => setHintText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleSubmit();
                }
              }}
              placeholder="예: 통계 리포트 화면, pstat, 마일리지 정산 등"
              style={{
                width: '100%',
                padding: '8px 10px',
                borderRadius: '4px',
                border: '1px solid #475569',
                background: 'rgba(15, 23, 42, 0.8)',
                color: '#fff',
                fontSize: '13px',
                outline: 'none',
                boxSizing: 'border-box'
              }}
            />
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '12px' }}>
            <button
              type="button"
              onClick={handleCancel}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '4px',
                padding: '6px 12px',
                fontSize: '12px',
                borderRadius: '4px',
                border: '1px solid #64748b',
                background: 'transparent',
                color: '#cbd5e1',
                cursor: 'pointer'
              }}
            >
              <SkipForward size={14} /> 건너뛰기 (자율 탐색)
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!hintText.trim()}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '4px',
                padding: '6px 14px',
                fontSize: '12px',
                fontWeight: 600,
                borderRadius: '4px',
                border: 'none',
                background: hintText.trim() ? '#3b82f6' : '#475569',
                color: '#fff',
                cursor: hintText.trim() ? 'pointer' : 'not-allowed'
              }}
            >
              <Check size={14} /> 도메인 힌트 제출
            </button>
          </div>
        </div>
      ) : (
        <div style={{
          marginTop: '12px',
          padding: '8px 12px',
          borderRadius: '4px',
          background: submittedAction === 'submit' ? 'rgba(16, 185, 129, 0.15)' : 'rgba(148, 163, 184, 0.15)',
          border: `1px solid ${submittedAction === 'submit' ? '#10b981' : '#64748b'}`,
          fontSize: '12px',
          color: submittedAction === 'submit' ? '#6ee7b7' : '#cbd5e1',
          display: 'flex',
          alignItems: 'center',
          gap: '6px'
        }}>
          <Check size={14} />
          {submittedAction === 'submit' ? (
            <span>도메인 힌트가 제출되었습니다: <strong>&quot;{hintText}&quot;</strong> (2턴 탐색 재진입)</span>
          ) : (
            <span>건너뛰기가 선택되었습니다. 시스템 자율 탐색으로 계속 진행합니다.</span>
          )}
        </div>
      )}
    </div>
  );
};
