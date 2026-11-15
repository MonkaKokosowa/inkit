package com.merrythieves.inkit.exporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.model.Page
import com.merrythieves.inkit.store.NotebookStore
import java.io.File
import java.io.FileOutputStream

/**
 * Exports notebooks and pages to image formats (PNG, JPEG).
 */
object ImageExporter {
    
    private const val TAG = "ImageExporter"
    
    /**
     * Image format options.
     */
    enum class ImageFormat(val mimeType: String, val extension: String) {
        PNG("image/png", ".png"),
        JPEG("image/jpeg", ".jpg")
    }
    
    /**
     * Result of an export operation.
     */
    sealed class ExportResult {
        data class Success(val count: Int, val fileSize: Long) : ExportResult()
        data class Failure(val message: String, val cause: Throwable? = null) : ExportResult()
    }
    
    /**
     * Export options for image export.
     */
    data class ExportOptions(
        val format: ImageFormat = ImageFormat.PNG,
        val quality: Int = 100,  // 0-100, only used for JPEG
        val includeBackground: Boolean = true,
        val scale: Float = 1.0f  // Scale factor for output size
    ) {
        companion object {
            val DEFAULT_PNG = ExportOptions(format = ImageFormat.PNG, quality = 100)
            val DEFAULT_JPEG = ExportOptions(format = ImageFormat.JPEG, quality = 90)
        }
    }
    
