package com.merrythieves.inkit.exporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.model.Page
import com.merrythieves.inkit.store.NotebookStore
import java.io.File
import java.io.FileOutputStream

/**
 * Exports notebooks and pages to PDF format.
 * 
 * Uses Android's PdfDocument API to create standard PDF files.
 * Supports configurable page sizes, orientations, and quality.
 */
object PdfExporter {
    
    private const val TAG = "PdfExporter"
    
    /**
     * Page size options for PDF export.
     */
    enum class PageSize(val widthPx: Int, val heightPx: Int, val displayName: String) {
        A4(595, 842, "A4 (210×297mm)"),
        A5(420, 595, "A5 (148×210mm)"),
        LETTER(612, 792, "Letter (8.5×11in)"),
        LEGAL(612, 1008, "Legal (8.5×14in)"),
        A3(842, 1191, "A3 (297×420mm)")
    }
    
    /**
     * Page orientation for PDF export.
     */
    enum class Orientation {
        PORTRAIT,
        LANDSCAPE
    }
    
    /**
     * Configuration for PDF export.
     */
    data class PdfConfig(
        val pageSize: PageSize = PageSize.A4,
        val orientation: Orientation = Orientation.PORTRAIT,
        val includeBackground: Boolean = true,
        val resolutionDpi: Int = 300,
        val author: String? = null,
        val title: String? = null
    ) {
        companion object {
            val DEFAULT = PdfConfig()
        }
    }
    
    /**
     * Result of an export operation.
     */
    sealed class ExportResult {
        data class Success(val pageCount: Int, val fileSize: Long) : ExportResult()
        data class Failure(val message: String, val cause: Throwable? = null) : ExportResult()
    }
    
