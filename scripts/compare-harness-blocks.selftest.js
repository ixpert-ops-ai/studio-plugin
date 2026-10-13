#!/usr/bin/env node
/*
 * scripts/compare-harness-blocks.js 자체 검증 (Gradle 불필요).
 *
 * 사용법:
 *   node scripts/compare-harness-blocks.selftest.js                       # T1~T13 (메모리에서 만든 XML)
 *   node scripts/compare-harness-blocks.selftest.js --real <기준선.xml> <비교.xml>   # 위 + 실데이터 확인 R1, R2
 *   (--real이 없거나 두 파일 중 하나라도 없으면 R 계열은 SKIP으로 표시하고 T 계열만으로 종료 코드를 정한다)
 *
 * 종료 코드: 실행한 사례가 모두 기대와 같으면 0, 하나라도 다르면 1 (SKIP은 판정에 넣지 않음).
 */
const fs = require("fs");
const os = require("os");
const path = require("path");
const cp = require("child_process");

const TOOL = path.join(__dirname, "compare-harness-blocks.js");
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "cmp-harness-"));
let failed = 0;
let total = 0;

const OPT = ["--exclude-line-prefix", "RefinedReq:"];

/** 실제 블록과 같은 모양: 같은 라벨의 줄 5개(RefinedReq 포함) + 들여쓴 항목 + MockTelemetry 끝 줄 */
function block(name, o = {}) {
  const L = `[GT Baseline: ${name}]`;
  const lines = [];
  if (o.refined !== null) lines.push(`${L} RefinedReq: "${o.refined === undefined ? "r" : o.refined}"`);
  if (o.refinedTwice) lines.push(`${L} RefinedReq: "second"`);
  if (o.fakeUnlabeled) lines.push(`RefinedReq: not a labeled line`);
  lines.push(`${L} Top-30 TargetFiles Count: 14`);
  lines.push(`${L} GT Survived: ${o.survived || "1/8"} (a)`);
  lines.push(`${L} Top-30 List:`);
  lines.push(`    [FP] #1. [MODIFY] src/A.java (Score: 60)`);
  lines.push(`[MockTelemetry: ${name}] PipelineCalls={}, unmatched=0`);
  return lines;
}

function writeXml(file, lines) {
  const p = path.join(TMP, file);
  fs.writeFileSync(p, `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="t"><system-out><![CDATA[${lines.join("\n")}]]></system-out></testsuite>\n`, "utf8");
  return p;
}

function run(args) {
  const r = cp.spawnSync(process.execPath, [TOOL, ...args], { encoding: "utf8" });
  return { status: r.status, out: (r.stdout || "") + (r.stderr || "") };
}

function check(id, desc, r, wantStatus, mustContain = [], mustNotContain = []) {
  total++;
  const problems = [];
  if (r.status !== wantStatus) problems.push(`exit=${r.status} (기대 ${wantStatus})`);
  for (const s of mustContain) if (!r.out.includes(s)) problems.push(`출력에 없음: ${s}`);
  for (const s of mustNotContain) if (r.out.includes(s)) problems.push(`출력에 있으면 안 됨: ${s}`);
  if (problems.length === 0) {
    console.log(`PASS ${id} ${desc}`);
  } else {
    failed++;
    console.log(`FAIL ${id} ${desc}\n  ${problems.join("\n  ")}\n  --- 출력 ---\n${r.out.split("\n").map((l) => "  " + l).join("\n")}`);
  }
}

function lineOf(out, startsWith) {
  return out.split("\n").find((l) => l.startsWith(startsWith)) || "";
}

const X = "X (Intent=LlmOn)";
const Y = "Y (Intent=LlmOn)";
const Z = "Z (Intent=LlmOff)";
const base = writeXml("base.xml", [...block(X), ...block(Y), ...block(Z)]);

// T1 같은 파일끼리
check("T1", "같은 파일끼리 EXACT_MATCH", run([base, base]), 0, [`[${X}] EXACT_MATCH`, `[${Y}] EXACT_MATCH`, `[${Z}] EXACT_MATCH`], ["FIRST_DIFF", "MATCH_EXCLUDING"]);

