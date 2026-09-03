const fs = require('fs');
const path = require('path');

const HELDOUT_DIR = 'c:/Workspace/DEV-ASSISTANT/IDE-PLUGIN/intelliJ/ai-assistant-plugin/heldout';

function evaluateProject(projectName, actualResultFiles) {
  const gtPath = path.join(HELDOUT_DIR, `${projectName.toLowerCase()}_gt.locked.json`);
  if (!fs.existsSync(gtPath)) {
    throw new Error(`Locked GT file not found: ${gtPath}`);
  }

  const gtData = JSON.parse(fs.readFileSync(gtPath, 'utf8'));
  const coreGt = gtData.scored_gt_core || [];
  const condGt = gtData.scored_gt_conditional || [];

  function normalize(p) {
    return p.replace(/\\/g, '/').replace(/^\/+/, '').trim();
  }

  function matches(gtItem, actualItem) {
    const nGt = normalize(gtItem);
    const nAct = normalize(actualItem);
    const gtBase = nGt.split('/').pop();
    const actBase = nAct.split('/').pop();
    if (gtBase !== actBase) return false;
    const gtParent = nGt.split('/').slice(-3).join('/');
    const actParent = nAct.split('/').slice(-3).join('/');
    return gtParent === actParent || nAct.endsWith(nGt) || nGt.endsWith(nAct);
  }

  const actualNorm = actualResultFiles.map(normalize);

  const coreHits = [];
  const coreMisses = [];
  for (const gt of coreGt) {
    const found = actualNorm.find(a => matches(gt, a));
    if (found) coreHits.push({ gt, actual: found });
    else coreMisses.push(gt);
  }

  const condHits = [];
  const condMisses = [];
  for (const gt of condGt) {
    const found = actualNorm.find(a => matches(gt, a));
    if (found) condHits.push({ gt, actual: found });
    else condMisses.push(gt);
  }

  const allGt = [...coreGt, ...condGt];
  const noiseFiles = actualNorm.filter(a => !allGt.some(gt => matches(gt, a)));

  return {
    project: projectName,
    totalResultFiles: actualResultFiles.length,
    tier1Core: {
      total: coreGt.length,
      hits: coreHits.length,
      recallPct: ((coreHits.length / (coreGt.length || 1)) * 100).toFixed(1) + '%',
      hitDetails: coreHits,
      missDetails: coreMisses
    },
    tier2Conditional: {
      total: condGt.length,
      hits: condHits.length,
      hitDetails: condHits,
      missDetails: condMisses
    },
    noise: {
      count: noiseFiles.length,
      files: noiseFiles
    }
  };
}

module.exports = { evaluateProject };

if (require.main === module) {
  console.log('Held-out Evaluator loaded successfully.');
}
