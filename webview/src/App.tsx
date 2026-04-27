import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Settings, Plus, MessageSquare, Square, Terminal, Send, ArrowDown } from 'lucide-react';
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
  filePath?: string;
  hasSelection?: boolean;
  selectedText?: string;
  modifiedFullCode?: string;
  isLoading?: boolean;
  isError?: boolean;
  isStreaming?: boolean;
  currentStatus?: string;
  stepNotiStatus?: 'started' | 'completed' | 'failed';
}

// ─────────────────────────────────────────────
//  컴포넌트: StepNotiItem (스텝 진행 알림 카드)
// ─────────────────────────────────────────────
const StepNotiItem = React.memo(({ msg }: { msg: Message }) => {
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
});

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
const MermaidChart = React.memo(({ chart }: { chart: string }) => {
  const [svg, setSvg] = useState<string>('');
  const [id] = useState(() => `mermaid-${Date.now()}-${Math.floor(Math.random() * 10000)}`);
  const containerRef = useRef<HTMLDivElement>(null);
  const hasRendered = useRef(false);

  useEffect(() => {
    if (!chart || hasRendered.current) return;
    const container = containerRef.current;
    if (!container) return;
    let mounted = true;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !hasRendered.current) {
          hasRendered.current = true;
          observer.disconnect();
          mermaid.render(id, chart)
            .then((result) => { if (mounted) setSvg(result.svg); })
            .catch((err) => {
              console.error("Mermaid parsing error:", err);
              if (mounted) setSvg(`<pre class="error-text" style="font-size:11px">Mermaid Error: ${err?.message || 'Syntax Error'}</pre>`);
            });
        }
      },
      { threshold: 0.1 }
    );
    observer.observe(container);
    return () => { mounted = false; observer.disconnect(); };
  }, [chart, id]);

  return (
    <div ref={containerRef}>
      {!svg ? (
        <div className="mermaid-loading inline-loading-area" style={{ margin: '16px 0', padding: '12px' }}>
          <div className="typing-dots"><span></span><span></span><span></span></div>
          <span className="status-text">차트 렌더링 중...</span>
        </div>
      ) : (
        <div className="mermaid-chart flex justify-center" dangerouslySetInnerHTML={{ __html: svg }} />
      )}
    </div>
  );
});

// ─────────────────────────────────────────────
//  컴포넌트: CodeBlock (에디터 스타일 코드블록)
// ─────────────────────────────────────────────
const CodeBlock = ({ children }: { children: React.ReactNode }) => {
  const [copied, setCopied] = useState(false);

  const codeEl = Array.isArray(children) ? children[0] : children;
  const lang = /language-(\w+)/.exec((codeEl as any)?.props?.className || '')?.[1] ?? '';
  const codeText = String((codeEl as any)?.props?.children ?? '').replace(/\n$/, '');

  const handleCopy = () => {
    navigator.clipboard.writeText(codeText).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div className="code-block">
      <div className="code-block-header">
        {lang && <span className="code-block-lang">{lang}</span>}
        <button className="code-block-copy" onClick={handleCopy}>
          {copied ? '✓ 복사됨' : 'Copy'}
        </button>
      </div>
      <pre className="code-block-pre">{children}</pre>
    </div>
  );
};

