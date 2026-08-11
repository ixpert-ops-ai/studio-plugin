// apc-arch-check.js
const fs = require('fs');

const file = 'C:/Workspace/graph/project-graph-a/project-graph.json';
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const files = Object.values(data.files || {});

let svo=0, bvo=0, dvo=0, otherVo=0;
files.forEach(f => {
  if((f.fileType && f.fileType.name==='VALUE_TYPE') || (f.className && f.className.endsWith('VO'))) {
    if(f.className.endsWith('SVO')) svo++;
    else if(f.className.endsWith('BVO')) bvo++;
    else if(f.className.endsWith('DVO')) dvo++;
    else otherVo++;
  }
});
console.log('VO Counts -> SVO: ' + svo + ', BVO: ' + bvo + ', DVO: ' + dvo + ', Other: ' + otherVo);

const svcImplNodes = files.filter(f => f.className && f.className.endsWith('SVC'));
console.log('SVC(Impl) nodes: ' + svcImplNodes.length);

const sampleSvc = svcImplNodes.find(s => (s.dependsOn||[]).some(d => d.includes('BIZ')));
if(sampleSvc) {
  console.log('Sample SVC: ' + sampleSvc.className);
  const usesBiz = (sampleSvc.usesTypes||[]).filter(d => d.includes('BIZ'));
  const dependsBiz = (sampleSvc.dependsOn||[]).filter(d => d.includes('BIZ'));
  console.log(' - usesTypes to BIZ: ' + usesBiz.length + ' (' + usesBiz.join(', ') + ')');
  console.log(' - dependsOn to BIZ: ' + dependsBiz.length + ' (' + dependsBiz.slice(0,3).join(', ') + (dependsBiz.length>3?'...':'') + ')');
} else {
  console.log('No SVCImpl found with a dependsOn to BIZ.');
}
