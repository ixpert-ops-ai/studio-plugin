#!/usr/bin/env node
// PreToolUse 훅(Bash, PowerShell): 작업 규칙을 명령 실행 전에 강제한다. 차단하면 종료 코드 2와 이유(stderr)를 돌려주고, Claude에는 그 이유가 전달된다.
//   1) heredoc(<<, <<-, <<<): 파일 내용을 명령줄로 만들지 않는다(Edit/Write만 쓴다)
//   2) sed -i / --in-place: 파일 제자리 수정 금지(Edit/Write만 쓴다)
//   3) git push (git -C … push, git -c k=v push 포함): push는 사용자의 별도 지시 없이는 하지 않는다. 탈출구는 없다.
// 검사 대상: 따옴표 밖의 명령 단어. 따옴표 안의 글(커밋 메시지, printf 인자, grep 패턴)은 명령이 아니므로 검사하지 않는다(오탐 방지).
// 중첩된 문자열도 검사한다: bash/sh/zsh -c "…", eval "…", powershell/pwsh -Command "…", -EncodedCommand(base64), cmd /c …, $( … ), 백틱.
// 한계: 이 훅은 문자열 검사라서 Write로 만든 스크립트를 실행하는 우회(예: 스크립트 파일 안에 git push를 쓰고 bash 스크립트.sh), node -e·python -c 안의 호출, git alias는 막지 못한다.
// 입력 형식(stdin JSON): { tool_name, tool_input: { command }, ... }. 마지막 입력은 디버그용으로 임시 폴더(claude-hook-last-input.json)에 남긴다.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const RULE = '규칙: heredoc(<<)·sed -i·git push는 쓰지 않는다(파일은 Edit/Write로 고치고, push는 사용자의 별도 지시가 있을 때만).';
const MAX_DEPTH = 6;
const WRAPPERS = new Set(['env', 'command', 'exec', 'time', 'nice', 'nohup', 'sudo', 'builtin', 'xargs', 'stdbuf', 'timeout']);
const POSIX_SHELLS = new Set(['bash', 'sh', 'zsh', 'dash', 'ksh', 'fish']);
const POWERSHELLS = new Set(['powershell', 'pwsh', 'powershell.exe', 'pwsh.exe']);

/** 따옴표를 존중해 단어로 나눈다. 따옴표 밖의 연산자(; && || | & 줄바꿈)는 명령 경계가 되고, 따옴표 밖의 << 는 heredoc으로 표시한다. */
function split(command, shell) {
  const segments = [];
  let words = [];
  let cur = '';
  let curQuoted = false; // 현재 단어에 따옴표로 묶인 부분이 있었는가
  let heredoc = false;
  let i = 0;
  const flushWord = () => { if (cur !== '' || curQuoted) words.push({ text: cur, quoted: curQuoted }); cur = ''; curQuoted = false; };
  const flushSegment = () => { flushWord(); if (words.length) segments.push(words); words = []; };
  const esc = shell === 'powershell' ? '`' : '\\';
  while (i < command.length) {
    const c = command[i];
    if (c === "'") { // 작은따옴표: 닫는 따옴표까지 그대로
      const end = command.indexOf("'", i + 1);
      const stop = end < 0 ? command.length : end;
      cur += command.slice(i + 1, stop); curQuoted = true; i = stop + 1; continue;
    }
    if (c === '"') { // 큰따옴표: 이스케이프를 존중한다
      let j = i + 1;
      while (j < command.length && command[j] !== '"') { if (command[j] === esc && j + 1 < command.length) { cur += command[j + 1]; j += 2; } else { cur += command[j]; j++; } }
      curQuoted = true; i = j + 1; continue;
    }
    if (c === esc && i + 1 < command.length && shell !== 'powershell') { cur += command[i + 1]; i += 2; continue; }
    if (c === '<' && command[i + 1] === '<') { heredoc = true; i += 2; while (command[i] === '<' || command[i] === '-') i++; continue; }
    if (c === ';' || c === '|' || c === '&' || c === '\n' || c === '\r') { flushSegment(); i++; while (command[i] === '&' || command[i] === '|') i++; continue; }
    if (c === ' ' || c === '\t') { flushWord(); i++; continue; }
    cur += c; i++;
  }
  flushSegment();
  return { segments, heredoc };
}

/** 따옴표 밖 + 큰따옴표 안의 $( … )와 백틱 안의 문장(중첩 명령 치환). 작은따옴표 안은 치환이 일어나지 않으므로 제외한다. */
function substitutions(command, shell) {
  const found = [];
  let i = 0;
  while (i < command.length) {
    const c = command[i];
    if (c === "'") { const end = command.indexOf("'", i + 1); i = end < 0 ? command.length : end + 1; continue; }
    if (c === '$' && command[i + 1] === '(') {
      let depth = 1; let j = i + 2;
      while (j < command.length && depth > 0) { if (command[j] === '(') depth++; else if (command[j] === ')') depth--; j++; }
      found.push(command.slice(i + 2, j - 1)); i = j; continue;
    }
    if (c === '`' && shell !== 'powershell') {
      const end = command.indexOf('`', i + 1);
      if (end > 0) { found.push(command.slice(i + 1, end)); i = end + 1; continue; }
    }
    i++;
  }
  return found;
}

