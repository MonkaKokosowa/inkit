package com.merrythieves.inkit

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.inksdk.ink.InkDefaults
import com.merrythieves.inkit.exporter.ExportManager
import com.merrythieves.inkit.importer.ImportManager
import com.merrythieves.inkit.store.NotebookStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var ink: InkSurfaceView
    private lateinit var notebookStore: NotebookStore

    private lateinit var txtPage: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPen: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnColor: ImageButton
    private lateinit var btnFinger: ImageButton
    private lateinit var btnNew: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var btnBackground: ImageButton
    private lateinit var palette: LinearLayout
    private lateinit var scrollIndicator: View

    private var currentBackgroundIndex = 0
    private var currentNotebookId: String? = null

    // Rainbow + greys
    private val colors = intArrayOf(
        Color.BLACK,
        Color.parseColor("#404040"),
        Color.parseColor("#808080"),
        Color.parseColor("#C0C0C0"),
        Color.RED,
        Color.parseColor("#FF6600"),
        Color.parseColor("#FFCC00"),
        Color.parseColor("#00CC00"),
        Color.parseColor("#00CCCC"),
        Color.BLUE,
        Color.parseColor("#6600CC"),
        Color.parseColor("#CC00CC"),
        Color.parseColor("#1E7B1E")
    )
    private var activeColor: Int = Color.BLACK

    private val saveIdleDelayMs = 2_500L
    private var contentLoaded: Boolean = false
    private var penDisableCount: Int = 0

    private val saveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "canvas-save").apply { priority = Thread.MIN_PRIORITY }
    }
    private val saveRunnable = Runnable { flushSaveAsync() }

    private lateinit var importPdfLauncher: ActivityResultLauncher<Intent>
    private lateinit var importImageLauncher: ActivityResultLauncher<Intent>
    private lateinit var importMultipleLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportPdfLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportImageLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportImagesLauncher: ActivityResultLauncher<Intent>

    // Import Manager
    private lateinit var importManager: ImportManager
    private lateinit var exportManager: ExportManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        notebookStore = NotebookStore.getInstance(this)
        
        // Get current notebook from intent
        currentNotebookId = intent.getStringExtra("NOTEBOOK_ID")
        if (currentNotebookId == null) {
            // If no notebook specified, use the current one from store
            currentNotebookId = notebookStore.getCurrentNotebookId()
            if (currentNotebookId == null) {
                // Create a default notebook
                val notebook = notebookStore.createNotebook(
                    name = "Untitled",
                    coverColor = Color.parseColor("#4285F4")
                )
                currentNotebookId = notebook.id
                notebookStore.setCurrentNotebook(notebook.id)
            }
        }
        notebookStore.setCurrentNotebook(currentNotebookId!!)

        // Register import launchers
        importPdfLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            importManager.handleImportPdfResult(result)
        }
        importImageLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            importManager.handleImportImageResult(result)
        }
        importMultipleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            importManager.handleImportImagesResult(result)
        }

        // Register export launchers
        exportPdfLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            exportManager.handleExportPdfResult(result)
        }
        exportImageLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            exportManager.handleExportImageResult(result)
        }
        exportImagesLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            exportManager.handleExportImagesResult(result)
        }

        exportManager = ExportManager(
            this,
            exportPdfLauncher,
            exportImageLauncher,
            exportImagesLauncher
        )
        importManager = ImportManager(
            this,
            importPdfLauncher,
            importImageLauncher,
            importMultipleLauncher,
            onImportComplete = { refreshAfterImport() }
        )
        importManager.initialize()

        ink = findViewById(R.id.inkSurface)
        txtPage = findViewById(R.id.txtPage)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnPen = findViewById(R.id.btnPen)
        btnEraser = findViewById(R.id.btnEraser)
        btnColor = findViewById(R.id.btnColor)
        btnFinger = findViewById(R.id.btnFinger)
        btnNew = findViewById(R.id.btnNew)
        btnUndo = findViewById(R.id.btnUndo)
        btnMore = findViewById(R.id.btnMore)
        btnBackground = findViewById(R.id.btnBackground)
        palette = findViewById(R.id.colorPalette)
        scrollIndicator = findViewById(R.id.scrollIndicator)

        ink.touchEnabled = false
        ink.setStrokeStyle(InkDefaults.DEFAULT_STROKE_WIDTH_PX, activeColor)

        btnPrev.setOnClickListener { goToPage(getCurrentPageIndex() - 1) }
        btnNext.setOnClickListener { goToPageOrCreateNew() }
        btnPen.setOnClickListener { selectPen() }
        btnEraser.setOnClickListener { selectEraser() }
        btnColor.setOnClickListener { togglePalette() }
        btnFinger.setOnClickListener {
            ink.touchEnabled = !ink.touchEnabled
            updateToolHighlights()
        }
        btnMore.setOnClickListener { showMoreMenu() }
        btnNew.setOnClickListener {
            cancelSaveAndFlush()
            currentNotebookId?.let { notebookId ->
                notebookStore.createPage(notebookId, inheritBackgroundType = currentBackgroundIndex)
                loadCurrentIntoView()
            }
        }
        btnUndo.setOnClickListener {
            if (ink.undo()) updateUndoButton()
        }
        btnBackground.setOnClickListener { showBackgroundPicker() }

        ink.setPageNavListener { dir ->
            val currentIndex = getCurrentPageIndex()
            val nextIdx = currentIndex + dir
            val pageCount = currentNotebookId?.let { notebookStore.getPageCount(it) } ?: 0
            if (nextIdx >= 0 && nextIdx < pageCount) {
                goToPage(nextIdx)
            } else if (dir > 0) {
                btnNew.performClick()
            }
        }
        ink.setScrollListener { y, max -> updateScrollIndicator(y, max) }
        ink.setDirtyListener { schedulePersist() }
        ink.setUndoListener { updateUndoButton() }
        ink.setSurfaceReadyListener {
            if (!contentLoaded) loadCurrentIntoView()
        }

        buildPalette()
        updateToolHighlights()
        updatePageLabel()
        updateUndoButton()

        ink.post { loadCurrentIntoView() }
    }

    // Handle hardware key presses (e.g., volume buttons on e-ink devices)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentNotebookId != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    goToPage(getCurrentPageIndex() - 1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    goToPageOrCreateNew()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        cancelSaveAndFlush()
        super.onPause()
    }

    override fun onDestroy() {
        saveExecutor.shutdown()
        super.onDestroy()
    }

    private fun getCurrentPageIndex(): Int {
        return currentNotebookId?.let { notebookStore.getPageIndex(notebookStore.getCurrentPageId() ?: "") } ?: 0
    }

    private fun goToPage(index: Int) {
        if (index < 0) return
        val currentNotebookId = currentNotebookId ?: return
        val pageCount = notebookStore.getPageCount(currentNotebookId)
        if (index >= pageCount) return
        
        cancelSaveAndFlush()
        val pages = notebookStore.getPagesInNotebook(currentNotebookId)
        if (index < pages.size) {
            notebookStore.setCurrentPage(pages[index].id)
            loadCurrentIntoView()
        }
    }

    private fun goToPageOrCreateNew() {
        val currentNotebookId = currentNotebookId ?: return
        val currentIndex = getCurrentPageIndex()
        val pageCount = notebookStore.getPageCount(currentNotebookId)
        
        if (currentIndex + 1 < pageCount) {
            goToPage(currentIndex + 1)
        } else {
            cancelSaveAndFlush()
            notebookStore.createPage(currentNotebookId, inheritBackgroundType = currentBackgroundIndex)
            loadCurrentIntoView()
        }
    }

    private fun refreshAfterImport() {
        loadCurrentIntoView()
        updatePageLabel()
    }

    private fun loadCurrentIntoView() {
        val w = ink.width
        val h = ink.height
        if (w <= 0 || h <= 0) return
        contentLoaded = false
        
        val currentPage = notebookStore.getCurrentPage()
        if (currentPage == null) {
            // No pages, create one
            currentNotebookId?.let { notebookId ->
                notebookStore.createPage(notebookId, inheritBackgroundType = currentBackgroundIndex)
            }
            contentLoaded = true
            updatePageLabel()
            return
        }
        
        val docHeight = h * ink.documentHeightFactor
        currentBackgroundIndex = currentPage.backgroundType
        
        val content = notebookStore.loadPageContent(currentPage.id, w, docHeight)
        ink.setDocBitmapWithContent(content, currentBackgroundIndex, scrollYReset = 0)
        updateToolHighlights()
        updatePageLabel()
        updateScrollIndicator(0, ink.maxScrollY())
        updateUndoButton()
        contentLoaded = true
    }

    private fun updateUndoButton() {
        val enabled = ink.canUndo()
        btnUndo.isEnabled = enabled
        btnUndo.alpha = if (enabled) 1.0f else 0.35f
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(this, btnMore)
        popup.menu.add(0, 1, 0, "Clear canvas")
        popup.menu.add(0, 2, 1, "Delete page")
        popup.menu.add(0, 3, 2, "-- Export --")
        popup.menu.add(0, 4, 3, "Export current page as PDF")
        popup.menu.add(0, 5, 4, "Export current page as PNG")
        popup.menu.add(0, 6, 5, "Export notebook as PDF")
        popup.menu.add(0, 7, 6, "-- Import --")
        popup.menu.add(0, 8, 7, "Import PDF")
        popup.menu.add(0, 9, 8, "Import image")
        popup.menu.add(0, 10, 9, "Import images (multiple)")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmClear()
                2 -> confirmDelete()
                4 -> exportManager.exportCurrentPageAsPdf()
                5 -> exportManager.exportCurrentPageAsImage()
                6 -> exportManager.exportCurrentNotebookAsPdf()
                8 -> importManager.importPdf()
                9 -> importManager.importImage()
                10 -> importManager.importImages()
            }
            true
        }
        popup.setOnDismissListener { popPenDisable() }
        pushPenDisable()
        popup.show()
    }

    private fun confirmClear() {
        pushPenDisable()
        AlertDialog.Builder(this)
            .setTitle("Clear canvas")
            .setMessage("Erase all ink on this page? This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                ink.clearCurrent()
                cancelSaveAndFlush()
            }
            .setOnDismissListener { popPenDisable() }
            .show()
    }

    private fun confirmDelete() {
        val currentNotebookId = currentNotebookId ?: return
        if (notebookStore.getPageCount(currentNotebookId) == 0) return
        
        pushPenDisable()
        AlertDialog.Builder(this)
            .setTitle("Delete page")
            .setMessage("Delete this page? This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                ink.removeCallbacks(saveRunnable)
                val currentPageId = notebookStore.getCurrentPageId()
                if (currentPageId != null) {
                    notebookStore.deletePage(currentPageId)
                }
                // Load first page or create new if empty
                val pages = notebookStore.getPagesInNotebook(currentNotebookId)
                if (pages.isNotEmpty()) {
                    notebookStore.setCurrentPage(pages[0].id)
                } else {
                    notebookStore.createPage(currentNotebookId, inheritBackgroundType = currentBackgroundIndex)
                }
                loadCurrentIntoView()
            }
            .setOnDismissListener { popPenDisable() }
            .show()
    }

    private fun selectPen() {
        ink.setStrokeStyle(InkDefaults.DEFAULT_STROKE_WIDTH_PX, activeColor)
        updateToolHighlights()
    }

    private fun selectEraser() {
        ink.setEraser(true)
        updateToolHighlights()
    }

    private fun togglePalette() {
        setPaletteVisible(palette.visibility != View.VISIBLE)
    }

    private fun setPaletteVisible(visible: Boolean) {
        val wasVisible = palette.visibility == View.VISIBLE
        if (visible == wasVisible) return
        palette.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) pushPenDisable() else popPenDisable()
    }

    private fun buildPalette() {
        palette.removeAllViews()
        val sizeDp = (resources.displayMetrics.density * 36).toInt()
        val marginDp = (resources.displayMetrics.density * 4).toInt()
        for (c in colors) {
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke((resources.displayMetrics.density * 1).toInt(), Color.BLACK)
                }
                layoutParams = LinearLayout.LayoutParams(sizeDp, sizeDp).apply {
                    marginStart = marginDp; marginEnd = marginDp
                }
                setOnClickListener {
                    activeColor = c
                    ink.setStrokeStyle(InkDefaults.DEFAULT_STROKE_WIDTH_PX, activeColor)
                    setPaletteVisible(false)
                    updateToolHighlights()
                }
            }
            palette.addView(swatch)
        }
    }

    private fun updateToolHighlights() {
        val penActive = !ink.isEraser
        btnPen.background = if (penActive) getDrawable(R.drawable.bg_tool_selected) else null
        btnEraser.background = if (!penActive) getDrawable(R.drawable.bg_tool_selected) else null
        btnFinger.setImageResource(if (ink.touchEnabled) R.drawable.ic_touch_enabled else R.drawable.ic_touch_disabled)
        btnFinger.background = if (ink.touchEnabled) getDrawable(R.drawable.bg_tool_selected) else null
        btnColor.imageTintList = android.content.res.ColorStateList.valueOf(activeColor)
        btnBackground.background = null
    }

    private fun showBackgroundPicker() {
        val options = arrayOf("No background", "Horizontal lines (ruled)", "Dot grid", "Grid")
        pushPenDisable()
        AlertDialog.Builder(this)
            .setTitle("Background")
            .setSingleChoiceItems(options, currentBackgroundIndex) { dialog, which ->
                currentBackgroundIndex = which
                val currentPage = notebookStore.getCurrentPage()
                currentPage?.let { page ->
                    notebookStore.savePageMetadata(page.copy(backgroundType = which))
                }
                ink.setBackground(which)
                updateToolHighlights()
                dialog.dismiss()
            }
            .setOnDismissListener { popPenDisable() }
            .show()
    }

    private fun updatePageLabel() {
        val currentNotebookId = currentNotebookId ?: return
        val total = notebookStore.getPageCount(currentNotebookId)
        val currentIndex = getCurrentPageIndex()
        val current = if (total > 0) currentIndex + 1 else 0
        txtPage.text = "$current/$total"
        btnPrev.isEnabled = currentIndex > 0
        btnNext.isEnabled = true
    }

    private fun updateScrollIndicator(scrollY: Int, maxScrollY: Int) {
        if (maxScrollY <= 0) {
            scrollIndicator.visibility = View.INVISIBLE
            return
        }
        scrollIndicator.visibility = View.VISIBLE
        val viewHeight = ink.height.coerceAtLeast(1)
        val frac = scrollY.toFloat() / maxScrollY.toFloat()
        val travel = (viewHeight - scrollIndicator.height).coerceAtLeast(0)
        scrollIndicator.translationY = (frac * travel)
    }

    private fun pushPenDisable() {
        if (penDisableCount == 0) ink.setOverlayEnabled(false)
        penDisableCount++
    }

    private fun popPenDisable() {
        penDisableCount = (penDisableCount - 1).coerceAtLeast(0)
        if (penDisableCount == 0) ink.setOverlayEnabled(true)
    }

    private fun schedulePersist() {
        ink.removeCallbacks(saveRunnable)
        ink.postDelayed(saveRunnable, saveIdleDelayMs)
    }

    private fun cancelSaveAndFlush() {
        ink.removeCallbacks(saveRunnable)
        flushSaveAsync()
    }

    private fun flushSaveAsync() {
        if (!contentLoaded) {
            Log.i(TAG, "skip save — current page not yet loaded from disk")
            return
        }
        val currentPageId = notebookStore.getCurrentPageId() ?: return
        val w = ink.width
        val h = ink.height
        if (w <= 0 || h <= 0) return
        
        val bmp = ink.peekDocBitmap() ?: return
        
        val snapshot: Bitmap = try {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot failed: ${t.message}")
            return
        }
        
        saveExecutor.execute {
            try {
                notebookStore.savePageContent(currentPageId, snapshot)
                Log.i(TAG, "flushSaveAsync: wrote $currentPageId to disk")
            } finally {
                snapshot.recycle()
            }
        }
    }

    companion object { private const val TAG = "MainActivity" }
}
