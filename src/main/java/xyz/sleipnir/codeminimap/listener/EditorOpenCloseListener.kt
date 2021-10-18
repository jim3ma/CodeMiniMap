package xyz.sleipnir.codeminimap.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import org.jetbrains.annotations.NotNull
import xyz.sleipnir.codeminimap.comps.CodeMiniMapPanel
import xyz.sleipnir.codeminimap.config.Config
import xyz.sleipnir.codeminimap.config.ConfigService
import java.awt.BorderLayout
import javax.swing.JLayeredPane
import javax.swing.JPanel

class EditorOpenCloseListener : FileEditorManagerListener {
    private val logger = Logger.getInstance(javaClass)
    private var config: Config = ServiceManager.getService(ConfigService::class.java).state!!

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val editors = source.allEditors
        for (editor in editors.filter { it is TextEditor }) {
            val lines = (editor as TextEditor).editor.document.lineCount
            if (lines <= config.maxLines) {
                injectCodeMiniMap(editor as TextEditor, source.project)
            }
        }
        super.fileOpened(source, file)
    }

    private fun injectCodeMiniMap(
        textEditor: TextEditor,
        project: @NotNull Project
    ) {
        val panel = getTargetPanel(textEditor) ?: return
        val innerLayout = panel.layout as BorderLayout

        val where = if (config.rightAligned)
            BorderLayout.LINE_END
        else
            BorderLayout.LINE_START

        if (innerLayout.getLayoutComponent(where) == null) {
            val codeMiniMapPanel = CodeMiniMapPanel(textEditor, project)
            panel.add(codeMiniMapPanel, where)
            // Is this really necessary???
            Disposer.register(textEditor, Disposable { panel.remove(codeMiniMapPanel) })
        }
    }

    private fun getTargetPanel(
        textEditor: TextEditor
    ): JPanel? {
        try {
            val outerPanel = textEditor.component as JPanel
            val outerLayout = outerPanel.layout as BorderLayout
            var layoutComponent = outerLayout.getLayoutComponent(BorderLayout.CENTER)

            if (layoutComponent is JBSplitter) {
                // editor is inside firstComponent of a JBSplitter
                val editorComp = layoutComponent.firstComponent as JPanel
                layoutComponent = (editorComp.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
            }

            val pane = layoutComponent as JLayeredPane

            return when {
                pane.componentCount > 1 -> pane.getComponent(1)
                else -> pane.getComponent(0)
            } as JPanel
        } catch (e: ClassCastException) {
            logger.warn("Injection of CodeMiniMap failed.")
            e.printStackTrace()
            return null
        }
    }

}