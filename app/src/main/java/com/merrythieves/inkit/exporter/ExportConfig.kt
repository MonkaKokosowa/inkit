package com.merrythieves.inkit.exporter

/**
 * Central configuration for export operations.
 * Defines defaults and available options for all export types.
 */
object ExportConfig {
    
    // Default configurations
    val DEFAULT_PDF_CONFIG = PdfExporter.PdfConfig.DEFAULT
    val DEFAULT_IMAGE_CONFIG = ImageExporter.ExportOptions.DEFAULT_PNG
    
    // Available page sizes for PDF
    val PDF_PAGE_SIZES = PdfExporter.getPageSizeOptions()
    
    // Available image formats
    val IMAGE_FORMATS = ImageExporter.getFormatOptions()
    
    // Quality options for JPEG
    val JPEG_QUALITY_OPTIONS = listOf(100, 90, 80, 70, 50)
    
    // Resolution options
    val RESOLUTION_OPTIONS = listOf(300, 150, 72)
}
