const fs = require('fs');
const https = require('https');

const transcriptPath = 'C:\\Users\\dffrp\\.gemini\\antigravity\\brain\\e25706e3-1ca4-4206-9f00-e8f085903fdc\\.system_generated\\logs\\transcript_full.jsonl';
const lines = fs.readFileSync(transcriptPath, 'utf8').split('\n');

let userContent = null;
for (const line of lines) {
    if (line.includes('── 메타 정보') && line.includes('req-8')) {
        try {
            const obj = JSON.parse(line);
            if (obj.content && obj.content.includes('── 메타 정보')) {
                userContent = obj.content;
            }
        } catch(e) {}
    }
}

if (!userContent) {
    console.log("Could not find meta info");
    process.exit(1);
}

const sysMatch = userContent.match(/── \[1\] system ──────────────────────────\n([\s\S]*?)\n── \[2\] user/);
const userMatch = userContent.match(/── \[2\] user ──────────────────────────\n([\s\S]*?)\n── 요청 파라미터/);
const toolsMatch = userContent.match(/── 요청 파라미터 ──────────────────────────\n([\s\S]*?)\n── 응답 정보/);

if (!sysMatch || !userMatch || !toolsMatch) {
    console.log("Failed to parse prompt");
    process.exit(1);
}

const sysText = sysMatch[1].trim();
const userText = userMatch[1].trim();
const toolsJsonStr = toolsMatch[1].trim();

let toolsPayload;
try {
    toolsPayload = JSON.parse(toolsJsonStr);
} catch (e) {
    console.log("Failed to parse tools JSON", e);
    process.exit(1);
}

const payload = JSON.stringify({
    model: "Qwen/Qwen3.6-35B-A3B-FP8",
    messages: [
        { role: "system", content: sysText },
        { role: "user", content: userText }
    ],
    tools: toolsPayload.tools || [],
    tool_choice: toolsPayload.tool_choice,
    stream: false,
    temperature: 0.1,
    max_tokens: 4000
});

const options = {
    hostname: 'vllm.ixpertops.cloud',
    port: 443,
    path: '/v1/chat/completions',
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload)
    }
};

console.log("Sending request to vLLM...");
const req = https.request(options, (res) => {
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        console.log("\n=== VLLM RESPONSE ===");
        console.log(data);
    });
});

req.on('error', (e) => {
    console.error(`Problem with request: ${e.message}`);
});

req.write(payload);
req.end();
