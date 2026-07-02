import React, { useState, useMemo, useCallback } from 'react';
import { Folder, FolderOpen, Check, X, AlertTriangle, ChevronRight, ChevronDown } from 'lucide-react';

interface DirectoryNode {
  path: string;
  displayName: string;
  fileCount: number;
  children: DirectoryNode[];
  depth: number;
}

interface ScopeConfig {
  minFilesPerNode: number;
  maxDepth: number;
  maxSelections: number;
  maxFiles: number;
  warningThreshold: number;
}

interface ScopeSelectionTreeProps {
  payload: string; // JSON string from msg.content
  onCancel: () => void;
  onSubmit: (selectedPaths: string[]) => void;
}

const TreeNode: React.FC<{
  node: DirectoryNode;
  selectedPaths: Set<string>;
  expandedPaths: Set<string>;
  toggleSelection: (path: string, checked: boolean, children: DirectoryNode[]) => void;
  toggleExpand: (path: string) => void;
}> = React.memo(({ node, selectedPaths, expandedPaths, toggleSelection, toggleExpand }) => {
  const isSelected = selectedPaths.has(node.path);
  const isExpanded = expandedPaths.has(node.path);
  const hasChildren = node.children && node.children.length > 0;

  return (
    <div className="tree-node" style={{ marginLeft: `${node.depth > 1 ? 20 : 0}px`, marginTop: '4px' }}>
      <div className="tree-node-row" style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
        <span 
          className="tree-expander"
          onClick={() => toggleExpand(node.path)}
          style={{ width: '16px', display: 'flex', justifyContent: 'center' }}
        >
          {hasChildren ? (isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />) : <span style={{width: 14}} />}
        </span>
        <input 
          type="checkbox" 
          checked={isSelected}
          onChange={(e) => toggleSelection(node.path, e.target.checked, node.children)}
          className="tree-checkbox"
          style={{ cursor: 'pointer' }}
        />
        <span className="tree-icon" onClick={() => toggleExpand(node.path)} style={{ display: 'flex', color: '#8aa1e1' }}>
          {hasChildren ? (isExpanded ? <FolderOpen size={16} /> : <Folder size={16} />) : <Folder size={16} />}
        </span>
        <span className="tree-label" onClick={() => toggleExpand(node.path)} style={{ fontSize: '13px', userSelect: 'none' }}>
          {node.displayName} <span className="tree-count" style={{ color: '#777', fontSize: '11px', marginLeft: '4px' }}>({node.fileCount})</span>
        </span>
      </div>
      {hasChildren && isExpanded && (
        <div className="tree-children">
          {node.children.map(child => (
            <TreeNode 
              key={child.path} 
              node={child} 
              selectedPaths={selectedPaths} 
              expandedPaths={expandedPaths}
              toggleSelection={toggleSelection}
              toggleExpand={toggleExpand}
            />
          ))}
        </div>
      )}
    </div>
  );
});

