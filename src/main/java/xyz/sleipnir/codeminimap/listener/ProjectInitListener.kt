package xyz.sleipnir.codeminimap.listener

import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class ProjectInitListener : ProjectManagerListener {
    private val injector: EditorOpenCloseListener = EditorOpenCloseListener()

    override fun projectOpened(project: Project) {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, injector)
        super.projectOpened(project)
    }
}