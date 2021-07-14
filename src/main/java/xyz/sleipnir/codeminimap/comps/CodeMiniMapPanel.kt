package xyz.sleipnir.codeminimap.comps

import com.intellij.find.EditorSearchSession
import com.intellij.find.FindManager
import com.intellij.find.FindResult
import com.intellij.ide.bookmarks.Bookmark
import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.ide.bookmarks.BookmarksListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.PersistentFSConstants
import com.jetbrains.rd.util.string.printToString
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import xyz.sleipnir.codeminimap.concurrent.DirtyLock
import xyz.sleipnir.codeminimap.config.Config
import xyz.sleipnir.codeminimap.config.ConfigService
import xyz.sleipnir.codeminimap.renderer.CodeMiniMap
import xyz.sleipnir.codeminimap.renderer.ScrollState
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.HierarchyEvent
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import javax.swing.JPanel

class CodeMiniMapPanel(
    private val textEditor: TextEditor,
    private val project: @NotNull Project
) : JPanel(), Disposable {
    private val editor = textEditor.editor as EditorEx
    private var mapRef = SoftReference<CodeMiniMap>(null)
    private val configService = ServiceManager.getService(ConfigService::class.java)
    private var config: Config = configService.state!!
    private val scrollstate = ScrollState()
    private val scrollbar = Scrollbar(editor, scrollstate)
    private val renderLock = DirtyLock()
    private val updateTask: ReadTask
    private var buf: BufferedImage? = null
    private val softWrappings: MutableList<Int> = ArrayList()

    // Anonymous Listeners that should be cleaned up.
    private val componentListener: ComponentListener
    private val documentListener: DocumentListener
    private val areaListener: VisibleAreaListener
    private val selectionListener: SelectionListener
    private val bookmarksListener: BookmarksListener
    private val caretListener: CaretListener

    init {
        Disposer.register(textEditor, this)
        Disposer.register(this, scrollbar)

        this.addHierarchyListener {
            if (it.changeFlags.and(HierarchyEvent.PARENT_CHANGED.toLong()) != 0L) {
                refresh()
            }
        }

        configService.addOnChange(this::refresh)

        componentListener = object : ComponentAdapter() {
            override fun componentResized(componentEvent: ComponentEvent?) = updateImage()
        }
        editor.contentComponent.addComponentListener(componentListener)

        documentListener = object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {}

            override fun documentChanged(event: DocumentEvent) = updateImage()
        }
        editor.document.addDocumentListener(documentListener)

        val foldListener = object : FoldingListener {
            override fun onFoldProcessingEnd() = updateImage()

            override fun onFoldRegionStateChange(region: FoldRegion) = updateImage()
        }
        editor.foldingModel.addListener(foldListener, this)

        areaListener = VisibleAreaListener {
            scrollstate.recomputeVisible(it.newRectangle)
            repaint()
        }
        editor.scrollingModel.addVisibleAreaListener(areaListener)

        selectionListener = object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = repaint()
        }
        editor.selectionModel.addSelectionListener(selectionListener)

        bookmarksListener = object : BookmarksListener {
            override fun bookmarkAdded(b: Bookmark) {
                updateImage()
                super.bookmarkAdded(b)
            }

            override fun bookmarkRemoved(b: Bookmark) {
                updateImage()
                super.bookmarkRemoved(b)
            }

            override fun bookmarkChanged(b: Bookmark) {
                updateImage()
                super.bookmarkChanged(b)
            }

            override fun bookmarksOrderChanged() {
                updateImage()
                super.bookmarksOrderChanged()
            }
        }
        project.messageBus.connect(this).subscribe(BookmarksListener.TOPIC, bookmarksListener)

        caretListener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                updateImage()
                super.caretPositionChanged(event)
            }
        }
        editor.caretModel.addCaretListener(caretListener)

        updateTask = object : ReadTask() {
            override fun onCanceled(indicator: ProgressIndicator) {
                renderLock.release()
                renderLock.clean()
                updateImageSoon()
            }

            override fun computeInReadAction(indicator: ProgressIndicator) {

                val map = getOrCreateMap() ?: return

                try {
                    scrollstate.computeDimensions(editor, config)
                    map.update(editor, scrollstate, indicator, softWrappings)
                    ApplicationManager.getApplication().invokeLater {
                        scrollstate.recomputeVisible(editor.scrollingModel.visibleArea)
                        repaint()
                    }
                } finally {
                    renderLock.release()
                    if (renderLock.dirty) {
                        renderLock.clean()
                        updateImageSoon()
                    }
                }
            }
        }

        isOpaque = false
        layout = BorderLayout()
        add(scrollbar)

        refresh()
    }

    // the minimap is held by a soft reference so the GC can delete it at any time.
    // if its been deleted and we want it again (active tab) we recreate it.
    private fun getOrCreateMap(): CodeMiniMap? {
        var map = mapRef.get()

        if (map == null) {
            map = CodeMiniMap(configService.state!!)
            mapRef = SoftReference(map)
        }

        return map
    }

    private fun updateImageSoon() = ApplicationManager.getApplication().invokeLater(this::updateImage)

    private val isDisabled: Boolean
        get() = config.disabled || editor.document.textLength > PersistentFSConstants.getMaxIntellisenseFileSize() || editor.document.lineCount < 0

    private fun refresh() {
        updateImage()
        updateSize()
        parent?.revalidate()
    }

    /**
     * Fires off a new task to the worker thread. This should only be called from the ui thread.
     */
    private fun updateImage() {
        if (isDisabled) return
        if (project.isDisposed) return
        if (!renderLock.acquire()) return

        if (editor.softWrapModel.isSoftWrappingEnabled) {
            softWrappings.clear()
            val size = editor.softWrapModel.registeredSoftWraps.size
            if (size > 0) {
                for (i in 0 until size) {
                    val sw = editor.softWrapModel.registeredSoftWraps[i]
                    softWrappings.add(sw.start)
                }
            }
        } else {
            softWrappings.clear()
        }

        ProgressIndicatorUtils.scheduleWithWriteActionPriority(updateTask)
    }

    /**
     * Adjusts the panels size to be a percentage of the total window
     */
    private fun updateSize() {
        preferredSize = if (isDisabled) {
            Dimension(0, 0)
        } else {
            Dimension(config.width, 0)
        }
    }

    private fun paintSelection(g: Graphics2D, startByte: Int, endByte: Int) {
        val start = editor.offsetToVisualPosition(startByte)
        val end = editor.offsetToVisualPosition(endByte)

        val sX = start.column
        val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart
        val eX = end.column + 1
        val eY = end.line * config.pixelsPerLine - scrollstate.visibleStart

        var c: Color
        if (config.selectionColor != null && config.selectionColor.length == 6) {
            c = Color.decode("#" + config.selectionColor)
            c = Color(c.red, c.green, c.blue, 127)
        } else {
            c = Color(0,0,255, 127)
        }

        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("SELECTION_BG", c))

        // Single line is real easy
        if (start.line == end.line) {
            g.fillRect(
                sX,
                sY,
                eX - sX,
                config.pixelsPerLine
            )
        } else {
            // Draw the line leading in
            g.fillRect(sX, sY, width - sX, config.pixelsPerLine)

            // Then the line at the end
            g.fillRect(0, eY, eX, config.pixelsPerLine)

            if (eY + config.pixelsPerLine != sY) {
                // And if there is anything in between, fill it in
                g.fillRect(0, sY + config.pixelsPerLine, width, eY - sY - config.pixelsPerLine)
            }
        }
    }

    private fun paintFindSymbol(g: Graphics2D, startByte: Int, endByte: Int) {
        val start = editor.offsetToVisualPosition(startByte)
        val end = editor.offsetToVisualPosition(endByte)

        val sX = start.column
        val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart
        val eX = end.column + 1
        val eY = end.line * config.pixelsPerLine - scrollstate.visibleStart

        var c: Color
        if (config.findSymbolsColor != null && config.findSymbolsColor.length == 6) {
            c = Color.decode("#" + config.findSymbolsColor)
            c = Color(c.red, c.green, c.blue, 127)
        } else {
            c = Color(255,165,0, 127)
        }

        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("FIND_SYMBOL", c))

        // Single line is real easy
        if (start.line == end.line) {
            g.fillRect(
                sX - 1,
                sY - 1,
                eX - sX + 2,
                config.pixelsPerLine + 2
            )
        } else {
            // Draw the line leading in
            g.fillRect(sX - 1, sY - 1, width - sX + 2, config.pixelsPerLine + 2)

            // Then the line at the end
            g.fillRect(0, eY - 1, eX + 2, config.pixelsPerLine + 2)

            if (eY + config.pixelsPerLine != sY) {
                // And if there is anything in between, fill it in
                g.fillRect(0, sY + config.pixelsPerLine - 1, width + 2, eY - sY - config.pixelsPerLine + 2)
            }
        }
    }

    private fun paintBookmark(g: Graphics2D, bookmarkLine: Int) {
        val offset = editor.logicalPositionToOffset(LogicalPosition(bookmarkLine, 0))
        val start = editor.offsetToVisualPosition(offset)

        val sX = start.column
        val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart

        var c: Color
        if (config.bookmarksColor != null && config.bookmarksColor.length == 6) {
            c = Color.decode("#" + config.bookmarksColor)
            c = Color(c.red, c.green, c.blue, 127)
        } else {
            c = Color(255,255,0, 127)
        }

        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("BOOKMARK_BACKGROUND", c))

        // Single line is real easy
        g.fillRect(
            sX,
            sY,
            config.width,
            config.pixelsPerLine
        )
    }

    private fun paintSelections(g: Graphics2D) {
        for ((index, start) in editor.selectionModel.blockSelectionStarts.withIndex()) {
            paintSelection(g, start, editor.selectionModel.blockSelectionEnds[index])
        }
    }

    private fun paintBookmarks(g: Graphics2D) {
        // 获取当前打开editor的书签
        val bookmarkManager: BookmarkManager = BookmarkManager.getInstance(project)
        val validBookmarks: List<Bookmark> = bookmarkManager.validBookmarks
        val size = validBookmarks.size
        if (size > 0) {
            for (i in size - 1 downTo 0) {
                val validBookmark = validBookmarks[i]
                val document: @Nullable Document? =
                    FileDocumentManager.getInstance().getCachedDocument(validBookmark.file)
                if (document != null && document == editor.document) {
                    // 这个书签在当前打开的editor中，进行绘制
                    paintBookmark(g, validBookmark.line)
                }
            }
        }
    }

    private fun paintCurrentLine(g: Graphics2D) {
        val start = editor.offsetToVisualPosition(editor.caretModel.currentCaret.visualLineStart)

        val sX = start.column
        val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart

        var c: Color
        if (config.currentLineColor != null && config.currentLineColor.length == 6) {
            c = Color.decode("#" + config.currentLineColor)
            c = Color(c.red, c.green, c.blue, 127)
        } else {
            c = Color(0,255,0, 127)
        }

        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("CURRENTLINE_BACKGROUND", c))
        // 当前行是bookmark行，则绘制颜色改为CYAN
        val bookmarkManager: BookmarkManager = BookmarkManager.getInstance(project)
        val validBookmarks: List<Bookmark> = bookmarkManager.validBookmarks
        val size = validBookmarks.size
        if (size > 0) {
            for (i in size - 1 downTo 0) {
                val validBookmark = validBookmarks[i]
                val document: @Nullable Document? =
                    FileDocumentManager.getInstance().getCachedDocument(validBookmark.file)
                if (document != null && document == editor.document) {
                    val offset = editor.logicalPositionToOffset(LogicalPosition(validBookmark.line, 0))
                    val startBookmark = editor.offsetToVisualPosition(offset)
                    if (startBookmark.line == start.line) {
                        g.color = editor.colorsScheme.getColor(
                            ColorKey.createColorKey(
                                "CURRENTLINE_BOOKMARK_BACKGROUND",
                                Color(0, 128, 0, 127)
                            )
                        )
                        break
                    }
                }
            }
        }

        // Single line is real easy
        g.fillRect(
            sX,
            sY,
            config.width,
            config.pixelsPerLine
        )


    }

    private fun paintFindSymbols(g: Graphics2D) {
        val search = EditorSearchSession.get(editor)
        if (search != null && search.findModel != null) {
            val findManager: FindManager = FindManager.getInstance(project)
            var findResult: FindResult =
                findManager.findString(editor.document.charsSequence, 0, search.findModel, editor.virtualFile)
            while (findResult.isStringFound) {
                paintFindSymbol(g, findResult.startOffset, findResult.endOffset)
                findResult = findManager.findString(
                    editor.document.charsSequence,
                    findResult.endOffset,
                    search.findModel,
                    editor.virtualFile
                );
            }
        }
    }

    private fun paintVCSs(g: Graphics2D) {
        val ranges = LineStatusTrackerManager.getInstance(project).getLineStatusTracker(editor.virtualFile)?.getRanges()
        if (ranges != null) {
            for (range in ranges) {
                var type = 0
                if (range.innerRanges != null) {
                    type = 1
                }
                paintVCS(g, range.line1, range.line2, type)
            }
        }
    }

    private class ErrorOrWarning public constructor(
        public val startLine: Int,
        public val endLine: Int,
        public val type: Int
    )

    private fun paintErrorsAndWarnings(g: Graphics2D) {
//        println("-error/warning--------->" + editor.filteredDocumentMarkupModel.allHighlighters.size)
        val errorsAndWarningsMap = hashMapOf<String, ErrorOrWarning>()
        for (rangeHighlighter in editor.filteredDocumentMarkupModel.allHighlighters) {
            if (rangeHighlighter.errorStripeTooltip != null) {
//            println(est)
                if (rangeHighlighter.errorStripeTooltip.printToString().indexOf("group=4") >= 0) { // error
                    val start = editor.offsetToVisualPosition(rangeHighlighter.startOffset)
                    val end = editor.offsetToVisualPosition(rangeHighlighter.endOffset)
//                println("-error--> " + start.line + "," + end.line)
                    val errorOrWarning = ErrorOrWarning(start.line, end.line, 1)
                    errorsAndWarningsMap.put(("" + start.line + "," + end.line), errorOrWarning)
//                paintErrorOrWarning(g, start.line, end.line, 1)
                }
                if (rangeHighlighter.errorStripeTooltip.printToString().indexOf("group=5") >= 0) { // warning
                    val start = editor.offsetToVisualPosition(rangeHighlighter.startOffset)
                    val end = editor.offsetToVisualPosition(rangeHighlighter.endOffset)
//                println("-warning--> " + start.line + "," + end.line)
                    val errorOrWarning = ErrorOrWarning(start.line, end.line, 2)
                    errorsAndWarningsMap.put(("" + start.line + "," + end.line), errorOrWarning)
//                paintErrorOrWarning(g, start.line, end.line, 2)
                }
            }
        }
        for (errorOrWarning in errorsAndWarningsMap.values) {
//            paintErrorOrWarning(g, errorOrWarning.startLine, errorOrWarning.endLine, errorOrWarning.type)
        }
    }

    private fun paintErrorOrWarning(g: Graphics2D, startLine: Int, endLine: Int, type: Int) {
        if (startLine != endLine) {
            val offsetStart = editor.logicalPositionToOffset(LogicalPosition(startLine, 0))
            val offsetEnd = editor.logicalPositionToOffset(LogicalPosition(endLine, 0))
            val start = editor.offsetToVisualPosition(offsetStart)
            val end = editor.offsetToVisualPosition(offsetEnd)

            val sX = 0
            val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart
            val eX = 2
            val eY = end.line * config.pixelsPerLine - scrollstate.visibleStart

            var c: Color

            if (type == 1) {
                if (config.errorsColor != null && config.errorsColor.length == 6) {
                    c = Color.decode("#" + config.errorsColor)
                    c = Color(c.red, c.green, c.blue, 96)
                } else {
                    c = Color(255,0,0, 96)
                }
            } else {
                if (config.warningsColor != null && config.warningsColor.length == 6) {
                    c = Color.decode("#" + config.changesAddColor)
                    c = Color(c.red, c.green, c.blue, 96)
                } else {
                    c = Color(255,255,0, 96)
                }
            }



            g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("ERRORSWARNINGS_BACKGROUND", c))

            // Draw the Rect
            g.fillRect(config.width - eX - eX, sY, eX, eY - sY)

        } else {
            val offsetStart = editor.logicalPositionToOffset(LogicalPosition(startLine, 0))
            val start = editor.offsetToVisualPosition(offsetStart)

            val sX = 0
            val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart - 1
            val eX = 2
            val eY = sY + 1 + config.pixelsPerLine

            var c: Color

            if (type == 1) {
                if (config.errorsColor != null && config.errorsColor.length == 6) {
                    c = Color.decode("#" + config.errorsColor)
                    c = Color(c.red, c.green, c.blue, 96)
                } else {
                    c = Color(255,0,0, 96)
                }
            } else {
                if (config.warningsColor != null && config.warningsColor.length == 6) {
                    c = Color.decode("#" + config.changesAddColor)
                    c = Color(c.red, c.green, c.blue, 96)
                } else {
                    c = Color(255,255,0, 96)
                }
            }

            g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("ERRORSWARNINGS_BACKGROUND", c))

            // Draw the Rect
            g.fillRect(config.width - eX - eX, sY, eX, eY - sY)

        }
    }

    private fun paintVCS(g: Graphics2D, startLine: Int, endLine: Int, type: Int) {
        if (startLine != endLine) {
            val offsetStart = editor.logicalPositionToOffset(LogicalPosition(startLine, 0))
            val offsetEnd = editor.logicalPositionToOffset(LogicalPosition(endLine, 0))
            val start = editor.offsetToVisualPosition(offsetStart)
            val end = editor.offsetToVisualPosition(offsetEnd)

            val sX = 0
            val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart
            val eX = 2
            val eY = end.line * config.pixelsPerLine - scrollstate.visibleStart

            var c: Color

            if (type == 1) {
                if (config.changesColor != null && config.changesColor.length == 6) {
                    c = Color.decode("#" + config.changesColor)
                    c = Color(c.red, c.green, c.blue, 96)
                } else {
                    c = Color(0,0,255, 96)
                }
            } else {
                if (config.changesAddColor != null && config.changesAddColor.length == 6) {
                    c = Color.decode("#" + config.changesAddColor)
                    c = Color(c.red, c.green, c.blue, 96)
                } else {
                    c = Color(0,255,0, 96)
                }
            }



            g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("CHANGES_BACKGROUND", c))

            // Draw the Rect
            g.fillRect(config.width - eX, sY, eX, eY - sY)

        } else {
            val offsetStart = editor.logicalPositionToOffset(LogicalPosition(startLine, 0))
            val start = editor.offsetToVisualPosition(offsetStart)

            val sX = 0
            val sY = start.line * config.pixelsPerLine - scrollstate.visibleStart - 1
            val eX = 2
            val eY = sY + 1 + 2

            var c: Color

                if (config.changesDeleteColor != null && config.changesDeleteColor.length == 6) {
                    c = Color.decode("#" + config.changesDeleteColor)
                    c = Color(c.red, c.green, c.blue, 96)
                } else {
                    c = Color(128,128,128, 96)
                }

            g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("CHANGES_BACKGROUND", c))

            // Draw the Rect
            g.fillRect(config.width - eX, sY, eX, eY - sY)

