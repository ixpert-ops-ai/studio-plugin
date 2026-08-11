// check-apc-biz.js
const fs = require('fs');

const file = 'C:/Workspace/graph/project-graph-a/project-graph.json';
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const files = Object.values(data.files || {});

const bizNodes = files.filter(f => f.fileType && f.fileType.name === 'BIZ');
console.log('Total BIZ nodes: ' + bizNodes.length);

let zeroEdges = 0;
let withEdges = [];

for (const biz of bizNodes) {
  const uses = (biz.usesTypes || []).length;
  const usedBy = (biz.usedByTypes || []).length;
  const edges = uses + usedBy;
  if (edges === 0) {
    zeroEdges++;
  } else {
    withEdges.push({name: biz.className, edges, uses, usedBy});
  }
}

console.log('BIZ with 0 edges: ' + zeroEdges + ' (' + ((zeroEdges / bizNodes.length) * 100).toFixed(2) + '%)');
withEdges.sort((a,b) => b.edges - a.edges);

console.log('Top BIZ with edges:');
withEdges.slice(0, 10).forEach(b => console.log(' - ' + b.name + ': ' + b.edges + ' edges (uses: ' + b.uses + ', usedBy: ' + b.usedBy + ')'));
