import React, { useState, useMemo, useEffect } from 'react';

export interface ClarifyItem {
  id: string;
  statement: string;
  anchorRationale: string;
  verdict: 'PENDING' | 'CONFIRMED' | 'REJECTED';
  source?: string;
  hint?: {
    type: string;
    filePath?: string;
    matchedTokens?: string[];
  };
  confidence?: 'HIGH_CONFIDENCE' | 'LOW_CONFIDENCE';
  provenanceSignals?: string[];
  domainPackage?: string | null;
  rejectionReason?: 'FILE_MISMATCH' | 'CONCEPT_IRRELEVANT';
  isReEmergence?: boolean;
}

export interface ClarifyPayload {
  originalRequirement: string;
  items: ClarifyItem[];
  openQuestion?: string | null;
  isExhausted?: boolean;
  isReadyForStage1?: boolean;
  taskSummary?: string | null;
  echoBackMessage?: string | null;
}

export interface DomainGroup {
  domain: string;
  isFallback: boolean;
  highItems: ClarifyItem[];
  lowItems: ClarifyItem[];
  totalItems: ClarifyItem[];
}

interface AnalyzeFileReviewFormProps {
  payload: ClarifyPayload;
  isSubmitted?: boolean;
  onVerdictChange?: (verdicts: Record<string, 'CONFIRMED' | 'REJECTED' | 'PENDING'>) => void;
  onReasonChange?: (reasons: Record<string, 'FILE_MISMATCH' | 'CONCEPT_IRRELEVANT'>) => void;
  onSubmit?: (verdicts: Record<string, 'CONFIRMED' | 'REJECTED' | 'PENDING'>, reasons: Record<string, 'FILE_MISMATCH' | 'CONCEPT_IRRELEVANT'>) => void;
}

/**
 * Analyze 단계 파일 정밀 검토 폼 자산 (AnalyzeFileReviewForm)
 * - 1차 도메인 그룹핑 & 2차 신뢰도(HIGH / LOW 형제 유추) 아코디언 접기
 * - 개별 파일 카드, 포함/제외 과도기 버튼, 2지선다 사유 선택 UI
 * - 그룹별 일괄 제외/복원 액션
 */
