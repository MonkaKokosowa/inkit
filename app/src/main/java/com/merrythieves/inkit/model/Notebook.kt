package com.merrythieves.inkit.model

import android.graphics.Color
import java.util.UUID

/**
 * Represents a notebook containing multiple pages.
 * 
 * A notebook is the primary organizational unit in inkit, containing
 * multiple Page objects. Notebooks can be created, renamed, reordered,
 * and deleted. Each notebook maintains its own set of pages.
 */
data class Notebook(
    /** Unique identifier for this notebook */
    val id: String = UUID.randomUUID().toString(),
    
    /** User-visible name of the notebook */
    var name: String,
    
    /** Timestamp when the notebook was created */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Timestamp when the notebook was last modified */
    var updatedAt: Long = System.currentTimeMillis(),
    
    /** List of page IDs in order (oldest first) */
    val pageIds: MutableList<String> = mutableListOf(),
    
    /** Cover color for the notebook thumbnail, null for default */
    var coverColor: Int? = null,
    
    /** Whether the notebook is locked/read-only */
    var isLocked: Boolean = false,
    
    /** Custom sort order (0 = default, lower = earlier) */
    var sortOrder: Int = 0,
    
    /** Whether this notebook is a favorite */
    var isFavorite: Boolean = false
) {
    /**
     * Get the default cover color if none is specified.
     * Uses a hash of the notebook ID to generate a consistent color.
     */
    fun getEffectiveCoverColor(): Int {
        return coverColor ?: generateDefaultColor(id ?: "")
    }
    
    /**
     * Add a page ID to this notebook.
     * Updates the updatedAt timestamp.
     */
    fun addPageId(pageId: String) {
        if (!pageIds.contains(pageId)) {
            pageIds.add(pageId)
            updatedAt = System.currentTimeMillis()
        }
    }
    
    /**
     * Remove a page ID from this notebook.
     * Updates the updatedAt timestamp.
     */
    fun removePageId(pageId: String) {
        if (pageIds.remove(pageId)) {
            updatedAt = System.currentTimeMillis()
        }
    }
    
    /**
     * Reorder pages within this notebook.
     */
    fun reorderPages(newOrder: List<String>) {
        pageIds.clear()
        pageIds.addAll(newOrder)
        updatedAt = System.currentTimeMillis()
    }
    
    companion object {
        /** Default notebook name for new notebooks */
        const val DEFAULT_NAME = "Untitled Notebook"
        
        /** List of predefined cover colors for notebooks */
        val PREDEFINED_COLORS = listOf(
            Color.parseColor("#4285F4"),  // Blue
            Color.parseColor("#34A853"),  // Green
            Color.parseColor("#FBBC05"),  // Yellow
            Color.parseColor("#EA4335"),  // Red
            Color.parseColor("#673AB7"),  // Purple
            Color.parseColor("#FF5722"),  // Orange
            Color.parseColor("#795548"),  // Brown
            Color.parseColor("#9E9E9E"),  // Grey
            Color.parseColor("#009688"),  // Teal
            Color.parseColor("#FF9800"),  // Orange
        )
        
        /**
         * Create a new notebook with default values.
         */
        fun createDefault(name: String = DEFAULT_NAME): Notebook {
            return Notebook(name = name)
        }
        
        /**
         * Generate a default color based on a string (e.g., notebook ID).
         * Uses a simple hash to produce a consistent color.
         */
        fun generateDefaultColor(seed: String): Int {
            if (seed.isEmpty()) {
                return PREDEFINED_COLORS[0]
            }
            var hash = 0
            for (char in seed) {
                hash = hash * 31 + char.code
            }
            return PREDEFINED_COLORS[Math.abs(hash) % PREDEFINED_COLORS.size]
        }
        
        /**
         * Create a notebook from legacy CanvasStore data.
         * Used during migration from the old flat page structure.
         */
        fun fromLegacy(
            legacyName: String = DEFAULT_NAME,
            pageIds: List<String> = emptyList()
        ): Notebook {
            return Notebook(
                name = legacyName,
                pageIds = pageIds.toMutableList(),
                sortOrder = 0
            )
        }
    }
}