    /**
     * Export a single page to an image file.
     * 
     * @param context Context for file operations
     * @param page Page to export
     * @param bitmap Page content as bitmap (transparent background)
     * @param dest Destination URI for the image
     * @param options Export options
     * @return ExportResult with success/failure info
     */
    fun exportPageToImage(
        context: Context,
        page: Page,
        bitmap: Bitmap,
        dest: Uri,
        options: ExportOptions = ExportOptions.DEFAULT_PNG
    ): ExportResult {
        return try {
            val finalBitmap = createFinalBitmap(bitmap, page, options)
            val fileSize = writeBitmapToUri(context, finalBitmap, dest, options)
            
            if (finalBitmap !== bitmap) {
                finalBitmap.recycle()
            }
            
            ExportResult.Success(1, fileSize)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export page to image: ${t.message}", t)
            ExportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Export all pages in a notebook to individual image files in a directory.
     * 
     * @param context Context for file operations
     * @param notebook Notebook to export
     * @param destDir Destination directory URI
     * @param options Export options
     * @return ExportResult with success/failure info
     */
    fun exportNotebookToImages(
        context: Context,
        notebook: Notebook,
        destDir: Uri,
        options: ExportOptions = ExportOptions.DEFAULT_PNG
    ): ExportResult {
        val notebookStore = NotebookStore.getInstance(context)
        val pages = notebookStore.getPagesInNotebook(notebook.id)
        
        if (pages.isEmpty()) {
            return ExportResult.Failure("Notebook has no pages")
        }
        
        return try {
            var totalSize = 0L
            var count = 0
            
            for ((index, page) in pages.withIndex()) {
                val bitmap = notebookStore.loadPageContent(page.id, page.width, page.height)
                val finalBitmap = createFinalBitmap(bitmap, page, options)
                
                val fileName = "${notebook.name}_page_${index + 1}${options.format.extension}"
                val fileUri = Uri.withAppendedPath(destDir, fileName)
                
                val fileSize = writeBitmapToUri(context, finalBitmap, fileUri, options)
                totalSize += fileSize
                count++
                
                if (finalBitmap !== bitmap) {
                    finalBitmap.recycle()
                }
                bitmap.recycle()
            }
            
            ExportResult.Success(count, totalSize)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export notebook to images: ${t.message}", t)
            ExportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Export multiple pages to individual image files.
     * 
     * @param context Context for file operations
     * @param pages List of pages to export
     * @param bitmaps List of bitmaps for each page
     * @param destDir Destination directory URI
     * @param options Export options
     * @return ExportResult with success/failure info
     */
    fun exportPagesToImages(
        context: Context,
        pages: List<Page>,
        bitmaps: List<Bitmap>,
        destDir: Uri,
        options: ExportOptions = ExportOptions.DEFAULT_PNG
    ): ExportResult {
        if (pages.size != bitmaps.size) {
            return ExportResult.Failure("Pages and bitmaps count mismatch")
        }
        
        if (pages.isEmpty()) {
            return ExportResult.Failure("No pages to export")
        }
        
        return try {
            var totalSize = 0L
            var count = 0
            
            for ((index, bitmap) in bitmaps.withIndex()) {
                val finalBitmap = createFinalBitmap(bitmap, pages[index], options)
                
                val fileName = "page_${index + 1}${options.format.extension}"
                val fileUri = Uri.withAppendedPath(destDir, fileName)
                
                val fileSize = writeBitmapToUri(context, finalBitmap, fileUri, options)
                totalSize += fileSize
                count++
                
                if (finalBitmap !== bitmap) {
                    finalBitmap.recycle()
                }
            }
            
            ExportResult.Success(count, totalSize)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export pages to images: ${t.message}", t)
            ExportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Create the final bitmap with background (if enabled) for export.
     */
    private fun createFinalBitmap(
        contentBitmap: Bitmap,
        page: Page,
        options: ExportOptions
    ): Bitmap {
        if (!options.includeBackground) {
            // Return the content as-is (transparent background)
            return contentBitmap
        }
        
        // Create a white bitmap with the same dimensions
        val finalBitmap = Bitmap.createBitmap(
            contentBitmap.width,
            contentBitmap.height,
            Bitmap.Config.ARGB_8888
        )
        
        val canvas = Canvas(finalBitmap)
        
        // Fill with white
        canvas.drawColor(Color.WHITE)
        
        // Draw background pattern
        drawBackgroundPattern(canvas, page.backgroundType, contentBitmap.width, contentBitmap.height)
        
        // Draw the content
        canvas.drawBitmap(contentBitmap, 0f, 0f, null)
        
        return finalBitmap
    }
    
    /**
     * Draw background pattern on a canvas.
     */
    private fun drawBackgroundPattern(
        canvas: Canvas,
        backgroundType: Int,
        width: Int,
        height: Int
    ) {
        val backgroundPaint = android.graphics.Paint().apply {
            color = Color.parseColor("#7090B8")
            strokeWidth = 1.5f
            style = android.graphics.Paint.Style.STROKE
        }
        
        val dotPaint = android.graphics.Paint().apply {
            color = Color.parseColor("#7090B8")
            style = android.graphics.Paint.Style.FILL
        }
        
        val lineSpacing = 90f
        val dotSpacing = 90f
        val dotRadius = 3f
        
        when (backgroundType) {
            Page.BACKGROUND_RULED -> {
                var y = 150f
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, backgroundPaint)
                    y += lineSpacing
                }
            }
            Page.BACKGROUND_DOTS -> {
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
     * Write bitmap to URI.
     */
    private fun writeBitmapToUri(
        context: Context,
        bitmap: Bitmap,
        dest: Uri,
        options: ExportOptions
    ): Long {
        return try {
            context.contentResolver.openOutputStream(dest)?.use { outputStream ->
                when (options.format) {
                    ImageFormat.PNG -> {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                    ImageFormat.JPEG -> {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, outputStream)
                    }
                }
                outputStream.flush()
                
                // Try to get file size
                context.contentResolver.openFileDescriptor(dest, "r")?.use { pfd ->
                    pfd.statSize
                } ?: 0L
            } ?: throw Exception("Could not open output stream for URI")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write bitmap to URI: ${t.message}", t)
            throw t
        }
    }
    
    /**
     * Get available image format options.
     */
    fun getFormatOptions(): List<ImageFormat> {
        return ImageFormat.values().toList()
    }
}
