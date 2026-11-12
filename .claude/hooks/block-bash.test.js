// block-bash.js 시험(Node 내장 러너). 실행: node --test .claude/hooks/block-bash.test.js
// 시험 문자열이 이 파일 안에 있어서, 이 시험을 Bash로 실행하는 명령줄에는 heredoc·sed -i·git push가 들어 있지 않다.
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const { analyze } = require('./block-bash.js');

const HOOK = path.join(__dirname, 'block-bash.js');
const blocked = (command, tool = 'Bash') => analyze(tool, command).blocked;

test('heredoc: <<, <<-, 따옴표 붙은 구분자, here-string, 연산자 뒤', () => {
  for (const c of [
    "cat <<EOF\nhello\nEOF",
    "cat > f.txt <<'EOF'\nx\nEOF",
    'cat <<-"END"\n\tx\nEND',
    'cat <<<"한 줄"',
    'echo ok && cat <<EOF\nx\nEOF',
    'cd src; python3 - <<PY\nprint(1)\nPY',
  ]) assert.equal(blocked(c), true, c);
});

test('sed 제자리 수정: -i, -i.bak, -ni, -E -i, --in-place, 연산자 뒤, 감싸는 명령', () => {
  for (const c of [
    "sed -i 's/a/b/' f",
    "sed -i.bak 's/a/b/' f",
    "sed -ni 's/a/b/p' f",
    "sed -E -i 's/a/b/' f",
    "sed --in-place 's/a/b/' f",
    "sed --in-place=.bak 's/a/b/' f",
    "cd x && sed -i 's/a/b/' f",
    "ls | xargs sed -i 's/a/b/'",
    "sudo sed -i 's/a/b/' f",
    "FOO=1 sed -i 's/a/b/' f",
    "/usr/bin/sed -i 's/a/b/' f",
  ]) assert.equal(blocked(c), true, c);
});

test('git push: 옵션 사이의 push, -C, -c k=v, 환경변수, 연산자 뒤, 파이프 뒤', () => {
  for (const c of [
    'git push',
    'git push origin feature/explain',
    'git push --force origin main',
    'git push -u origin HEAD',
    'git -C C:/Workspace/x push',
    'git -c user.name=x push origin main',
    'git -c core.editor=true -c user.email=a@b push',
    'git --no-pager push',
    'git --git-dir=.git push',
    'GIT_TRACE=1 git push',
    'env GIT_TRACE=1 git push',
    'echo done && git push',
    'git status; git push',
    'git fetch || git push',
    'echo x | git push',
    'git push --dry-run origin HEAD',
  ]) assert.equal(blocked(c), true, c);
});

test('중첩 문자열: bash/sh -c, eval, powershell/pwsh -Command, -EncodedCommand, cmd /c, $( ), 백틱', () => {
  const encoded = Buffer.from('git push', 'utf16le').toString('base64');
  for (const c of [
    'bash -c "git push"',
    "sh -c 'git push origin main'",
    "bash -lc 'cd x && git push'",
    'eval "git push"',
    'eval git push',
    'powershell -Command "git push"',
    'powershell.exe -NoProfile -Command "git push origin main"',
    'pwsh -c "git push"',
    `powershell -EncodedCommand ${encoded}`,
    'cmd /c git push',
    'cmd.exe /c "git push"',
    'echo $(git push)',
    'echo `git push`',
    'echo "x $(git push) y"',
    "bash -c \"sed -i 's/a/b/' f\"",
    "bash -c \"cat <<EOF\nx\nEOF\"",
    "bash -c \"bash -c 'git push'\"",
  ]) assert.equal(blocked(c), true, c);
});

test('PowerShell 도구: git push와 sed -i만(heredoc 연산자는 PowerShell 문법이 아니다)', () => {
  assert.equal(blocked('git push', 'PowerShell'), true);
  assert.equal(blocked('git -C . push origin main', 'PowerShell'), true);
  assert.equal(blocked('Get-ChildItem; git push', 'PowerShell'), true);
  assert.equal(blocked("sed -i 's/a/b/' f", 'PowerShell'), true);
  assert.equal(blocked('Write-Output "a << b"', 'PowerShell'), false);
  assert.equal(blocked('git status', 'PowerShell'), false);
});

test('허용: 읽기·빌드·시험 명령과, 따옴표 안에 글로만 들어 있는 금지 낱말', () => {
  for (const c of [
    'git status',
    'git log --oneline -5',
    'git diff --stat',
    'git stash push -m wip', // stash push는 원격 push가 아니다
    'git remote -v',
    'git ls-remote origin refs/heads/feature/explain',
    "git commit -q -F msg.txt",
    'git commit -m "docs: git push 규칙과 sed -i, heredoc(<<) 금지를 기록"',
    "printf '%s\\n' \"sed -i 한 건과 <<'EOF' 한 건을 기록\" > msg.txt",
    "grep -rn '<<' src",
    'grep -rn "git push" docs',
    'echo "git push"',
    'echo \'sed -i\'',
    "sed -n '1,5p' file.txt",
    "sed -E 's/a/b/' file.txt",
    './gradlew test --offline --tests "*Foo"',
    'node scripts/ui-check/run.mjs',
    'node --experimental-strip-types --test webview/test/*.test.ts',
    'npm run build',
    'cd "C:/Workspace/x" && ls -la',
    'git log --grep="push" --oneline',
    'rg "<<" -n',
    'cat file | head -5',
    'bash -c "git status"',
    'powershell -Command "Get-Date"',
    'echo $(date)',
    '',
  ]) assert.equal(blocked(c), false, c);
});

test('CLI: 차단은 종료 코드 2와 이유(stderr), 통과는 0, 깨진 입력은 1(차단하지 않는 오류)', () => {
  const run = (input) => spawnSync(process.execPath, [HOOK], { input, encoding: 'utf8' });
  const ok = run(JSON.stringify({ hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_input: { command: 'git status' } }));
  assert.equal(ok.status, 0);
  const blockedPush = run(JSON.stringify({ hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_input: { command: 'git -c a=b push origin main' } }));
  assert.equal(blockedPush.status, 2);
  assert.match(blockedPush.stderr, /차단됨: git push/);
  assert.match(blockedPush.stderr, /규칙:/);
  const blockedSed = run(JSON.stringify({ tool_name: 'Bash', tool_input: { command: "sed -i 's/a/b/' f" } }));
  assert.equal(blockedSed.status, 2);
  assert.match(blockedSed.stderr, /sed -i/);
  const blockedHeredoc = run(JSON.stringify({ tool_name: 'Bash', tool_input: { command: 'cat <<EOF\nx\nEOF' } }));
  assert.equal(blockedHeredoc.status, 2);
  assert.match(blockedHeredoc.stderr, /heredoc/);
  const ps = run(JSON.stringify({ tool_name: 'PowerShell', tool_input: { command: 'git push' } }));
  assert.equal(ps.status, 2);
  assert.equal(run('깨진 입력').status, 1);
  assert.equal(run(JSON.stringify({ tool_name: 'Bash', tool_input: {} })).status, 0); // command가 없으면 통과
  assert.equal(run('').status, 1);
});
