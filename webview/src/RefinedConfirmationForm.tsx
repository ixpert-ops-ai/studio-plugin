import React, { useMemo, useState } from 'react';
import { FileCheck2, Play, Pencil, X } from 'lucide-react';
import { EXPIRED_TEXT, buildSubmitText, evaluateEdit, parsePayload } from './refinedConfirmLogic';

interface RefinedConfirmationFormProps {
  payload: string; // JSON string from msg.content
  onCancel: (projectId: string) => void;
  onSubmit: (projectId: string, text: string) => void; // text: 백엔드로 보낼 본문(JSON)
  expired?: boolean; // 응답 없이 대기 시간이 지나 원래 문장으로 분석이 진행됨: 카드는 비활성이 되고 안내가 보인다
}

/** 분석 시작 전 확인 카드: 원문 SR과 정제문을 나란히 보이고, 정제문은 고칠 수 있다. */
export const RefinedConfirmationForm: React.FC<RefinedConfirmationFormProps> = ({ payload, onCancel, onSubmit, expired = false }) => {
  const data = useMemo(() => parsePayload(payload), [payload]);
  const shown = data?.refinedRequirement ?? '';
  const [edited, setEdited] = useState<string>(shown);
  const [submittedAction, setSubmittedAction] = useState<'asis' | 'edited' | 'cancel' | null>(null);
  const isSubmitted = submittedAction !== null || expired;

  if (!data) {
    return (
      <div className="msg-ai analysis refined-confirm" data-message-role="ai" style={{ padding: '12px', border: '1px solid #ef4444', borderRadius: '6px' }}>
        정제문 확인 데이터를 불러오는 데 실패했습니다.
      </div>
    );
  }

  const state = evaluateEdit(shown, edited);

  const send = (action: 'asis' | 'edited') => {
    if (isSubmitted) return;
    const text = buildSubmitText(data.projectId, shown, edited, action);
    if (text === null) return;
    setSubmittedAction(action);
    onSubmit(data.projectId, text);
  };

  const cancel = () => {
    if (isSubmitted) return;
    setSubmittedAction('cancel');
    onCancel(data.projectId);
  };

  const box: React.CSSProperties = {
    background: 'rgba(15, 23, 42, 0.6)',
    border: '1px solid #1e293b',
    borderRadius: '6px',
    padding: '10px',
    fontSize: '13px',
    lineHeight: 1.5,
    color: '#e2e8f0',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  };
  const label: React.CSSProperties = { fontSize: '11px', fontWeight: 600, color: '#93c5fd', marginBottom: '4px' };
  const button = (enabled: boolean, primary: boolean): React.CSSProperties => ({
    display: 'flex',
    alignItems: 'center',
    gap: '4px',
    padding: '6px 12px',
    background: primary ? (enabled ? '#2563eb' : '#334155') : 'rgba(255, 255, 255, 0.05)',
    border: primary ? 'none' : '1px solid #475569',
    borderRadius: '4px',
    color: primary ? '#fff' : '#94a3b8',
    fontSize: '12px',
    cursor: enabled ? 'pointer' : 'not-allowed',
    opacity: enabled ? 1 : 0.5,
  });

  return (
    <div
      className="msg-ai analysis refined-confirm"
      data-message-role="ai"
      data-refined-confirm="true"
      style={{
        background: 'rgba(17, 24, 39, 0.85)',
        border: '1px solid #3b82f6',
        borderRadius: '8px',
        padding: '16px',
        marginTop: '12px',
        marginBottom: '12px',
        color: '#e2e8f0',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px', paddingBottom: '8px', borderBottom: '1px solid rgba(59, 130, 246, 0.3)' }}>
        <FileCheck2 size={18} color="#60a5fa" />
        <span style={{ fontWeight: 600, fontSize: '14px', color: '#93c5fd' }}>분석 시작 전 요구사항 확인</span>
      </div>

      <div style={{ fontSize: '13px', color: '#cbd5e1', marginBottom: '12px', lineHeight: 1.5 }}>
        {data.message || '분석을 시작하기 전에 정제된 요구사항을 확인해 주세요. 그대로 진행하거나 고쳐서 진행할 수 있습니다.'}
      </div>

      <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap', marginBottom: '12px' }}>
        <div style={{ flex: '1 1 240px', minWidth: 0 }}>
          <div style={label}>원문 요구사항</div>
          <div style={box} data-refined-confirm-original="true">{data.originalRequirement}</div>
        </div>
        <div style={{ flex: '1 1 240px', minWidth: 0 }}>
          <div style={label}>정제된 요구사항 (고칠 수 있습니다)</div>
          <textarea
            data-refined-confirm-edit="true"
            value={edited}
            disabled={isSubmitted}
            onChange={e => setEdited(e.target.value)}
            rows={5}
            style={{ ...box, width: '100%', boxSizing: 'border-box', resize: 'vertical', fontFamily: 'inherit', borderColor: state.blank ? '#ef4444' : '#1e293b' }}
          />
          {state.blank && (
            <div data-refined-confirm-blank="true" style={{ fontSize: '11px', color: '#fca5a5', marginTop: '4px' }}>
              정제문이 비어 있으면 진행할 수 없습니다.{' '}
              <button
                type="button"
                onClick={() => setEdited(shown)}
                disabled={isSubmitted}
                style={{ background: 'transparent', border: 'none', color: '#60a5fa', textDecoration: 'underline', cursor: 'pointer', fontSize: '11px', padding: 0 }}
              >
                원래대로 되돌리기
              </button>
            </div>
          )}
        </div>
      </div>

      {expired && (
        <div data-refined-confirm-expired="true" style={{ fontSize: '12px', color: '#fcd34d', marginBottom: '10px' }}>
          {EXPIRED_TEXT}
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', flexWrap: 'wrap' }}>
        <button type="button" data-refined-confirm-action="cancel" onClick={cancel} disabled={isSubmitted} style={button(!isSubmitted, false)}>
          <X size={14} />
          {submittedAction === 'cancel' ? '취소함' : '취소'}
        </button>
        <button type="button" data-refined-confirm-action="asis" onClick={() => send('asis')} disabled={isSubmitted || !state.canProceedAsIs} style={button(!isSubmitted && state.canProceedAsIs, false)}>
          <Play size={14} />
          {submittedAction === 'asis' ? '진행 중' : '이대로 분석'}
        </button>
        <button type="button" data-refined-confirm-action="edited" onClick={() => send('edited')} disabled={isSubmitted || !state.canProceedEdited} style={button(!isSubmitted && state.canProceedEdited, true)}>
          <Pencil size={14} />
          {submittedAction === 'edited' ? '진행 중' : '수정한 내용으로 분석'}
        </button>
      </div>
    </div>
  );
};
