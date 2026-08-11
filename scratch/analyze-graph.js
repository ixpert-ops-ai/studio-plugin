// analyze-graph.js
// 사용법: node analyze-graph.js <project-graph.json>
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('사용법: node analyze-graph.js <project-graph.json>');
  process.exit(1);
}

const data = JSON.parse(fs.readFileSync(file, 'utf8'));

// 노드 배열 위치 자동 탐색 (스키마에 맞게 조정 가능)
const nodes = Object.values(data.files || data.nodes || data.fileNodes || {});
const resources = data.resourceNodes || [];

const arrLen = (v) => (Array.isArray(v) ? v.length : 0);

let totalNodes = nodes.length;
let sumUsesTypes = 0;
let sumUsedByTypes = 0;
let sumDependsOn = 0;
let sumDependedBy = 0;
let nodesWithUsesTypes = 0;

// 타입별 집계
const byType = {}; // type -> { count, usesTypes, usedByTypes }

for (const n of nodes) {
  const ut = arrLen(n.usesTypes);
  const ubt = arrLen(n.usedByTypes);
  const dep = arrLen(n.dependsOn);
  const depBy = arrLen(n.dependedBy);

  sumUsesTypes += ut;
  sumUsedByTypes += ubt;
  sumDependsOn += dep;
  sumDependedBy += depBy;
  if (ut > 0) nodesWithUsesTypes++;

  const t = n.fileType || n.type || 'UNKNOWN';
  if (!byType[t]) byType[t] = { count: 0, usesTypes: 0, usedByTypes: 0 };
  byType[t].count++;
  byType[t].usesTypes += ut;
  byType[t].usedByTypes += ubt;
}

const pct = (a, b) => (b === 0 ? '0.0' : ((a / b) * 100).toFixed(1));

console.log('==================================================');
console.log('파일:', file);
console.log('최상위 키 목록:', Object.keys(data).join(', '));
console.log('배열 탐색 결과 (nodes 개수):', totalNodes);
console.log('==================================================');
console.log('[전체 규모]');
console.log('  총 노드 수         :', totalNodes);
console.log('  resourceNodes 수   :', resources.length);
console.log('');
console.log('[엣지 총계]');
console.log('  usesTypes   (신규) :', sumUsesTypes);
console.log('  usedByTypes (신규) :', sumUsedByTypes);
console.log('  dependsOn   (기존) :', sumDependsOn);
console.log('  dependedBy  (기존) :', sumDependedBy);
console.log('');
console.log('[usesTypes 커버리지]');
console.log('  usesTypes 채워진 노드:', nodesWithUsesTypes,
            `(${pct(nodesWithUsesTypes, totalNodes)}%)`);
console.log('');
console.log('[타입별 집계]  type | count | usesTypes합 | 노드당평균 | usedByTypes합');
Object.keys(byType).sort().forEach((t) => {
  const b = byType[t];
  const avg = b.count === 0 ? '0.00' : (b.usesTypes / b.count).toFixed(2);
  console.log(
    `  ${t.padEnd(20)} ${String(b.count).padStart(6)} ${String(b.usesTypes).padStart(11)} ${avg.padStart(10)} ${String(b.usedByTypes).padStart(13)}`
  );
});

// Fan-in 분포 분석 (usedByTypes 기준)
console.log('');
console.log('[usedByTypes (Fan-in) 분포 및 상위 노드]');
const fanInNodes = nodes.map(n => ({
  path: n.path,
  className: n.className || n.path,
  type: n.fileType || n.type || 'UNKNOWN',
  count: arrLen(n.usedByTypes)
})).sort((a, b) => b.count - a.count);

let count0 = 0, count1_5 = 0, count6_20 = 0, count21_100 = 0, countOver100 = 0;
for (const n of fanInNodes) {
  if (n.count === 0) count0++;
  else if (n.count <= 5) count1_5++;
  else if (n.count <= 20) count6_20++;
  else if (n.count <= 100) count21_100++;
  else countOver100++;
}

console.log(`  Fan-in == 0       : ${count0}`);
console.log(`  Fan-in 1 ~ 5      : ${count1_5}`);
console.log(`  Fan-in 6 ~ 20     : ${count6_20}`);
console.log(`  Fan-in 21 ~ 100   : ${count21_100}`);
console.log(`  Fan-in > 100      : ${countOver100}`);

console.log('');
console.log('  Top 20 Fan-in Nodes:');
fanInNodes.slice(0, 20).forEach((n, i) => {
  console.log(`    ${String(i + 1).padStart(2)}. [${n.count.toString().padStart(4)}] [${n.type}] ${n.className}`);
});
console.log('==================================================\n');
