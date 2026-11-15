package com.merrythieves.inkit.model

import java.util.UUID

/**
 * Represents a single page within a notebook.
 * 
 * A page contains ink strokes, background settings, and metadata.
 * Pages are stored as PNG files with separate metadata.
 */
data class Page(
    /** Unique identifier for this page */
    val id: String = UUID.randomUUID().toString(),
    
    /** ID of the notebook this page belongs to */
    val notebookId: String,
    
    /** Timestamp when the page was created */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Timestamp when the page was last modified */
    var updatedAt: Long = System.currentTimeMillis(),
    
    /** Background type: 0=none, 1=ruled, 2=dots, 3=grid */
    var backgroundType: Int = 0,
    
    /** Width of the page in pixels */
    var width: Int = 0,
    
    /** Height of the page in pixels */
    var height: Int = 0,
    
    /** Document height factor (multiplier for vertical scroll) */
    var documentHeightFactor: Int = 2,
    
    /** Custom title for the page, null for default */
    var title: String? = null,
    
    /** Whether this page is a template */
    var isTemplate: Boolean = false,
    
    /** Sort order within the notebook (0 = default, lower = earlier) */
    var sortOrder: Int = 0
) {
    /**
     * Get the display name for this page.
     * Returns the title if set, otherwise a default based on position.
     */
    fun getDisplayName(defaultPrefix: String = "Page"): String {
        return title ?: "$defaultPrefix ${id.take(8)}"
    }
    
    /**
     * Get the full height of the document (height * documentHeightFactor) */
    fun getDocumentHeight(): Int {
        return height * documentHeightFactor
    }
    
    /**
     * Background type constants for type safety.
     */
    companion object {
        /** No background */
        const val BACKGROUND_NONE = 0
        
        /** Horizontal ruled lines */
        const val BACKGROUND_RULED = 1
        
        /** Dot grid */
        const val BACKGROUND_DOTS = 2
        
        /** Grid lines */
        const val BACKGROUND_GRID = 3
        
        /** Maximum background type value */
        const val BACKGROUND_MAX = 3
        
        /** Default page dimensions for new pages (will be set to device dimensions) */
        const val DEFAULT_WIDTH = 1404  // ~720dp at mdpi
        const val DEFAULT_HEIGHT = 1872 // ~936dp at mdpi
        
        /** Default document height factor */
        const val DEFAULT_DOCUMENT_HEIGHT_FACTOR = 2
        
        /**
         * Create a new page with default values for a notebook.
         */
        fun createDefault(notebookId: String): Page {
            return Page(
                notebookId = notebookId,
                backgroundType = BACKGROUND_NONE,
                documentHeightFactor = DEFAULT_DOCUMENT_HEIGHT_FACTOR
            )
        }
        
        /**
         * Create a page from legacy CanvasStore data.
         * Used during migration from the old flat page structure.
         */
        fun fromLegacy(
            notebookId: String,
            legacyBackgroundType: Int = BACKGROUND_NONE,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT
        ): Page {
            return Page(
                notebookId = notebookId,
                backgroundType = legacyBackgroundType.coerceIn(0, BACKGROUND_MAX),
                width = width,
                height = height,
                documentHeightFactor = DEFAULT_DOCUMENT_HEIGHT_FACTOR
            )
        }
        
        /**
         * Get background type from legacy value, ensuring it's within valid range.
         */
        fun normalizeBackgroundType(type: Int): Int {
            return type.coerceIn(BACKGROUND_NONE, BACKGROUND_MAX)
        }
    }
}
