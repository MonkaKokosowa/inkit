package com.merrythieves.inkit.exporter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.merrythieves.inkit.R
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.model.Page
import com.merrythieves.inkit.store.NotebookStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages export operations with user interaction.
 * 
 * Handles SAF (Storage Access Framework) for file/directory selection
 * and coordinates between different export types.
 */
class ExportManager(
    private val activity: Activity,
    private val exportPdfLauncher: ActivityResultLauncher<Intent>,
    private val exportImageLauncher: ActivityResultLauncher<Intent>,
    private val exportImagesLauncher: ActivityResultLauncher<Intent>
) {
    
    private val notebookStore: NotebookStore = NotebookStore.getInstance(activity)
    
    // Pending export operations
    private var pendingExport: PendingExport? = null
    
    /**
     * Initialize the export manager.
     * Call this from onCreate() of your activity.
     */
    fun initialize() {
        // Launchers are already registered by the activity
        // We just use them here
    }
    
    /**
     * Export current page as PDF.
     */
    fun exportCurrentPageAsPdf(pdfConfig: PdfExporter.PdfConfig = PdfExporter.PdfConfig.DEFAULT) {
        val currentPage = notebookStore.getCurrentPage()
        val currentNotebook = notebookStore.getCurrentNotebook()
        
        if (currentPage == null || currentNotebook == null) {
            Toast.makeText(activity, "No page selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        pendingExport = PendingExport(
            type = ExportType.PAGE_PDF,
            notebook = currentNotebook,
            page = currentPage,
            pdfConfig = pdfConfig
        )
        
        launchPdfExport("${currentNotebook.name}_page_${notebookStore.getPageIndex(currentPage.id) + 1}.pdf")
    }
    
    /**
     * Export current notebook as PDF.
     */
    fun exportCurrentNotebookAsPdf(pdfConfig: PdfExporter.PdfConfig = PdfExporter.PdfConfig.DEFAULT) {
        val currentNotebook = notebookStore.getCurrentNotebook()
        
        if (currentNotebook == null) {
            Toast.makeText(activity, "No notebook selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        pendingExport = PendingExport(
            type = ExportType.NOTEBOOK_PDF,
            notebook = currentNotebook,
            pdfConfig = pdfConfig
        )
        
        launchPdfExport("${currentNotebook.name}.pdf")
    }
    
    /**
     * Export current page as image.
     */
    fun exportCurrentPageAsImage(options: ImageExporter.ExportOptions = ImageExporter.ExportOptions.DEFAULT_PNG) {
        val currentPage = notebookStore.getCurrentPage()
        val currentNotebook = notebookStore.getCurrentNotebook()
        
        if (currentPage == null || currentNotebook == null) {
            Toast.makeText(activity, "No page selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        pendingExport = PendingExport(
            type = ExportType.PAGE_IMAGE,
            notebook = currentNotebook,
            page = currentPage,
            imageOptions = options
        )
        
        val extension = if (options.format == ImageExporter.ImageFormat.PNG) ".png" else ".jpg"
        launchImageExport(
            "${currentNotebook.name}_page_${notebookStore.getPageIndex(currentPage.id) + 1}$extension"
        )
    }
    
    /**
     * Export current notebook pages as individual images.
     */
    fun exportCurrentNotebookAsImages(options: ImageExporter.ExportOptions = ImageExporter.ExportOptions.DEFAULT_PNG) {
        val currentNotebook = notebookStore.getCurrentNotebook()
        
        if (currentNotebook == null) {
            Toast.makeText(activity, "No notebook selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        pendingExport = PendingExport(
            type = ExportType.NOTEBOOK_IMAGES,
            notebook = currentNotebook,
            imageOptions = options
        )
        
        launchDirectoryExport(currentNotebook.name)
    }
    
    /**
     * Launch PDF export dialog.
     */
    private fun launchPdfExport(defaultFileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, defaultFileName)
        }
        exportPdfLauncher.launch(intent)
    }
    
    /**
     * Launch image export dialog.
     */
    private fun launchImageExport(defaultFileName: String) {
        val mimeType = when (pendingExport?.imageOptions?.format) {
            ImageExporter.ImageFormat.JPEG -> "image/jpeg"
            else -> "image/png"
        }
        
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, defaultFileName)
        }
        exportImageLauncher.launch(intent)
    }
    
    /**
     * Launch directory selection for multiple image export.
     */
    private fun launchDirectoryExport(defaultName: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "Select folder for $defaultName")
        }
        exportImagesLauncher.launch(intent)
    }
    
    /**
     * Handle PDF export result.
     * Call this from your activity's launcher callback.
     */
    fun handleExportPdfResult(result: androidx.activity.result.ActivityResult) {
        val pending = pendingExport ?: return
        
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            pendingExport = null
            return
        }
        
        val uri = result.data!!.data!!
        
        when (pending.type) {
            ExportType.PAGE_PDF -> {
                exportPagePdf(uri, pending)
            }
            ExportType.NOTEBOOK_PDF -> {
                exportNotebookPdf(uri, pending)
            }
            else -> {
                pendingExport = null
            }
        }
    }
    
    /**
     * Handle image export result.
     * Call this from your activity's launcher callback.
     */
    fun handleExportImageResult(result: androidx.activity.result.ActivityResult) {
        val pending = pendingExport ?: return
        
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            pendingExport = null
            return
        }
        
        val uri = result.data!!.data!!
        
        when (pending.type) {
            ExportType.PAGE_IMAGE -> {
                exportPageImage(uri, pending)
            }
            else -> {
                pendingExport = null
            }
        }
    }
    
    /**
     * Handle multiple images export result.
     * Call this from your activity's launcher callback.
     */
    fun handleExportImagesResult(result: androidx.activity.result.ActivityResult) {
        val pending = pendingExport ?: return
        
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            pendingExport = null
            return
        }
        
        val uri = result.data!!.data!!
        
        when (pending.type) {
            ExportType.NOTEBOOK_IMAGES -> {
                exportNotebookImages(uri, pending)
            }
            else -> {
                pendingExport = null
            }
        }
    }
    
    /**
     * Export a single page as PDF.
     */
    private fun exportPagePdf(uri: Uri, pending: PendingExport) {
        val page = pending.page ?: return
        val config = pending.pdfConfig ?: PdfExporter.PdfConfig.DEFAULT
        
        // Get the current page content
        val width = activity.findViewById<com.merrythieves.inkit.InkSurfaceView>(R.id.inkSurface)?.width ?: 1404
        val height = activity.findViewById<com.merrythieves.inkit.InkSurfaceView>(R.id.inkSurface)?.height ?: 1872
        val bitmap = notebookStore.loadPageContent(page.id, width, height)
        
        val result = PdfExporter.exportPageToPdf(activity, page, bitmap, uri, config)
        
        bitmap.recycle()
        pendingExport = null
        
        when (result) {
            is PdfExporter.ExportResult.Success -> {
                Toast.makeText(
                    activity,
                    "Exported ${result.pageCount} page(s) to PDF",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is PdfExporter.ExportResult.Failure -> {
                Toast.makeText(
                    activity,
                    "PDF export failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Export a notebook as PDF.
     */
    private fun exportNotebookPdf(uri: Uri, pending: PendingExport) {
        val notebook = pending.notebook ?: return
        val config = pending.pdfConfig ?: PdfExporter.PdfConfig.DEFAULT
        
        val result = PdfExporter.exportNotebookToPdf(activity, notebook, uri, config)
        
        pendingExport = null
        
        when (result) {
            is PdfExporter.ExportResult.Success -> {
                Toast.makeText(
                    activity,
                    "Exported ${result.pageCount} pages to PDF",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is PdfExporter.ExportResult.Failure -> {
                Toast.makeText(
                    activity,
                    "PDF export failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Export a single page as image.
     */
    private fun exportPageImage(uri: Uri, pending: PendingExport) {
        val page = pending.page ?: return
        val options = pending.imageOptions ?: ImageExporter.ExportOptions.DEFAULT_PNG
        
        val width = activity.findViewById<com.merrythieves.inkit.InkSurfaceView>(R.id.inkSurface)?.width ?: 1404
        val height = activity.findViewById<com.merrythieves.inkit.InkSurfaceView>(R.id.inkSurface)?.height ?: 1872
        val bitmap = notebookStore.loadPageContent(page.id, width, height)
        
        val result = ImageExporter.exportPageToImage(activity, page, bitmap, uri, options)
        
        bitmap.recycle()
        pendingExport = null
        
        when (result) {
            is ImageExporter.ExportResult.Success -> {
                Toast.makeText(
                    activity,
                    "Exported page as image",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is ImageExporter.ExportResult.Failure -> {
                Toast.makeText(
                    activity,
                    "Image export failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Export a notebook as multiple images.
     */
    private fun exportNotebookImages(uri: Uri, pending: PendingExport) {
        val notebook = pending.notebook ?: return
        val options = pending.imageOptions ?: ImageExporter.ExportOptions.DEFAULT_PNG
        
        val result = ImageExporter.exportNotebookToImages(activity, notebook, uri, options)
        
        pendingExport = null
        
        when (result) {
            is ImageExporter.ExportResult.Success -> {
                Toast.makeText(
                    activity,
                    "Exported ${result.count} pages as images",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is ImageExporter.ExportResult.Failure -> {
                Toast.makeText(
                    activity,
                    "Image export failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Export types.
     */
    private enum class ExportType {
        PAGE_PDF,
        NOTEBOOK_PDF,
        PAGE_IMAGE,
        NOTEBOOK_IMAGES
    }
    
    /**
     * Pending export operation.
     */
    private data class PendingExport(
        val type: ExportType,
        val notebook: Notebook? = null,
        val page: Page? = null,
        val pdfConfig: PdfExporter.PdfConfig? = null,
        val imageOptions: ImageExporter.ExportOptions? = null
    )
}
