package com.merrythieves.inkit.model

import java.util.UUID

/**
 * Represents a layer within a page.
 * 
 * Layers allow users to organize content on a page, with each layer
 * containing its own set of strokes. Layers can be shown/hidden,
 * reordered, and locked.
 */
data class Layer(
    /** Unique identifier for this layer */
    val id: String = UUID.randomUUID().toString(),
    
    /** ID of the page this layer belongs to */
    val pageId: String,
    
    /** User-visible name of the layer */
    var name: String,
    
    /** Sort order (0 = bottom, higher = top) */
    var order: Int = 0,
    
    /** Whether the layer is visible */
    var isVisible: Boolean = true,
    
    /** Whether the layer is locked (cannot be edited) */
    var isLocked: Boolean = false,
    
    /** Opacity of the layer (0.0 to 1.0) */
    var opacity: Float = 1.0f,
    
    /** Blend mode for the layer */
    var blendMode: BlendMode = BlendMode.NORMAL
) {
    /**
     * List of stroke IDs in this layer.
     * Note: This is a reference list; actual stroke data is stored separately.
     */
    val strokeIds: MutableList<String> = mutableListOf()
    
    /**
     * Add a stroke ID to this layer.
     */
    fun addStrokeId(strokeId: String) {
        if (!strokeIds.contains(strokeId)) {
            strokeIds.add(strokeId)
        }
    }
    
    /**
     * Remove a stroke ID from this layer.
     */
    fun removeStrokeId(strokeId: String) {
        strokeIds.remove(strokeId)
    }
    
    /**
     * Reorder strokes within this layer.
     */
    fun reorderStrokes(newOrder: List<String>) {
        strokeIds.clear()
        strokeIds.addAll(newOrder)
    }
    
    companion object {
        /** Default layer name */
        const val DEFAULT_NAME = "Layer"
        
        /**
         * Create a default layer for a page.
         */
        fun createDefault(pageId: String, order: Int = 0): Layer {
            return Layer(
                pageId = pageId,
                name = "$DEFAULT_NAME ${order + 1}",
                order = order
            )
        }
        
        /**
         * Create the base layer (always exists, cannot be deleted).
         */
        fun createBaseLayer(pageId: String): Layer {
            return Layer(
                pageId = pageId,
                name = "Background",
                order = 0,
                isLocked = false,
                isVisible = true
            )
        }
    }
}

/**
 * Blend modes for layers.
 */
enum class BlendMode {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    DARKEN,
    LIGHTEN,
    COLOR_DODGE,
    COLOR_BURN,
    HARD_LIGHT,
    SOFT_LIGHT,
    DIFFERENCE,
    EXCLUSION,
    HUE,
    SATURATION,
    COLOR,
    LUMINOSITY
}
