const fs=require('fs'); 
const data=JSON.parse(fs.readFileSync('C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json','utf8')); 

let q=[{path:'src/main/java/sc/chn/aps/apc/co/co16/svc/impl/APCCOMbotSvProcsSVCImpl.java',hop:0, isDown:true}, {path:'src/main/java/sc/chn/aps/apc/co/co16/svc/APCCOMbotSvProcsSVC.java',hop:0, isDown:true}]; 
let visited=new Map();
q.forEach(x => visited.set(x.path, x));
let dems=new Set(); 

while(q.length>0) { 
    let curr=q.shift(); 
    if(curr.hop>=5) continue; 
    let node=data.files[curr.path]; 
    if(!node) continue; 
    
    // Downward (dependsOn)
    let down=node.dependsOn||[]; 
    for(let n of down) { 
        if(!visited.has(n)) { 
            let dn=data.files[n]; 
            if(!dn) continue; 
            
            let allowed=true; 
            if((dn.dependedBy||[]).length>=4) allowed=false; 
            
            // RULE 1 OFF (Applies to all BIZ):
            if(node.fileType === 'BIZ' && (dn.fileType==='DATA_ACCESS' || dn.fileType==='REPOSITORY')) { 
                allowed = true; 
            } 
            
            if(allowed) { 
                let step = {path:n, hop:curr.hop+1, isDown: curr.isDown};
                visited.set(n, step); 
                q.push(step); 
                if(dn.fileType==='DATA_ACCESS') dems.add(n); 
            } 
        } 
    } 
    
    // Upward (dependedBy)
    let up=node.dependedBy||[]; 
    for(let n of up) { 
        if(!visited.has(n)) { 
            let dn=data.files[n]; 
            if(!dn) continue; 
            
            let allowed=true; 
            if((dn.dependedBy||[]).length>=4) allowed=false; 
            
            // RULE 2 ON: Block UPWARD jumps from DATA_ACCESS (unless it's the seed, but hop>0 here)
            if (node.fileType === 'DATA_ACCESS' || node.fileType === 'REPOSITORY') {
                allowed = false;
            }
            
            if(allowed) { 
                let step = {path:n, hop:curr.hop+1, isDown: false};
                visited.set(n, step); 
                q.push(step); 
                if(dn.fileType==='DATA_ACCESS') dems.add(n); 
            } 
        } 
    } 
} 
console.log('Simulation (b) Rule 2 ONLY -> DEMs reached:', dems.size);
if (dems.size > 0 && dems.size <= 20) {
    console.log('Captured DEMs:');
    dems.forEach(d => console.log('  ' + d.split('/').pop()));
}
