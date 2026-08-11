// simulate-expansion.js
const fs = require('fs');

const data = JSON.parse(fs.readFileSync('C:/Workspace/graph/project-graph-i/project-graph.json', 'utf8'));
const files = Object.values(data.files || {});

const SEED = 'PdInfoController';
const GT_SET = new Set([
  'PdInfoRequest',
  'PdInfoResponse',
  'PdInfoService',
  'PdInfoServiceImpl',
  'PdSaveServiceImpl',
  'ECPDTBISM032TrxMapper'
]);

const seedNode = files.find(f => f.className === SEED);
if (!seedNode) {
  console.error('Seed not found!');
  process.exit(1);
}

const cutoffs = [10, 20, 33, 40, 100, Infinity];

console.log('=== 정답 집합 Recall 시뮬레이션 ===');
console.log(`SEED: ${SEED}`);
console.log(`GT Set: ${Array.from(GT_SET).join(', ')}\n`);

for (const K of cutoffs) {
  let visited = new Set([seedNode.path]);
  let queue = [{ path: seedNode.path, hop: 0 }];
  let gtFound = new Set();
  
  if (GT_SET.has(seedNode.className)) gtFound.add(seedNode.className);

  let totalNodesVisited = 1;

  while (queue.length > 0) {
    const curr = queue.shift();
    if (curr.hop >= 4) continue; // maxHop = 4 테스트

    const node = files.find(f => f.path === curr.path);
    if (!node) continue;

    const uses = node.usesTypes || [];
    const usedBy = node.usedByTypes || [];
    const neighbors = [...uses, ...usedBy];

    for (const n of neighbors) {
      if (!visited.has(n)) {
        const neighborNode = files.find(f => f.path === n);
        if (!neighborNode) continue;

        // Fan-in 컷오프 적용 (단, SEED에서 직접 연결된 Hop 1은 컷오프 면제)
        // -> wait, 만약 Hop 1에서 DTO를 만나고, 그 DTO에서 Hop 2로 갈 때 DTO의 fan-in이 K보다 크면?
        // 컷오프는 "현재 방문하려는 노드"의 fan-in이 아니라, "경유지 노드"의 fan-in이어야 함.
        // 아니면 "방문하려는 노드의 fan-in이 K 이상이면 방문 자체를 금지"?
        // 만약 DTO 방문을 금지하면 컨트롤러가 DTO를 참조하는 것조차 누락됨.
        // 따라서 "경유지(curr) 노드의 fan-in이 K 이상이면 그 노드에서부터의 추가 확장(outbound)을 금지"하는 게 맞음.
        if (curr.hop > 0) {
           const currFanIn = (node.usedByTypes || []).length;
           if (currFanIn > K) {
             continue; // K 초과 허브 노드에서는 추가 확산을 중단
           }
        }

        visited.add(n);
        totalNodesVisited++;
        
        if (GT_SET.has(neighborNode.className)) {
          gtFound.add(neighborNode.className);
        }
        
        queue.push({ path: n, hop: curr.hop + 1 });
      }
    }
  }

  const recall = `${gtFound.size} / ${GT_SET.size}`;
  console.log(`[Fan-in Cutoff: ${K === Infinity ? '∞' : K}]`);
  console.log(`  - Total Visited Nodes: ${totalNodesVisited}`);
  console.log(`  - GT Recall: ${recall}`);
  console.log(`  - Missing GTs: ${Array.from(GT_SET).filter(x => !gtFound.has(x)).join(', ') || 'None'}`);
  console.log('');
}
