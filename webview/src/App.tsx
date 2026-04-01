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
}

// ─────────────────────────────────────────────
//  Apply 버튼 포함 TaskStep 말풍선
// ─────────────────────────────────────────────
const TaskStepBubble = ({ msg }: { msg: Message }) => {
  const [applied, setApplied] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleApply = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ command: '/apply', text: msg.content }));
      setApplied(true);
    }
  };

  const handleCopy = () => {
    navigator.clipboard.writeText(msg.content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="msg-ai">
      <div className="msg-ai-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>
          WhatUWant?
          {msg.stepLabel && (
            <span style={{ fontSize: '11px', color: 'var(--accent-color)', marginLeft: '6px' }}>
              ✅ {msg.stepLabel}
            </span>
          )}
        </span>
      </div>
      <div className="msg-ai-content">
        <p style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</p>
      </div>
      <div className="msg-ai-actions">
        <button className="btn-small" onClick={handleCopy}>
          {copied ? '복사됨 ✓' : 'Copy'}
        </button>
        {msg.applyable && (
          <button
            className="btn-small"
            onClick={handleApply}
            disabled={applied}
            style={applied ? { opacity: 0.5 } : { background: 'var(--bg-button-active)', color: '#fff', border: 'none' }}
          >
            {applied ? 'Applied ✓' : '⚡ Apply'}
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
  const label = msg.subType === 'explain' ? ' [Explain]'
               : msg.subType === 'chat'    ? ' [Chat]'
               : msg.subType === 'apply_result' ? ' [Apply]'
               : '';
  return (
    <div className="msg-ai">
      <div className="msg-ai-header">
        WhatUWant?
        {label && <span style={{ fontSize: '11px', color: 'var(--accent-color)' }}>{label}</span>}
      </div>
      <div className="msg-ai-content">
        <p style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</p>
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
        <button className={`toggle-btn ${mode === 'Act' ? 'active' : ''}`} onClick={() => setMode('Act')}>Act</button>
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
}
const ChatInputArea = ({ inputText, setInputText, onSend }: ChatInputAreaProps) => {
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
            <button className="btn btn-danger"><Square size={12} fill="currentColor" />Stop</button>
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
    id: '1', role: 'ai', content:
      '/explain 명령어로 코드 설명 · /chat으로 일반 대화 · 아무 텍스트나 입력하면 AI가 알아서 처리합니다!',
    subType: 'welcome'
  }]);
  const [isTyping, setIsTyping] = useState(false);
  const [inputText, setInputText] = useState('');
  const chatListRef = useRef<HTMLDivElement>(null);

  // 메시지 추가 시 자동 스크롤
  useEffect(() => {
    chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, isTyping]);

  // IDE → React 이벤트 수신
  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const data = event.data;
      if (!data || data.type !== 'ai_message') return;

      const newMsg: Message = {
        id: Date.now().toString(),
        role: 'ai',
        content: data.content,
        subType: data.subType,
        stepLabel: data.stepLabel || undefined,
        applyable: data.applyable === 'true',
      };
      setMessages(prev => [...prev, newMsg]);
      setIsTyping(false);
    };

    const handleError = (e: any) => {
      const msg = e.message || e.toString();
      setMessages(prev => [...prev, { id: Date.now().toString(), role: 'tool', content: `[JS Error] ${msg}` }]);
      setIsTyping(false);
    };

    window.addEventListener('message', handleMessage);
    window.addEventListener('error', handleError);
    return () => {
      window.removeEventListener('message', handleMessage);
      window.removeEventListener('error', handleError);
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

    // 명령어 라우팅
    if (text.startsWith('/explain')) {
      window.sendToIde(JSON.stringify({ command: '/explain', text: text.replace('/explain', '').trim() }));
    } else if (text.startsWith('/chat')) {
      window.sendToIde(JSON.stringify({ command: '/chat', text: text.replace('/chat', '').trim() }));
    } else {
      // 일반 텍스트 → TaskAgent (IntentAnalyzer가 분기)
      window.sendToIde(JSON.stringify({ command: '/task', text }));
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
          // task_step: Apply 버튼 있는 전용 말풍선
          if (msg.subType === 'task_step') return <TaskStepBubble key={msg.id} msg={msg} />;
          // 일반 AI 말풍선
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

      <ChatInputArea inputText={inputText} setInputText={setInputText} onSend={handleSend} />
    </>
  );
}

export default App;