// T2 제외 대상이 아닌 줄(GT Survived)이 다름 (옵션 없음)
const survDiff = writeXml("surv_diff.xml", [...block(X, { survived: "2/8" }), ...block(Y), ...block(Z)]);
check("T2", "GT Survived 줄이 다르면 FIRST_DIFF", run([base, survDiff]), 1, [`[${X}] FIRST_DIFF`, "GT Survived: 1/8", "GT Survived: 2/8"]);

// T3 RefinedReq 줄만 다름 + 옵션
const refDiff = writeXml("ref_diff.xml", [...block(X, { refined: "r (tag)" }), ...block(Y), ...block(Z)]);
const t3 = run([base, refDiff, "--scenarios", X, ...OPT]);
check("T3", "RefinedReq만 다르고 옵션 사용 → MATCH_EXCLUDING, excludedLinesDiffer=true", t3, 0,
  [`[${X}] MATCH_EXCLUDING`, "excluded=1", "excludedLinesDiffer=true", `excluded A: [GT Baseline: ${X}] RefinedReq: "r"`, `excluded B: [GT Baseline: ${X}] RefinedReq: "r (tag)"`, "summary: excluded lines total A=1 B=1"],
  ["FIRST_DIFF", "EXACT_MATCH"]);

// T4 같은 파일, 옵션 없음
check("T4", "RefinedReq만 다른 파일을 옵션 없이 비교 → FIRST_DIFF", run([base, refDiff, "--scenarios", X]), 1, [`[${X}] FIRST_DIFF`], ["MATCH_EXCLUDING"]);

// T5 RefinedReq는 같고 제외 밖 줄이 다름 + 옵션 → 제외가 가리지 않음
check("T5", "제외 밖 줄이 다르면 옵션을 써도 FIRST_DIFF", run([base, survDiff, "--scenarios", X, ...OPT]), 1,
  [`[${X}] FIRST_DIFF`, "(excluding \"RefinedReq:\")", "GT Survived: 2/8"], ["MATCH_EXCLUDING"]);

// T6 지정한 시나리오 블록에 접두어가 없음
const noRef = writeXml("no_ref.xml", [...block(X, { refined: null }), ...block(Y), ...block(Z)]);
check("T6", "접두어 줄이 없으면 exit 2 prefix not found", run([noRef, noRef, "--scenarios", X, ...OPT]), 2, ["prefix not found", `block "${X}" of A`]);

// T7 블록 안에 접두어 줄이 2개
const twice = writeXml("twice.xml", [...block(X, { refinedTwice: true }), ...block(Y), ...block(Z)]);
check("T7", "접두어 줄이 2개면 exit 2 ambiguous", run([twice, base, "--scenarios", X, ...OPT]), 2, ["ambiguous prefix", "matches 2 lines"]);

// T8 허용 목록 밖 접두어
check("T8", "허용 목록 밖 접두어는 exit 2", run([base, base, "--scenarios", X, "--exclude-line-prefix", "GT Survived:"]), 2, ["is not allowed", "\"RefinedReq:\""]);

// T9 --scenarios 없이 옵션
check("T9", "--scenarios 없이 옵션만 쓰면 exit 2", run([base, base, ...OPT]), 2, ["requires --scenarios"]);

// T10 A에는 접두어 줄이 있고 B에는 없음 (제외 줄 수 불일치)
check("T10", "A에만 접두어 줄이 있으면 exit 2 (B에서 not found)", run([base, noRef, "--scenarios", X, ...OPT]), 2, ["prefix not found", `block "${X}" of B`]);

// T11 블록 X(RefinedReq 다름)와 Y(같음)를 옵션과 한 번에 호출 → 블록별로 excludedLinesDiffer 출력
const t11 = run([base, refDiff, "--scenarios", `${X},${Y}`, ...OPT]);
check("T11", "X(다름)와 Y(같음)를 한 번에 → 둘 다 MATCH_EXCLUDING, 블록별 excludedLinesDiffer", t11, 0,
  [`[${X}] MATCH_EXCLUDING`, `[${Y}] MATCH_EXCLUDING`, "summary: excluded lines total A=2 B=2"]);
