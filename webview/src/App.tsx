import { useState, useEffect, useRef, memo } from 'react';
import { Settings, Edit, RotateCcw, Square, Terminal } from 'lucide-react';
import './index.css';

declare global {
  interface Window {
    sendToIde?: (message: string) => void;
  }
}

// ─────────────────────────────────────────────
//  데이터 타입 정의
// ─────────────────────────────────────────────
interface Message {
  id: string;
  role: 'user' | 'ai' | 'tool';
  subType?: string;
  content: string;
  stepLabel?: string;
  applyable?: boolean;
  isSuccess?: boolean;
  originalCode?: string;
  extractedCode?: string;
  applyScope?: string;
}

type ApplyPhase = 'initial' | 'viewingDiff' | 'applied';

// ─────────────────────────────────────────────
//  유틸리티: Diff 계산
// ─────────────────────────────────────────────
type DiffLine = { type: 'same' | 'add' | 'remove'; text: string };

function computeLineDiff(oldText: string, newText: string): DiffLine[] {
  const oldLines = oldText.split('\n');
  const newLines = newText.split('\n');
  const m = oldLines.length, n = newLines.length;

  if (m * n > 250_000) {
    return [
      ...oldLines.map(t => ({ type: 'remove' as const, text: t })),
      ...newLines.map(t => ({ type: 'add' as const, text: t })),
    ];
  }

  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      dp[i][j] = oldLines[i - 1] === newLines[j - 1] ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);

  const result: DiffLine[] = [];
  let i = m, j = n;
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      result.unshift({ type: 'same', text: oldLines[i - 1] }); i--; j--;
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      result.unshift({ type: 'add', text: newLines[j - 1] }); j--;
    } else {
      result.unshift({ type: 'remove', text: oldLines[i - 1] }); i--;
    }
  }
  return result;
}

// ─────────────────────────────────────────────
//  컴포넌트: DiffPanel
// ─────────────────────────────────────────────
const DiffPanel = memo(({ originalCode, newCode }: { originalCode: string; newCode: string }) => {
  const lines = computeLineDiff(originalCode, newCode);
  return (
    <div className="diff-panel">
      <div className="diff-content">
        {lines.map((line, idx) => (
          <div key={idx} className={`diff-line diff-line-${line.type}`}>
            <span style={{ width: 12, textAlign: 'center', opacity: 0.5 }}>
              {line.type === 'add' ? '+' : line.type === 'remove' ? '-' : ' '}
            </span>
            <pre>{line.text}</pre>
          </div>
        ))}
      </div>
    </div>
  );
});

