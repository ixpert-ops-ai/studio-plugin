const fs = require('fs');

const apcGraphFile = 'C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json';
const data = JSON.parse(fs.readFileSync(apcGraphFile, 'utf8'));

// The 14 DEMs we captured in the test
const capturedDems = [
    'ACAOTBAPC113DEM',
    'ACAMTBAPC005DEM',
    'ACMBTBAPC006DEM',
    'ACAOTBAPC111DEM',
    'ACMBTBAPC009DEM',
    'ACMBTBAPC017DEM',
    'ACAMTBAPC001DEM',
    'ACMBTBAPC014DEM',
    'ACAOTBAPC013DEM',
    'ACAMTBAPC007DEM',
    'ACMBTBAPC019DEM',
    'ACAOTBAPC018DEM',
    'ACAMTBAPC008DEM',
    'ACMBTBAPC010DEM'
];

let allDemPaths = [];
let reachedBiz = new Set(); for(let biz of data.files[" src/main/java/sc/chn/aps/apc/co/co16/svc/APCCOMbotSvProcsSVC.java\].dependsOn) { reachedBiz.add(biz); } for (const dem of capturedDems) {
    for (const key in data.files) {
        if (key.includes(dem)) {
            allDemPaths.push(key);
            break;
        }
    }
}

console.log("Analyzing 14 DEMs...");

let totalFoundInBiz = 0;

for (const demPath of allDemPaths) {
    const demNode = data.files[demPath];
    if (!demNode) {
        console.log(`Node not found: ${demPath}`);
        continue;
    }
    
    // Check who depends on this DEM
    const dependedBy = demNode.dependedBy || [];
    let calledByBizCount = 0;
    
    console.log(`\nDEM: ${demPath.split('/').pop()}`);
    for (const parent of dependedBy) {
        const parentNode = data.files[parent];
        if (parentNode && parentNode.fileType && parentNode.fileType === 'BIZ') {
            calledByBizCount++;
            console.log(`  <- BIZ: ${parent.split('/').pop()}`);
        }
    }
    if (calledByBizCount > 0) {
        totalFoundInBiz++;
    }
}

console.log(`\nSummary: Out of ${capturedDems.length} DEMs, ${totalFoundInBiz} are DIRECTLY called by at least one BIZ node.`);
