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
    echo -e "  ${GREEN}build${NC}        전체 빌드 (webview + plugin + release 복사)"
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

cmd_build() {
    log_step "전체 빌드 시작..."
    $GRADLEW build
    log_done "빌드 완료!"
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
    cmd_build
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