// ─────────────────────────────────────────────
//  컴포넌트: ActionCard (코드 제안 카드)
// ─────────────────────────────────────────────
const ActionCard = ({ msg, onApply }: { msg: Message; onApply: () => void }) => {
  const [showDiff, setShowDiff] = useState(false);
  
  return (
    <div className="action-card">
      <div className="action-card-header">
        <span className="title">코드 개선 제안</span>
        <span className="filename">{msg.applyScope || 'MainActivity.kt'}</span>
      </div>
      {showDiff && msg.originalCode && (
        <DiffPanel originalCode={msg.originalCode} newCode={msg.extractedCode || ''} />
      )}
      <div className="action-card-body">
        <button className="btn-action" onClick={() => setShowDiff(!showDiff)}>
          {showDiff ? 'Diff 숨기기' : 'Diff 보기'}
        </button>
        <button className="btn-action btn-apply" onClick={onApply}>Apply</button>
        <button className="btn-action">Ignore</button>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────
//  컴포넌트: SuccessCard (적용 완료 카드)
// ─────────────────────────────────────────────
const SuccessCard = ({ filename, onUndo }: { filename: string; onUndo: () => void }) => (
  <div className="success-card">
    <div className="success-card-header">
      <span className="title">Applied ✨</span>
      <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>{filename}</span>
    </div>
    <div className="success-card-content">
      <span>✓ 파일이 성공적으로 수정되었습니다.</span>
      <button className="btn-undo" onClick={onUndo}><RotateCcw size={10} /> Undo</button>
    </div>
  </div>
);

// ─────────────────────────────────────────────
//  컴포넌트: MessageItem (역할별 분기)
// ─────────────────────────────────────────────
const MessageItem = ({ msg }: { msg: Message }) => {
  const [phase, setPhase] = useState<ApplyPhase>('initial');

  // 1. Tool / Status 메시지
  if (msg.role === 'tool') {
    return (
      <div className="msg-tool">
        <div className="dot" />
        {msg.content}
      </div>
    );
  }

  // 2. User 메시지
  if (msg.role === 'user') {
    return <div className="msg-user">{msg.content}</div>;
  }

  const handleApply = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ command: '/apply', text: msg.extractedCode || msg.content }));
      setPhase('applied');
    }
  };

  const handleUndo = () => {
    if (window.sendToIde) window.sendToIde(JSON.stringify({ command: '/undo' }));
    setPhase('initial');
  };

  // 3. AI 메시지
  const isError = msg.isSuccess === false;
  const canShowActionCard = msg.applyable && msg.isSuccess !== false && phase === 'initial';

  return (
    <div className="msg-ai">
      <div className="msg-ai-header" style={{ color: isError ? 'var(--danger-color)' : 'inherit' }}>
        WhatUWant?
      </div>
      <div className="msg-ai-content">
        <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{msg.content}</p>
      </div>

      {canShowActionCard && (
        <ActionCard msg={msg} onApply={handleApply} />
      )}

      {phase === 'applied' && (
        <SuccessCard filename={msg.applyScope || 'MainActivity.kt'} onUndo={handleUndo} />
      )}

      <div className="msg-ai-actions">
        <button className="btn-small" onClick={() => navigator.clipboard.writeText(msg.content)}>Copy</button>
        {!canShowActionCard && phase !== 'applied' && <button className="btn-small">Save</button>}
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────
//  메인 App
// ─────────────────────────────────────────────
function App() {
  const [messages, setMessages] = useState<Message[]>([{
    id: '1', role: 'ai', content: '무엇을 도와드릴까요? (/explain, /chat, /task 등을 지원합니다.)'
  }]);
  const [isTyping, setIsTyping] = useState(false);
  const [inputText, setInputText] = useState('');
  const chatListRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, isTyping]);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const data = event.data;
      if (!data || data.type !== 'ai_message') return;

      if (['task_start', 'task_progress', 'task_cancelled'].includes(data.subType)) {
        setMessages(prev => [...prev, { id: Date.now().toString(), role: 'tool', content: data.content }]);
        setIsTyping(false);
        return;
      }

      setMessages(prev => [...prev, {
        id:            Date.now().toString(),
        role:          'ai',
        content:       data.content,
        subType:       data.subType,
        stepLabel:     data.stepLabel,
        applyable:     data.applyable === 'true',
        isSuccess:     data.isSuccess === 'true',
        originalCode:  data.originalCode,
        extractedCode: data.extractedCode,
        applyScope:    data.applyScope,
      }]);
      setIsTyping(false);
    };
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, []);

  const handleSend = () => {
    const text = inputText.trim();
    if (!text || !window.sendToIde) return;
    setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', content: text }]);
    setInputText('');
    setIsTyping(true);
    window.sendToIde(JSON.stringify({ command: text.startsWith('/') ? text.split(' ')[0] : '/task', text }));
  };

  return (
    <div id="root">
      <header className="header flex justify-between items-center">
        <span className="title">WhatUWant?</span>
        <div className="flex gap-2">
          <button className="icon-btn"><Terminal size={14} /></button>
          <button className="icon-btn"><Edit size={14} /></button>
          <button className="icon-btn"><Settings size={14} /></button>
        </div>
      </header>

      <div className="chat-list" ref={chatListRef}>
        {messages.map(msg => <MessageItem key={msg.id} msg={msg} />)}
        {isTyping && (
          <div className="msg-ai" style={{ width: 'fit-content' }}>
            <div className="msg-ai-header">WhatUWant?</div>
            <div className="flex items-center gap-2">
              <div className="typing-dots"><span></span><span></span><span></span></div>
              <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>응답 생성 중...</span>
            </div>
          </div>
        )}
      </div>

      <div className="chat-input-area">
        <div className="input-toolbar">
          <button className="toolbar-btn">@ 파일 첨부</button>
          <button className="toolbar-btn">/ 명령어</button>
        </div>
        <div className="textarea-wrapper">
          <textarea
            placeholder="메시지를 입력하세요..."
            value={inputText}
            onChange={e => setInputText(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); } }}
          />
          <div className="input-footer">
            <span className="helper-text">Shift+Enter : 줄바꿈 / Enter : 전송</span>
            <div className="flex gap-2">
              <button className="btn-small" style={{ borderColor: 'transparent' }} onClick={() => window.sendToIde?.(JSON.stringify({ command: '/undo' }))}>
                <RotateCcw size={12} />
              </button>
              <button className="btn-small" style={{ background: 'var(--danger-color)', border: 'none' }} onClick={() => window.sendToIde?.(JSON.stringify({ command: '/cancel' }))}>
                <Square size={10} fill="currentColor" /> Stop
              </button>
              <button className="btn-primary" onClick={handleSend}>전송</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default App;
