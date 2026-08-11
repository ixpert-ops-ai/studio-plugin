const fs = require('fs');

try {
    const data = JSON.parse(fs.readFileSync('C:\\Workspace\\project-graph_b\\project-graph_b.json', 'utf8'));
    const files = data.files;
    
    const seedPath = "WfrPointDlngServiceImpl"; // wait, the key in map is the class name or path?
    // Let's find the exact path for WfrPointDlngServiceImpl
    let actualSeedPath = "";
    for (const p in files) {
        if (files[p].className === "WfrPointDlngServiceImpl") {
            actualSeedPath = p;
            break;
        }
    }
    
    if (!actualSeedPath) {
        console.log("Seed not found!");
        process.exit(1);
    }
    
    const seedNode = files[actualSeedPath];
    console.log(`Seed: ${actualSeedPath} (Type: ${seedNode.fileType})`);
    
    let visited = new Set();
    visited.add(actualSeedPath);
    
    let currentQueue = [files[actualSeedPath]];
    let nextQueue = [];
    
    let totalNodes = 1;
    
    for (let hop = 1; hop <= 5; hop++) {
        for (const node of currentQueue) {
            if (!node) continue;
            
            // dependsOn (Downward)
            for (const dep of (node.dependsOn || [])) {
                if (!visited.has(dep)) {
                    visited.add(dep);
                    if (files[dep]) {
                        nextQueue.push(files[dep]);
                        totalNodes++;
                    }
                }
            }
            
            // implements
            for (const dep of (node.implements || [])) {
                if (!visited.has(dep)) {
                    visited.add(dep);
                    if (files[dep]) {
                        nextQueue.push(files[dep]);
                        totalNodes++;
                    }
                }
            }
            
            // dependedBy (Upward)
            for (const dep of (node.dependedBy || [])) {
                if (!visited.has(dep)) {
                    // WITHOUT Rule 2
                    visited.add(dep);
                    if (files[dep]) {
                        nextQueue.push(files[dep]);
                        totalNodes++;
                    }
                }
            }
            
            // Same Package (Simplified)
            // ... omitting for basic count unless necessary
        }
        currentQueue = nextQueue;
        nextQueue = [];
    }
    
    console.log(`Total nodes visited WITHOUT Rule 2: ${totalNodes}`);
    fs.writeFileSync('C:\\Workspace\\DEV-ASSISTANT\\IDE-PLUGIN\\intelliJ\\ai-assistant-plugin\\scratch\\ism_without_rule2.txt', Array.from(visited).sort().join('\n'));
    
} catch(e) {
    console.log(e);
}
