#!/usr/bin/env node
/*
 * Stage0EngineRoutedBaselineHarnessTest 결과 XML 두 개의 GT 기준선 블록을 비교한다.
 *
 * 사용법:
 *   node scripts/compare-harness-blocks.js <A.xml> <B.xml> [--scenarios "이름,이름,..."]
 *
 * 블록 정의:
 *   - 시작: <system-out>에서 `[GT Baseline: <이름>]`이 처음 나오는 줄 (같은 이름의 연속 줄은 모두 블록 안)
 *   - 끝  : 그 뒤 처음 나오는 `[MockTelemetry:` 줄 (끝 줄 포함)
 *   - 시나리오 키는 `[GT Baseline: <이름>]`의 이름이다 (MockTelemetry 라벨은 이름이 다르다).
 *   - 시작 없이 나오는 `[MockTelemetry:` 줄(시뮬레이션 등)은 무시한다.
 *
 * 에러(종료 코드 2): 같은 이름의 블록이 한 파일에 둘 이상, 블록이 끝나기 전에 다른 이름의 시작,
 *                    끝나지 않은 블록, 읽을 수 없는 입력, 잘못된 인자.
 * 종료 코드: 비교 대상이 모두 EXACT_MATCH일 때만 0, 하나라도 아니면 1.
 */
const fs = require("fs");
const crypto = require("crypto");

const START_RE = /\[GT Baseline:\s*([^\]]+)\]/;
const END_RE = /\[MockTelemetry:/;

function usage(msg) {
  if (msg) console.error("ERROR: " + msg);
  console.error('usage: node scripts/compare-harness-blocks.js <A.xml> <B.xml> [--scenarios "이름,이름,..."]');
  process.exit(2);
}

function decodeEntities(s) {
  return s
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&");
}

/** 모든 <system-out> 본문을 순서대로 이어 붙여 CDATA/엔티티를 푼 텍스트 줄 목록을 돌려준다. */
function readSystemOutLines(path) {
  let xml;
  try {
    xml = fs.readFileSync(path, "utf8");
  } catch (e) {
    usage("cannot read " + path + ": " + e.message);
  }
  const parts = [];
  const re = /<system-out>([\s\S]*?)<\/system-out>/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    let body = m[1];
    if (body.includes("<![CDATA[")) {
      body = body.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_, c) => c);
    } else {
      body = decodeEntities(body);
    }
    parts.push(body);
  }
  if (parts.length === 0) usage("no <system-out> in " + path);
  return parts.join("\n").replace(/\r\n/g, "\n").split("\n");
}

/** 이름 -> { lines, startLine, endLine } (줄 번호는 합쳐진 system-out 기준 1-based) */
function extractBlocks(path) {
  const lines = readSystemOutLines(path);
  const blocks = new Map();
  let cur = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const s = line.match(START_RE);
    if (s) {
      const name = s[1].trim();
      if (cur === null) {
        if (blocks.has(name)) {
          console.error(`ERROR: duplicate block "${name}" in ${path} (second start at system-out line ${i + 1}, first at ${blocks.get(name).startLine})`);
          process.exit(2);
        }
        cur = { name, lines: [line], startLine: i + 1 };
        continue;
      }
      if (cur.name !== name) {
        console.error(`ERROR: block "${cur.name}" (start line ${cur.startLine}) not closed before "${name}" starts at line ${i + 1} in ${path}`);
        process.exit(2);
      }
    }
    if (cur !== null) {
      cur.lines.push(line);
      if (END_RE.test(line)) {
        cur.endLine = i + 1;
        blocks.set(cur.name, cur);
        cur = null;
      }
    }
  }
  if (cur !== null) {
    console.error(`ERROR: block "${cur.name}" starting at line ${cur.startLine} has no [MockTelemetry: line in ${path}`);
    process.exit(2);
  }
  return blocks;
}

function sha256(lines) {
  return crypto.createHash("sha256").update(lines.join("\n"), "utf8").digest("hex");
}

function main() {
  const args = process.argv.slice(2);
  const files = [];
  let scenarios = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--scenarios") {
      if (i + 1 >= args.length) usage("--scenarios needs a value");
      scenarios = args[++i].split(",").map((x) => x.trim()).filter(Boolean);
      if (scenarios.length === 0) usage("--scenarios is empty");
    } else if (args[i].startsWith("--")) {
      usage("unknown option " + args[i]);
    } else {
      files.push(args[i]);
    }
  }
  if (files.length !== 2) usage("exactly two XML paths are required");

  const a = extractBlocks(files[0]);
  const b = extractBlocks(files[1]);
  console.log(`A: ${files[0]} (${a.size} blocks)`);
  console.log(`B: ${files[1]} (${b.size} blocks)`);

  const names = scenarios !== null ? scenarios : [...new Set([...a.keys(), ...b.keys()])];
  let ok = true;
  for (const name of names) {
    const ba = a.get(name);
    const bb = b.get(name);
    if (!ba && !bb) {
      console.log(`[${name}] MISSING_IN_BOTH`);
      ok = false;
      continue;
    }
    if (ba && !bb) {
      console.log(`[${name}] ONLY_IN_A  sha256A=${sha256(ba.lines)}`);
      ok = false;
      continue;
    }
    if (!ba && bb) {
      console.log(`[${name}] ONLY_IN_B  sha256B=${sha256(bb.lines)}`);
      ok = false;
      continue;
    }
    const ha = sha256(ba.lines);
    const hb = sha256(bb.lines);
    let diff = -1;
    const n = Math.max(ba.lines.length, bb.lines.length);
    for (let k = 0; k < n; k++) {
      if (ba.lines[k] !== bb.lines[k]) {
        diff = k;
        break;
      }
    }
    if (diff === -1) {
      console.log(`[${name}] EXACT_MATCH  lines=${ba.lines.length} sha256A=${ha} sha256B=${hb}`);
    } else {
      ok = false;
      console.log(`[${name}] FIRST_DIFF line ${diff + 1} (A system-out line ${ba.startLine + diff}, B system-out line ${bb.startLine + diff})`);
      console.log(`  A: ${ba.lines[diff] === undefined ? "<missing>" : ba.lines[diff]}`);
      console.log(`  B: ${bb.lines[diff] === undefined ? "<missing>" : bb.lines[diff]}`);
      console.log(`  sha256A=${ha} sha256B=${hb}`);
    }
  }
  process.exit(ok ? 0 : 1);
}

main();
