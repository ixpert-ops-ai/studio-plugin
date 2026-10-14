#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

GRADLEW="./gradlew"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

print_header() {
    echo ""
    echo -e "${CYAN}========================================${NC}"
    echo -e "${CYAN}  WuwAgent Studio Plugin Build Tool${NC}"
    echo -e "${CYAN}========================================${NC}"
    echo ""
}

print_usage() {
    print_header
    echo -e "사용법: ${GREEN}./build.sh${NC} ${YELLOW}[command]${NC}"
    echo -e "  command 없이 실행하면 ${GREEN}buildrun${NC} 이 기본으로 실행됩니다."
    echo ""
    echo -e "${YELLOW}명령어:${NC}"
    echo -e "  ${GREEN}build${NC}        전체 빌드 - 기존용 + 2026.2용 zip 2벌 생성"
    echo -e "  ${GREEN}plugin${NC}       플러그인만 빌드 (webview 빌드 포함)"
    echo -e "  ${GREEN}webview${NC}      웹뷰만 빌드"
    echo -e "  ${GREEN}clean${NC}        빌드 캐시 정리"
    echo -e "  ${GREEN}rebuild${NC}      클린 후 전체 빌드"
    echo -e "  ${GREEN}buildrun${NC}     전체 빌드 후 IDE 샌드박스 실행"
    echo -e "  ${GREEN}run${NC}          IDE 샌드박스에서 플러그인 실행"
    echo -e "  ${GREEN}release${NC}      릴리즈 파일 위치 확인"
    echo -e "  ${GREEN}verify${NC}       플러그인 검증 (verifyPlugin)"
    echo -e "  ${GREEN}help${NC}         사용법 출력"
    echo ""
}

log_step() {
    echo -e "${CYAN}[>>]${NC} $1"
}

log_done() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 개발용 1벌 빌드 (기존용만). buildrun 에서 사용
cmd_build_single() {
    log_step "빌드 시작 (기존용 1벌)..."
    $GRADLEW build
    log_done "빌드 완료!"
    cmd_release
}

# 설치파일 생성: 기존용(since-build 243) + IntelliJ 2026.2용(-253) 2벌. 하나라도 누락되면 실패
cmd_build() {
    local release_dir="$PROJECT_DIR/_release"

    # a. 기존 zip을 _release/old/ 로 이동 (같은 이름은 덮어쓰기)
    mkdir -p "$release_dir/old"
    shopt -s nullglob
    local old_zips=("$release_dir"/*.zip)
    shopt -u nullglob
    if [ ${#old_zips[@]} -gt 0 ]; then
        log_step "기존 zip ${#old_zips[@]}개를 _release/old/ 로 이동..."
        mv -f "${old_zips[@]}" "$release_dir/old/"
    fi

    # b. 기존용 빌드
    log_step "[1/2] 기존용 빌드 (since-build 243, 테스트 제외)..."
    $GRADLEW clean build -x test

    # c. 2026.2용 빌드 (platform 값이 patchPluginXml input 이므로 clean 없이도 캐시 재사용 없음)
    log_step "[2/2] 2026.2용 빌드 (-Pplatform=253)..."
    $GRADLEW buildPlugin copyToRelease -Pplatform=253

    # d. 검증: 기존용 1개 + -253 1개
    shopt -s nullglob
    local zips=("$release_dir"/*.zip)
    shopt -u nullglob
    local std=0 p253=0
    for f in "${zips[@]}"; do
        case "$f" in
            *-253.zip) p253=$((p253 + 1)) ;;
            *)         std=$((std + 1)) ;;
        esac
    done
    if [ "$std" -ne 1 ] || [ "$p253" -ne 1 ]; then
        log_error "_release 검증 실패: 기존용 ${std}개, 2026.2용(-253) ${p253}개 (각 1개여야 합니다)"
        exit 1
    fi

    log_done "빌드 완료! (기존용 + 2026.2용)"
    # e. 결과 출력
    cmd_release
}

cmd_plugin() {
    log_step "플러그인 빌드 시작..."
    $GRADLEW buildPlugin
    log_done "플러그인 빌드 완료!"
}

cmd_webview() {
    log_step "웹뷰 빌드 시작..."
    $GRADLEW buildWebview
    log_done "웹뷰 빌드 완료!"
}

cmd_clean() {
    log_step "빌드 캐시 정리 중..."
    $GRADLEW clean
    log_done "정리 완료!"
}

cmd_rebuild() {
    cmd_clean
    cmd_build
}

cmd_buildrun() {
    cmd_build_single
    log_step "IDE 샌드박스에서 플러그인 실행..."
    $GRADLEW runIde
}

cmd_run() {
    log_step "IDE 샌드박스에서 플러그인 실행..."
    $GRADLEW runIde
}

cmd_release() {
    echo ""
    if [ -d "$PROJECT_DIR/_release" ]; then
        local files=$(ls "$PROJECT_DIR/_release"/*.zip 2>/dev/null)
        if [ -n "$files" ]; then
            log_done "릴리즈 파일:"
            for f in $files; do
                local size=$(du -h "$f" | cut -f1)
                echo -e "  ${GREEN}->  ${f}${NC}  (${size})"
            done
        else
            echo -e "  ${YELLOW}_release 폴더에 zip 파일이 없습니다.${NC}"
        fi
    else
        echo -e "  ${YELLOW}_release 폴더가 아직 없습니다. 먼저 build를 실행하세요.${NC}"
    fi
    echo ""
}

cmd_verify() {
    log_step "플러그인 검증 중..."
    $GRADLEW verifyPlugin
    log_done "검증 완료!"
}

# 메인
case "${1:-}" in
    build)    cmd_build ;;
    plugin)   cmd_plugin ;;
    webview)  cmd_webview ;;
    clean)    cmd_clean ;;
    rebuild)  cmd_rebuild ;;
    buildrun) cmd_buildrun ;;
    run)      cmd_run ;;
    release)  cmd_release ;;
    verify)   cmd_verify ;;
    help)     print_usage ;;
    "")       cmd_buildrun ;;
    *)        log_error "알 수 없는 명령어: $1"; echo ""; print_usage ;;
esac
