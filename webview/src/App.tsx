import { useState, useEffect, useRef } from 'react';
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
  modifiedCode?: string;
  extractedCode?: string;
  applyScope?: string;
  applied?: boolean;
}

// ─────────────────────────────────────────────
//  컴포넌트: ActionCard (코드 제안 카드)
// ─────────────────────────────────────────────
const ActionCard = ({ msg, onApply }: { msg: Message; onApply: () => void }) => {
  const hasOriginal = !!msg.originalCode && msg.originalCode.trim() !== '';
  const hasModified = !!msg.modifiedCode && msg.modifiedCode.trim() !== '';
  const canShowDiff = msg.isSuccess === true && hasOriginal && hasModified;

  const handleApply = () => {
    onApply();
  };

  const handleViewDiff = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ 
        command: '/viewDiff', 
        original: msg.originalCode,
        modified: msg.modifiedCode,
        scope: msg.applyScope
      }));
    }
  };



  return (
    <div className="action-card">
      <div className="action-card-header">
        <span className="title">✨ 코드 개선 제안</span>
        <span className="filename">{msg.applyScope || 'MainActivity.kt'}</span>
      </div>
      <div className="action-card-body">
        {canShowDiff && (
          <button className="btn-action btn-diff" onClick={handleViewDiff}>
            🔍 Diff 보기
          </button>
        )}
        <button className="btn-action btn-apply" onClick={handleApply}>
          Apply
        </button>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────
//  컴포넌트: MessageItem (역할별 분기)
// ─────────────────────────────────────────────
const MessageItem = ({ msg }: { msg: Message }) => {
  const isApplied = msg.applied === true;

  // 1. Tool / Status 메시지 (진행 상태 등)
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

  // 3. AI 메시지 분기 처리
  const isError = msg.isSuccess === false;
  const isImprovement = msg.applyable === true;
  const isAnalysis = !isError && !isImprovement;

  const handleApply = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ 
        command: '/apply',
        id: msg.id,
        text: msg.modifiedCode || msg.extractedCode || msg.content,
        scope: msg.applyScope || '',
        original: msg.originalCode || ''
      }));
    }
  };

  const handleUndo = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ command: '/undo', id: msg.id }));
    }
  };

  return (
    <div className={`msg-ai ${isError ? 'error' : isAnalysis ? 'analysis' : 'improvement'}`}>
      <div className="msg-ai-header">
        {isError ? '❌ Error' : isAnalysis ? '📋 영향 분석' : '💡 개선 제안'}
      </div>
      
      <div className="msg-ai-content">
        <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{msg.content}</p>
      </div>

      {isImprovement && !isError && !isApplied && (
        <ActionCard msg={msg} onApply={handleApply} />
      )}

      {isImprovement && isApplied && (
        <div className="applied-info">
          <span>✓ 코드가 에디터에 적용되었습니다.</span>
          <button className="btn-undo-link" onClick={handleUndo}>Undo</button>
        </div>
      )}

      {/* UX: 일반 메시지에는 버튼을 붙이지 않음 (Copy, Save 제거) */}
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
  const [selectedModel, setSelectedModel] = useState<string>('Loading...');
  const chatListRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, isTyping]);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const data = event.data;
      if (!data || data.type !== 'ai_message') return;

      if (data.subType === 'selected_model') {
        setSelectedModel(data.content);
        return;
      }

      if (data.subType === 'apply_success') {
        const targetId = data.id;
        if (targetId) {
          setMessages(prev => prev.map(m => m.id === targetId ? { ...m, applied: true } : m));
        }
        return;
      }
      if (data.subType === 'undo_success') {
        const targetId = data.id;
        if (targetId) {
          setMessages(prev => prev.map(m => m.id === targetId ? { ...m, applied: false } : m));
        }
        return;
      }

      if (data.subType === 'chat_chunk') {
        const { messageId, content } = data;
        setMessages(prev => {
          const index = prev.findIndex(m => m.id === messageId);
          if (index !== -1) {
            // 기존 메시지에 내용 추가
            const updated = [...prev];
            updated[index] = { ...updated[index], content: updated[index].content + content };
            return updated;
          } else {
            // 새로운 스트리밍 메시지 생성
            return [...prev, {
              id: messageId,
              role: 'ai',
              content: content,
              subType: 'chat_chunk'
            }];
          }
        });
        setIsTyping(true); // 스트리밍 중에도 타이핑 상태 유지
        return;
      }

      if (['task_start', 'task_progress', 'task_cancelled', 'error'].includes(data.subType)) {
        setMessages(prev => [...prev, { id: Date.now().toString(), role: 'tool', content: data.content }]);
        setIsTyping(false);
        return;
      }

      const messageId = data.messageId || Date.now().toString();
      const newMsg: Message = {
        id:            messageId,
        role:          'ai',
        content:       data.content,
        subType:       data.subType,
        stepLabel:     data.stepLabel    || undefined,
        applyable:     data.applyable    === 'true',
        isSuccess:     data.isSuccess    !== 'false',
        originalCode:  data.originalCode || undefined,
        modifiedCode:  data.modifiedCode || undefined,
        extractedCode: data.extractedCode || '',
        applyScope:    data.applyScope   || '',
        applied:       false,
      };

      setMessages(prev => {
        // 이미 스트리밍으로 생성된 메시지가 있다면 메타데이터만 보강하고, 없으면 추가
        const index = prev.findIndex(m => m.id === messageId);
        if (index !== -1) {
          const updated = [...prev];
          // 중요: content는 스트리밍으로 쌓인 기존 데이터를 유지하고 다른 필드만 덮어씀
          updated[index] = { ...newMsg, content: updated[index].content };
          return updated;
        }
        return [...prev, newMsg];
      });
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
    if (!text || !window.sendToIde) return;
    setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', content: text }]);
    setInputText('');
    setIsTyping(true);
    window.sendToIde(JSON.stringify({ command: text.startsWith('/') ? text.split(' ')[0] : '/task', text }));
  };

  return (
    <div id="root">
      <header className="header flex justify-between items-center">
        <span className="title">iXpert AI Assistant</span>
        <div className="flex gap-2">
          <button className="icon-btn"><Terminal size={14} /></button>
          <button className="icon-btn"><Edit size={14} /></button>
          <button className="icon-btn" onClick={() => window.sendToIde?.(JSON.stringify({ command: '/openSettings' }))}>
            <Settings size={14} />
          </button>
        </div>
      </header>

      <div className="chat-list" ref={chatListRef}>
        {messages.map(msg => <MessageItem key={msg.id} msg={msg} />)}
        {isTyping && (
          <div className="msg-ai" style={{ width: 'fit-content' }}>
            <div className="msg-ai-header">iXpert AI Assistant</div>
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
        <div className="model-status-footer">
          <Terminal size={10} className="inline mr-1 opacity-60" />
          <span>Model: <strong>{selectedModel}</strong></span>
        </div>
      </div>
    </div>
  );
}

export default App;
