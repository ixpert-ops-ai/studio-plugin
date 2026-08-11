// check-apc-biz-fallback.js
const fs = require('fs');

const file = 'C:/Workspace/graph/project-graph-a/project-graph.json';
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const files = Object.values(data.files || {});

const bizNodes = files.filter(f => f.className && f.className.endsWith('BIZ'));
console.log('Total BIZ nodes: ' + bizNodes.length);

let isolatedUsesCount = 0;
let isolatedWithDependedBy = 0;
let isolatedWithDependsOn = 0;

let isolatedBizNodes = [];

for (const biz of bizNodes) {
  const uses = (biz.usesTypes || []).length;
  const usedBy = (biz.usedByTypes || []).length;
  
  if (uses === 0 && usedBy === 0) {
    isolatedUsesCount++;
    const dependsOn = (biz.dependsOn || []).length;
    const dependedBy = (biz.dependedBy || []).length;
    
    if (dependedBy > 0) isolatedWithDependedBy++;
    if (dependsOn > 0) isolatedWithDependsOn++;
    
    isolatedBizNodes.push({ name: biz.className, dependsOn, dependedBy });
  }
}

console.log('--- usesTypes/usedByTypes 기준으로 고립된 BIZ 분석 ---');
console.log('고립 BIZ 수: ' + isolatedUsesCount);
console.log('그 중 dependedBy(상향) 엣지를 가진 BIZ 수: ' + isolatedWithDependedBy + ' (' + ((isolatedWithDependedBy/isolatedUsesCount)*100).toFixed(2) + '%)');
console.log('그 중 dependsOn(하향) 엣지를 가진 BIZ 수: ' + isolatedWithDependsOn + ' (' + ((isolatedWithDependsOn/isolatedUsesCount)*100).toFixed(2) + '%)');

isolatedBizNodes.sort((a,b) => b.dependedBy - a.dependedBy);
console.log('\nTop 10 고립 BIZ (dependedBy 순):');
isolatedBizNodes.slice(0, 10).forEach(b => console.log(' - ' + b.name + ': dependedBy=' + b.dependedBy + ', dependsOn=' + b.dependsOn));