function decodeEncodedCommand(b64) {
  try { return Buffer.from(b64, 'base64').toString('utf16le'); } catch (e) { return ''; }
}

function isInPlaceSed(args) {
  return args.some(a => !a.quoted && (/^--in-place(=.*)?$/.test(a.text) || /^-[A-Za-z]*i[A-Za-z0-9.]*$/.test(a.text)));
}

/** git의 전역 옵션을 건너뛰고 첫 하위 명령을 찾는다: -C <경로>, -c <k=v>, --git-dir <값> 등. */
function gitSubcommand(args) {
  const takesValue = new Set(['-C', '-c', '--git-dir', '--work-tree', '--namespace', '--super-prefix', '--config-env', '--exec-path']);
  for (let i = 0; i < args.length; i++) {
    const a = args[i].text;
    if (takesValue.has(a)) { i++; continue; }
    if (a.startsWith('-')) continue;
    return a;
  }
  return null;
}

/** 한 명령(단어 목록)을 검사한다. 차단 이유가 있으면 문자열, 없으면 null. */
function checkWords(words, shell, depth) {
  let i = 0;
  // 앞의 환경변수 대입(FOO=1)과 감싸는 명령(env, sudo, time …)을 건너뛴다
  while (i < words.length) {
    const w = words[i].text;
    if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(w) && !words[i].quoted) { i++; continue; }
    if (WRAPPERS.has(w.toLowerCase())) { i++; while (i < words.length && words[i].text.startsWith('-') && !words[i].quoted) i++; continue; }
    break;
  }
  if (i >= words.length) return null;
  const head = words[i].text.replace(/\\/g, '/').split('/').pop().toLowerCase();
  const args = words.slice(i + 1);
  if (head === 'sed' && isInPlaceSed(args)) return 'sed -i(제자리 수정)';
  if (head === 'git' && gitSubcommand(args) === 'push') return 'git push';
  if (head === 'eval') return check(args.map(a => a.text).join(' '), 'bash', depth + 1);
  if (POSIX_SHELLS.has(head)) {
    const k = args.findIndex(a => !a.quoted && /^-[A-Za-z]*c[A-Za-z]*$/.test(a.text));
    if (k >= 0 && args[k + 1]) return check(args[k + 1].text, 'bash', depth + 1);
  }
  if (POWERSHELLS.has(head)) {
    const k = args.findIndex(a => !a.quoted && /^-(c|command)$/i.test(a.text));
    if (k >= 0) return check(args.slice(k + 1).map(a => a.text).join(' '), 'powershell', depth + 1);
    const e = args.findIndex(a => !a.quoted && /^-(e|ec|encodedcommand)$/i.test(a.text));
    if (e >= 0 && args[e + 1]) return check(decodeEncodedCommand(args[e + 1].text), 'powershell', depth + 1);
  }
  if (head === 'cmd' || head === 'cmd.exe') {
    const k = args.findIndex(a => !a.quoted && /^\/[ck]$/i.test(a.text));
    if (k >= 0) return check(args.slice(k + 1).map(a => a.text).join(' '), 'bash', depth + 1);
  }
  return null;
}

/** 명령 문자열 전체를 검사한다. 차단 이유가 있으면 문자열, 없으면 null. */
function check(command, shell, depth) {
  if (typeof command !== 'string' || command.trim() === '' || depth > MAX_DEPTH) return null;
  const { segments, heredoc } = split(command, shell);
  if (heredoc && shell !== 'powershell') return 'heredoc(<<)';
  for (const words of segments) {
    const r = checkWords(words, shell, depth);
    if (r) return r;
  }
  for (const inner of substitutions(command, shell)) {
    const r = check(inner, shell, depth + 1);
    if (r) return r;
  }
  return null;
}

/** 도구 이름에 맞는 셸로 검사한다. 외부에서 쓰는 진입점(시험도 이것을 쓴다). */
function analyze(toolName, command) {
  const shell = /powershell/i.test(String(toolName)) ? 'powershell' : 'bash';
  const reason = check(command, shell, 0);
  return reason ? { blocked: true, reason } : { blocked: false, reason: null };
}

function main() {
  let raw = '';
  try { raw = fs.readFileSync(0, 'utf8'); } catch (e) { raw = ''; }
  try { fs.writeFileSync(path.join(os.tmpdir(), 'claude-hook-last-input.json'), raw, 'utf8'); } catch (e) { /* 디버그 기록 실패는 무시 */ }
  let input;
  try { input = JSON.parse(raw); } catch (e) {
    process.stderr.write('block-bash: 입력 JSON을 읽지 못했습니다(검사 없이 통과): ' + e.message + '\n');
    process.exit(1); // 차단하지 않는 오류: 훅 고장으로 모든 Bash를 막지 않는다
  }
  const command = input && input.tool_input && input.tool_input.command;
  const result = analyze(input && input.tool_name, command);
  if (result.blocked) {
    process.stderr.write('차단됨: ' + result.reason + '. ' + RULE + '\n');
    process.exit(2);
  }
  process.exit(0);
}

if (require.main === module) main();
module.exports = { analyze, split, check };
