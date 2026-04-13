#!/bin/bash

#########################################
# 스크립트 사용방법
# ./git_sync.sh "git 사용법 가이드 문서 추가"


# 1. 인자값(커밋 메시지) 체크
if [ -z "$1" ]; then
    echo "❌ 에러: 커밋 메시지를 입력해주세요."
    echo "사용법: ./git_sync.sh \"커밋 메시지 내용\""
    exit 1
fi

COMMIT_MSG=$1
BRANCH_NAME="feature/generate_test"

echo "🚀 Git 자동화 프로세스를 시작합니다... (Target Branch: $BRANCH_NAME)"

# 2. 작업 브랜치로 이동 및 변경사항 추가
git checkout $BRANCH_NAME
git add .

# 3. 커밋 및 작업 브랜치 푸시
git commit -m "$COMMIT_MSG"
git push origin $BRANCH_NAME

# 4. 메인 브랜치로 이동 후 최신화
git checkout main
git pull origin main

# 5. 병합(Merge) 및 메인 푸시
git merge $BRANCH_NAME
git push origin main

# 6. 다시 작업 브랜치로 복귀
git checkout $BRANCH_NAME

echo "✅ 모든 작업이 완료되었습니다! 현재 브랜치: $(git branch --show-current)"


