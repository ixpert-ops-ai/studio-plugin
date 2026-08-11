const https = require('https');

const payload = JSON.stringify({
    model: "Qwen/Qwen3.6-35B-A3B-FP8",
    messages: [
        { role: "system", content: "당신은 코드 변경 범위 검증자입니다. 아래 요구사항을 구현하기 위해 수정이 필요한 파일 목록을 검증합니다. 반드시 제공된 `submit_verification` 도구를 호출하여 결과를 제출하세요." },
        { role: "user", content: "요구사항: 배너관리 API에 신규컬럼(testValue)을 추가\n후보 파일 목록: 1. BnnrMngtService.java\n2. BnnrMngtController.java" }
    ],
    tools: [
        {
            type: "function",
            function: {
                name: "submit_verification",
                description: "검증된 파일 목록 제출",
                parameters: {
                    type: "object",
                    properties: {
                        fileVerdicts: { type: "array", items: { type: "object", properties: { filePath: { type: "string" }, verdict: { type: "string" } } } }
                    },
                    required: ["fileVerdicts"]
                }
            }
        }
    ],
    tool_choice: { type: "function", function: { name: "submit_verification" } },
    stream: false,
    temperature: 0.1,
    max_tokens: 1000
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

const req = https.request(options, (res) => {
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        console.log(data);
    });
});
req.write(payload);
req.end();
