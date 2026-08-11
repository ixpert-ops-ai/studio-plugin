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
for (const dem of capturedDems) {
    for (const key in data.files) {
        if (key.includes(dem)) {
            allDemPaths.push(key);
            break;
        }
    }
}

const seedSvc = data.files['src/main/java/sc/chn/aps/apc/co/co16/svc/APCCOMbotSvProcsSVC.java'];
let reachedBiz = new Set(seedSvc.dependsOn);

console.log("Seed SVC depends on BIZ nodes:");
for(let b of reachedBiz) {
    console.log("  " + b.split('/').pop());
}

let directlyCalledDems = 0;

for (const demPath of allDemPaths) {
    const demNode = data.files[demPath];
    let calledByOurBiz = [];
    
    for (const parent of demNode.dependedBy || []) {
        if (reachedBiz.has(parent)) {
            calledByOurBiz.push(parent.split('/').pop());
        }
    }
    
    console.log(`\nDEM: ${demPath.split('/').pop()}`);
    if (calledByOurBiz.length > 0) {
        console.log(`  -> Yes, called by our BIZ: ${calledByOurBiz.join(', ')}`);
        directlyCalledDems++;
    } else {
        console.log(`  -> No, NOT called by our direct BIZ nodes.`);
    }
}

console.log(`\nConclusion: Out of ${capturedDems.length} DEMs captured, ${directlyCalledDems} are DIRECTLY called by the BIZ nodes that the seed SVC depends on.`);