export const AnalyzeFileReviewForm: React.FC<AnalyzeFileReviewFormProps> = ({
  payload,
  isSubmitted = false,
  onVerdictChange,
  onReasonChange
}) => {
  // id -> Verdict
  const [verdicts, setVerdicts] = useState<Record<string, 'CONFIRMED' | 'REJECTED' | 'PENDING'>>(() => {
    const init: Record<string, 'CONFIRMED' | 'REJECTED' | 'PENDING'> = {};
    payload.items.forEach(item => {
      init[item.id] = item.verdict || 'PENDING';
    });
    return init;
  });

  // id -> RejectionReason
  const [rejectionReasons, setRejectionReasons] = useState<Record<string, 'FILE_MISMATCH' | 'CONCEPT_IRRELEVANT'>>(() => {
    const init: Record<string, 'FILE_MISMATCH' | 'CONCEPT_IRRELEVANT'> = {};
    payload.items.forEach(item => {
      if (item.rejectionReason) {
        init[item.id] = item.rejectionReason;
      }
    });
    return init;
  });

  // 1차 도메인 그룹핑 & 결정론적 정렬
  const domainGroups = useMemo<DomainGroup[]>(() => {
    const map = new Map<string, ClarifyItem[]>();
    payload.items.forEach(item => {
      const key = item.domainPackage?.trim() || '__OTHER__';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(item);
    });

    const groups: DomainGroup[] = [];
    map.forEach((items, key) => {
      const isFallback = key === '__OTHER__';
      const highItems = items.filter(it => it.confidence !== 'LOW_CONFIDENCE');
      const lowItems = items.filter(it => it.confidence === 'LOW_CONFIDENCE');
      groups.push({
        domain: isFallback ? '기타 / 미분류' : key,
        isFallback,
        highItems,
        lowItems,
        totalItems: items
      });
    });

    groups.sort((a, b) => {
      if (a.isFallback !== b.isFallback) return a.isFallback ? 1 : -1;
      if (a.highItems.length !== b.highItems.length) return b.highItems.length - a.highItems.length;
      if (a.totalItems.length !== b.totalItems.length) return b.totalItems.length - a.totalItems.length;
      return a.domain.localeCompare(b.domain);
    });

    return groups;
  }, [payload.items]);

  const [expandedLowSections, setExpandedLowSections] = useState<Record<string, boolean>>({});

  const toggleLowSection = (domain: string) => {
    setExpandedLowSections(prev => ({
      ...prev,
      [domain]: !prev[domain]
    }));
  };

  const isGroupAllRejected = (group: DomainGroup): boolean => {
    if (group.totalItems.length === 0) return false;
    return group.totalItems.every(it => (verdicts[it.id] || it.verdict) === 'REJECTED');
  };

  const toggleGroupVerdict = (group: DomainGroup) => {
    if (isSubmitted) return;
    const allRejected = isGroupAllRejected(group);

    if (allRejected) {
      setVerdicts(prev => {
        const next = { ...prev };
        group.totalItems.forEach(it => {
          next[it.id] = 'PENDING';
        });
        onVerdictChange?.(next);
        return next;
      });
      setRejectionReasons(prev => {
        const next = { ...prev };
        group.totalItems.forEach(it => {
          delete next[it.id];
        });
        onReasonChange?.(next);
        return next;
      });
    } else {
      setVerdicts(prev => {
        const next = { ...prev };
        group.totalItems.forEach(it => {
          next[it.id] = 'REJECTED';
        });
        onVerdictChange?.(next);
        return next;
      });
      setRejectionReasons(prev => {
        const next = { ...prev };
        group.totalItems.forEach(it => {
          if (next[it.id] !== 'CONCEPT_IRRELEVANT') {
            next[it.id] = 'FILE_MISMATCH';
          }
        });
        onReasonChange?.(next);
        return next;
      });
    }
  };

  // SSOT 무조건 덮어쓰기
  useEffect(() => {
    setVerdicts(() => {
      const next: Record<string, 'CONFIRMED' | 'REJECTED' | 'PENDING'> = {};
      payload.items.forEach(item => {
        next[item.id] = item.verdict || 'PENDING';
      });
      return next;
    });

    setRejectionReasons(() => {
      const next: Record<string, 'FILE_MISMATCH' | 'CONCEPT_IRRELEVANT'> = {};
      payload.items.forEach(item => {
        if (item.rejectionReason) {
          next[item.id] = item.rejectionReason;
        }
      });
      return next;
    });
  }, [payload.items.map(i => `${i.id}:${i.verdict}:${i.rejectionReason || ''}`).join(',')]);

  const toggleVerdict = (id: string, targetVerdict: 'CONFIRMED' | 'REJECTED') => {
    if (isSubmitted) return;
    setVerdicts(prev => {
      const next: Record<string, 'CONFIRMED' | 'REJECTED' | 'PENDING'> = {
        ...prev,
        [id]: prev[id] === targetVerdict ? 'PENDING' : targetVerdict
      };
      onVerdictChange?.(next);
      return next;
    });
  };

  const setReason = (id: string, reason: 'FILE_MISMATCH' | 'CONCEPT_IRRELEVANT') => {
    if (isSubmitted) return;
    setRejectionReasons(prev => {
      const next = {
        ...prev,
        [id]: reason
      };
      onReasonChange?.(next);
      return next;
    });
  };

  const renderItemCard = (item: ClarifyItem) => {
    const currentVerdict = verdicts[item.id] || item.verdict || 'PENDING';
    const isConfirmed = currentVerdict === 'CONFIRMED';
    const isRejected = currentVerdict === 'REJECTED';
    const isLowConfidence = item.confidence === 'LOW_CONFIDENCE';

    return (
      <div 
        key={item.id}
        style={{
          padding: '8px 10px',
          borderRadius: '4px',
          background: isConfirmed ? 'rgba(16, 185, 129, 0.1)' : isRejected ? 'rgba(239, 68, 68, 0.08)' : 'rgba(255,255,255,0.04)',
          border: `1px solid ${isConfirmed ? '#10b981' : isRejected ? '#ef4444' : 'rgba(255,255,255,0.1)'}`,
          display: 'flex',
          flexDirection: 'column',
          gap: '6px',
          transition: 'all 0.15s ease'
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ flex: 1, marginRight: '10px' }}>
            <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '6px' }}>
              <span style={{ fontSize: '13px', fontWeight: 'bold', color: isRejected ? '#888' : '#eee', textDecoration: isRejected ? 'line-through' : 'none' }}>
                {item.statement}
              </span>
              {isLowConfidence && (
                <span style={{
                  padding: '1px 6px',
                  fontSize: '10px',
                  borderRadius: '3px',
                  background: 'rgba(168, 85, 247, 0.15)',
                  border: '1px solid #a855f7',
                  color: '#c084fc',
                  fontWeight: 600
                }}>
                  🔍 형제 유추 후보
                </span>
              )}
              {item.isReEmergence && (
                <span style={{
                  padding: '1px 6px',
                  fontSize: '10px',
                  borderRadius: '3px',
                  background: 'rgba(245, 158, 11, 0.2)',
                  border: '1px solid #f59e0b',
                  color: '#fbbf24',
                  fontWeight: 600
                }}>
                  ⚠️ 재확인 필요: 새 문맥에서 재등장
                </span>
              )}
            </div>
            {item.anchorRationale && (
              <div style={{ fontSize: '11px', color: '#9ca3af', marginTop: '2px' }}>
                💡 {item.anchorRationale}
              </div>
            )}
          </div>

          {!isSubmitted && (
            <div style={{ display: 'flex', gap: '4px', flexShrink: 0 }}>
              <button
                type="button"
                onClick={() => toggleVerdict(item.id, 'CONFIRMED')}
                style={{
                  padding: '3px 8px',
                  fontSize: '11px',
                  borderRadius: '3px',
                  border: 'none',
                  cursor: 'pointer',
                  background: isConfirmed ? '#10b981' : '#374151',
                  color: '#fff',
                  fontWeight: isConfirmed ? 'bold' : 'normal'
                }}
              >
                ✓ 포함
              </button>
              <button
                type="button"
                onClick={() => toggleVerdict(item.id, 'REJECTED')}
                style={{
                  padding: '3px 8px',
                  fontSize: '11px',
                  borderRadius: '3px',
                  border: 'none',
                  cursor: 'pointer',
                  background: isRejected ? '#ef4444' : '#374151',
                  color: '#fff',
                  fontWeight: isRejected ? 'bold' : 'normal'
                }}
              >
                ✗ 제외
              </button>
            </div>
          )}
          {isSubmitted && (
            <span style={{ fontSize: '11px', color: isConfirmed ? '#10b981' : isRejected ? '#ef4444' : '#aaa', fontWeight: 'bold', flexShrink: 0 }}>
              {isConfirmed ? '✓ 포함됨' : isRejected ? '✗ 제외됨' : '미판정'}
            </span>
          )}
        </div>

        {/* 제외 상태일 때 2지선다 사유 선택 UI */}
        {isRejected && !isSubmitted && (
          <div style={{ marginTop: '4px', padding: '6px 8px', background: 'rgba(239, 68, 68, 0.06)', borderRadius: '4px', border: '1px solid rgba(239, 68, 68, 0.2)' }}>
            <div style={{ fontSize: '11px', color: '#fca5a5', marginBottom: '4px', fontWeight: 600 }}>
              제외 사유 선택 (선택 시 맞춤 반영, 미선택 시 기본 '파일 불일치' 적용):
            </div>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                type="button"
                onClick={() => setReason(item.id, 'FILE_MISMATCH')}
                style={{
                  padding: '2px 8px',
                  fontSize: '11px',
                  borderRadius: '3px',
                  border: `1px solid ${rejectionReasons[item.id] === 'FILE_MISMATCH' ? '#f87171' : 'rgba(255,255,255,0.2)'}`,
                  background: rejectionReasons[item.id] === 'FILE_MISMATCH' ? 'rgba(239, 68, 68, 0.3)' : 'transparent',
                  color: rejectionReasons[item.id] === 'FILE_MISMATCH' ? '#fff' : '#aaa',
                  cursor: 'pointer'
                }}
              >
                📁 이 파일이 아님 (새 문맥 시 재등장 가능)
              </button>
              <button
                type="button"
                onClick={() => setReason(item.id, 'CONCEPT_IRRELEVANT')}
                style={{
                  padding: '2px 8px',
                  fontSize: '11px',
                  borderRadius: '3px',
                  border: `1px solid ${rejectionReasons[item.id] === 'CONCEPT_IRRELEVANT' ? '#f87171' : 'rgba(255,255,255,0.2)'}`,
                  background: rejectionReasons[item.id] === 'CONCEPT_IRRELEVANT' ? 'rgba(239, 68, 68, 0.3)' : 'transparent',
                  color: rejectionReasons[item.id] === 'CONCEPT_IRRELEVANT' ? '#fff' : '#aaa',
                  cursor: 'pointer'
                }}
              >
                🚫 개념/업무 무관 (영구 억제)
              </button>
            </div>
          </div>
        )}
        {isRejected && isSubmitted && (
          <div style={{ fontSize: '11px', color: '#fca5a5' }}>
            사유: {rejectionReasons[item.id] === 'CONCEPT_IRRELEVANT' ? '🚫 개념/업무 무관 (영구 억제)' : '📁 파일 불일치 (기본값)'}
          </div>
        )}
      </div>
    );
  };

  return (
    <div style={{ marginTop: '12px' }}>
      <h4 style={{ margin: '0 0 10px 0', fontSize: '13px', color: '#60a5fa' }}>📋 감지된 변경 대상 & 도메인 그룹</h4>
      {domainGroups.length > 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          {domainGroups.map(group => {
            const allRejected = isGroupAllRejected(group);
            const isExpanded = expandedLowSections[group.domain] ?? false;

            return (
              <div 
                key={group.domain}
                style={{
                  padding: '10px 12px',
                  background: 'rgba(255, 255, 255, 0.02)',
                  border: '1px solid rgba(255, 255, 255, 0.08)',
                  borderRadius: '6px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '8px'
                }}
              >
                {/* 도메인 그룹 헤더 */}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingBottom: '6px', borderBottom: '1px solid rgba(255, 255, 255, 0.06)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '13px', fontWeight: 'bold', color: '#38bdf8' }}>
                      📁 {group.domain}
                    </span>
                    <span style={{
                      padding: '1px 6px',
                      fontSize: '11px',
                      borderRadius: '10px',
                      background: 'rgba(56, 189, 248, 0.15)',
                      color: '#7dd3fc',
                      fontWeight: 600
                    }}>
                      {group.totalItems.length}개 파일
                    </span>
                    {group.highItems.length > 0 && (
                      <span style={{
                        padding: '1px 6px',
                        fontSize: '10px',
                        borderRadius: '10px',
                        background: 'rgba(168, 85, 247, 0.15)',
                        color: '#c084fc',
                        fontWeight: 600
                      }}>
                        형제 유추 {group.lowItems.length}
                      </span>
                    )}
                  </div>

                  {!isSubmitted && (
                    <button
                      type="button"
                      onClick={() => toggleGroupVerdict(group)}
                      style={{
                        padding: '3px 8px',
                        fontSize: '11px',
                        borderRadius: '3px',
                        border: allRejected ? '1px solid #10b981' : '1px solid rgba(239, 68, 68, 0.4)',
                        background: allRejected ? 'rgba(168, 85, 247, 0.2)' : 'rgba(239, 68, 68, 0.1)',
                        color: allRejected ? '#34d399' : '#fca5a5',
                        cursor: 'pointer',
                        fontWeight: 600
                      }}
                      title={allRejected ? "그룹 내 모든 파일 상태를 원복합니다." : "그룹 내 모든 파일을 제외(FILE_MISMATCH)합니다."}
                    >
                      {allRejected ? "↺ 그룹 전체 복원" : "✗ 그룹 전체 제외"}
                    </button>
                  )}
                </div>

                {/* 1) HIGH_CONFIDENCE 항목들 */}
                {group.highItems.length > 0 && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                    {group.highItems.map(renderItemCard)}
                  </div>
                )}

                {/* 2) LOW_CONFIDENCE 항목들 */}
                {group.lowItems.length > 0 && (
                  <div style={{ marginTop: group.highItems.length > 0 ? '4px' : '0' }}>
                    <button
                      type="button"
                      onClick={() => toggleLowSection(group.domain)}
                      style={{
                        width: '100%',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        padding: '6px 8px',
                        fontSize: '11px',
                        borderRadius: '4px',
                        background: 'rgba(168, 85, 247, 0.08)',
                        border: '1px dashed rgba(168, 85, 247, 0.3)',
                        color: '#c084fc',
                        cursor: 'pointer'
                      }}
                    >
                      <span>
                        🔍 추가 연관 후보 (형제 유추 {group.lowItems.length}개) {isExpanded ? '접기' : '펼쳐보기'}
                      </span>
                      <span>{isExpanded ? '▲' : '▼'}</span>
                    </button>

                    {isExpanded && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', marginTop: '6px', paddingLeft: '8px', borderLeft: '2px solid rgba(168, 85, 247, 0.3)' }}>
                        {group.lowItems.map(renderItemCard)}
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      ) : (
        <p style={{ fontSize: '12px', color: '#888', padding: '8px', background: 'rgba(255,255,255,0.02)', borderRadius: '4px' }}>
          구조적으로 즉시 감지된 후보가 없습니다.
        </p>
      )}
    </div>
  );
};
