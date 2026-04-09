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
  isLoading?: boolean;
  isError?: boolean;
  isStreaming?: boolean;
  currentStatus?: string;
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
  const isError = msg.isError === true;
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
        {isError ? '❌ Error' : isAnalysis ? '📋 분석 & 결과' : '💡 개선 제안'}
      </div>
      
      <div className={`msg-ai-content ${msg.isError ? 'error-text' : ''}`}>
        {/* 누적된 설명/분석 텍스트 */}
        {msg.content && <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{msg.content}</p>}

        {/* 인라인 진행 상태 표시 */}
        {msg.isLoading && (
          <div className="inline-loading-area" style={{ marginTop: msg.content ? '12px' : '0' }}>
            <div className="typing-dots"><span></span><span></span><span></span></div>
            {msg.currentStatus && <span className="status-text">{msg.currentStatus}</span>}
          </div>
        )}

        {/* 에러 정보 배지 */}
        {isError && <div className="error-badge" style={{ marginTop: '12px' }}>ERROR</div>}
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
  const [inputText, setInputText] = useState('');
  const [selectedModel, setSelectedModel] = useState<string>('Loading...');
  const chatListRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const data = event.data;
      if (!data || data.type !== 'ai_message') return;

      if (data.subType === 'selected_model') {
        setSelectedModel(data.content);
        return;
      }

      const messageId = data.messageId || data.id; // messageId 또는 id 필드 확인

      if (data.subType === 'apply_success') {
        if (messageId) {
          setMessages(prev => prev.map(m => m.id === messageId ? { ...m, applied: true } : m));
        }
        return;
      }
      if (data.subType === 'undo_success') {
        if (messageId) {
          setMessages(prev => prev.map(m => m.id === messageId ? { ...m, applied: false } : m));
        }
        return;
      }

      // 공통 처리 로직: messageId가 있는 모든 AI 응답
      if (messageId) {
        setMessages(prev => {
          const index = prev.findIndex(m => m.id === messageId);
          
          // 1. 기존 메시지가 있는 경우 (Update)
          if (index !== -1) {
            const existing = prev[index];
            const updated = [...prev];
            
            let newContent = existing.content;
            let currentStatus = existing.currentStatus;
            let isLoading = existing.isLoading;
            let isError = existing.isError;
            let isStreaming = existing.isStreaming;

            // 서브타입별 업데이트 정책
            switch (data.subType) {
              case 'chat_chunk':
                newContent += data.content;
                isLoading = false;
                isStreaming = true;
                break;
              case 'task_progress':
                currentStatus = data.content;
                isLoading = true;
                break;
              case 'task_step':
                // 텍스트 누적 (말풍선 확장 구조)
                newContent += (newContent ? "\n\n" : "") + data.content;
                currentStatus = undefined;
                isLoading = true; // 다음 Step이 있을 수 있으므로 계속 로딩 유지
                isStreaming = false;
                break;
              case 'task_success':
                isLoading = false;
                currentStatus = undefined;
                // "완료되었습니다" 텍스트는 굳이 본문에 누적하지 않고 로딩만 해제 (사용자 선택)
                break;
              case 'error':
              case 'task_cancelled':
                currentStatus = undefined;
                isLoading = false;
                isError = data.subType === 'error';
                newContent += (newContent ? "\n\n" : "") + data.content;
                break;
              case 'chat':
              case 'explain':
                newContent = data.isStreaming ? existing.content : data.content;
                isLoading = false;
                break;
            }

            updated[index] = {
              ...existing,
              content:      newContent,
              currentStatus: currentStatus,
              isLoading:    isLoading,
              isError:      isError,
              isStreaming:  isStreaming,
              // 코드 관련 메타데이터는 무조건 최신 정보로 덮어쓰기 (사용자 요구사항 - 항상 최신 제안만 유지)
              subType:       data.subType,
              applyable:     data.applyable === 'true',
              isSuccess:     data.isSuccess !== 'false',
              applyScope:    data.applyScope || existing.applyScope,
              originalCode:  data.originalCode || existing.originalCode,
              modifiedCode:  data.modifiedCode || existing.modifiedCode,
              extractedCode: data.extractedCode || existing.extractedCode,
            };
            return updated;
          }

          // 2. 신규 메시지 생성 (Create)
          const newMsg: Message = {
            id: messageId,
            role: 'ai',
            content: data.content,
            subType: data.subType,
            isLoading: ['task_start', 'explain_start', 'chat_start', 'task_progress'].includes(data.subType),
            currentStatus: data.subType.endsWith('_start') ? data.content : undefined
          };
          return [...prev, newMsg];
        });
      }
    };

    const handleError = (e: ErrorEvent) => {
      setMessages(prev => [...prev, { id: Date.now().toString(), role: 'tool', content: `[JS Error] ${e.message}` }]);
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
