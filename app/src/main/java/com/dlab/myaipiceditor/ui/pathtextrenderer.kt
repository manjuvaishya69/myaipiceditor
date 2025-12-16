package com.dlab.myaipiceditor.ui

import android.content.Context
import android.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat
import com.dlab.myaipiceditor.R

object PathTextRenderer {

    private data class CacheKey(
        val text: String,
        val fontSize: Float,
        val typefaceHash: Int,
        val letterSpacing: Float
    )

    private val pathCache = HashMap<CacheKey, Path>()

    fun renderText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        fontSize: Float,
        fontFamily: FontFamily,
        isBold: Boolean,
        textColor: Color,
        opacity: Float,
        strokeWidth: Float,
        strokeColor: Color,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        rotation: Float = 0f,
        highlightColor: Color = Color.Transparent,
        highlightPadding: Float = 10f,
        letterSpacing: Float = 0f,
        shadowRadius: Float = 0f,
        shadowColor: Color = Color.Transparent,
        shadowOffset: Offset = Offset.Zero,
        density: Float,
        context: Context? = null
    ) {
        val typeface = resolveTypeface(fontFamily, isBold, context)

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        val textPath = getOrCreateTextPath(
            text = text,
            fontSize = fontSize,
            typeface = typeface,
            letterSpacing = letterSpacing
        )

        // Highlight background
        if (highlightColor.alpha > 0f) {
            val bounds = RectF()
            textPath.computeBounds(bounds, true)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = highlightColor.toArgb()
                style = Paint.Style.FILL
            }

            val bgRect = RectF(
                bounds.left - highlightPadding,
                bounds.top - highlightPadding,
                bounds.right + highlightPadding,
                bounds.bottom + highlightPadding
            )

            canvas.drawRoundRect(bgRect, 6f, 6f, bgPaint)
        }

        // Shadow
        if (shadowRadius > 0f && shadowColor.alpha > 0f) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = shadowColor.toArgb()
                maskFilter = BlurMaskFilter(
                    shadowRadius * density,
                    BlurMaskFilter.Blur.NORMAL
                )
            }

            canvas.save()
            canvas.translate(
                shadowOffset.x * density,
                shadowOffset.y * density
            )
            canvas.drawPath(textPath, shadowPaint)
            canvas.restore()
        }

        // Stroke
        if (strokeWidth > 0f) {
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = strokeColor.toArgb()
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                this.strokeWidth = strokeWidth * fontSize * 0.08f
            }
            canvas.drawPath(textPath, strokePaint)
        }

        // Fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = textColor.copy(alpha = opacity).toArgb()
        }
        canvas.drawPath(textPath, fillPaint)

        canvas.restore()
    }

    private fun getOrCreateTextPath(
        text: String,
        fontSize: Float,
        typeface: Typeface,
        letterSpacing: Float
    ): Path {
        val key = CacheKey(
            text = text,
            fontSize = fontSize,
            typefaceHash = typeface.hashCode(),
            letterSpacing = letterSpacing
        )

        pathCache[key]?.let { return it }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = fontSize
            this.typeface = typeface
            textAlign = Paint.Align.LEFT
        }

        val fm = paint.fontMetrics
        val baseline = -fm.top

        val path = Path()

        if (letterSpacing == 0f) {
            paint.getTextPath(text, 0, text.length, 0f, baseline, path)
        } else {
            var cursorX = 0f
            text.forEach { ch ->
                val s = ch.toString()
                val glyphPath = Path()
                paint.getTextPath(s, 0, 1, cursorX, baseline, glyphPath)
                path.addPath(glyphPath)
                cursorX += paint.measureText(s) + letterSpacing
            }
        }

        // Center path perfectly on both X and Y axes
        val bounds = RectF()
        path.computeBounds(bounds, true)

        val centerMatrix = Matrix().apply {
            // FIX: Center vertically as well as horizontally
            postTranslate(-bounds.centerX(), -bounds.centerY())
        }
        path.transform(centerMatrix)

        pathCache[key] = path
        return path
    }

    private fun resolveTypeface(
        family: FontFamily,
        isBold: Boolean,
        context: Context?
    ): Typeface {
        val customTypeface = context?.let {
            try {
                when (family) {
                    FontCollections.fonts[0].family -> ResourcesCompat.getFont(it, R.font.archivoblack_regular)
                    FontCollections.fonts[1].family -> ResourcesCompat.getFont(it, R.font.montserrat_regular)
                    FontCollections.fonts[2].family -> ResourcesCompat.getFont(it, R.font.opensans_regular)
                    FontCollections.fonts[3].family -> ResourcesCompat.getFont(it, R.font.roboto_regular)
                    FontCollections.fonts[4].family -> ResourcesCompat.getFont(it, R.font.robotomono_regular)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        val base = customTypeface ?: when (family) {
            FontFamily.Serif -> Typeface.SERIF
            FontFamily.SansSerif -> Typeface.SANS_SERIF
            FontFamily.Monospace -> Typeface.MONOSPACE
            FontFamily.Cursive -> Typeface.create("cursive", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }

        return if (isBold && customTypeface == null) {
            Typeface.create(base, Typeface.BOLD)
        } else {
            base
        }
    }

    fun clearCache() {
        pathCache.clear()
    }
}