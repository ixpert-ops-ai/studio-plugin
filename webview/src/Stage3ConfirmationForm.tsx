import React, { useState, useMemo, useEffect } from 'react';
import { CheckSquare, Square, Check, SkipForward, FileCode, ShieldCheck } from 'lucide-react';

export interface Stage3Candidate {
  order: number;
  path: string;
  type: string;
  description: string;
}

export interface Stage3ConfirmationPayload {
  projectId: string;
  candidates: Stage3Candidate[];
  message?: string;
}

interface Stage3ConfirmationFormProps {
  payload: string; // JSON string from msg.content
  onCancel: (projectId: string) => void;
  onSubmit: (projectId: string, selectedPaths: string[]) => void;
}

export const Stage3ConfirmationForm: React.FC<Stage3ConfirmationFormProps> = ({ payload, onCancel, onSubmit }) => {
  const data = useMemo<Stage3ConfirmationPayload | null>(() => {
    try {
      return JSON.parse(payload) as Stage3ConfirmationPayload;
    } catch (e) {
      console.error("Failed to parse stage3Confirmation payload", e);
      return null;
    }
  }, [payload]);

  const candidates = useMemo(() => data?.candidates || [], [data]);

  // 기본 상태는 모든 후보 파일 선택
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(() => {
    return new Set(candidates.map(c => c.path));
  });

  const [isSubmitted, setIsSubmitted] = useState(false);
  const [submittedAction, setSubmittedAction] = useState<'submit' | 'cancel' | null>(null);

  useEffect(() => {
    if (candidates.length > 0) {
      setSelectedPaths(new Set(candidates.map(c => c.path)));
      setIsSubmitted(false);
      setSubmittedAction(null);
    }
  }, [candidates, payload]);

  if (!data || candidates.length === 0) {
    return (
      <div className="msg-ai analysis" style={{ padding: '12px', background: 'rgba(239, 68, 68, 0.1)', borderRadius: '6px', border: '1px solid #ef4444', color: '#fca5a5' }}>
        Stage 3 후보 파일 확인 데이터를 불러오는 데 실패했습니다.
      </div>
    );
  }

  const { projectId, message } = data;

  const handleTogglePath = (path: string) => {
    if (isSubmitted) return;
    setSelectedPaths(prev => {
      const next = new Set(prev);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  };

  const handleSelectAll = () => {
    if (isSubmitted) return;
    setSelectedPaths(new Set(candidates.map(c => c.path)));
  };

  const handleDeselectAll = () => {
    if (isSubmitted) return;
    setSelectedPaths(new Set());
  };

  const handleOpenFile = (filePath: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({
        command: '/openInEditor',
        filePath: filePath
      }));
    }
  };

  const handleSubmit = () => {
    if (isSubmitted) return;
    const pathsArray = Array.from(selectedPaths);
    if (pathsArray.length === 0) {
      alert("선택된 파일이 없습니다. 모든 파일을 검증에 포함하려면 '건너뛰기'를 선택하세요.");
      return;
    }
    setIsSubmitted(true);
    setSubmittedAction('submit');
    onSubmit(projectId, pathsArray);
  };

  const handleCancel = () => {
    if (isSubmitted) return;
    setIsSubmitted(true);
    setSubmittedAction('cancel');
    onCancel(projectId);
  };

  return (
    <div className="msg-ai analysis" style={{
      background: 'rgba(17, 24, 39, 0.85)',
      border: '1px solid #3b82f6',
      borderRadius: '8px',
      padding: '16px',
      marginTop: '12px',
      marginBottom: '12px',
      boxShadow: '0 4px 16px rgba(59, 130, 246, 0.15)',
      color: '#e2e8f0',
      fontFamily: 'inherit'
    }}>
      {/* 헤더 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px', borderBottom: '1px solid rgba(59, 130, 246, 0.3)', paddingBottom: '8px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <ShieldCheck size={18} color="#60a5fa" />
          <span style={{ fontWeight: 600, fontSize: '14px', color: '#93c5fd' }}>
            Stage 3 최종 검증 전 대상 파일 확인
          </span>
        </div>
        <div style={{ display: 'flex', gap: '8px', fontSize: '12px' }}>
          <button
            onClick={handleSelectAll}
            disabled={isSubmitted}
            style={{ background: 'transparent', border: 'none', color: '#60a5fa', cursor: isSubmitted ? 'not-allowed' : 'pointer', textDecoration: 'underline' }}
          >
            전체 선택
          </button>
          <span style={{ color: '#475569' }}>|</span>
          <button
            onClick={handleDeselectAll}
            disabled={isSubmitted}
            style={{ background: 'transparent', border: 'none', color: '#94a3b8', cursor: isSubmitted ? 'not-allowed' : 'pointer', textDecoration: 'underline' }}
          >
            전체 해제
          </button>
        </div>
      </div>

      {/* 안내 메시지 */}
      <div style={{ fontSize: '13px', color: '#cbd5e1', marginBottom: '12px', lineHeight: 1.5 }}>
        {message || "탐색 및 경로 보정이 완료된 후보 파일 목록입니다. 최종 LLM 심사에 포함할 파일을 선택해 주세요."}
      </div>

      {/* 파일 목록 카드 */}
      <div style={{
        maxHeight: '280px',
        overflowY: 'auto',
        background: 'rgba(15, 23, 42, 0.6)',
        borderRadius: '6px',
        border: '1px solid #1e293b',
        padding: '8px',
        marginBottom: '14px'
      }}>
        {candidates.map((cand, idx) => {
          const isChecked = selectedPaths.has(cand.path);
          const fileName = cand.path.split('/').pop() || cand.path;
          const dirPath = cand.path.includes('/') ? cand.path.substring(0, cand.path.lastIndexOf('/')) : '';

          return (
            <div
              key={cand.path || idx}
              onClick={() => handleTogglePath(cand.path)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '10px',
                padding: '6px 8px',
                borderRadius: '4px',
                cursor: isSubmitted ? 'default' : 'pointer',
                background: isChecked ? 'rgba(59, 130, 246, 0.1)' : 'transparent',
                border: isChecked ? '1px solid rgba(59, 130, 246, 0.3)' : '1px solid transparent',
                marginBottom: '4px',
                transition: 'all 0.15s ease'
              }}
            >
              {/* 체크박스 */}
              <div style={{ color: isChecked ? '#60a5fa' : '#64748b', display: 'flex', alignItems: 'center' }}>
                {isChecked ? <CheckSquare size={16} /> : <Square size={16} />}
              </div>

              {/* 파일 아이콘 및 정보 */}
              <FileCode size={14} color="#94a3b8" style={{ flexShrink: 0 }} />
              
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <span
                    onClick={(e) => handleOpenFile(cand.path, e)}
                    title="클릭 시 에디터에서 열기"
                    style={{
                      fontWeight: 600,
                      fontSize: '13px',
                      color: isChecked ? '#f8fafc' : '#94a3b8',
                      textDecoration: 'underline',
                      cursor: 'pointer'
                    }}
                  >
                    {fileName}
                  </span>
                  <span style={{
                    fontSize: '10px',
                    padding: '1px 5px',
                    borderRadius: '3px',
                    fontWeight: 600,
                    background: cand.type === 'CREATE' ? '#059669' : '#334155',
                    color: '#fff'
                  }}>
                    {cand.type}
                  </span>
                </div>
                {dirPath && (
                  <div style={{ fontSize: '11px', color: '#64748b', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {dirPath}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* 하단 버튼 영역 */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
        <button
          onClick={handleCancel}
          disabled={isSubmitted}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '4px',
            padding: '6px 12px',
            background: 'rgba(255, 255, 255, 0.05)',
            border: '1px solid #475569',
            borderRadius: '4px',
            color: '#94a3b8',
            fontSize: '12px',
            cursor: isSubmitted ? 'not-allowed' : 'pointer',
            opacity: isSubmitted && submittedAction !== 'cancel' ? 0.5 : 1
          }}
        >
          <SkipForward size={14} />
          {isSubmitted && submittedAction === 'cancel' ? '전체 진행 완료' : `건너뛰기 (전체 ${candidates.length}개 진행)`}
        </button>

        <button
          onClick={handleSubmit}
          disabled={isSubmitted || selectedPaths.size === 0}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '4px',
            padding: '6px 16px',
            background: isSubmitted || selectedPaths.size === 0 ? '#475569' : '#2563eb',
            border: 'none',
            borderRadius: '4px',
            color: '#fff',
            fontSize: '12px',
            fontWeight: 600,
            cursor: isSubmitted || selectedPaths.size === 0 ? 'not-allowed' : 'pointer',
            opacity: isSubmitted && submittedAction !== 'submit' ? 0.5 : 1
          }}
        >
          <Check size={14} />
          {isSubmitted && submittedAction === 'submit' ? '선택 완료' : `선택 파일(${selectedPaths.size}개)로 검증 진행`}
        </button>
      </div>
    </div>
  );
};
