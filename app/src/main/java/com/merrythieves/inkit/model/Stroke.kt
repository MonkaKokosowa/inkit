package com.merrythieves.inkit.model

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import java.util.UUID

/**
 * Represents a single stroke (pen or eraser movement).
 * 
 * A stroke consists of a sequence of points drawn with a consistent
 * style (width, color, etc.). Strokes are the atomic unit of ink.
 */
data class Stroke(
    /** Unique identifier for this stroke */
    val id: String = UUID.randomUUID().toString(),
    
    /** ID of the page this stroke belongs to */
    val pageId: String,
    
    /** ID of the layer this stroke belongs to (for future layer support) */
    val layerId: String? = null,
    
    /** Timestamp when the stroke was created */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** List of points in the stroke, in order */
    val points: List<PointF>,
    
    /** Stroke style properties */
    val style: StrokeStyle,
    
    /** Whether this stroke was drawn with a pen (true) or eraser (false) */
    val isPen: Boolean = true,
    
    /** Pressure data for each point (0.0 to 1.0), null if not available */
    val pressures: List<Float>? = null,
    
    /** Timestamp for each point in milliseconds, null if not available */
    val timestamps: List<Long>? = null
) {
    /**
     * Get whether this stroke is an eraser stroke.
     */
    val isEraser: Boolean get() = !isPen
    
    /**
     * Get the bounds of this stroke.
     */
    fun getBounds(): StrokeBounds {
        if (points.isEmpty()) return StrokeBounds(0f, 0f, 0f, 0f)
        
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        
        for (point in points) {
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }
        
        // Account for stroke width
        val halfWidth = style.width / 2f
        return StrokeBounds(
            left = minX - halfWidth,
            top = minY - halfWidth,
            right = maxX + halfWidth,
            bottom = maxY + halfWidth
        )
    }
    
    /**
     * Create a Path object for this stroke.
     */
    fun toPath(): Path {
        val path = Path()
        if (points.isEmpty()) return path
        
        val first = points[0]
        path.moveTo(first.x, first.y)
        
        for (i in 1 until points.size) {
            val point = points[i]
            path.lineTo(point.x, point.y)
        }
        
        return path
    }
    
    /**
     * Create a Paint object for this stroke.
     */
    fun toPaint(): Paint {
        val strokeStyle = this.style
        return Paint().apply {
            color = strokeStyle.color
            strokeWidth = strokeStyle.width
            this.style = Paint.Style.STROKE
            strokeCap = strokeStyle.cap
            strokeJoin = strokeStyle.join
            isAntiAlias = strokeStyle.isAntiAlias
            
            if (isEraser) {
                // Eraser uses CLEAR mode
                strokeStyle.eraserMode?.let { xfermode = it }
            }
        }
    }
    
    companion object {
        /**
         * Create a stroke from a list of points and style.
         */
        fun create(
            pageId: String,
            points: List<PointF>,
            style: StrokeStyle,
            isPen: Boolean = true
        ): Stroke {
            return Stroke(
                pageId = pageId,
                points = points,
                style = style,
                isPen = isPen
            )
        }
        
        /**
         * Create a single-point stroke (dot).
         */
        fun createDot(
            pageId: String,
            point: PointF,
            style: StrokeStyle,
            isPen: Boolean = true
        ): Stroke {
            return create(pageId, listOf(point), style, isPen)
        }
    }
}

/**
 * Style properties for a stroke.
 */
data class StrokeStyle(
    /** Width of the stroke in pixels */
    val width: Float,
    
    /** Color of the stroke */
    val color: Int,
    
    /** Opacity of the stroke (0.0 to 1.0) */
    val opacity: Float = 1.0f,
    
    /** Stroke cap style */
    val cap: Paint.Cap = Paint.Cap.ROUND,
    
    /** Stroke join style */
    val join: Paint.Join = Paint.Join.ROUND,
    
    /** Whether anti-aliasing is enabled */
    val isAntiAlias: Boolean = false,
    
    /** Highlighter mode (semi-transparent) */
    val isHighlighter: Boolean = false,
    
    /** Xfermode for eraser (defaults to CLEAR) */
    val eraserMode: android.graphics.Xfermode? = null
) {
    /**
     * Get the effective color with opacity applied.
     */
    fun getEffectiveColor(): Int {
        if (opacity >= 1.0f) return color
        
        val alpha = ((color ushr 24) * opacity).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
    
    companion object {
        /** Default stroke style for pen */
        val DEFAULT_PEN = StrokeStyle(
            width = 2f,
            color = android.graphics.Color.BLACK,
            isAntiAlias = false
        )
        
        /** Default stroke style for eraser */
        val DEFAULT_ERASER = StrokeStyle(
            width = 30f,
            color = android.graphics.Color.BLACK,
            isAntiAlias = false
        )
        
        /** Default stroke style for highlighter */
        val DEFAULT_HIGHLIGHTER = StrokeStyle(
            width = 8f,
            color = android.graphics.Color.YELLOW,
            opacity = 0.4f,
            isAntiAlias = false,
            isHighlighter = true
        )
    }
}

/**
 * Bounding box for a stroke.
 */
data class StrokeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    
    fun contains(x: Float, y: Float): Boolean {
        return x >= left && x <= right && y >= top && y <= bottom
    }
    
    fun intersects(other: StrokeBounds): Boolean {
        return left < other.right && right > other.left &&
               top < other.bottom && bottom > other.top
    }
}
