#!/usr/bin/env node
/*
 * Stage0EngineRoutedBaselineHarnessTest 결과 XML 두 개의 GT 기준선 블록을 비교한다.
 *
 * 사용법:
 *   node scripts/compare-harness-blocks.js <A.xml> <B.xml> [--scenarios "이름,이름,..."]
 *       [--exclude-line-prefix "RefinedReq:"]
 *
 * --exclude-line-prefix (반복 가능, 쉼표로 쪼개지 않음; 커밋 4의 `RefinedReq:` 태그 차이를 따로 보고하기 위한 옵션):
 *   - `[GT Baseline: <이름>] ` 라벨 뒤의 내용이 접두어로 시작하는 줄을 양쪽 블록에서 빼고 나머지를 비교한다.
 *     라벨이 없는 줄(Top-30 항목 등)은 제외 대상이 될 수 없다.
 *   - 허용 접두어는 코드 상수 EXCLUDABLE_PREFIXES(현재 `RefinedReq:` 하나)뿐이다.
 *   - `--scenarios`와 함께만 쓸 수 있다(제외 범위를 명시). 양쪽에 있는 각 시나리오 블록에서 접두어와 일치하는 줄이
 *     A와 B 각각 정확히 1줄이어야 한다(0줄이면 prefix not found, 2줄 이상이면 ambiguous로 오류).
 *   - 제외 후 일치하면 MATCH_EXCLUDING(EXACT_MATCH는 제외가 없을 때만), 제외한 줄 수와 줄 원문,
 *     excludedLinesDiffer(제외한 줄이 A와 B에서 다른지)를 블록별로 출력한다.
 *
 * 블록 정의:
 *   - 시작: <system-out>에서 `[GT Baseline: <이름>]`이 처음 나오는 줄 (같은 이름의 연속 줄은 모두 블록 안)
 *   - 끝  : 그 뒤 처음 나오는 `[MockTelemetry:` 줄 (끝 줄 포함)
 *   - 시나리오 키는 `[GT Baseline: <이름>]`의 이름이다 (MockTelemetry 라벨은 이름이 다르다).
 *   - 시작 없이 나오는 `[MockTelemetry:` 줄(시뮬레이션 등)은 무시한다.
 *
 * 에러(종료 코드 2): 같은 이름의 블록이 한 파일에 둘 이상, 블록이 끝나기 전에 다른 이름의 시작,
 *                    끝나지 않은 블록, 읽을 수 없는 입력, 잘못된 인자,
 *                    --exclude-line-prefix 오용(--scenarios 없음, 허용 목록 밖 접두어, 블록에 접두어 줄이 정확히 1줄이 아님).
 * 종료 코드: 비교 대상이 모두 EXACT_MATCH 또는 MATCH_EXCLUDING일 때만 0, 하나라도 아니면 1.
 */
const fs = require("fs");
const crypto = require("crypto");

const START_RE = /\[GT Baseline:\s*([^\]]+)\]/;
const END_RE = /\[MockTelemetry:/;
const LABEL_RE = /^\[GT Baseline:\s*([^\]]+)\]\s?/;
/** 제외할 수 있는 줄 접두어. 다른 접두어(Top-30 TargetFiles Count:, GT Survived:, GT Missing: 등)는 오류로 거부한다. */
const EXCLUDABLE_PREFIXES = ["RefinedReq:"];

function usage(msg) {
  if (msg) console.error("ERROR: " + msg);
  console.error('usage: node scripts/compare-harness-blocks.js <A.xml> <B.xml> [--scenarios "이름,이름,..."] [--exclude-line-prefix "RefinedReq:"]');
  process.exit(2);
}