//            val xPoints = intArrayOf(config.width - eX - (eX - sX) - 2, config.width - eX - (eX - sX) - 2, config.width - eX - 1)
//            val yPoints = intArrayOf(sY - 2, sY + 2, sY)
//
//            val oldStroke = g.stroke
//            g.stroke = BasicStroke(JBUIScale.scale(1).toFloat())
//            g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("CHANGES_BACKGROUND", JBColor.BLUE))
//            g.drawPolygon(xPoints, yPoints, xPoints.size)
//            g.stroke = oldStroke

        }

    }

    private fun codeGlance(g: Graphics2D) {
        // TODO 当鼠标在CMM上移动时，出现对应行附近区域的浮动展示
    }

    private fun paintLast(gfx: Graphics?) {
        val g = gfx as Graphics2D

        if (buf != null) {
            g.drawImage(
                buf,
                0, 0, buf!!.width, buf!!.height,
                0, 0, buf!!.width, buf!!.height,
                null
            )
        }
        if (config.showSelection) {
            paintSelections(g)
        }
        if (config.showBookmarks) {
            paintBookmarks(g)
        }
        if (config.showCurrentLine) {
            paintCurrentLine(g)
        }
        if (config.showFindSymbols) {
            paintFindSymbols(g)
        }
        if (config.showChanges) {
            paintVCSs(g)
        }
        if (config.showErrorsAndWarnings) {
            paintErrorsAndWarnings(g)
        }
        scrollbar.paint(gfx)
    }

    override fun paint(gfx: Graphics?) {
        if (renderLock.locked) {
            paintLast(gfx)
            return
        }

        val minimap = mapRef.get()
        if (minimap == null) {
            updateImageSoon()
            paintLast(gfx)
            return
        }

        if (buf == null || buf?.width!! < width || buf?.height!! < height) {
            // TODO: Add handling for HiDPI scaling and switch back to UIUtil.createImage
            buf = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
//            buf = UIUtil.createImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        }

        val g = buf!!.createGraphics()

        g.composite = AlphaComposite.getInstance(AlphaComposite.CLEAR)
        g.fillRect(0, 0, width, height)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f)

        if (editor.document.textLength != 0) {
            g.drawImage(
                minimap.img,
                0, 0, scrollstate.documentWidth, scrollstate.drawHeight,
                0, scrollstate.visibleStart, scrollstate.documentWidth, scrollstate.visibleEnd,
                null
            )
        }

        if (config.showSelection) {
            paintSelections(gfx as Graphics2D)
        }
        if (config.showBookmarks) {
            paintBookmarks(gfx as Graphics2D)
        }
        if (config.showCurrentLine) {
            paintCurrentLine(gfx as Graphics2D)
        }
        if (config.showFindSymbols) {
            paintFindSymbols(gfx as Graphics2D)
        }
        if (config.showChanges) {
            paintVCSs(gfx as Graphics2D)
        }
        if (config.showErrorsAndWarnings) {
            paintErrorsAndWarnings(gfx as Graphics2D)
        }
        gfx?.drawImage(buf, 0, 0, null)
        scrollbar.paint(gfx)
    }

    override fun dispose() {
        configService.removeOnChange(this::refresh)
        editor.contentComponent.removeComponentListener(componentListener)
        editor.document.removeDocumentListener(documentListener)
        editor.scrollingModel.removeVisibleAreaListener(areaListener)
        editor.selectionModel.removeSelectionListener(selectionListener)
        editor.caretModel.removeCaretListener(caretListener)
        remove(scrollbar)

        mapRef.clear()
    }

}
