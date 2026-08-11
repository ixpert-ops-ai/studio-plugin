import json
import urllib.request
import re

transcript_path = r'C:\Users\dffrp\.gemini\antigravity\brain\e25706e3-1ca4-4206-9f00-e8f085903fdc\.system_generated\logs\transcript_full.jsonl'

with open(transcript_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Extract the block from the last user message
last_user_idx = content.rfind('── 메타 정보')
if last_user_idx == -1:
    print("Could not find meta info")
    exit(1)

log_block = content[last_user_idx:]

sys_match = re.search(r'── \[1\] system ──────────────────────────\n(.*?)\n── \[2\] user', log_block, re.DOTALL)
user_match = re.search(r'── \[2\] user ──────────────────────────\n(.*?)\n── 요청 파라미터', log_block, re.DOTALL)
tools_match = re.search(r'── 요청 파라미터 ──────────────────────────\n(.*?)\n── 응답 정보', log_block, re.DOTALL)

if not sys_match or not user_match or not tools_match:
    print("Failed to parse prompt")
    exit(1)

sys_text = sys_match.group(1).strip()
user_text = user_match.group(1).strip()
tools_json_str = tools_match.group(1).strip()

try:
    tools_payload = json.loads(tools_json_str)
except Exception as e:
    print("Failed to parse tools JSON", e)
    exit(1)

payload = {
    "model": "Qwen/Qwen3.6-35B-A3B-FP8",
    "messages": [
        {"role": "system", "content": sys_text},
        {"role": "user", "content": user_text}
    ],
    "tools": tools_payload.get("tools", []),
    "tool_choice": tools_payload.get("tool_choice"),
    "stream": False,
    "temperature": 0.1,
    "max_tokens": 4000
}

url = "https://vllm.ixpertops.cloud/v1/chat/completions"
req = urllib.request.Request(url, data=json.dumps(payload).encode('utf-8'), headers={'Content-Type': 'application/json'})

print("Sending request to vLLM...")
try:
    with urllib.request.urlopen(req, timeout=120) as response:
        res_body = response.read().decode('utf-8')
        print("\n=== VLLM RESPONSE ===")
        print(res_body)
except Exception as e:
    print(f"Error connecting to vLLM: {e}")