total++;
{
  const lx = lineOf(t11.out, `[${X}] MATCH_EXCLUDING`);
  const ly = lineOf(t11.out, `[${Y}] MATCH_EXCLUDING`);
  if (lx.includes("excludedLinesDiffer=true") && ly.includes("excludedLinesDiffer=false")) {
    console.log("PASS T11b X는 excludedLinesDiffer=true, Y는 false");
  } else {
    failed++;
    console.log(`FAIL T11b\n  X: ${lx}\n  Y: ${ly}`);
  }
}
// T11c 누수 확인: --scenarios 밖 블록(Z)의 RefinedReq 차이는 별도 엄격 비교에서 잡힌다
const refDiffZ = writeXml("ref_diff_z.xml", [...block(X), ...block(Y), ...block(Z, { refined: "changed" })]);
check("T11c", "옵션을 쓰지 않은 블록(Z)의 RefinedReq 차이는 엄격 비교에서 FIRST_DIFF(제외가 새지 않음)", run([base, refDiffZ, "--scenarios", Z]), 1, [`[${Z}] FIRST_DIFF`]);
check("T11d", "같은 파일에서 X,Y만 제외 비교하면 Z 차이와 무관하게 통과", run([base, refDiffZ, "--scenarios", `${X},${Y}`, ...OPT]), 0, [`[${X}] MATCH_EXCLUDING`, `[${Y}] MATCH_EXCLUDING`]);

// T12 같은 이름 블록이 두 번 완결되면 duplicate block (기존 동작 유지)
const dup = writeXml("dup.xml", [...block(X), ...block(X), ...block(Y), ...block(Z)]);
check("T12", "같은 이름 블록이 두 번 완결되면 exit 2 duplicate block", run([dup, base]), 2, ["duplicate block"]);
check("T12b", "옵션을 써도 duplicate block은 exit 2", run([dup, base, "--scenarios", X, ...OPT]), 2, ["duplicate block"]);

// T13 같은 라벨의 시작 줄이 5번 반복되는 실제 구조 + 옵션: 중복 오류 없이 정상 비교. 라벨 없는 'RefinedReq:' 줄은 제외 대상이 아니다.
const fake = writeXml("fake.xml", [...block(X, { fakeUnlabeled: true }), ...block(Y), ...block(Z)]);
check("T13", "라벨이 5번 반복되는 블록 + 옵션 → duplicate 오류 없이 MATCH_EXCLUDING(라벨 없는 줄은 제외 안 됨)", run([fake, fake, "--scenarios", X, ...OPT]), 0,
  [`[${X}] MATCH_EXCLUDING`, "excluded=1", "excludedLinesDiffer=false"], ["duplicate block", "ambiguous"]);

