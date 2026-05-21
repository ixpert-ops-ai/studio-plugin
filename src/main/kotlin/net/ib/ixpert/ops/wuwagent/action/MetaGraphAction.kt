package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import net.ib.ixpert.ops.wuwagent.service.metagraph.ProjectGraphBuilder

class MetaGraphAction : AnAction("Generate Project MetaGraph", "Analyzes the project and generates project-graph.json", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
        val options = arrayOf("Spring Boot (기본)", "Anyframe Enterprise")
        val initialValue = if (settings.state.frameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME) {
            "Anyframe Enterprise"
        } else {
            "Spring Boot (기본)"
        }

        val selectedIdx = Messages.showChooseDialog(
            project,
            "메타그래프를 분석할 대상 프레임워크를 선택하세요.",
            "대상 프레임워크 선택",
            Messages.getQuestionIcon(),
            options,
            initialValue
        )

        if (selectedIdx == -1) return // Cancelled by user

        val chosenType = if (selectedIdx == 1) {
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME
        } else {
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT
        }
        settings.state.frameworkType = chosenType

        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = "Select Project Root for MetaGraph"
            description = "메타그래프를 생성할 최상위 디렉토리를 선택하세요."
        }
        
        val selectedDir = FileChooser.chooseFile(descriptor, project, project.baseDir)
        if (selectedDir == null) return // Cancelled by user
        
        // 백그라운드 태스크로 메타그래프 생성 실행
        val builder = ProjectGraphBuilder(project, selectedDir)
        
        builder.buildGraphAsync(
            onProgress = { msg -> 
                // 상태 표시줄 갱신 (선택 사항)
            },
            onComplete = { graph ->
                Messages.showInfoMessage(
                    project, 
                    "메타그래프 생성이 완료되었습니다.\n대상 프레임워크: ${graph.frameworkType.displayName}\n총 ${graph.statistics.totalFiles}개 파일, ${graph.statistics.totalRelationships}개 의존성이 분석되었습니다.", 
                    "MetaGraph Generated"
                )
            },
            onError = { error ->
                Messages.showErrorDialog(project, error, "MetaGraph Generation Failed")
            }
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}
