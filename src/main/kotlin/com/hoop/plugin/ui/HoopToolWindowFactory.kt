package com.hoop.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class HoopToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val hoopToolWindow = HoopToolWindow(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(hoopToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }
}