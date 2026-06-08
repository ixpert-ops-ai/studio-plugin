package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

object ScanExclusionUtil {
    val EXCLUDED_DIRS = setOf(
        ".metadata", "node_modules", ".git", ".idea", ".gradle",
        "build", "dist", "target", ".DS_Store", "out",
        ".settings", ".classpath", ".project", ".mvn", "bin",
        "vendor", "lib", "libs", "bower_components",
        "test", "tests", "__tests__", "spec"
    )

    /**
     * 특정 디렉토리/파일 이름 또는 경로가 스캔 제외 대상인지 검사합니다.
     * @param name 파일/디렉토리 이름 (예: "node_modules")
     * @param path 전체 또는 상대 경로 (예: "C:/path/to/node_modules/...")
     */
    fun shouldExclude(name: String, path: String): Boolean {
        if (EXCLUDED_DIRS.contains(name)) return true

        val normalizedPath = path.replace("\\", "/")
        return EXCLUDED_DIRS.any { excluded ->
            normalizedPath.contains("/$excluded/") || normalizedPath.endsWith("/$excluded")
        }
    }
}
