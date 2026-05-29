package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.ui.Messages
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import com.intellij.openapi.diagnostic.Logger

class AnyframeDetector : ProjectActivity {

    private val logger = Logger.getInstance(AnyframeDetector::class.java)

    override suspend fun execute(project: Project) {
        logger.info("Running Anyframe Detector...")
        // Only run if not already set to ANYFRAME to avoid spamming the user
        val settings = SettingsState.getInstance()
        if (settings.state.frameworkType == FrameworkType.ANYFRAME_AP) {
            logger.info("Project already configured with Anyframe framework. Skipping detection.")
            return
        }

        // We run the heavy check in a background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val detected = checkAnyframeProject(project)
            if (detected) {
                logger.info("Anyframe framework detected in project!")
                ApplicationManager.getApplication().invokeLater {
                    val result = Messages.showYesNoDialog(
                        project,
                        "Anyframe 프레임워크가 감지되었습니다. 메타그래프 분석 설정을 전환하시겠습니까?\n(설정을 전환하면 BIZ, DEM/DQM, SVO/BVO/DVO 및 @ServiceIdMapping 등 Anyframe 고유의 호출 체인이 정밀 정적 분석됩니다.)",
                        "Anyframe 프레임워크 감지",
                        "예 (전환)",
                        "아니오 (Spring Boot 유지)",
                        Messages.getQuestionIcon()
                    )
                    if (result == Messages.YES) {
                        settings.state.frameworkType = FrameworkType.ANYFRAME_AP
                        logger.info("Framework setting changed to ANYFRAME")
                        Messages.showInfoMessage(
                            project,
                            "메타그래프 분석 설정이 Anyframe Enterprise로 변경되었습니다.\n'/metagraph' 명령으로 메타그래프를 다시 생성하세요.",
                            "설정 변경 완료"
                        )
                    }
                }
            } else {
                logger.info("Anyframe framework not detected.")
            }
        }
    }

    private fun checkAnyframeProject(project: Project): Boolean {
        @Suppress("DEPRECATION")
        val projectBaseDir = project.baseDir ?: return false
        
        // Condition 1: Check pom.xml or build.gradle for anyframe-core
        var pomOrGradleHasAnyframe = false
        VfsUtil.iterateChildrenRecursively(projectBaseDir, null) { file ->
            if (file.name == "pom.xml" || file.name == "build.gradle" || file.name == "build.gradle.kts") {
                try {
                    val content = VfsUtil.loadText(file)
                    if (content.contains("anyframe-core") || content.contains("anyframe.")) {
                        pomOrGradleHasAnyframe = true
                        return@iterateChildrenRecursively false // Stop iteration
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            true
        }
        if (pomOrGradleHasAnyframe) return true

        // Condition 2: Check files with *DEM.java or *DQM.java suffix (count >= 3)
        var demOrDqmCount = 0
        VfsUtil.iterateChildrenRecursively(projectBaseDir, null) { file ->
            if (file.name.endsWith("DEM.java") || file.name.endsWith("NDEM.java") || file.name.endsWith("DQM.java") ||
                file.name.endsWith("DEM.kt") || file.name.endsWith("DQM.kt")) {
                demOrDqmCount++
                if (demOrDqmCount >= 3) {
                    return@iterateChildrenRecursively false // Stop iteration
                }
            }
            true
        }
        if (demOrDqmCount >= 3) return true

        // Condition 3: Check import of AbstractDAO or IQueryService in Java/Kotlin files
        var importFound = false
        VfsUtil.iterateChildrenRecursively(projectBaseDir, null) { file ->
            if (file.extension == "java" || file.extension == "kt") {
                try {
                    val content = VfsUtil.loadText(file)
                    if (content.contains("AbstractDAO") || content.contains("IQueryService")) {
                        importFound = true
                        return@iterateChildrenRecursively false
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            true
        }
        
        return importFound
    }
}
