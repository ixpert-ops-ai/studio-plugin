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

  const isError = msg.isError === true;

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

  const handleCopy = () => {
    navigator.clipboard.writeText(msg.content).catch(() => {});
  };

  const handleSave = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ command: '/saveMarkdown', content: msg.content }));
    }
  };

  // ── 코드 말풍선 (task_code): Diff 카드만 표시 ────────────────────
  if (msg.subType === 'task_code') {
    return (
      <div className="msg-ai improvement">
        <div className="msg-ai-header">💡 코드 개선 제안</div>
        <div className="msg-ai-content">
          {msg.isLoading && (
            <div className="inline-loading-area">
              <div className="typing-dots"><span></span><span></span><span></span></div>
              <span className="status-text">개선 코드 생성 중...</span>
            </div>
          )}
        </div>
        {!isApplied && !isError && msg.applyable && (
          <ActionCard msg={msg} onApply={handleApply} />
        )}
        {isApplied && (
          <div className="applied-info">
            <span>✓ 코드가 에디터에 적용되었습니다.</span>
            <button className="btn-undo-link" onClick={handleUndo}>Undo</button>
          </div>
        )}
      </div>
    );
  }

  // ── 텍스트 말풍선 (텍스트 + Copy + Save) ──────────────────────────
  return (
    <div className={`msg-ai ${isError ? 'error' : 'analysis'}`}>
      <div className="msg-ai-header">
        {isError ? '❌ Error' : '📋 분석 & 결과'}
      </div>

      <div className={`msg-ai-content ${isError ? 'error-text' : ''}`}>
        {msg.content && <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{msg.content}</p>}

        {msg.isLoading && (
          <div className="inline-loading-area" style={{ marginTop: msg.content ? '12px' : '0' }}>
            <div className="typing-dots"><span></span><span></span><span></span></div>
            {msg.currentStatus && <span className="status-text">{msg.currentStatus}</span>}
          </div>
        )}

        {isError && <div className="error-badge" style={{ marginTop: '12px' }}>ERROR</div>}
      </div>

      {/* Copy / Save 버튼 (텍스트 말풍선에만 표시, 완료 후) */}
      {!isError && !msg.isLoading && msg.content && (
        <div className="msg-text-actions">
          <button className="btn-text-action" onClick={handleCopy} title="클립보드에 복사">
            📋 Copy
          </button>
          <button className="btn-text-action" onClick={handleSave} title="Markdown 파일로 저장">
            💾 Save .md
          </button>
        </div>
      )}
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
  const isNearBottom = useRef(true); // 사용자가 하단 근처에 있는지 추적
  const isComposing = useRef(false); // 한글 IME composition 상태 추적 (JCEF 자모 분리 방지)

  // 스크롤 위치 감지: 하단 50px 이내이면 자동 스크롤 활성화
  useEffect(() => {
    const el = chatListRef.current;
    if (!el) return;
    const handleScroll = () => {
      isNearBottom.current = el.scrollTop + el.clientHeight >= el.scrollHeight - 50;
    };
    el.addEventListener('scroll', handleScroll, { passive: true });
    return () => el.removeEventListener('scroll', handleScroll);
  }, []);

  // 메시지 변경 시 하단 근처인 경우에만 자동 스크롤
  useEffect(() => {
    if (isNearBottom.current) {
      chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' });
    }
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
              case 'task_chunk':
                // 분석 Step의 스트리밍 청크: 텍스트 누적만 수행
                // ✅ 첫 청크 도착 = 실제 응답 시작 → currentStatus(로딩 문구) 제거
                newContent += data.content;
                isLoading = false;
                isStreaming = true;
                currentStatus = undefined;  // 로딩 문구 제거
                break;
              case 'task_progress':
                currentStatus = data.content;
                isLoading = true;
                break;
              case 'task_step':
                if (data.applyable === 'true') {
                  // 개선 Step: 코드 카드 데이터 설정 (content는 덕려쓰지 않음)
                  isLoading = true;
                  isStreaming = false;
                  currentStatus = undefined;
                } else {
                  // 분석 Step: 스트리밍으로 이미 콘텐츠가 쌓였으면 다시 append 안 함
                  if (!existing.isStreaming) {
                    // 스트리밍이 없었던 케이스 (fallback): 전체 텍스트 사용
                    newContent = data.content || existing.content;
                  }
                  // 스트리밍이 쬄 케이스: content 기존 유지 (chunk로 이미 쌓임)
                  isLoading = true;
                  isStreaming = false;
                  currentStatus = undefined;
                }
                break;
              case 'task_success':
                isLoading = false;
                isStreaming = false;
                currentStatus = undefined;
                break;
              case 'error':
              case 'task_cancelled':
                currentStatus = undefined;
                isLoading = false;
                isStreaming = false;
                isError = data.subType === 'error';
                newContent += (newContent ? '\n\n' : '') + data.content;
                break;
              case 'chat_chunk':
                // /chat, /explain 전용 스트리밍
                newContent += data.content;
                isLoading = false;
                isStreaming = true;
                break;
              case 'chat':
              case 'explain':
                newContent = data.isStreaming ? existing.content : data.content;
                isLoading = false;
                isStreaming = false;
                break;
            }

            updated[index] = {
              ...existing,
              content:      newContent,
              currentStatus: currentStatus,
              isLoading:    isLoading,
              isError:      isError,
              isStreaming:  isStreaming,
              subType:      data.subType,
              // ✅ 코드 카드 메타데이터: applyable=true인 Step 완료 시에만 갱신
              ...(data.subType === 'task_step' && data.applyable === 'true' ? {
                applyable:    true,
                isSuccess:    data.isSuccess !== 'false',
                applyScope:   data.applyScope || existing.applyScope,
                originalCode: data.originalCode || existing.originalCode,
                modifiedCode: data.modifiedCode || existing.modifiedCode,
                extractedCode: data.extractedCode || existing.extractedCode,
              } : {
                // 분석 Step 완료 시 코드 카드 상태를 건드리지 않음
                applyable:    existing.applyable,
                isSuccess:    existing.isSuccess,
                applyScope:   existing.applyScope,
                originalCode: existing.originalCode,
                modifiedCode: existing.modifiedCode,
                extractedCode: existing.extractedCode,
              }),
            };
            return updated;
          }

          // 2. 신규 메시지 생성 (Create)
          if (data.subType === 'task_code') {
            // 코드 말풍선: Diff 카드에 필요한 메타데이터를 즉시 설정
            const codeMsg: Message = {
              id: messageId,
              role: 'ai',
              subType: 'task_code',
              content: '',
              isLoading: false,
              applyable: data.applyable === 'true',
              isSuccess: data.isSuccess !== 'false',
              applyScope: data.applyScope,
              originalCode: data.originalCode,
              modifiedCode: data.modifiedCode,
              extractedCode: data.extractedCode,
            };
            return [...prev, codeMsg];
          }

          // 구조: _start 또는 task_code 신호에만 신규 메시지 생성 허용
          // 그 외 모든 신호(task_success, task_step, explain, error 등)는
          // matching messageId가 없어도 새 말풍선 생성 금지 → 빈 말풍선 방지
          const isStartSubType = data.subType.endsWith('_start');
          if (!isStartSubType) {
            // matching 메시지가 없는데 완료/에러 신호가 오면 무시
            return prev;
          }

          const newMsg: Message = {
            id: messageId,
            role: 'ai',
            content: '',  // 로딩 문구는 content에 넣지 않음
            subType: data.subType,
            isLoading: true,
            currentStatus: data.content  // 로딩 문구는 UI 상태로만 관리
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
            onCompositionStart={() => { isComposing.current = true; }}
            onCompositionEnd={e => {
              isComposing.current = false;
              // composition 종료 시점에 최종 조합 완료 값을 state에 반영
              setInputText((e.target as HTMLTextAreaElement).value);
            }}
            onKeyDown={e => {
              // composition 진행 중 Enter는 무시 (한글 확정 전 전송 방지)
              if (e.key === 'Enter' && !e.shiftKey && !isComposing.current) {
                e.preventDefault();
                handleSend();
              }
            }}
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
