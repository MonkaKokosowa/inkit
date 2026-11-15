package com.merrythieves.inkit.importer

/**
 * Configuration for import operations.
 */
object ImportConfig {
    
    /**
     * Supported image MIME types for import.
     */
    val SUPPORTED_IMAGE_TYPES = arrayOf(
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/webp",
        "image/gif",
        "image/bmp"
    )
    
    /**
     * Supported PDF MIME types for import.
     */
    val SUPPORTED_PDF_TYPES = arrayOf(
        "application/pdf"
    )
    
    /**
     * All supported MIME types for import.
     */
    val ALL_SUPPORTED_TYPES: Array<String> = SUPPORTED_IMAGE_TYPES + SUPPORTED_PDF_TYPES
    
    /**
     * Default quality for imported images.
     */
    const val DEFAULT_IMAGE_QUALITY = 100
    
    /**
     * Default DPI for PDF import.
     */
    const val DEFAULT_PDF_DPI = 300
    
    /**
     * Maximum number of pages to import from a PDF.
     */
    const val MAX_PDF_PAGES = 50
}
