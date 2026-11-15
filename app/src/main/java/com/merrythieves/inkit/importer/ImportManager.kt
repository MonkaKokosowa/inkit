package com.merrythieves.inkit.importer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.store.NotebookStore

/**
 * Manages import operations with user interaction.
 * 
 * Handles SAF (Storage Access Framework) for file selection
 * and coordinates between different import types (PDF, images).
 */
class ImportManager(
    private val activity: Activity,
    private val importPdfLauncher: ActivityResultLauncher<Intent>,
    private val importImageLauncher: ActivityResultLauncher<Intent>,
    private val importMultipleLauncher: ActivityResultLauncher<Intent>,
    private val onImportComplete: (() -> Unit)? = null
) {
    
    /**
     * Extract a readable name from a URI for use as a notebook name.
     */
    private fun getFileNameFromUri(uri: Uri): String {
        return try {
            // Try to get display name
            var fileName = uri.lastPathSegment ?: ""
            
            // Remove file extension
            val lastDotIndex = fileName.lastIndexOf('.')
            if (lastDotIndex > 0) {
                fileName = fileName.substring(0, lastDotIndex)
            }
            
            // If still empty or looks like a path, use a default
            if (fileName.isEmpty() || fileName.contains('/')) {
                "Imported"
            } else {
                fileName
            }
        } catch (t: Throwable) {
            "Imported"
        }
    }
    
    private val notebookStore: NotebookStore = NotebookStore.getInstance(activity)
    
    // Pending import operations
    private var pendingImport: PendingImport? = null
    
    /**
     * Initialize the import manager.
     * Call this from onCreate() of your activity.
     */
    fun initialize() {
        // Launchers are already registered by the activity
        // We just use them here
    }
    
    /**
     * Import a PDF file.
     * Opens file picker for PDF selection.
     */
    fun importPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_MIME_TYPES, ImportConfig.SUPPORTED_PDF_TYPES)
        }
        pendingImport = PendingImport(ImportType.PDF)
        importPdfLauncher.launch(intent)
    }
    
    /**
     * Import a single image file.
     * Opens file picker for image selection.
     */
    fun importImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, ImportConfig.SUPPORTED_IMAGE_TYPES)
        }
        pendingImport = PendingImport(ImportType.IMAGE)
        importImageLauncher.launch(intent)
    }
    
    /**
     * Import multiple image files.
     * Opens file picker for multiple image selection.
     */
    fun importImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, ImportConfig.SUPPORTED_IMAGE_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pendingImport = PendingImport(ImportType.IMAGES)
        importMultipleLauncher.launch(intent)
    }
    
    /**
     * Handle PDF import result.
     * Call this from your activity's launcher callback.
     */
    fun handleImportPdfResult(result: ActivityResult) {
        val pending = pendingImport ?: return
        
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            pendingImport = null
            return
        }
        
        val uri = result.data!!.data!!
        val config = PdfImporter.PdfImportConfig.DEFAULT.copy(
            asNewNotebook = true
        )
        
        when (pending.type) {
            ImportType.PDF -> {
                importPdfFromUri(uri, config)
            }
            else -> {
                pendingImport = null
            }
        }
    }
    
    /**
     * Handle image import result.
     * Call this from your activity's launcher callback.
     */
    fun handleImportImageResult(result: ActivityResult) {
        val pending = pendingImport ?: return
        
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            pendingImport = null
            return
        }
        
        val uri = result.data!!.data!!
        
        when (pending.type) {
            ImportType.IMAGE -> {
                importImageFromUri(uri)
            }
            else -> {
                pendingImport = null
            }
        }
    }
    
    /**
     * Handle multiple images import result.
     * Call this from your activity's launcher callback.
     */
    fun handleImportImagesResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            pendingImport = null
            return
        }
        
        val uri = result.data!!.data!!
        
        // Handle both single and multiple selection
        if (result.data?.clipData != null) {
            // Multiple files selected
            val clipData = result.data!!.clipData!!
            val uris = mutableListOf<Uri>()
            for (i in 0 until clipData.itemCount) {
                uris.add(clipData.getItemAt(i).uri)
            }
            importImagesFromUris(uris)
        } else {
            // Single file selected
            importImagesFromUris(listOf(uri))
        }
        
        pendingImport = null
    }
    
    /**
     * Import PDF from URI.
     */
    private fun importPdfFromUri(uri: Uri, config: PdfImporter.PdfImportConfig, notebookName: String? = null) {
        val actualNotebookName = notebookName ?: getFileNameFromUri(uri)
        val result = PdfImporter.importPdf(activity, uri, actualNotebookName, config)
        
        pendingImport = null
        
        when (result) {
            is PdfImporter.ImportResult.Success -> {
                Toast.makeText(
                    activity,
                    "Imported ${result.pagesImported} page(s) from PDF",
                    Toast.LENGTH_SHORT
                ).show()
                onImportComplete?.invoke()
            }
            is PdfImporter.ImportResult.Failure -> {
                Toast.makeText(
                    activity,
                    "PDF import failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Import single image from URI.
     */
    private fun importImageFromUri(uri: Uri) {
        val fileName = getFileNameFromUri(uri)
        val result = ImageImporter.importImage(activity, uri, fileName)
        
        pendingImport = null
        
        when (result) {
            is ImageImporter.ImportResult.Success -> {
                Toast.makeText(
                    activity,
                    "Imported ${result.pagesImported} image(s)",
                    Toast.LENGTH_SHORT
                ).show()
                onImportComplete?.invoke()
            }
            is ImageImporter.ImportResult.Failure -> {
                Toast.makeText(
                    activity,
                    "Image import failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Import multiple images from URIs.
     */
    private fun importImagesFromUris(uris: List<Uri>) {
        // For multiple images, use the first file's name or a generic name
        val fileName = if (uris.isNotEmpty()) {
            getFileNameFromUri(uris[0]) + " (multiple)"
        } else {
            "Imported Images"
        }
        val result = ImageImporter.importImages(activity, uris, fileName)
        
        pendingImport = null
        
        when (result) {
            is ImageImporter.ImportResult.Success -> {
                Toast.makeText(
                    activity,
                    "Imported ${result.pagesImported} image(s)",
                    Toast.LENGTH_SHORT
                ).show()
                onImportComplete?.invoke()
            }
            is ImageImporter.ImportResult.Failure -> {
                Toast.makeText(
                    activity,
                    "Image import failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Import types.
     */
    private enum class ImportType {
        PDF,
        IMAGE,
        IMAGES
    }
    
    /**
     * Pending import operation.
     */
    private data class PendingImport(
        val type: ImportType
    )
}