export const ScopeSelectionTree: React.FC<ScopeSelectionTreeProps> = ({ payload, onCancel, onSubmit }) => {
  const data = useMemo(() => {
    try {
      return JSON.parse(payload) as {
        projectName: string;
        tree: DirectoryNode[];
        config: ScopeConfig;
      };
    } catch (e) {
      console.error("Failed to parse scopeSelection payload", e);
      return null;
    }
  }, [payload]);

  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(new Set());
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(new Set());

  // Recursively collect all paths in a node
  const collectPaths = (node: DirectoryNode, paths: string[]) => {
    paths.push(node.path);
    if (node.children) {
      node.children.forEach(child => collectPaths(child, paths));
    }
  };

  const toggleSelection = useCallback((path: string, checked: boolean, children: DirectoryNode[]) => {
    setSelectedPaths(prev => {
      const next = new Set(prev);
      const pathsToToggle: string[] = [path];
      if (children) {
          children.forEach(child => collectPaths(child, pathsToToggle));
      }
      
      pathsToToggle.forEach(p => {
        if (checked) next.add(p);
        else next.delete(p);
      });
      return next;
    });
  }, []);

  const toggleExpand = useCallback((path: string) => {
    setExpandedPaths(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  if (!data) {
    return <div className="error-text">데이터를 불러오는 데 실패했습니다.</div>;
  }

  // Calculate total files for selected top-level distinct paths to avoid double counting
  const calculateTotalFiles = () => {
    if (selectedPaths.size === 0) return 0;
    
    let total = 0;
    const countedPrefixes: string[] = [];

    const isSubPath = (p: string) => countedPrefixes.some(prefix => p === prefix || p.startsWith(prefix + '/'));

    const countNode = (nodes: DirectoryNode[]) => {
      for (const node of nodes) {
        if (selectedPaths.has(node.path) && !isSubPath(node.path)) {
          total += node.fileCount;
          countedPrefixes.push(node.path);
        } else if (node.children) {
          countNode(node.children);
        }
      }
    };
    countNode(data.tree);
    return total;
  };

  const totalSelectedFiles = calculateTotalFiles();
  const config = data.config;

  const isTooMany = totalSelectedFiles > config.maxFiles;
  const isWarning = totalSelectedFiles > config.warningThreshold;

  return (
    <div className="scope-selection-container" style={{ background: '#1e1e1e', border: '1px solid #333', borderRadius: '8px', padding: '16px', marginTop: '12px', marginBottom: '12px' }}>
      <div className="scope-header">
        <h4 style={{ margin: '0 0 8px 0', fontSize: '15px', color: '#e0e0e0', display: 'flex', alignItems: 'center', gap: '6px' }}>
          📦 분석 대상 패키지 선택
        </h4>
        <p style={{ margin: '0 0 12px 0', fontSize: '13px', color: '#aaa', lineHeight: '1.4' }}>
          프로젝트 규모가 큽니다. 요구사항과 관련된 패키지만 선택해 주세요.<br/>
          범위를 좁힐수록 AI 분석 정확도와 속도가 크게 향상됩니다.
        </p>
      </div>

      <div className="tree-scroll-area" style={{ maxHeight: '350px', overflowY: 'auto', border: '1px solid #333', borderRadius: '6px', padding: '12px', background: '#181818' }}>
        {data.tree.map(node => (
          <TreeNode 
            key={node.path} 
            node={node} 
            selectedPaths={selectedPaths} 
            expandedPaths={expandedPaths}
            toggleSelection={toggleSelection}
            toggleExpand={toggleExpand}
          />
        ))}
      </div>

      <div className="scope-footer" style={{ marginTop: '16px' }}>
        <div className="scope-stats" style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
          <span>선택된 파일: <strong style={{ color: '#fff' }}>{totalSelectedFiles}</strong>개</span>
          
          {isTooMany && (
            <span style={{ color: '#ff6b6b', display: 'flex', alignItems: 'center', gap: '4px' }}>
              <AlertTriangle size={14} /> 최대 허용({config.maxFiles}) 초과
            </span>
          )}
          {!isTooMany && isWarning && (
            <span style={{ color: '#fca311', display: 'flex', alignItems: 'center', gap: '4px' }}>
              <AlertTriangle size={14} /> 너무 많습니다 (권장 &lt; {config.warningThreshold})
            </span>
          )}
        </div>

        <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
          <button 
            className="btn-secondary" 
            onClick={onCancel}
            style={{ display: 'flex', alignItems: 'center', padding: '8px 16px', borderRadius: '4px', border: '1px solid #444', background: '#2a2a2a', color: '#ccc', cursor: 'pointer', fontSize: '13px' }}
          >
            <X size={16} style={{ marginRight: '6px' }} /> 취소 (전체 선택)
          </button>
          <button 
            className="btn-primary" 
            onClick={() => onSubmit(Array.from(selectedPaths))}
            disabled={selectedPaths.size === 0 || isTooMany}
            style={{ display: 'flex', alignItems: 'center', padding: '8px 16px', borderRadius: '4px', border: 'none', background: (selectedPaths.size === 0 || isTooMany) ? '#444' : '#0066cc', color: '#fff', cursor: (selectedPaths.size === 0 || isTooMany) ? 'not-allowed' : 'pointer', fontSize: '13px', fontWeight: 'bold' }}
          >
            <Check size={16} style={{ marginRight: '6px' }} /> 확인
          </button>
        </div>
      </div>
    </div>
  );
};
