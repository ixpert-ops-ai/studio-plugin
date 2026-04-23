#!/bin/bash

# 인자값(커밋 메시지) 체크
if [ -z "$1" ]; then
    echo "❌ 에러: 커밋 메시지를 입력해주세요."
    echo "사용법: ./merge_to_dev.sh \"메시지\""
    exit 1
fi

COMMIT_MSG=$1
SOURCE_BRANCH="feature/generate_test"
TARGET_BRANCH="develop"

echo "🚀 작업을 $TARGET_BRANCH 브랜치로 병합합니다..."

# 1. 작업 브랜치에서 커밋 및 푸시
git checkout $SOURCE_BRANCH
git add .
git commit -m "$COMMIT_MSG"
git push origin $SOURCE_BRANCH

# 2. develop 브랜치로 이동 및 최신화
# 만약 로컬에 develop이 없다면 생성하고 가져옵니다.
git checkout $TARGET_BRANCH 2>/dev/null || git checkout -b $TARGET_BRANCH
git pull origin $TARGET_BRANCH

# 3. 병합 (충돌 시 develop 브랜치 내용 우선)
echo "🔄 $SOURCE_BRANCH -> $TARGET_BRANCH 병합 중..."
git merge -X ours $SOURCE_BRANCH

# 4. 서버에 푸시
git push origin $TARGET_BRANCH

# 5. 다시 작업 브랜치로 복귀
git checkout $SOURCE_BRANCH

echo "✅ 모든 과정이 완료되었습니다!"