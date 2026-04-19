import { useState, useEffect, useRef, useMemo } from 'react';
import { Settings, Edit, Square, Terminal, Send } from 'lucide-react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import mermaid from 'mermaid';
import 'highlight.js/styles/github-dark.css';
import './index.css';

// Mermaid 글로벌 초기 설정
mermaid.initialize({
  startOnLoad: false,
  theme: 'dark'
});

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
  sourceFile?: string;
  isLoading?: boolean;
  isError?: boolean;
  isStreaming?: boolean;
  currentStatus?: string;
  stepNotiStatus?: 'started' | 'completed' | 'failed';
}

// ─────────────────────────────────────────────
//  컴포넌트: StepNotiItem (스텝 진행 알림 카드)
// ─────────────────────────────────────────────
const StepNotiItem = ({ msg }: { msg: Message }) => {
  const isStarted   = msg.stepNotiStatus === 'started';
  const isCompleted = msg.stepNotiStatus === 'completed';
  const isFailed    = msg.stepNotiStatus === 'failed';

  return (
    <div className={`step-noti ${msg.stepNotiStatus ?? ''}`}>
      <span className="step-noti-icon">
        {isCompleted && '✓'}
        {isFailed    && '✗'}
        {isStarted   && <span className="step-noti-spinner" />}
      </span>
      <span className="step-noti-label">{msg.content}</span>
      <span className="step-noti-badge">
        {isCompleted ? '완료' : isFailed ? '실패' : '진행 중'}
      </span>
    </div>
  );
};

// ─────────────────────────────────────────────
//  유틸: 마크다운에서 코드 블록만 추출
// ─────────────────────────────────────────────
function extractCodeBlocks(markdown: string): string {
  const blocks = [...markdown.matchAll(/```[\w]*\n([\s\S]*?)```/g)];
  return blocks.map(m => m[1].trim()).join('\n\n');
}

