package com.merrythieves.inkit.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.model.Page
import com.merrythieves.inkit.store.NotebookStore
import java.io.File
import java.io.FileOutputStream

/**
 * Imports PDF files as notebook pages.
 * 
 * Each PDF page is converted to a notebook page.
 * Supports configurable DPI and page range selection.
 */
object PdfImporter {
    
    private const val TAG = "PdfImporter"
    private const val DEFAULT_PAGE_WIDTH = 1404
    private const val DEFAULT_PAGE_HEIGHT = 1872
    
    /**
     * Configuration for PDF import.
     */
    data class PdfImportConfig(
        val dpi: Int = ImportConfig.DEFAULT_PDF_DPI,
        val pageRange: IntRange? = null,
        val maxPages: Int = ImportConfig.MAX_PDF_PAGES,
        val asNewNotebook: Boolean = true,
        val notebookName: String = "Imported PDF"
    ) {
        companion object {
            val DEFAULT = PdfImportConfig()
        }
    }
    
    /**
     * Result of an import operation.
     */
    sealed class ImportResult {
        data class Success(val pagesImported: Int, val notebook: Notebook) : ImportResult()
        data class Failure(val message: String, val cause: Throwable? = null) : ImportResult()
    }
    
    /**
     * Import a PDF file into notebooks.
     * 
     * @param context Context for file operations
     * @param uri URI of the PDF file to import
     * @param notebookName Optional name for the new notebook (if targetNotebook is null)
     * @param config Import configuration
     * @param targetNotebook Notebook to import into (null to create new notebook with notebookName)
     * @return ImportResult with success/failure info
     */
    fun importPdf(
        context: Context,
        uri: Uri,
        notebookName: String? = null,
        config: PdfImportConfig = PdfImportConfig.DEFAULT,
        targetNotebook: Notebook? = null
    ): ImportResult {
        return try {
            // Copy PDF to temp file for rendering
            val tempFile = copyUriToTempFile(context, uri) ?: 
                return ImportResult.Failure("Could not copy PDF to temp file")
            
            val fileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(fileDescriptor)
            
            val notebookStore = NotebookStore.getInstance(context)
            val actualNotebookName = notebookName ?: config.notebookName
            val notebook = targetNotebook ?: notebookStore.createNotebook(actualNotebookName)
            
            var pagesImported = 0
            val pageCount = pdfRenderer.pageCount
            val startPage = config.pageRange?.first ?: 0
            val endPage = config.pageRange?.last?.coerceAtMost(pageCount - 1) ?: (pageCount - 1)
            
            for (i in startPage..endPage) {
                if (pagesImported >= config.maxPages) break
                
                val pdfPage = pdfRenderer.openPage(i)
                val bitmap = createBitmapFromPdfPage(pdfPage, config.dpi)
                
                // Create a new page in the notebook
                val page = notebookStore.createPage(notebook.id)
                
                if (page != null) {
                    // Convert and save the bitmap as a page
                    val pageBitmap = convertToPageSize(bitmap, DEFAULT_PAGE_WIDTH, DEFAULT_PAGE_HEIGHT)
                    notebookStore.savePageContent(page.id, pageBitmap)
                    pageBitmap.recycle()
                }
                
                bitmap.recycle()
                pdfPage.close()
                pagesImported++
            }
            
            pdfRenderer.close()
            fileDescriptor.close()
            tempFile.delete()
            
            ImportResult.Success(pagesImported, notebook)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to import PDF: ${t.message}", t)
            ImportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Copy a URI to a temporary file.
     * 
     * This is necessary because PdfRenderer requires a file descriptor.
     */
    private fun copyUriToTempFile(context: Context, uri: Uri): File? {
        return try {
            val tempFile = File.createTempFile("pdf_import_", ".pdf", context.cacheDir)
            tempFile.deleteOnExit()
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            tempFile
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy URI to temp file: ${t.message}", t)
            null
        }
    }
    
    /**
     * Create a bitmap from a PDF page.
     * 
     * @param pdfPage The PDF page to render
     * @param dpi Target DPI for the bitmap
     * @return Bitmap of the rendered page
     */
    private fun createBitmapFromPdfPage(pdfPage: PdfRenderer.Page, dpi: Int): Bitmap {
        // Get page dimensions in points (1/72 inch)
        val widthPt = pdfPage.width.toFloat()
        val heightPt = pdfPage.height.toFloat()
        
        // Convert to pixels at target DPI
        val widthPx = (widthPt * dpi / 72).toInt()
        val heightPx = (heightPt * dpi / 72).toInt()
        
        // Create bitmap with white background
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Fill with white
        canvas.drawColor(Color.WHITE)
        
        // Render PDF page onto bitmap
        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        return bitmap
    }
    
    /**
     * Convert a bitmap to the target page size.
     * Maintains aspect ratio with a white background.
     */
    private fun convertToPageSize(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val sourceWidth = source.width.toFloat()
        val sourceHeight = source.height.toFloat()
        
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val sourceRatio = sourceWidth / sourceHeight
        
        val scale: Float
        var scaledWidth: Int
        var scaledHeight: Int
        
        if (sourceRatio > targetRatio) {
            // Source is wider - scale to match width
            scale = targetWidth / sourceWidth
            scaledWidth = targetWidth
            scaledHeight = (sourceHeight * scale).toInt()
        } else {
            // Source is taller or same ratio - scale to match height
            scale = targetHeight / sourceHeight
            scaledWidth = (sourceWidth * scale).toInt()
            scaledHeight = targetHeight
        }
        
        // Create scaled bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(
            source, 
            scaledWidth, 
            scaledHeight, 
            true
        )
        
        // Create final bitmap with white background at target size
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // Fill with white
        canvas.drawColor(Color.WHITE)
        
        // Calculate positioning to center the scaled image
        val left = (targetWidth - scaledWidth) / 2f
        val top = (targetHeight - scaledHeight) / 2f
        
        // Draw the scaled image centered
        canvas.drawBitmap(scaledBitmap, left, top, null)
        
        // Clean up
        scaledBitmap.recycle()
        
        return result
    }
    
    /**
     * Get PDF page count from a URI.
     */
    fun getPdfPageCount(context: Context, uri: Uri): Int? {
        return try {
            val tempFile = copyUriToTempFile(context, uri) ?: return null
            val fileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(fileDescriptor)
            val count = pdfRenderer.pageCount
            pdfRenderer.close()
            fileDescriptor.close()
            tempFile.delete()
            count
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get PDF page count: ${t.message}", t)
            null
        }
    }
    
    /**
     * Check if a URI points to a PDF file.
     */
    fun isPdfFile(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType != null && ImportConfig.SUPPORTED_PDF_TYPES.contains(mimeType)
        } catch (t: Throwable) {
            false
        }
    }
}
