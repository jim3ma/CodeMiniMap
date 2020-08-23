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
import com.intellij.openapi.vfs.PersistentFSConstants
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
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
//                            println("sw")
//                            println(sw.start)
//                            println(sw.end)
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

        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f)
        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("SELECTION_BACKGROUND", JBColor.BLUE))

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

        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f)
        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("FIND_SYMBOL", JBColor.ORANGE))

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

        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f)
        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("BOOKMARK_BACKGROUND", JBColor.YELLOW))

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

        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f)
        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("CURRENTLINE_BACKGROUND", JBColor.GREEN))
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
                                "CURRENTLINE_BACKGROUND",
                                JBColor.CYAN
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
//                println(findResult.startOffset)
//                println(findResult.endOffset)
                paintFindSymbol(g, findResult.startOffset, findResult.endOffset)
                findResult = findManager.findString(editor.document.charsSequence, findResult.endOffset, search.findModel, editor.virtualFile);
            }
        }
    }

    private fun paintVCS(g: Graphics2D) {
        // TODO 文件处在VCS控制下，idea的编辑器左侧会出现新增、修改、删除等指示
        // TODO 文件处在VCS控制下，打开文件diff窗口，左右两侧出现CMM供浏览
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
        paintVCS(g)
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
//            buf = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
            buf = UIUtil.createImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        }

        val g = buf!!.createGraphics()

        g.composite = AlphaComposite.getInstance(AlphaComposite.CLEAR)
        g.fillRect(0, 0, width, height)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)

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
        paintVCS(gfx as Graphics2D)
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
