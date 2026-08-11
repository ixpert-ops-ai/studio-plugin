// simulate-explosion.js
const fs = require('fs');

function simulate(project, seedName) {
  const file = `C:/Workspace/graph/${project}/project-graph.json`;
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  const files = Object.values(data.files || {});

  const seedNode = files.find(f => f.className === seedName);
  if (!seedNode) {
    console.log(`[${project}] Seed ${seedName} not found!`);
    return;
  }

  let visited = new Set([seedNode.path]);
  let queue = [{ path: seedNode.path, hop: 0 }];
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
        visited.add(n);
        totalNodesVisited++;
        queue.push({ path: n, hop: curr.hop + 1 });
      }
    }
  }

  console.log(`[${project}] SEED: ${seedName} (${seedNode.fileType || seedNode.type || 'UNKNOWN'})`);
  console.log(`  - Total Visited Nodes (maxHop=4): ${totalNodesVisited}`);
  console.log(`  - Graph Total Nodes: ${files.length}`);
  console.log(`  - Coverage %: ${((totalNodesVisited / files.length) * 100).toFixed(2)}%\n`);
}

console.log('=== 다양한 시드를 통한 폭발 테스트 (Precision 실측) ===\n');

// 1. ISM (Spring Boot)
simulate('project-graph-i', 'PdInfoController');           // Controller
simulate('project-graph-i', 'PdInfoRequest');              // Hub DTO (Fan-in 33)
simulate('project-graph-i', 'CommonPdMgmtServiceImpl');    // Common Service
simulate('project-graph-i', 'ECPDTBISM032TrxMapper');      // Mapper (말단)

// 2. APC (Anyframe)
simulate('project-graph-a', 'ACAOTBAPC101DVO');            // Hub VO (Fan-in 13)
simulate('project-graph-a', 'APCMMSmpySpacdBIZ');          // BIZ
simulate('project-graph-a', 'APCMGSpacdController');       // Controller
