import { useState } from 'react';
import { Settings, Edit, Clock, CornerDownLeft, Square } from 'lucide-react';
import './index.css';

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
        <button 
          className={`toggle-btn ${mode === 'Plan' ? 'active' : ''}`}
          onClick={() => setMode('Plan')}
        >
          Plan
        </button>
        <button 
          className={`toggle-btn ${mode === 'Act' ? 'active' : ''}`}
          onClick={() => setMode('Act')}
        >
          Act
        </button>
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

const ChatInputArea = () => {
  return (
    <div className="chat-input-area">
      <button className="attach-btn">
        @ 파일 첨부
      </button>
      <div className="textarea-wrapper">
        <textarea placeholder="메시지를 입력하세요..." />
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
            <button className="btn btn-primary">
              전송
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

function App() {
  return (
    <>
      <Header />
      <ContextBar />
      
      <div className="chat-list">
        {/* User Message */}
        <div className="msg-user">
          MainActivity.kt 이 코드 리뷰해줘
        </div>
        
        {/* Tool Action Message */}
        <div className="msg-tool">
          <div className="dot"></div>
          파일 읽기 · MainActivity.kt
        </div>
        
        {/* AI Message */}
        <div className="msg-ai">
          <div className="msg-ai-header">WhatUWant?</div>
          <div className="msg-ai-content">
            <p>전반적인 구조는 양호합니다. 다만 몇 가지 개선이 필요한 부분이 있습니다.</p>
            <br />
            <p>1. ViewModel 생명주기 처리 미흡</p>
            <p>2. Coroutine scope 누수 가능성</p>
          </div>
          <div className="msg-ai-actions">
            <button className="btn-small">Copy</button>
            <button className="btn-small">Save</button>
          </div>
        </div>

        {/* Tool Action Update Request */}
        <div className="msg-tool" style={{ alignSelf: 'flex-end', background: 'transparent', border: '1px solid var(--border-color)' }}>
          코드 개선 제<span style={{flex: 1, minWidth: '40px'}}></span><span style={{color: 'var(--text-main)'}}>MainActivity.kt</span>
        </div>

        {/* AI Streaming Message */}
        <div className="msg-ai" style={{ width: 'fit-content' }}>
          <div className="msg-ai-header">WhatUWant?</div>
          <div className="flex items-center gap-2 text-muted" style={{ fontSize: '12px', color: 'var(--text-muted)'}}>
            <div className="typing-dots">
              <span></span><span></span><span></span>
            </div>
            응답 생성 중
          </div>
        </div>
      </div>

      <ChatInputArea />
    </>
  );
}

export default App;
