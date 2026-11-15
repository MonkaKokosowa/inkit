package com.merrythieves.inkit.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.model.Page
import com.merrythieves.inkit.store.NotebookStore
import java.io.InputStream

/**
 * Imports image files as notebook pages.
 * 
 * Supports PNG, JPEG, WebP, GIF, and BMP formats.
 * Images are converted to the notebook's page dimensions.
 */
object ImageImporter {
    
    private const val TAG = "ImageImporter"
    private const val DEFAULT_PAGE_WIDTH = 1404
    private const val DEFAULT_PAGE_HEIGHT = 1872
    
    /**
     * Result of an import operation.
     */
    sealed class ImportResult {
        data class Success(val pagesImported: Int, val notebook: Notebook) : ImportResult()
        data class Failure(val message: String, val cause: Throwable? = null) : ImportResult()
    }
    
    /**
     * Import a single image file into a notebook.
     * 
     * @param context Context for file operations
     * @param uri URI of the image file to import
     * @param notebookName Optional name for the new notebook (if targetNotebook is null)
     * @param targetNotebook Notebook to import into (null to create new notebook with notebookName)
     * @return ImportResult with success/failure info
     */
    fun importImage(
        context: Context,
        uri: Uri,
        notebookName: String? = null,
        targetNotebook: Notebook? = null
    ): ImportResult {
        return try {
            // Load the image
            val bitmap = loadBitmapFromUri(context, uri) ?: 
                return ImportResult.Failure("Could not load image from URI")
            
            val notebookStore = NotebookStore.getInstance(context)
            val actualNotebookName = notebookName ?: "Imported Images"
            val notebook = targetNotebook ?: notebookStore.createNotebook(actualNotebookName)
            
            // Create a new page in the notebook
            val page = notebookStore.createPage(notebook.id) ?: 
                return ImportResult.Failure("Could not create page")
            
            // Convert and save the image as a page
            val pageBitmap = convertToPageSize(bitmap, DEFAULT_PAGE_WIDTH, DEFAULT_PAGE_HEIGHT)
            notebookStore.savePageContent(page.id, pageBitmap)
            
            pageBitmap.recycle()
            bitmap.recycle()
            
            ImportResult.Success(1, notebook)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to import image: ${t.message}", t)
            ImportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Import multiple image files into a notebook.
     * 
     * @param context Context for file operations
     * @param uris List of image URIs to import
     * @param notebookName Optional name for the new notebook (if targetNotebook is null)
     * @param targetNotebook Notebook to import into (null to create new notebook with notebookName)
     * @return ImportResult with success/failure info
     */
    fun importImages(
        context: Context,
        uris: List<Uri>,
        notebookName: String? = null,
        targetNotebook: Notebook? = null
    ): ImportResult {
        if (uris.isEmpty()) {
            return ImportResult.Failure("No images to import")
        }
        
        return try {
            val notebookStore = NotebookStore.getInstance(context)
            val actualNotebookName = notebookName ?: "Imported Images"
            val notebook = targetNotebook ?: notebookStore.createNotebook(actualNotebookName)
            var pagesImported = 0
            
            for (uri in uris) {
                val bitmap = loadBitmapFromUri(context, uri) ?: continue
                
                val page = notebookStore.createPage(notebook.id) ?: continue
                val pageBitmap = convertToPageSize(bitmap, DEFAULT_PAGE_WIDTH, DEFAULT_PAGE_HEIGHT)
                notebookStore.savePageContent(page.id, pageBitmap)
                
                pageBitmap.recycle()
                bitmap.recycle()
                pagesImported++
            }
            
            ImportResult.Success(pagesImported, notebook)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to import images: ${t.message}", t)
            ImportResult.Failure(t.message ?: "Unknown error", t)
        }
    }
    
    /**
     * Load a bitmap from a URI.
     */
    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load bitmap from URI: ${t.message}", t)
            null
        }
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
     * Rotate a bitmap by the specified degrees.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    /**
     * Check if a URI points to a supported image file.
     */
    fun isSupportedImage(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType != null && ImportConfig.SUPPORTED_IMAGE_TYPES.contains(mimeType)
        } catch (t: Throwable) {
            false
        }
    }
}
