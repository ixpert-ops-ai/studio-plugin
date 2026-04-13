# 🚀 Git Branch & Merge 가이드

이 문서는 새로운 기능을 안전하게 개발하고, 검증된 코드를 `main` 브랜치에 통합하는 표준 절차를 안내합니다.

## 1. 새로운 브랜치 생성 및 이동
작업을 시작하기 전, 항상 새로운 브랜치를 생성하여 원본 코드를 보호합니다.

```bash
# 브랜치 생성과 동시에 이동
git checkout -b feature/기능이름
- 예) git checkout -b feature/chat
- 예) git checkout -b feature/explain
- 예) git checkout -b feature/generate_test
- 예) git checkout -b feature/impact
- 예) git checkout -b feature/improve
- 예) git checkout -b feature/intent
- 예) git checkout -b feature/review
```

## 2. 작업 내용 저장 (Commit)
수정된 내용을 로컬 저장소에 기록합니다.

```bash
# 1. 변경된 모든 파일을 스테이징 영역에 추가
git add .

# 2. 커밋 메시지와 함께 저장
git commit -m "feat: 구현한 기능에 대한 간략한 설명"
```

## 3. 원격 저장소에 업로드 (Push)
내 컴퓨터의 작업을 GitHub 서버로 보냅니다.

```bash
# 처음 브랜치를 올릴 때 (이후에는 git push만 입력 가능)
git push origin feature/기능이름

- 예) git push origin feature/chat
- 예) git push origin feature/explain
- 예) git push origin feature/generate_test
- 예) git push origin feature/impact
- 예) git push origin feature/improve
- 예) git push origin feature/intent
- 예) git push origin feature/review

```

## 4. 메인 브랜치로 병합 (Merge)
작업이 완료된 브랜치를 `main` 브랜치에 통합합니다.

### 방법 A: 로컬 터미널에서 병합 (직접 병합)
1. **메인 브랜치로 이동:** `git checkout main`
2. **최신 상태 업데이트:** `git pull origin main`
3. **병합 실행:** `git merge feature/기능이름`
- 예) git merge feature/chat
- 예) git merge feature/explain
- 예) git merge feature/generate_test
- 예) git merge feature/impact
- 예) git merge feature/improve
- 예) git merge feature/intent
- 예) git merge feature/review
4. **결과 반영:** `git push origin main`

### 방법 B: GitHub Pull Request 사용 (권장 ⭐)
1. GitHub 저장소 페이지 접속
2. "Compare & pull request" 버튼 클릭
3. 코드 리뷰 진행 후 "Merge pull request" 클릭

## 5. 작업 종료 후 브랜치 삭제
병합이 완료된 브랜치는 깔끔하게 삭제하여 관리합니다.

```bash
# 로컬 브랜치 삭제
git branch -d feature/기능이름

# 원격 브랜치 삭제
git push origin --delete feature/기능이름
```

---

### 💡 주의사항: 충돌(Conflict) 해결
병합 중 동일한 파일의 같은 위치가 수정되어 충돌이 발생하면, 에러 메시지가 나타납니다. 이때는 IDE(IntelliJ 등)의 **Merge Tool**을 사용하여 남길 코드를 선택한 후, 다시 `add` - `commit` 과정을 거쳐 병합을 완료하세요.