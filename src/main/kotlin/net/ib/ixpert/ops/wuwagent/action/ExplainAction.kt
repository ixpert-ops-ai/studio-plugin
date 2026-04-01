package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import net.ib.ixpert.ops.wuwagent.agent.ExplainAgent

/**
 * 에디터에서 "Explain This Code" 이벤트를 받아 Agent에게 위임하는 Action 클래스
 */
class ExplainAction : AnAction("Explain This Code", "Explain the selected code using WhatUWant Agent", com.intellij.icons.AllIcons.Actions.IntentionBulb) {
    
    override fun actionPerformed(e: AnActionEvent) {
        // 비즈니스 로직 처리 금지, Context(Project, Editor) 획득
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Agent로 진입
        val agent = ExplainAgent(project, editor)
        agent.execute()
    }

    override fun update(e: AnActionEvent) {
        // Project와 Editor가 모두 활성화된 경우만 동작하도록 세팅
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
}