// ─────────────────────────────────────────────
//  컴포넌트: MessageItem (역할별 분기)
// ─────────────────────────────────────────────
const MessageItem = React.memo(({ msg }: { msg: Message }) => {
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

  const handleCopy = () => {
    navigator.clipboard.writeText(msg.content).catch(() => {});
  };

  const handleSave = () => {
    if (window.sendToIde) {
      window.sendToIde(JSON.stringify({ command: '/saveMarkdown', content: msg.content }));
    }
  };

  const handleDiff = () => {
    if (!window.sendToIde || !msg.filePath) return;
    // [이전 코드 백업 - 선택 케이스는 SimpleDiff 2-way 방식 사용]
    // if (msg.hasSelection) {
    //   window.sendToIde(JSON.stringify({ command: '/viewDiffSimple', text: msg.content, originalCode: msg.selectedText ?? '' }));
    // } else {
    //   window.sendToIde(JSON.stringify({ command: '/viewDiff', text: msg.content, filePath: msg.filePath }));
    // }
    // 선택 케이스도 3-way Diff로 처리: ImproveAction에서 미리 계산한 modifiedFullCode 사용
    window.sendToIde(JSON.stringify({
      command: '/viewDiff',
      text: msg.modifiedFullCode || msg.content,
      filePath: msg.filePath
    }));
  };

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
                pre: (props: any) => <CodeBlock>{props.children}</CodeBlock>,
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

      {/* 일반 결과 버튼 (Copy / Save / Diff 보기, 초기 인사말 제외) */}
      {!isError && !msg.isLoading && msg.content && !isTestResult && msg.id !== '1' && (
        <div className="msg-text-actions">
          <button className="btn-text-action" onClick={handleCopy} title="클립보드에 복사">
            Copy
          </button>
          <button className="btn-text-action" onClick={handleSave} title="Markdown 파일로 저장">
            Save .md
          </button>
          {msg.filePath && (
            <button className="btn-text-action" onClick={handleDiff} title="원본 파일과 개선 결과 비교">
              Diff 보기
            </button>
          )}
        </div>
      )}
    </div>
  );
});

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
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const isNearBottom = useRef(true); // 사용자가 하단 근처에 있는지 추적 (자동 스크롤용)
  const [showScrollButton, setShowScrollButton] = useState(false); // 스크롤 하단 이동 버튼 노출 여부
  const isComposing = useRef(false); // 한글 IME composition 상태 추적 (JCEF 자모 분리 방지)
  const atTriggerPos = useRef<number>(-1); // @ 타이핑으로 팝업 열린 경우 @ 위치

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
  const [filePopupSelectedIndex, setFilePopupSelectedIndex] = useState(0);
  const filePopupRef = useRef<HTMLDivElement>(null);

  // 채팅 기록 관련 상태
  const [chatId, setChatId] = useState<string>(() => `chat_${Date.now()}`);
  const [chatTitle, setChatTitle] = useState<string>('New Chat');
  const [showChatListPopup, setShowChatListPopup] = useState(false);
  const [chatList, setChatList] = useState<Array<{id: string, title: string, date: string}>>([]);
  const [isChatListLoading, setIsChatListLoading] = useState(false);
  const chatListPopupRef = useRef<HTMLDivElement>(null);
  const isLoadingChat = useRef(false);

  // 스크롤 위치 감지: 하단 50px 이내이면 자동 스크롤 활성화 및 버튼 표시 여부 업데이트
  useEffect(() => {
    const el = chatListRef.current;
    if (!el) return;
    
    const handleScroll = () => {
      // 자동 스크롤을 위한 근접 여부 (50px 오차 허용)
      isNearBottom.current = el.scrollTop + el.clientHeight >= el.scrollHeight - 50;

      // 버튼 노출 조건: 
      // 1. 스크롤 내용이 화면보다 크고 (scrollHeight > clientHeight)
      // 2. 현재 스크롤이 최하단이 아닐 때 (오차 10px 허용)
      const hasScroll = el.scrollHeight > el.clientHeight;
      const notAtBottom = el.scrollTop + el.clientHeight < el.scrollHeight - 10;
      
      setShowScrollButton(hasScroll && notAtBottom);
    };

    el.addEventListener('scroll', handleScroll, { passive: true });
    
    // 내부 컨텐츠(메시지) 크기 변경 감지
    const resizeObserver = new ResizeObserver(() => {
      handleScroll();
    });
    // Array.from(el.children) 처럼 특정요소를 관찰할지, el 자체를 할지.
    // el 내부 콘텐츠 크기이므로 el 자체 스크롤 높이 변경 확인
    resizeObserver.observe(el);

    // 컴포넌트 마운트 초기화
    setTimeout(handleScroll, 50);

    return () => {
      el.removeEventListener('scroll', handleScroll);
      resizeObserver.disconnect();
    };
  }, []);

  const scrollToBottom = () => {
    chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' });
  };

  // 플러그인 시작 시 마지막 채팅 자동 복원
  // sendToIde는 JCEF 브리지가 비동기로 주입하므로 준비될 때까지 폴링
  useEffect(() => {
    let attempts = 0;
    const tryLoad = () => {
      if (window.sendToIde) {
        console.log('[loadLastChat] sendToIde 준비 완료, 커맨드 전송 (시도=' + attempts + ')');
        window.sendToIde(JSON.stringify({ command: '/loadLastChat' }));
      } else {
        attempts++;
        if (attempts < 30) {
          setTimeout(tryLoad, 200);
        } else {
          console.log('[loadLastChat] sendToIde 30회 시도 후 포기');
        }
      }
    };
    setTimeout(tryLoad, 200);
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

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (chatListPopupRef.current && !chatListPopupRef.current.contains(event.target as Node)) {
        setShowChatListPopup(false);
      }
    };
    if (showChatListPopup) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showChatListPopup]);

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

      if (data.subType === 'chat_list') {
        try {
          setChatList(JSON.parse(data.content));
        } catch {
          setChatList([]);
        }
        setIsChatListLoading(false);
        return;
      }

      if (data.subType === 'chat_loaded') {
        console.log('[chat_loaded] 수신 content 길이=' + data.content?.length);
        try {
          const chatData = JSON.parse(data.content);
          const loadedMessages: Message[] = Array.isArray(chatData.messages)
            ? chatData.messages
            : JSON.parse(chatData.messages);
          console.log('[chat_loaded] 파싱 성공 id=' + chatData.id + ' messages=' + loadedMessages.length + '개');
          isLoadingChat.current = true;
          setChatId(chatData.id);
          setChatTitle(chatData.title || 'New Chat');
          setMessages(loadedMessages);
          setTimeout(() => { isLoadingChat.current = false; }, 100);
        } catch (err) {
          console.log('[chat_loaded] 파싱 실패:', err);
        }
        setShowChatListPopup(false);
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
            let modifiedFullCode = existing.modifiedFullCode;

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
                // 스트리밍 완료 후 온 경우 기존 content 유지, 아니면 data.content 사용
                if (existing.isStreaming) {
                  newContent = existing.content;
                } else if (data.content) {
                  newContent = data.content;
                }
                if (data.modifiedFullCode) modifiedFullCode = data.modifiedFullCode;
                isLoading = false;
                isStreaming = false;
                currentStatus = undefined;
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
              case 'testExecutionResult':
                // 테스트 실행 최종 결과(Markdown) — 로딩 버블을 리포트로 교체
                newContent = data.content;
                isLoading = false;
                isStreaming = false;
                currentStatus = undefined;
                break;
            }

            updated[index] = {
              ...existing,
              content:         newContent,
              currentStatus:   currentStatus,
              isLoading:       isLoading,
              isError:         isError,
              isStreaming:     isStreaming,
              subType:         data.subType,
              sourceFile:      data.sourceFile || existing.sourceFile,
              isSuccess:       data.isSuccess !== 'false' ? true : existing.isSuccess,
              modifiedFullCode: modifiedFullCode,
            };
            return updated;
          }

          // 2. 신규 메시지 생성 (Create)
          if (data.subType === 'task_code') {
            const codeMsg: Message = {
              id: messageId,
              role: 'ai',
              subType: 'task_code',
              content: data.extractedCode || data.modifiedCode || '',
              isLoading: false,
              isSuccess: data.isSuccess !== 'false',
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
            sourceFile: data.sourceFile,  // test_start 시 소스파일명 저장
            filePath: data.filePath,      // Improve Step2 시작 시 원본 파일 경로 저장 (Diff 버튼용)
            hasSelection: data.hasSelection === 'true',
            selectedText: data.selectedText
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

  // 메시지 변경 시 자동 저장 (초기 인사 메시지 1개만 있는 경우 제외)
  useEffect(() => {
    if (isLoadingChat.current) return;
    if (messages.length <= 1) return;
    const firstUser = messages.find(m => m.role === 'user');
    const firstAi   = messages.find(m => m.role === 'ai' && m.id !== '1' && m.content.trim());
    const title = firstUser?.content.slice(0, 20)
      ?? firstAi?.content.slice(0, 20)
      ?? '새 채팅';
    setChatTitle(title);
    window.sendToIde?.(JSON.stringify({
      command: '/saveChat',
      id: chatId,
      title,
      messages: JSON.stringify(messages)
    }));
  }, [messages, chatId]);

  const handleNewChat = () => {
    const userMessages = messages.filter(m => m.role === 'user');
    if (userMessages.length > 0) {
      const title = userMessages[0].content.slice(0, 20);
      window.sendToIde?.(JSON.stringify({
        command: '/saveChat',
        id: chatId,
        title,
        messages: JSON.stringify(messages)
      }));
    }
    const newId = `chat_${Date.now()}`;
    isLoadingChat.current = true;
    setChatId(newId);
    setChatTitle('New Chat');
    setMessages([{ id: '1', role: 'ai', content: '무엇을 도와드릴까요?' }]);
    setTimeout(() => { isLoadingChat.current = false; }, 100);
  };

  const handleOpenChatList = () => {
    if (showChatListPopup) {
      setShowChatListPopup(false);
      return;
    }
    setShowChatListPopup(true);
    setIsChatListLoading(true);
    window.sendToIde?.(JSON.stringify({ command: '/listChats' }));
  };

  const handleSend = () => {
    const text = inputText.trim();
    if (!text || !window.sendToIde) return;
    const filesToSend = [...selectedFiles];
    setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', content: text }]);
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
    } else if (text === '/doc' || text.startsWith('/doc ')) {
      command = '/doc';
      payload = '';
    } else if (text === '/ragdoc' || text.startsWith('/ragdoc ')) {
      command = '/ragdoc';
      payload = '';
    } else if (/개선|리팩토링|리팩|refactor|improve|최적화|optimize/i.test(text)) {
      command = '/task';
    } else if (/리뷰|review|검토|점검/i.test(text)) {
      command = '/task';
    } else if (/설명|explain|어떻게 동작|이게 뭐야/i.test(text)) {
      command = '/task';
    } else if (/영향 분석|영향도|impact|analyze|분석/i.test(text)) {
      command = '/task';
    } else if (/쿼리|query|sql|검증/i.test(text)) {
      command = '/task';
    } else if (/테스트|test|생성/i.test(text)) {
      command = '/task';
    }
    window.sendToIde(JSON.stringify({
      command,
      text: payload,
      ...(filesToSend.length > 0 ? { files: JSON.stringify(filesToSend) } : {})
    }));
  };

  // 팝업 아이템 로직 (모델 및 명령어 조합)
  const popupItems = useMemo(() => {
    const items: Array<{type: string, cmd: string, desc?: string, label?: string, index: number}> = [];
    let idx = 0;
    fetchedModels.forEach(m => items.push({ type: 'model', cmd: m, index: idx++ }));
    const cmds = [
      { cmd: '/explain', desc: '코드를 설명해줘' },
      { cmd: '/improve', desc: '코드를 개선해줘' },
      { cmd: '/test', desc: '테스트 코드를 생성해줘' },
      { cmd: '/analyze', desc: '영향도를 분석해줘' },
      { cmd: '/query', desc: '쿼리를 검증해줘' },
      { cmd: '/doc', desc: '디렉토리 분석 문서 생성' },
      { cmd: '/ragdoc', desc: 'RAG 전용 분석 문서 생성 (FAQ 포함)' }
    ];
    cmds.forEach(c => items.push({ type: 'cmd', cmd: c.cmd, desc: c.desc, index: idx++ }));
    items.push({ type: 'settings', cmd: '/openSettings', label: '설정', index: idx++ });
    return items;
  }, [fetchedModels]);

  const applyPopupSelection = (item: {type: string, cmd: string}) => {
    if (item.type === 'model') {
      window.sendToIde?.(JSON.stringify({ command: '/changeModel', model: item.cmd }));
      setShowCommandPopup(false);
      return;
    }
    if (item.type === 'settings') {
      window.sendToIde?.(JSON.stringify({ command: '/openSettings' }));
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

  const openFilePopup = (triggerPos: number = -1) => {
    atTriggerPos.current = triggerPos;
    setFilePopupSelectedIndex(0);
    setShowFilePopup(true);
    setShowCommandPopup(false);
    setIsLoadingTabs(true);
    window.sendToIde?.(JSON.stringify({ command: '/openTabs' }));
  };

  const handleFileButtonClick = () => {
    if (showFilePopup) { setShowFilePopup(false); return; }
    openFilePopup(-1);
  };

  const toggleFileSelection = (file: {name: string, path: string}) => {
    const textarea = textareaRef.current;
    const insertText = `@${file.name} `;
    let newText: string;
    let newCursorPos: number;

    if (atTriggerPos.current >= 0) {
      // @ 타이핑으로 팝업 열린 경우: @ 자리부터 교체
      const pos = atTriggerPos.current;
      newText = inputText.slice(0, pos) + insertText + inputText.slice(pos + 1);
      newCursorPos = pos + insertText.length;
    } else {
      // 버튼으로 팝업 열린 경우: 현재 커서 위치에 삽입
      const pos = textarea?.selectionStart ?? inputText.length;
      newText = inputText.slice(0, pos) + insertText + inputText.slice(pos);
      newCursorPos = pos + insertText.length;
    }

    setInputText(newText);
    setSelectedFiles(prev => prev.some(f => f.path === file.path) ? prev : [...prev, file]);
    setShowFilePopup(false);
    atTriggerPos.current = -1;

    setTimeout(() => {
      if (textarea) {
        textarea.focus();
        textarea.setSelectionRange(newCursorPos, newCursorPos);
      }
    }, 10);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (showFilePopup && !isLoadingTabs && openTabs.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setFilePopupSelectedIndex(prev => (prev + 1) % openTabs.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setFilePopupSelectedIndex(prev => (prev - 1 + openTabs.length) % openTabs.length);
        return;
      }
      if (e.key === 'Enter' && !isComposing.current) {
        e.preventDefault();
        toggleFileSelection(openTabs[filePopupSelectedIndex]);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setShowFilePopup(false);
        return;
      }
    }
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
    <div id="root" style={{ position: 'relative' }}>
      <header className="header flex justify-between items-center">
        <span className="title">{chatTitle}</span>
        <div className="flex gap-2">
          <button className="icon-btn" onClick={handleNewChat} title="새 채팅">
            <Plus size={14} />
          </button>
          <button className="icon-btn" onClick={handleOpenChatList} title="채팅 목록">
            <MessageSquare size={14} />
          </button>
          <button className="icon-btn" onClick={() => window.sendToIde?.(JSON.stringify({ command: '/openSettings' }))}>
            <Settings size={14} />
          </button>
        </div>
      </header>

      {showChatListPopup && (
        <div
          className="command-popup"
          ref={chatListPopupRef}
          style={{ position: 'fixed', top: '44px', right: '8px', bottom: 'auto', left: 'auto', width: '280px', zIndex: 9999 }}
        >
          <div className="popup-section">
            <div className="popup-section-title">채팅 기록</div>
            {isChatListLoading ? (
              <div className="popup-loading">로딩 중...</div>
            ) : chatList.length === 0 ? (
              <div className="popup-loading">저장된 채팅이 없습니다.</div>
            ) : (
              chatList.map(chat => (
                <div key={chat.id} className="popup-item" style={{ display: 'flex', alignItems: 'center', gap: '4px', padding: '0' }}>
                  <button
                    style={{ flex: 1, background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left', padding: '6px 8px', color: 'inherit' }}
                    onClick={() => {
                      console.log('[loadChat] 항목 클릭 id=' + chat.id);
                      window.sendToIde?.(JSON.stringify({ command: '/loadChat', id: chat.id }));
                    }}
                  >
                    <span className="popup-item-command" style={{ display: 'block' }}>{chat.title || '(제목 없음)'}</span>
                    <span className="popup-item-desc">{chat.date?.slice(0, 10)}</span>
                  </button>
                  <button
                    style={{ background: 'none', border: 'none', cursor: 'pointer', padding: '6px 8px', color: '#888', fontSize: '16px', lineHeight: 1, flexShrink: 0 }}
                    title="삭제"
                    onClick={e => {
                      e.stopPropagation();
                      if (!confirm('이 대화를 삭제하시겠습니까?')) return;
                      window.sendToIde?.(JSON.stringify({ command: '/deleteChat', id: chat.id }));
                      setChatList(prev => prev.filter(c => c.id !== chat.id));
                    }}
                  >
                    ×
                  </button>
                </div>
              ))
            )}
          </div>
        </div>
      )}

      <div className="chat-list" ref={chatListRef}>
        {messages.map(msg => <MessageItem key={msg.id} msg={msg} />)}
      </div>

      <div className="chat-input-area">
        {showScrollButton && (
          <button className="scroll-bottom-btn" onClick={scrollToBottom}>
            <ArrowDown size={14} />
          </button>
        )}
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
        <div className="textarea-wrapper">
          {showFilePopup && (
            <div className="command-popup" ref={filePopupRef}>
              <div className="popup-section">
                <div className="popup-section-title">열린 파일</div>
                {isLoadingTabs ? (
                  <div className="popup-loading">로딩 중...</div>
                ) : openTabs.length === 0 ? (
                  <div className="popup-loading">열린 파일이 없습니다.</div>
                ) : (
                  openTabs.map((tab, idx) => (
                    <button
                      key={tab.path}
                      className={`popup-item ${filePopupSelectedIndex === idx ? 'selected' : ''}`}
                      onClick={() => toggleFileSelection(tab)}
                      onMouseEnter={() => setFilePopupSelectedIndex(idx)}
                    >
                      <span className="popup-item-command">📄 {tab.name}</span>
                    </button>
                  ))
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
                  </button>
                ))}
              </div>
              <div className="popup-section">
                <div className="popup-section-title">기타</div>
                {popupItems.filter(item => item.type === 'settings').map(item => (
                  <button
                    key={item.cmd}
                    className={`popup-item ${popupSelectedIndex === item.index ? 'selected' : ''}`}
                    onClick={() => applyPopupSelection(item)}
                    onMouseEnter={() => setPopupSelectedIndex(item.index)}
                  >
                    <span className="popup-item-command">{item.label}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
          <textarea
            ref={textareaRef}
            placeholder="무엇을 도와드릴까요?"
            value={inputText}
            onChange={e => {
              const val = e.target.value;
              setInputText(val);
              // @ 타이핑 감지: 커서 바로 앞 글자가 @이면 파일 팝업 오픈
              const cursorPos = e.target.selectionStart;
              if (val[cursorPos - 1] === '@' && !showFilePopup) {
                openFilePopup(cursorPos - 1);
              }
              if (val === '/' || val.endsWith(' /') || val.endsWith('\n/')) {
                setShowCommandPopup(true);
                setPopupSelectedIndex(0);
                if (fetchedModels.length === 0) {
                  setIsFetchingModels(true);
                  setModelsError('');
                  window.sendToIde?.(JSON.stringify({ command: '/fetchModels' }));
                }
              } else {
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