    /**
     * Export a single page to PDF.
     * 
     * @param context Context for file operations
     * @param page Page to export
     * @param bitmap Page content as bitmap (transparent background)
     * @param dest Destination URI for the PDF
     * @param config PDF configuration
     * @return ExportResult with success/failure info
     */
    fun exportPageToPdf(
        context: Context,
        page: Page,
        bitmap: Bitmap,
        dest: Uri,
        config: PdfConfig = PdfConfig.DEFAULT
    ): ExportResult {
        return try {
            val pdfDocument = PdfDocument()
            
            // Use bitmap dimensions for PDF page size to avoid cropping
            val widthPx = bitmap.width
            val heightPx = bitmap.height
            
            // Create PDF page with bitmap dimensions (in pixels)
            // The page size enum is ignored when using custom dimensions
            val pdfPage = pdfDocument.startPage(PdfDocument.PageInfo.Builder(
                widthPx, 
                heightPx, 
                1  // Page number, not size enum
            ).create())
            
            // Draw page content on PDF
            drawPageOnPdfCanvas(pdfPage.canvas, bitmap, config, page)
            
            pdfDocument.finishPage(pdfPage)
            
            // Write to destination
            val fileSize = writePdfToUri(context, pdfDocument, dest)
            pdfDocument.close()
            
            ExportResult.Success(1, fileSize)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export page to PDF: ${t.message}", t)
            ExportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Export all pages in a notebook to a single PDF.
     * 
     * @param context Context for file operations
     * @param notebook Notebook to export
     * @param dest Destination URI for the PDF
     * @param config PDF configuration
     * @return ExportResult with success/failure info
     */
    fun exportNotebookToPdf(
        context: Context,
        notebook: Notebook,
        dest: Uri,
        config: PdfConfig = PdfConfig.DEFAULT
    ): ExportResult {
        val notebookStore = NotebookStore.getInstance(context)
        val pages = notebookStore.getPagesInNotebook(notebook.id)
        
        if (pages.isEmpty()) {
            return ExportResult.Failure("Notebook has no pages")
        }
        
        return try {
            val pdfDocument = PdfDocument()
            var pageCount = 0
            
            for (page in pages) {
                // Load page content at device resolution
                // Use default dimensions that match the device screen
                val widthPx = 1404  // Default e-ink width
                val heightPx = 1872  // Default e-ink height
                val bitmap = notebookStore.loadPageContent(page.id, widthPx, heightPx)
                
                // Create PDF page with bitmap dimensions to avoid cropping
                val pdfPage = pdfDocument.startPage(PdfDocument.PageInfo.Builder(
                    bitmap.width, 
                    bitmap.height, 
                    pageCount + 1
                ).create())
                
                // Draw page content on PDF
                drawPageOnPdfCanvas(pdfPage.canvas, bitmap, config, page)
                
                pdfDocument.finishPage(pdfPage)
                pageCount++
                
                // Clean up
                bitmap.recycle()
            }
            
            // Write to destination
            val fileSize = writePdfToUri(context, pdfDocument, dest)
            pdfDocument.close()
            
            ExportResult.Success(pageCount, fileSize)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export notebook to PDF: ${t.message}", t)
            ExportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Export multiple pages to a single PDF.
     * 
     * @param context Context for file operations
     * @param pages List of pages to export
     * @param bitmaps List of bitmaps corresponding to each page
     * @param dest Destination URI for the PDF
     * @param config PDF configuration
     * @return ExportResult with success/failure info
     */
    fun exportPagesToPdf(
        context: Context,
        pages: List<Page>,
        bitmaps: List<Bitmap>,
        dest: Uri,
        config: PdfConfig = PdfConfig.DEFAULT
    ): ExportResult {
        if (pages.size != bitmaps.size) {
            return ExportResult.Failure("Pages and bitmaps count mismatch")
        }
        
        if (pages.isEmpty()) {
            return ExportResult.Failure("No pages to export")
        }
        
        return try {
            val pdfDocument = PdfDocument()
            
            for ((index, bitmap) in bitmaps.withIndex()) {
                // Create PDF page with bitmap dimensions to avoid cropping
                val pdfPage = pdfDocument.startPage(PdfDocument.PageInfo.Builder(
                    bitmap.width, 
                    bitmap.height, 
                    index + 1
                ).create())
                
                drawPageOnPdfCanvas(pdfPage.canvas, bitmap, config, pages[index])
                pdfDocument.finishPage(pdfPage)
            }
            
            val fileSize = writePdfToUri(context, pdfDocument, dest)
            pdfDocument.close()
            
            ExportResult.Success(pages.size, fileSize)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export pages to PDF: ${t.message}", t)
            ExportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Draw page content on a PDF canvas.
     */
    private fun drawPageOnPdfCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        config: PdfConfig,
        page: Page
    ) {
        // Fill with white background
        canvas.drawColor(Color.WHITE)
        
        // Draw background pattern if enabled and requested
        if (config.includeBackground) {
            drawBackgroundPattern(canvas, page.backgroundType, bitmap.width, bitmap.height)
        }
        
        // Draw the ink content (which is on transparent background)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint().apply {
            isFilterBitmap = true
        })
    }
    
    /**
     * Draw background pattern on PDF canvas.
     */
    private fun drawBackgroundPattern(
        canvas: Canvas,
        backgroundType: Int,
        width: Int,
        height: Int
    ) {
        val backgroundPaint = Paint().apply {
            color = Color.parseColor("#7090B8") // Same blue as in InkSurfaceView
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        
        val dotPaint = Paint().apply {
            color = Color.parseColor("#7090B8")
            style = Paint.Style.FILL
        }
        
        val lineSpacing = 90f
        val dotSpacing = 90f
        val dotRadius = 3f
        
        when (backgroundType) {
            Page.BACKGROUND_RULED -> {
                // Draw horizontal ruled lines
                var y = 150f
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, backgroundPaint)
                    y += lineSpacing
                }
            }
            Page.BACKGROUND_DOTS -> {
                // Draw dot grid
                var x = dotSpacing / 4
                while (x <= width) {
                    var y = dotSpacing.toFloat()
                    while (y < height) {
                        canvas.drawCircle(x, y, dotRadius, dotPaint)
                        y += dotSpacing
                    }
                    x += dotSpacing
                }
            }
            Page.BACKGROUND_GRID -> {
                // Draw grid lines
                var y = dotSpacing.toFloat()
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, backgroundPaint)
                    y += dotSpacing
                }
                
                var x = dotSpacing / 4
                while (x <= width) {
                    canvas.drawLine(x, 0f, x, height.toFloat(), backgroundPaint)
                    x += dotSpacing
                }
            }
        }
    }
    
    /**
     * Get PDF page dimensions based on config.
     */
    private fun getDimensions(config: PdfConfig): Pair<Int, Int> {
        val baseWidth = config.pageSize.widthPx
        val baseHeight = config.pageSize.heightPx
        
        return if (config.orientation == Orientation.LANDSCAPE) {
            Pair(baseHeight, baseWidth)
        } else {
            Pair(baseWidth, baseHeight)
        }
    }
    
    /**
     * Write PDF document to URI.
     */
    private fun writePdfToUri(
        context: Context,
        pdfDocument: PdfDocument,
        dest: Uri
    ): Long {
        return try {
            context.contentResolver.openFileDescriptor(dest, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                    pfd.statSize
                }
            } ?: throw Exception("Could not open file descriptor for URI")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write PDF to URI: ${t.message}", t)
            throw t
        }
    }
    
    /**
     * Get available page size options.
     */
    fun getPageSizeOptions(): List<PageSize> {
        return PageSize.values().toList()
    }
    
    /**
     * Get page size by display name.
     */
    fun getPageSizeByName(name: String): PageSize? {
        return PageSize.values().firstOrNull { it.displayName == name }
    }
}