// ─────────────────────────────────────────────
//  컴포넌트: MermaidChart (플로우차트 렌더링)
// ─────────────────────────────────────────────
const MermaidChart = ({ chart }: { chart: string }) => {
  const [svg, setSvg] = useState<string>('');
  // 고유 id 보장 (동시 렌더링 충돌 회피)
  const [id] = useState(() => `mermaid-${Date.now()}-${Math.floor(Math.random() * 10000)}`);

  useEffect(() => {
    let mounted = true;
    if (chart) {
      mermaid.render(id, chart)
        .then((result) => {
          if (mounted) setSvg(result.svg);
        })
        .catch((err) => {
          console.error("Mermaid parsing error:", err);
          if (mounted) setSvg(`<pre class="error-text" style="font-size:11px">Mermaid Error: ${err?.message || 'Syntax Error'}</pre>`);
        });
    }
    return () => { mounted = false; };
  }, [chart, id]);

  if (!svg) {
    return (
      <div className="mermaid-loading inline-loading-area" style={{ margin: '16px 0', padding: '12px' }}>
        <div className="typing-dots"><span></span><span></span><span></span></div>
        <span className="status-text">차트 렌더링 중...</span>
      </div>
    );
  }
  return <div className="mermaid-chart flex justify-center" dangerouslySetInnerHTML={{ __html: svg }} />;
};

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

  // 0. Step 알림 카드
  if (msg.subType === 'step_noti') {
    return <StepNotiItem msg={msg} />;
  }

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

  // ── 테스트 결과 여부 판별 ────────────────────────────────────────
  const isTestResult = msg.subType === 'test' || msg.subType === 'test_start' || msg.subType === 'task_chunk' && !!msg.sourceFile;

  const handleCopyCode = () => {
    const codeOnly = extractCodeBlocks(msg.content);
    navigator.clipboard.writeText(codeOnly || msg.content).catch(() => {});
  };

  const handleCreateFile = () => {
    if (window.sendToIde) {
      const codeOnly = extractCodeBlocks(msg.content);
      window.sendToIde(JSON.stringify({
        command: '/createTestFile',
        code: codeOnly || msg.content,
        sourceFile: msg.sourceFile || '',
        id: msg.id
      }));
    }
  };

  // ── 텍스트 말풍선 (텍스트 + Copy + Save) ──────────────────────────
  return (
    <div className={`msg-ai ${isError ? 'error' : isTestResult ? 'test-result' : 'analysis'}`}>

      <div className={`msg-ai-content ${isError ? 'error-text' : ''}`}>
        {msg.content && (
          <div className="markdown-body" style={{ maxWidth: '100%', overflowX: 'hidden' }}>
            <Markdown 
              remarkPlugins={[remarkGfm]} 
              rehypePlugins={[[rehypeHighlight, { ignoreMissing: true }]]}
              components={{
                code(props: any) {
                  const { children, className, node, ...rest } = props;
                  const match = /language-(\w+)/.exec(className || '');
                  if (match && match[1] === 'mermaid') {
                    return <MermaidChart chart={String(children).replace(/\n$/, '')} />;
                  }
                  return <code className={className} {...rest}>{children}</code>;
                }
              }}
            >
              {msg.content}
            </Markdown>
          </div>
        )}

        {msg.isLoading && (
          <div className="inline-loading-area" style={{ marginTop: msg.content ? '12px' : '0' }}>
            <div className="typing-dots"><span></span><span></span><span></span></div>
            {msg.currentStatus && <span className="status-text">{msg.currentStatus}</span>}
          </div>
        )}

        {isError && <div className="error-badge" style={{ marginTop: '12px' }}>ERROR</div>}
      </div>

      {/* 테스트 결과 전용 버튼 */}
      {!isError && !msg.isLoading && msg.content && isTestResult && (
        <div className="msg-text-actions test-actions">
          <button className="btn-text-action btn-copy-code" onClick={handleCopyCode} title="테스트 코드만 클립보드에 복사">
            📋 Copy Code
          </button>
          <button className="btn-text-action btn-create-file" onClick={handleCreateFile} title="테스트 파일을 프로젝트에 생성">
            📁 Create File
          </button>
          <button className="btn-text-action" onClick={handleSave} title="전체 결과를 Markdown 파일로 저장">
            💾 Save .md
          </button>
        </div>
      )}

      {/* 일반 결과 버튼 (Copy / Save, 초기 인사말 제외) */}
      {!isError && !msg.isLoading && msg.content && !isTestResult && msg.id !== '1' && (
        <div className="msg-text-actions">
          <button className="btn-text-action" onClick={handleCopy} title="클립보드에 복사">
            Copy
          </button>
          <button className="btn-text-action" onClick={handleSave} title="Markdown 파일로 저장">
            Save .md
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
    id: '1', role: 'ai', content: '무엇을 도와드릴까요?'
  }]);
  const [inputText, setInputText] = useState('');
  const [selectedModel, setSelectedModel] = useState<string>('Loading...');
  const chatListRef = useRef<HTMLDivElement>(null);
  const isNearBottom = useRef(true); // 사용자가 하단 근처에 있는지 추적
  const isComposing = useRef(false); // 한글 IME composition 상태 추적 (JCEF 자모 분리 방지)

  // 슬래시 커맨드 팝업을 위한 상태
  const [showCommandPopup, setShowCommandPopup] = useState(false);
  const [fetchedModels, setFetchedModels] = useState<string[]>([]);
  const [isFetchingModels, setIsFetchingModels] = useState(false);
  const [modelsError, setModelsError] = useState('');
  const [popupSelectedIndex, setPopupSelectedIndex] = useState(0);
  const commandPopupRef = useRef<HTMLDivElement>(null);

  // @ 파일 팝업을 위한 상태
  const [showFilePopup, setShowFilePopup] = useState(false);
  const [openTabs, setOpenTabs] = useState<Array<{name: string, path: string}>>([]);
  const [selectedFiles, setSelectedFiles] = useState<Array<{name: string, path: string}>>([]);
  const [isLoadingTabs, setIsLoadingTabs] = useState(false);
  const filePopupRef = useRef<HTMLDivElement>(null);

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

  // 외부 클릭 시 팝업 닫기
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (commandPopupRef.current && !commandPopupRef.current.contains(event.target as Node)) {
        setShowCommandPopup(false);
      }
    };
    if (showCommandPopup) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showCommandPopup]);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (filePopupRef.current && !filePopupRef.current.contains(event.target as Node)) {
        setShowFilePopup(false);
      }
    };
    if (showFilePopup) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showFilePopup]);

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

      if (data.subType === 'fetched_models') {
        const modelsArray = data.content ? data.content.split(',') : [];
        setFetchedModels(modelsArray.filter((m: string) => m.trim() !== ''));
        setIsFetchingModels(false);
        setModelsError('');
        return;
      }

      if (data.subType === 'fetched_models_error') {
        setIsFetchingModels(false);
        setModelsError(data.content || "모델 조회 실패");
        return;
      }

      if (data.subType === 'openTabs') {
        try {
          setOpenTabs(JSON.parse(data.content));
        } catch {
          setOpenTabs([]);
        }
        setIsLoadingTabs(false);
        return;
      }

      const messageId = data.messageId || data.id; // messageId 또는 id 필드 확인

      if (data.subType === 'step_noti') {
        if (messageId) {
          setMessages(prev => {
            const index = prev.findIndex(m => m.id === messageId);
            if (index !== -1) {
              const updated = [...prev];
              updated[index] = { ...prev[index], stepNotiStatus: data.status };
              return updated;
            }
            return [...prev, {
              id: messageId,
              role: 'ai' as const,
              subType: 'step_noti',
              content: data.content,
              stepNotiStatus: data.status as 'started' | 'completed' | 'failed',
              isLoading: false,
            }];
          });
        }
        return;
      }

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
                // 이미 스트리밍 내용이 있으면 인라인 로딩 숨김 (step_noti가 진행 상태 담당)
                isLoading = existing.content.trim() === '';
                isStreaming = false;
                break;
              case 'task_step':
                if (data.applyable === 'true') {
                  // 개선 Step: 코드 카드 데이터 설정 (content는 건드리지 않음)
                  isLoading = false;
                  isStreaming = false;
                  currentStatus = undefined;
                } else {
                  // 분석 Step: 스트리밍 여부로 경로 구분
                  if (existing.isStreaming) {
                    // 스트리밍 완료 후 온 task_step
                    // → 이미 헤더+전체 내용이 청크로 쌓여 있으므로 기존 content 유지
                    newContent = existing.content;
                  } else if (data.content) {
                    // 스트리밍 없이 온 task_step (fallback)
                    newContent = data.content;
                  }
                  // isStreaming → false: 로딩 스피너 종료, Copy/Save 버튼 활성화
                  isLoading = false;
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
                // chunk로 이미 content가 쌓인 경우(isStreaming=true) 덮어쓰지 않음
                // data.content가 비어있으면 기존 content 유지 (완료 신호로 빈 값이 올 때 방어)
                if (!existing.isStreaming) {
                  newContent = data.content || existing.content;
                }
                isLoading = false;
                isStreaming = false;
                break;
              case 'test':
                // 스트리밍으로 이미 청크가 쌓였으면 기존 content 유지
                // (onSuccess의 done=true 응답은 content가 비어있을 수 있음)
                newContent = existing.isStreaming ? existing.content : (data.content || existing.content);
                isLoading = false;
                isStreaming = false;
                break;
              case 'test_file_created':
                // 파일 생성 완료 알림 — 기존 메시지 유지하고 상태만 갱신
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
              sourceFile:   data.sourceFile || existing.sourceFile,
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
            currentStatus: data.content,  // 로딩 문구는 UI 상태로만 관리
            sourceFile: data.sourceFile   // test_start 시 소스파일명 저장
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
    const filesToSend = [...selectedFiles];
    const fileLabels = filesToSend.map(f => `📎 ${f.name}`).join('  ');
    const displayText = fileLabels ? `${text}\n${fileLabels}` : text;
    setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', content: displayText }]);
    setInputText('');
    setShowCommandPopup(false);
    setShowFilePopup(false);
    setSelectedFiles([]);
    let command = '/chat';
    let payload = text;
    if (text === '/explain' || text.startsWith('/explain ')) {
      command = '/explain';
      payload = text.startsWith('/explain ') ? text.slice(9).trim() : '';
    } else if (text === '/review' || text.startsWith('/review ')) {
      command = '/task';
      payload = '/review 선택된 코드를 검토하고 개선 사항을 제안해주세요.';
    } else if (text === '/improve' || text.startsWith('/improve ')) {
      command = '/task';
      payload = '코드를 개선해주세요.';
    } else if (text === '/analyze' || text.startsWith('/analyze ')) {
      command = '/task';
      payload = '영향도를 분석해주세요.';
    } else if (text === '/query' || text.startsWith('/query ')) {
      command = '/task';
      payload = '쿼리를 검증해주세요.';
    } else if (text === '/test' || text.startsWith('/test ')) {
      command = '/task';
      payload = '테스트 코드를 생성해주세요.';
    }
    window.sendToIde(JSON.stringify({
      command,
      text: payload,
      ...(filesToSend.length > 0 ? { files: JSON.stringify(filesToSend) } : {})
    }));
  };

  // 팝업 아이템 로직 (모델 및 명령어 조합)
  const popupItems = useMemo(() => {
    const items: Array<{type: string, cmd: string, desc?: string, index: number}> = [];
    let idx = 0;
    fetchedModels.forEach(m => items.push({ type: 'model', cmd: m, index: idx++ }));
    const cmds = [
      { cmd: '/explain', desc: '코드를 설명해줘' },
      { cmd: '/review', desc: '코드를 리뷰해줘' },
      { cmd: '/improve', desc: '코드를 개선해줘' },
      { cmd: '/test', desc: '테스트 코드를 생성해줘' },
      { cmd: '/analyze', desc: '영향도를 분석해줘' },
      { cmd: '/query', desc: '쿼리를 검증해줘' }
    ];
    cmds.forEach(c => items.push({ type: 'cmd', cmd: c.cmd, desc: c.desc, index: idx++ }));
    return items;
  }, [fetchedModels]);

  const applyPopupSelection = (item: {type: string, cmd: string}) => {
    if (item.type === 'model') {
      window.sendToIde?.(JSON.stringify({ command: '/changeModel', model: item.cmd }));
      setShowCommandPopup(false);
      return;
    }

    const newText = item.cmd + ' ';
    setInputText(newText);
    setShowCommandPopup(false);
    
    setTimeout(() => {
      const el = document.querySelector('.textarea-wrapper textarea') as HTMLTextAreaElement;
      if (el) {
        el.focus();
        el.setSelectionRange(newText.length, newText.length);
      }
    }, 10);
  };

  const handleFileButtonClick = () => {
    const opening = !showFilePopup;
    setShowFilePopup(opening);
    if (opening) {
      setShowCommandPopup(false);
      setIsLoadingTabs(true);
      window.sendToIde?.(JSON.stringify({ command: '/openTabs' }));
    }
  };

  const toggleFileSelection = (file: {name: string, path: string}) => {
    setSelectedFiles(prev => {
      const isSelected = prev.some(f => f.path === file.path);
      if (isSelected) return prev.filter(f => f.path !== file.path);
      if (prev.length >= 3) return prev;
      return [...prev, file];
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (showCommandPopup) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setPopupSelectedIndex(prev => (prev + 1) % popupItems.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setPopupSelectedIndex(prev => (prev - 1 + popupItems.length) % popupItems.length);
        return;
      }
      if (e.key === 'Enter' && !isComposing.current) {
        e.preventDefault();
        const selected = popupItems[popupSelectedIndex];
        if (selected) {
          applyPopupSelection(selected);
        }
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setShowCommandPopup(false);
        return;
      }
    }
    // 기본 엔터 처리
    if (e.key === 'Enter' && !e.shiftKey && !isComposing.current) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div id="root">
      <header className="header flex justify-between items-center">
        <span className="title">New Chat</span>
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
          <button className="toolbar-btn" onClick={handleFileButtonClick}>@ 파일</button>
          <button 
            className="toolbar-btn" 
            onClick={() => {
              setShowCommandPopup(!showCommandPopup);
              if (!showCommandPopup && fetchedModels.length === 0) {
                setIsFetchingModels(true);
                window.sendToIde?.(JSON.stringify({ command: '/fetchModels' }));
              }
            }}
          >
            / 명령어
          </button>
        </div>
        {selectedFiles.length > 0 && (
          <div className="selected-files-chips">
            {selectedFiles.map(f => (
              <span key={f.path} className="file-chip">
                📎 {f.name}
                <button className="chip-remove" onClick={() => toggleFileSelection(f)}>×</button>
              </span>
            ))}
          </div>
        )}
        <div className="textarea-wrapper">
          {showFilePopup && (
            <div className="command-popup" ref={filePopupRef}>
              <div className="popup-section">
                <div className="popup-section-title">
                  열린 파일{selectedFiles.length > 0 ? ` (${selectedFiles.length}/3 선택됨)` : ''}
                </div>
                {isLoadingTabs ? (
                  <div className="popup-loading">로딩 중...</div>
                ) : openTabs.length === 0 ? (
                  <div className="popup-loading">열린 파일이 없습니다.</div>
                ) : (
                  openTabs.map(tab => {
                    const isSelected = selectedFiles.some(f => f.path === tab.path);
                    const isDisabled = !isSelected && selectedFiles.length >= 3;
                    return (
                      <button
                        key={tab.path}
                        className={`popup-item ${isSelected ? 'selected' : ''} ${isDisabled ? 'disabled' : ''}`}
                        onClick={() => { if (!isDisabled) toggleFileSelection(tab); }}
                      >
                        <span className="popup-item-command">📄 {tab.name}</span>
                        <span className="popup-item-desc">
                          {isSelected ? '✓ 선택됨' : isDisabled ? '최대 3개' : ''}
                        </span>
                      </button>
                    );
                  })
                )}
              </div>
            </div>
          )}
          {showCommandPopup && (
            <div className="command-popup" ref={commandPopupRef}>
              <div className="popup-section">
                <div className="popup-section-title">모델 변경</div>
                {isFetchingModels ? (
                  <div className="popup-loading">로딩 중...</div>
                ) : modelsError ? (
                  <div className="popup-loading error-text">{modelsError}</div>
                ) : (
                  popupItems.filter(item => item.type === 'model').map(item => (
                    <button 
                      key={item.cmd} 
                      className={`popup-item ${popupSelectedIndex === item.index ? 'selected' : ''}`}
                      onClick={() => applyPopupSelection(item)}
                      onMouseEnter={() => setPopupSelectedIndex(item.index)}
                    >
                      <span className="popup-item-command">{item.cmd}</span>
                    </button>
                  ))
                )}
              </div>
              <div className="popup-section">
                <div className="popup-section-title">명령어</div>
                {popupItems.filter(item => item.type === 'cmd').map(item => (
                  <button 
                    key={item.cmd} 
                    className={`popup-item ${popupSelectedIndex === item.index ? 'selected' : ''}`}
                    onClick={() => applyPopupSelection(item)}
                    onMouseEnter={() => setPopupSelectedIndex(item.index)}
                  >
                    <span className="popup-item-command">{item.cmd}</span>
                    <span className="popup-item-desc">{item.desc}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
          <textarea
            placeholder="무엇을 도와드릴까요?"
            value={inputText}
            onChange={e => {
              const val = e.target.value;
              setInputText(val);
              if (val === '/' || val.endsWith(' /') || val.endsWith('\n/')) {
                setShowCommandPopup(true);
                setPopupSelectedIndex(0);
                if (fetchedModels.length === 0) {
                  setIsFetchingModels(true);
                  setModelsError('');
                  window.sendToIde?.(JSON.stringify({ command: '/fetchModels' }));
                }
              } else if (!val.includes('/')) {
                setShowCommandPopup(false);
              }
            }}
            onCompositionStart={() => { isComposing.current = true; }}
            onCompositionEnd={e => {
              isComposing.current = false;
              // composition 종료 시점에 최종 조합 완료 값을 state에 반영
              setInputText((e.target as HTMLTextAreaElement).value);
            }}
            onKeyDown={handleKeyDown}
          />
          {messages.some(m => m.isLoading || m.isStreaming) ? (
            <button className="btn-circle stop" onClick={() => {
              setMessages(prev => prev.map(m => {
                if (m.isLoading || m.isStreaming)
                  return { ...m, isLoading: false, isStreaming: false, currentStatus: undefined };
                if (m.subType === 'step_noti' && m.stepNotiStatus === 'started')
                  return { ...m, stepNotiStatus: 'failed' as const };
                return m;
              }));
              window.sendToIde?.(JSON.stringify({ command: '/cancel' }));
            }}>
              <Square size={14} fill="currentColor" />
            </button>
          ) : (
            <button className="btn-circle send" onClick={handleSend}>
              <Send size={14} />
            </button>
          )}
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
