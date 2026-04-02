import { useState, useEffect, useRef } from 'react';
import { Settings, Edit, Clock, CornerDownLeft, Square } from 'lucide-react';
import './index.css';

declare global {
  interface Window {
    sendToIde?: (message: string) => void;
  }
}

interface Message {
  id: string;
  role: 'user' | 'ai' | 'tool';
  content: string;
  subType?: string;
  stepLabel?: string;
  applyable?: boolean;
  originalCode?: string;
  extractedCode?: string;
  applyScope?: string;
}

// ─────────────────────────────────────────────
//  라인 Diff 알고리즘 (LCS 기반, 외부 라이브러리 없음)
// ─────────────────────────────────────────────
type DiffLine =
  | { type: 'same';   text: string }
  | { type: 'add';    text: string }
  | { type: 'remove'; text: string };

function computeLineDiff(oldText: string, newText: string): DiffLine[] {
  const oldLines = oldText.split('\n');
  const newLines = newText.split('\n');
  const m = oldLines.length, n = newLines.length;

  // 라인 수가 너무 많으면 단순 split 표시(성능 보호)
  if (m * n > 250_000) {
    return [
      ...oldLines.map(t => ({ type: 'remove' as const, text: t })),
      ...newLines.map(t => ({ type: 'add'    as const, text: t })),
    ];
  }

  // LCS DP 테이블
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      dp[i][j] = oldLines[i - 1] === newLines[j - 1]
        ? dp[i - 1][j - 1] + 1
        : Math.max(dp[i - 1][j], dp[i][j - 1]);

  // 역추적
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
//  DiffPanel 컴포넌트
// ─────────────────────────────────────────────
const DiffPanel = ({ originalCode, newCode, applyScope }: {
  originalCode: string;
  newCode: string;
  applyScope?: string;
}) => {
  const lines = computeLineDiff(originalCode, newCode);
  const added   = lines.filter(l => l.type === 'add').length;
  const removed = lines.filter(l => l.type === 'remove').length;

  return (
    <div className="diff-panel">
      <div className="diff-header">
        <span>
          <span style={{ color: '#6abf69', marginRight: 6 }}>+{added}</span>
          <span style={{ color: '#cf6679' }}>-{removed}</span>
        </span>
        {applyScope && <span className="scope-badge">📌 {applyScope} 기준</span>}
      </div>
      <div className="diff-content">
        {lines.map((line, idx) => (
          <div key={idx} className={`diff-line diff-line-${line.type}`}>
            <span className="diff-marker">
              {line.type === 'add' ? '+' : line.type === 'remove' ? '-' : ' '}
            </span>
            <pre>{line.text}</pre>
          </div>
        ))}
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────
//  TaskStepBubble — 비교 → 승인 → 적용 상태 머신
// ─────────────────────────────────────────────
type ApplyPhase = 'initial' | 'viewingDiff' | 'applied';

const TaskStepBubble = ({ msg }: { msg: Message }) => {
  const [phase, setPhase]   = useState<ApplyPhase>('initial');
  const [copied, setCopied] = useState(false);

  const hasOriginal  = !!msg.originalCode && msg.originalCode.trim() !== '';
  const codeToApply  = msg.extractedCode || msg.content;

  const handleCopy = () => {
    navigator.clipboard.writeText(codeToApply).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  const handleApply = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ command: '/apply', text: codeToApply }));
      setPhase('applied');
    }
  };

  return (
    <div className="msg-ai">
      {/* 헤더 */}
      <div className="msg-ai-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>WhatUWant?</span>
        {msg.stepLabel && (
          <span style={{ fontSize: '11px', color: 'var(--accent-color, #7eb8f7)' }}>
            ✅ {msg.stepLabel}
          </span>
        )}
      </div>

      {/* LLM 설명 텍스트 */}
      <div className="msg-ai-content">
        <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{msg.content}</p>
      </div>

      {/* Diff 패널 (viewingDiff 단계에서만 표시) */}
      {phase === 'viewingDiff' && hasOriginal && (
        <DiffPanel
          originalCode={msg.originalCode!}
          newCode={msg.extractedCode || ''}
          applyScope={msg.applyScope}
        />
      )}
      {/* originalCode 없을 때 새 코드 생성 알림 */}
      {phase === 'viewingDiff' && !hasOriginal && (
        <div style={{ marginTop: 8, fontSize: '11px', color: 'var(--text-muted)' }}>
          ℹ️ 기존 코드가 없어 Diff를 표시할 수 없습니다.
        </div>
      )}

      {/* 액션 버튼 영역 */}
      <div className="msg-ai-actions" style={{ flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
        <button className="btn-small" onClick={handleCopy}>
          {copied ? '복사됨 ✓' : 'Copy'}
        </button>

        {msg.applyable && phase === 'applied' && (
          <span style={{ fontSize: '11px', color: '#6abf69' }}>Applied ✓</span>
        )}

        {/* originalCode 없음: 새 코드 생성 배지 + 바로 Apply */}
        {msg.applyable && phase === 'initial' && !hasOriginal && (
          <>
            <span className="scope-badge" style={{ fontSize: '10px' }}>새 코드 생성 (비교 없음)</span>
            <button className="btn-small" onClick={handleApply}
              style={{ background: 'var(--bg-button-active)', color: '#fff', border: 'none' }}>
              ⚡ Apply
            </button>
          </>
        )}

        {/* originalCode 있음: Diff 보기 버튼 */}
        {msg.applyable && phase === 'initial' && hasOriginal && (
          <button className="btn-small" onClick={() => setPhase('viewingDiff')}>
            🔍 Diff 보기
          </button>
        )}

        {/* Diff 열람 후: Confirm Apply 버튼 */}
        {msg.applyable && phase === 'viewingDiff' && (
          <button className="btn-confirm" onClick={handleApply}>
            ✅ Confirm Apply
          </button>
        )}
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────
//  일반 AI 말풍선
// ─────────────────────────────────────────────
const AiBubble = ({ msg }: { msg: Message }) => {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(msg.content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };
  const label = msg.subType === 'explain'      ? ' [Explain]'
              : msg.subType === 'chat'          ? ' [Chat]'
              : msg.subType === 'apply_result'  ? ' [Apply]'
              : '';
  return (
    <div className="msg-ai">
      <div className="msg-ai-header">
        WhatUWant?
        {label && <span style={{ fontSize: '11px', color: 'var(--accent-color, #7eb8f7)' }}>{label}</span>}
      </div>
      <div className="msg-ai-content">
        <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{msg.content}</p>
      </div>
      <div className="msg-ai-actions">
        <button className="btn-small" onClick={handleCopy}>{copied ? '복사됨 ✓' : 'Copy'}</button>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────
//  Header
// ─────────────────────────────────────────────
const Header = () => {
  const [mode, setMode] = useState<'Plan' | 'Act'>('Plan');
  return (
    <div className="header flex-col">
      <div className="flex justify-between items-center">
        <div className="flex items-center gap-3">
          <span className="title">WhatUWant?</span>
          <span className="badge">Android</span>
        </div>
        <div className="flex gap-2">
          <button className="icon-btn" title="History"><Clock size={14} /></button>
          <button className="icon-btn" title="New Chat"><Edit size={14} /></button>
          <button className="icon-btn" title="Settings"><Settings size={14} /></button>
        </div>
      </div>
      <div className="toggle-group">
        <button className={`toggle-btn ${mode === 'Plan' ? 'active' : ''}`} onClick={() => setMode('Plan')}>Plan</button>
        <button className={`toggle-btn ${mode === 'Act'  ? 'active' : ''}`} onClick={() => setMode('Act')}>Act</button>
      </div>
    </div>
  );
};

const ContextBar = () => (
  <div className="context-bar">
    <span>Context</span>
    <div className="progress-track"><div className="progress-fill" style={{ width: '30%' }}></div></div>
    <span>1,240 / 4,096 tokens</span>
  </div>
);

// ─────────────────────────────────────────────
//  입력창
// ─────────────────────────────────────────────
interface ChatInputAreaProps {
  inputText: string;
  setInputText: (t: string) => void;
  onSend: () => void;
  onStop: () => void;
}
const ChatInputArea = ({ inputText, setInputText, onSend, onStop }: ChatInputAreaProps) => {
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSend(); }
  };
  return (
    <div className="chat-input-area">
      <button className="attach-btn">@ 파일 첨부</button>
      <div className="textarea-wrapper">
        <textarea
          placeholder="질문이나 /explain, /chat 명령어를 입력하세요..."
          value={inputText}
          onChange={(e) => setInputText(e.target.value)}
          onKeyDown={handleKeyDown}
        />
        <div className="input-footer">
          <button className="btn btn-secondary">
            <CornerDownLeft size={14} style={{ transform: 'rotate(90deg) scaleX(-1)' }} />
            Undo
          </button>
          <div className="flex gap-2">
            <button className="btn btn-danger" onClick={onStop}><Square size={12} fill="currentColor" />Stop</button>
            <button className="btn btn-primary" onClick={onSend}>전송</button>
          </div>
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────
//  App
// ─────────────────────────────────────────────
function App() {
  const [messages, setMessages] = useState<Message[]>([{
    id: '1', role: 'ai',
    content: '/explain 코드 설명 · /chat 일반 대화 · 그 외 입력은 AI가 의도를 분석해 자동 실행합니다!',
    subType: 'welcome'
  }]);
  const [isTyping, setIsTyping]   = useState(false);
  const [inputText, setInputText] = useState('');
  const chatListRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, isTyping]);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const data = event.data;
      if (!data || data.type !== 'ai_message') return;

      // 진행 상태 tool 메시지 (typing 중지)
      if (['task_start', 'task_progress', 'task_cancelled'].includes(data.subType)) {
        setMessages(prev => [...prev, { id: Date.now().toString(), role: 'tool', content: data.content }]);
        setIsTyping(false);
        return;
      }

      const newMsg: Message = {
        id:            Date.now().toString(),
        role:          'ai',
        content:       data.content,
        subType:       data.subType,
        stepLabel:     data.stepLabel    || undefined,
        applyable:     data.applyable    === 'true',
        originalCode:  data.originalCode || '',
        extractedCode: data.extractedCode || '',
        applyScope:    data.applyScope   || '',
      };
      setMessages(prev => [...prev, newMsg]);
      setIsTyping(false);
    };

    const handleError = (e: ErrorEvent) => {
      setMessages(prev => [...prev, { id: Date.now().toString(), role: 'tool', content: `[JS Error] ${e.message}` }]);
      setIsTyping(false);
    };

    window.addEventListener('message', handleMessage);
    window.addEventListener('error', handleError as EventListener);
    return () => {
      window.removeEventListener('message', handleMessage);
      window.removeEventListener('error', handleError as EventListener);
    };
  }, []);

  const handleSend = () => {
    const text = inputText.trim();
    if (!text) return;

    setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', content: text }]);
    setInputText('');
    setIsTyping(true);

    if (!window.sendToIde) {
      setMessages(prev => [...prev, { id: Date.now().toString(), role: 'tool', content: '[오류] IDE 브릿지가 연결되지 않았습니다.' }]);
      setIsTyping(false);
      return;
    }

    if (text.startsWith('/explain')) {
      window.sendToIde(JSON.stringify({ command: '/explain', text: text.replace('/explain', '').trim() }));
    } else if (text.startsWith('/chat')) {
      window.sendToIde(JSON.stringify({ command: '/chat',    text: text.replace('/chat', '').trim() }));
    } else {
      window.sendToIde(JSON.stringify({ command: '/task', text }));
    }
  };

  const handleStop = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ command: '/cancel' }));
    }
  };

  return (
    <>
      <Header />
      <ContextBar />
      <div className="chat-list" ref={chatListRef}>
        {messages.map((msg) => {
          if (msg.role === 'user') return <div key={msg.id} className="msg-user">{msg.content}</div>;
          if (msg.role === 'tool') return (
            <div key={msg.id} className="msg-tool"><div className="dot"></div>{msg.content}</div>
          );
          if (msg.subType === 'task_step') return <TaskStepBubble key={msg.id} msg={msg} />;
          return <AiBubble key={msg.id} msg={msg} />;
        })}

        {isTyping && (
          <div className="msg-ai" style={{ width: 'fit-content' }}>
            <div className="msg-ai-header">WhatUWant?</div>
            <div className="flex items-center gap-2" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              <div className="typing-dots"><span></span><span></span><span></span></div>
              응답 생성 중...
            </div>
          </div>
        )}
      </div>
      <ChatInputArea inputText={inputText} setInputText={setInputText} onSend={handleSend} onStop={handleStop} />
    </>
  );
}

export default App;
