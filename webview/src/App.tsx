import { useState, useEffect } from 'react';
import { Settings, Edit, Clock, CornerDownLeft, Square } from 'lucide-react';
import './index.css';

// 타입 선언 병합으로 전역 객체에 JCEF가 주입한 함수 추가
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
}

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

const ContextBar = () => {
  return (
    <div className="context-bar">
      <span>Context</span>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: '30%' }}></div>
      </div>
      <span>1,240 / 4,096 tokens</span>
    </div>
  );
};

interface ChatInputAreaProps {
  inputText: string;
  setInputText: (text: string) => void;
  onSend: () => void;
}

const ChatInputArea = ({ inputText, setInputText, onSend }: ChatInputAreaProps) => {
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSend();
    }
  };

  return (
    <div className="chat-input-area">
      <button className="attach-btn">@ 파일 첨부</button>
      <div className="textarea-wrapper">
        <textarea 
          placeholder="명령어(/explain 등)나 메시지를 입력하세요..." 
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
            <button className="btn btn-danger">
              <Square size={12} fill="currentColor" />
              Stop
            </button>
            <button className="btn btn-primary" onClick={onSend}>
              전송
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

function App() {
  const [messages, setMessages] = useState<Message[]>([
    {
      id: "1",
      role: "ai",
      content: "/explain 명령어를 입력하여 선택된 코드를 분석해보세요.",
      subType: "welcome"
    }
  ]);
  const [isTyping, setIsTyping] = useState<boolean>(false);
  const [inputText, setInputText] = useState("");

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const data = event.data;
      if (data && data.type === 'ai_message') {
        const newMessage: Message = {
          id: Date.now().toString(),
          role: 'ai',
          content: data.content,
          subType: data.subType
        };
        setMessages(prev => [...prev, newMessage]);
        setIsTyping(false);
      }
    };

    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, []);

  const handleSend = () => {
    const text = inputText.trim();
    if (!text) return;

    // 사용자 메시지 화면에 렌더링
    setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', content: text }]);
    setInputText("");
    
    // 명령어 감지 시 백엔드로 요청
    if (text === '/explain') {
      setIsTyping(true);
      if (window.sendToIde) {
        console.log("JS -> IDE: Sending /explain command");
        window.sendToIde(JSON.stringify({ command: "/explain" }));
      } else {
        console.error("window.sendToIde is undefined. Not running in IDE environment or JCEF injection failed.");
        setIsTyping(false);
      }
    }
  };

  return (
    <>
      <Header />
      <ContextBar />
      
      <div className="chat-list">
        {messages.map((msg) => {
          if (msg.role === 'user') return <div key={msg.id} className="msg-user">{msg.content}</div>;
          if (msg.role === 'tool') return <div key={msg.id} className="msg-tool"><div className="dot"></div>{msg.content}</div>;

          return (
            <div key={msg.id} className="msg-ai">
              <div className="msg-ai-header">WhatUWant? {msg.subType === 'explain' && <span style={{fontSize: '11px', color: 'var(--accent-color)'}}> [Explain]</span>}</div>
              <div className="msg-ai-content"><p style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</p></div>
              <div className="msg-ai-actions">
                <button className="btn-small">Copy</button>
                <button className="btn-small">Save</button>
              </div>
            </div>
          );
        })}

        {isTyping && (
          <div className="msg-ai" style={{ width: 'fit-content' }}>
            <div className="msg-ai-header">WhatUWant?</div>
            <div className="flex items-center gap-2 text-muted" style={{ fontSize: '12px', color: 'var(--text-muted)'}}>
              <div className="typing-dots"><span></span><span></span><span></span></div>
              분석 중...
            </div>
          </div>
        )}
      </div>

      <ChatInputArea inputText={inputText} setInputText={setInputText} onSend={handleSend} />
    </>
  );
}

export default App;