// ───────────── 실데이터 확인 R1, R2 (옵션 --real) ─────────────
// R 계열은 XML 경로를 `--real <기준선.xml> <비교.xml>` 인자로만 받는다(하드코딩/환경 변수 없음).
// 인자가 없거나 파일이 없으면 R 계열을 SKIP으로 따로 표시하고 종료 코드는 T 계열만으로 정한다.
const realIdx = process.argv.indexOf("--real");
const R_IDS = "R1a R1b R2-0 R2a R2b R2c R2d";
const realPaths = realIdx === -1 ? [] : [process.argv[realIdx + 1], process.argv[realIdx + 2]];
const realMissing = realPaths.filter((p) => !p || !fs.existsSync(p));
let rSkipped = false;
if (realIdx === -1) {
  rSkipped = true;
  console.log(`SKIP ${R_IDS} (--real 미지정; 종료 코드는 T 계열만으로 결정)`);
} else if (realMissing.length > 0) {
  rSkipped = true;
  console.log(`SKIP ${R_IDS} (파일 없음 또는 인자 누락: ${realMissing.map((p) => p || "<인자 없음>").join(", ")}; 종료 코드는 T 계열만으로 결정)`);
} else {
  const baseline = realPaths[0];
  const after = realPaths[1];
  const LLMON = "survey_admin (Intent=LlmOn),APC DEM Isolated (Intent=LlmOn)";
  const STRICT = [
    "APC Transit Card (Intent=LlmOff)", "survey_admin (Intent=LlmOff)", "ISM Core (Intent=LlmOff)",
    "Ablation-a (survey_admin LlmOff refined + 5 identifier lines)", "Ablation-b (survey_admin LlmOn refined only - no identifiers)",
  ].join(",");
  const strictOk = (r) => (r.out.match(/EXACT_MATCH/g) || []).length === 5;

  // R1 기준선 대 비교 파일(같은 RefinedReq): 제외 그룹은 excludedLinesDiffer=false
  const r1 = run([baseline, after, "--scenarios", LLMON, ...OPT]);
  check("R1a", "실데이터: 기준선 대 비교 파일, 제외 그룹 2종 MATCH_EXCLUDING excludedLinesDiffer=false", r1, 0,
    ["[survey_admin (Intent=LlmOn)] MATCH_EXCLUDING", "[APC DEM Isolated (Intent=LlmOn)] MATCH_EXCLUDING", "excludedLinesDiffer=false", "summary: excluded lines total A=2 B=2"], ["excludedLinesDiffer=true", "FIRST_DIFF"]);
  const r1s = run([baseline, after, "--scenarios", STRICT]);
  total++;
  if (r1s.status === 0 && strictOk(r1s)) console.log("PASS R1b 실데이터: 엄격 그룹 5종 EXACT_MATCH");
  else { failed++; console.log(`FAIL R1b\n${r1s.out}`); }

  // R2 커밋 4를 흉내 낸 합성 사본: 기준선에서 LlmOn 2종의 RefinedReq 태그만 지운다
  const xmlText = fs.readFileSync(baseline, "utf8");
  let replaced = 0;
  const stripped = xmlText.replace(/^(\[GT Baseline: (?:survey_admin|APC DEM Isolated) \(Intent=LlmOn\)\] RefinedReq: ".*?) \(명시된 식별자: [^\n]*\)"$/gm, (_, head) => {
    replaced++;
    return head + '"';
  });
  total++;
  if (replaced === 2) console.log("PASS R2-0 합성 사본: LlmOn RefinedReq 줄 2개에서 태그를 지움");
  else { failed++; console.log(`FAIL R2-0 합성 사본에서 태그를 지운 줄 수=${replaced} (기대 2)`); }
  const synth = path.join(TMP, "baseline_tag_stripped.xml");
  fs.writeFileSync(synth, stripped, "utf8");
  const r2 = run([baseline, synth, "--scenarios", LLMON, ...OPT]);
  check("R2a", "실데이터: 태그만 지운 합성 사본, 제외 그룹 2종 MATCH_EXCLUDING excludedLinesDiffer=true", r2, 0,
    ["[survey_admin (Intent=LlmOn)] MATCH_EXCLUDING", "[APC DEM Isolated (Intent=LlmOn)] MATCH_EXCLUDING", "summary: excluded lines total A=2 B=2"], ["excludedLinesDiffer=false", "FIRST_DIFF"]);
  total++;
  const diffTrue = (r2.out.match(/excludedLinesDiffer=true/g) || []).length;
  if (diffTrue === 2) console.log("PASS R2b excludedLinesDiffer=true가 정확히 2개 블록");
  else { failed++; console.log(`FAIL R2b excludedLinesDiffer=true 개수=${diffTrue} (기대 2)`); }
  check("R2c", "실데이터: 같은 합성 사본을 옵션 없이 비교하면 LlmOn 2종 FIRST_DIFF", run([baseline, synth, "--scenarios", LLMON]), 1,
    ["[survey_admin (Intent=LlmOn)] FIRST_DIFF", "[APC DEM Isolated (Intent=LlmOn)] FIRST_DIFF"], ["MATCH_EXCLUDING"]);
  const r2s = run([baseline, synth, "--scenarios", STRICT]);
  total++;
  if (r2s.status === 0 && strictOk(r2s)) console.log("PASS R2d 실데이터: 합성 사본에서도 엄격 그룹 5종 EXACT_MATCH");
  else { failed++; console.log(`FAIL R2d\n${r2s.out}`); }
}

console.log(`\n${failed === 0 ? "ALL PASSED" : "FAILED"}: ${total - failed}/${total}${rSkipped ? " (R 계열 SKIP: 실데이터 미확인)" : ""}`);
process.exit(failed === 0 ? 0 : 1);
