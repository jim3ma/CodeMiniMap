package xyz.sleipnir.codeminimap.config

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import javax.swing.JComponent

class ConfigEntry : Configurable {
    private var form: ConfigForm? = null
    private val configService = ServiceManager.getService(ConfigService::class.java)
    private val config = configService.state!!

    override fun isModified(): Boolean {
        return form != null && (
                config.disabled != form!!.isDisabled
                        || config.pixelsPerLine != form!!.pixelsPerLine
                        || config.width != form!!.width
                        || config.cleanRenderStyle != form!!.renderStyle
                        || config.jumpOnMouseDown != form!!.jumpOn
                        || config.widthLocked != form!!.isWidthLocked
                        || config.rightAligned != form!!.alignment
                        || config.viewportColor != form!!.viewportColor
                        || config.showBookmarks != form!!.isShowBookmarks
                        || config.showCurrentLine != form!!.isShowCurrentLine
                        || config.showSelection != form!!.isShowSelection
                        || config.showFindSymbols != form!!.isShowFindSymbols
                        || config.showChanges != form!!.isShowChanges
                        || config.selectionColor != form!!.selectionColor
                        || config.bookmarksColor != form!!.bookmarksColor
                        || config.currentLineColor != form!!.currentLineColor
                        || config.findSymbolsColor != form!!.findSymbolsColor
                        || config.changesColor != form!!.changesColor
                        || config.changesAddColor != form!!.changesAddColor
                        || config.changesDeleteColor != form!!.changesDeleteColor
                        || config.showErrorsAndWarnings != form!!.isShowErrorsAndWarnings
                        || config.errorsColor != form!!.errorsColor
                        || config.warningsColor != form!!.warningsColor
                )
    }

    override fun getDisplayName(): String {
        return "Code MiniMap"
    }

    override fun getHelpTopic(): String? {
        return "Configuration for Code Minimap"
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        if (form == null) return

        config.disabled = form!!.isDisabled
        config.pixelsPerLine = form!!.pixelsPerLine
        config.width = form!!.width
        config.cleanRenderStyle = form!!.renderStyle
        config.jumpOnMouseDown = form!!.jumpOn
        config.widthLocked = form!!.isWidthLocked
        config.rightAligned = form!!.alignment
        config.viewportColor = form!!.viewportColor
        config.showBookmarks = form!!.isShowBookmarks
        config.showCurrentLine = form!!.isShowCurrentLine
        config.showSelection = form!!.isShowSelection
        config.showFindSymbols = form!!.isShowFindSymbols
        config.showChanges = form!!.isShowChanges
        config.selectionColor = form!!.selectionColor
        config.currentLineColor = form!!.currentLineColor
        config.bookmarksColor = form!!.bookmarksColor
        config.findSymbolsColor = form!!.findSymbolsColor
        config.changesColor = form!!.changesColor
        config.changesAddColor = form!!.changesAddColor
        config.changesDeleteColor = form!!.changesDeleteColor
        config.showErrorsAndWarnings = form!!.isShowErrorsAndWarnings
        config.errorsColor = form!!.errorsColor
        config.warningsColor = form!!.warningsColor

        configService.notifyChange()
    }

    override fun createComponent(): JComponent? {
        form = ConfigForm()
        reset()
        return form!!.root
    }

    override fun reset() {
        if (form == null) return

        form!!.isDisabled = config.disabled
        form!!.pixelsPerLine = config.pixelsPerLine
        form!!.width = config.width
        form!!.renderStyle = config.cleanRenderStyle
        form!!.jumpOn = config.jumpOnMouseDown
        form!!.isWidthLocked = config.widthLocked
        form!!.alignment = config.rightAligned
        form!!.viewportColor = config.viewportColor
        form!!.isShowBookmarks = config.showBookmarks
        form!!.isShowCurrentLine = config.showCurrentLine
        form!!.isShowSelection = config.showSelection
        form!!.isShowFindSymbols = config.showFindSymbols
        form!!.isShowChanges = config.showChanges
        form!!.selectionColor = config.selectionColor
        form!!.currentLineColor = config.currentLineColor
        form!!.bookmarksColor = config.bookmarksColor
        form!!.findSymbolsColor = config.findSymbolsColor
        form!!.changesColor = config.changesColor
        form!!.changesAddColor = config.changesAddColor
        form!!.changesDeleteColor = config.changesDeleteColor
        form!!.isShowErrorsAndWarnings = config.showErrorsAndWarnings
        form!!.errorsColor = config.errorsColor
        form!!.warningsColor = config.warningsColor
    }

    override fun disposeUIResources() {
        form = null
    }
}