/** 블록 안에서 라벨을 뗀 내용이 접두어로 시작하는 줄의 인덱스 목록. 라벨이 없는 줄은 포함하지 않는다. */
function findExcludable(block, prefix) {
  const idx = [];
  block.lines.forEach((line, i) => {
    const m = line.match(LABEL_RE);
    if (m && m[1].trim() === block.name && line.slice(m[0].length).startsWith(prefix)) idx.push(i);
  });
  return idx;
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
  const excludePrefixes = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--scenarios") {
      if (i + 1 >= args.length) usage("--scenarios needs a value");
      scenarios = args[++i].split(",").map((x) => x.trim()).filter(Boolean);
      if (scenarios.length === 0) usage("--scenarios is empty");
    } else if (args[i] === "--exclude-line-prefix") {
      if (i + 1 >= args.length) usage("--exclude-line-prefix needs a value");
      excludePrefixes.push(args[++i]);
    } else if (args[i].startsWith("--")) {
      usage("unknown option " + args[i]);
    } else {
      files.push(args[i]);
    }
  }
  if (files.length !== 2) usage("exactly two XML paths are required");
  if (excludePrefixes.length > 0 && scenarios === null) {
    usage("--exclude-line-prefix requires --scenarios (the exclusion scope must be explicit)");
  }
  for (const p of excludePrefixes) {
    if (!EXCLUDABLE_PREFIXES.includes(p)) {
      usage(`--exclude-line-prefix "${p}" is not allowed; allowed: ${EXCLUDABLE_PREFIXES.map((x) => `"${x}"`).join(", ")}`);
    }
  }
  let excludedTotalA = 0;
  let excludedTotalB = 0;
  const excludedScenarios = new Map(); // 접두어 -> 제외가 적용된 시나리오 수

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
    // 제외 줄 수집: 접두어마다 A와 B 각 블록에서 정확히 1줄이어야 한다(아니면 검증 오류, 종료 코드 2)
    const excludedA = new Set();
    const excludedB = new Set();
    const excludedLinesA = [];
    const excludedLinesB = [];
    for (const p of excludePrefixes) {
      const ia = findExcludable(ba, p);
      const ib = findExcludable(bb, p);
      for (const [label, idx, file] of [["A", ia, files[0]], ["B", ib, files[1]]]) {
        if (idx.length === 0) {
          console.error(`ERROR: prefix not found: "${p}" has no line in block "${name}" of ${label} (${file})`);
          process.exit(2);
        }
        if (idx.length > 1) {
          console.error(`ERROR: ambiguous prefix: "${p}" matches ${idx.length} lines in block "${name}" of ${label} (${file}); exactly 1 is required`);
          process.exit(2);
        }
      }
      excludedA.add(ia[0]);
      excludedB.add(ib[0]);
      excludedLinesA.push(ba.lines[ia[0]]);
      excludedLinesB.push(bb.lines[ib[0]]);
      excludedScenarios.set(p, (excludedScenarios.get(p) || 0) + 1);
    }
    excludedTotalA += excludedA.size;
    excludedTotalB += excludedB.size;
    // 제외 후 줄 목록과 원래 인덱스 매핑 (FIRST_DIFF 줄 번호는 제외 전 블록 기준으로 표기)
    const keepA = ba.lines.map((_, i) => i).filter((i) => !excludedA.has(i));
    const keepB = bb.lines.map((_, i) => i).filter((i) => !excludedB.has(i));
    const la = keepA.map((i) => ba.lines[i]);
    const lb = keepB.map((i) => bb.lines[i]);
    const ha = sha256(la);
    const hb = sha256(lb);
    let diff = -1;
    const n = Math.max(la.length, lb.length);
    for (let k = 0; k < n; k++) {
      if (la[k] !== lb[k]) {
        diff = k;
        break;
      }
    }
    const excluding = excludedA.size > 0;
    const printExcluded = () => {
      excludedLinesA.forEach((l) => console.log(`  excluded A: ${l}`));
      excludedLinesB.forEach((l) => console.log(`  excluded B: ${l}`));
    };
    if (diff === -1) {
      if (!excluding) {
        console.log(`[${name}] EXACT_MATCH  lines=${ba.lines.length} sha256A=${ha} sha256B=${hb}`);
      } else {
        const differ = excludedLinesA.join("\n") !== excludedLinesB.join("\n");
        console.log(`[${name}] MATCH_EXCLUDING  excluded=${excludedA.size} (prefix ${excludePrefixes.map((p) => `"${p}"`).join(", ")})  lines=${la.length}  sha256A(excl)=${ha} sha256B(excl)=${hb}  excludedLinesDiffer=${differ}`);
        printExcluded();
      }
    } else {
      ok = false;
      const oa = keepA[diff];
      const ob = keepB[diff];
      const note = excluding ? ` (excluding ${excludePrefixes.map((p) => `"${p}"`).join(", ")})` : "";
      console.log(`[${name}] FIRST_DIFF line ${oa === undefined ? "?" : oa + 1}${note} (A system-out line ${oa === undefined ? "?" : ba.startLine + oa}, B system-out line ${ob === undefined ? "?" : bb.startLine + ob})`);
      console.log(`  A: ${la[diff] === undefined ? "<missing>" : la[diff]}`);
      console.log(`  B: ${lb[diff] === undefined ? "<missing>" : lb[diff]}`);
      console.log(`  sha256A=${ha} sha256B=${hb}`);
      if (excluding) printExcluded();
    }
  }
  if (excludePrefixes.length > 0) {
    console.log(`summary: excluded lines total A=${excludedTotalA} B=${excludedTotalB}; ${[...excludedScenarios].map(([p, c]) => `"${p}" in ${c} scenario(s)`).join(", ")}`);
  }
  process.exit(ok ? 0 : 1);
}

main();
