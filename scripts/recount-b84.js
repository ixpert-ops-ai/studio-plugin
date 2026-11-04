// B-84 측정 집계: 변형별 원문 덤프(build/b84_variant_logs/*.txt, MeasureB84Test.variants가 남김)에서 저장·수집 언급을 다시 센다.
// 사용: node scripts/recount-b84.js [덤프 디렉터리]
//  - 같은 문장이 로그 줄과 도구 인자에 두 번 들어가므로 다른 항목에 통째로 들어 있는 항목은 뺀다.
//  - 언급 앞뒤 25자에 부정 표현(없|않|발견되지|확인되지|미확인|아니)이 있으면 "부정문", 없으면 긍정으로 센다.
//  - 긍정은 둘로 나눈다(분류 규칙):
//    * 근거 없는 저장·수집 단정: (1) "조회/저장", "응답 저장", "저장 처리" 같은 서술, 그리고 (2) updateResult를 저장·응답·테이블과 묶은 언급
//      (언급 앞뒤 40자에 저장|수집|응답|테이블|ISSW_SURVEY_R|담당|처리|접근이 있는 것: "응답 조회/저장(… updateResult 등)", "응답 테이블의 updateResult를 담당" 등).
//      survey_admin에서 updateResult는 통계 프로시저 호출이라 응답 저장이 아니다(sql_survey.xml:788-791 `call sp_eb_stat_daily_mt_insert('0')`).
//    * 중립(메서드 이름 나열): updateResult를 그런 낱말 없이 이름만 늘어놓은 언급.
//  - 판정 범위(바뀐 점과 이유): 부정문 판정은 언급 앞뒤 25자 그대로이고, updateResult가 저장·응답과 묶였는지 보는 범위만 25자에서 40자로 넓혔다(TIE_WINDOW).
//    이유: "응답 조회/저장(selectResResultList, selectSubAnswerList, updateResult 등)"처럼 "저장"이 updateResult 앞쪽 먼 곳에 있는 서술을 놓치지 않으려는
//    예방이다. 이 8개 원문에서는 25자와 40자의 결과가 같다(사실 블록 없음 9 + 중립 0, 있음 24 + 중립 1; `TIE_WINDOW=25 node scripts/recount-b84.js`로 확인).
//    1차 분류(합 25 = 단정 18 + 메서드 이름 7)에서 바뀐 것은 범위가 아니라 규칙이다: 1차는 updateResult 토큰을 모두 "메서드 이름"으로 셌고,
//    지금은 저장·응답 낱말과 묶인 것만 단정으로, 이름만 나열한 것만 중립으로 센다.
const fs = require("fs");
const dir = process.argv[2] || "build/b84_variant_logs";
const methodName = /updateResult/g;
const assertion = /조회\s*\/\s*저장|저장\s*\/\s*조회|수집\s*\/\s*저장|저장\s*(처리|로직|담당)|수집\s*(처리|로직|담당)|응답\s*(저장|수집)/g;
const neg = /없|않|발견되지|확인되지|미확인|아니/;
const tiedToStorage = /저장|수집|응답|테이블|ISSW_SURVEY_R|담당|처리|접근/;
const TIE_WINDOW = Number(process.env.TIE_WINDOW || 40); // updateResult가 저장·응답과 묶였는지 보는 범위(앞뒤 글자 수)

function countIn(text, re) {
  let affirm = 0, denied = 0, m;
  re.lastIndex = 0;
  while ((m = re.exec(text))) {
    const w = text.slice(Math.max(0, m.index - 25), m.index + m[0].length + 25);
    if (neg.test(w)) denied++; else affirm++;
  }
  return { affirm, denied };
}

// updateResult 언급을 (저장·응답과 묶인 것, 이름만 나열한 것, 부정문)으로 나눈다
function countMethod(text) {
  let tied = 0, nameOnly = 0, denied = 0, m;
  methodName.lastIndex = 0;
  while ((m = methodName.exec(text))) {
    const w25 = text.slice(Math.max(0, m.index - 25), m.index + m[0].length + 25);
    const w40 = text.slice(Math.max(0, m.index - TIE_WINDOW), m.index + m[0].length + TIE_WINDOW);
    if (neg.test(w25)) denied++;
    else if (tiedToStorage.test(w40)) tied++;
    else nameOnly++;
  }
  return { tied, nameOnly, denied };
}

const rows = [];
for (const f of fs.readdirSync(dir).filter(x => x.endsWith(".txt")).sort()) {
  const parts = fs.readFileSync(dir + "/" + f, "utf8").split("\n---\n");
  const kept = [];
  for (const p of parts) if (!kept.some(k => k.includes(p) || p.includes(k))) kept.push(p);
  const t = kept.join("\n");
  const a = countIn(t, assertion), m = countMethod(t);
  rows.push({ f: f.replace("b84_var_", "").replace(".txt", ""), ungrounded: a.affirm + m.tied, denied: a.denied + m.denied, method: m.nameOnly });
}
console.log("변형".padEnd(24), "근거 없는 단정", "메서드 이름", "부정문");
for (const r of rows) console.log(r.f.padEnd(24), String(r.ungrounded).padEnd(14), String(r.method).padEnd(11), r.denied);
const sum = (k, key) => rows.filter(r => r.f.includes(k)).reduce((s, r) => s + r[key], 0);
console.log("사실 블록 있음 합: 근거 없는 단정", sum("_facts", "ungrounded"), "메서드 이름", sum("_facts", "method"));
console.log("사실 블록 없음 합: 근거 없는 단정", sum("_nofacts", "ungrounded"), "메서드 이름", sum("_nofacts", "method"));
