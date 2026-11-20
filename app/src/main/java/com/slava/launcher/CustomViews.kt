package com.slava.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WallpaperScrimView(context: Context) : View(context) {
    var scrimMode: Int = Prefs.SCRIM_MEDIUM
        set(value) {
            field = value
            rebuildShader()
            invalidate()
        }

    var drawerProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    // Горизонтальний свайп: від -1f (свайп вліво -> права дія) до +1f (свайп вправо -> ліва дія)
    var horizontalSwipeProgress: Float = 0f
        set(value) {
            field = value.coerceIn(-1.25f, 1.25f)
            invalidate()
        }

    var activeSwipeLabel: String = ""

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawerOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val pillRect = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    private fun rebuildShader() {
        val h = height.toFloat()
        if (h <= 0f) return

        if (scrimMode == Prefs.SCRIM_AMOLED) {
            gradientPaint.shader = null
            gradientPaint.color = Color.BLACK
            return
        }

        val (topAlpha, midAlpha, bottomAlpha) = when (scrimMode) {
            Prefs.SCRIM_LIGHT -> Triple(0x4D, 0x22, 0x99)   // ~30% -> 13% -> 60%
            Prefs.SCRIM_DEEP -> Triple(0xB3, 0x88, 0xEB)    // ~70% -> 53% -> 92%
            else -> Triple(0x80, 0x48, 0xCC)                // ~50% -> 28% -> 80% (Medium)
        }

        val colors = intArrayOf(
            Color.argb(topAlpha, 4, 6, 10),
            Color.argb(midAlpha, 4, 6, 10),
            Color.argb(bottomAlpha, 4, 6, 10),
            Color.argb(min(255, bottomAlpha + 24), 2, 3, 6)
        )
        val positions = floatArrayOf(0f, 0.34f, 0.72f, 1f)
        gradientPaint.color = Color.BLACK
        gradientPaint.shader = LinearGradient(0f, 0f, 0f, h, colors, positions, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Базовий скрім над шпалерами
        canvas.drawRect(0f, 0f, w, h, gradientPaint)

        // 2. Додаткове затемнення при відкритті шторки всіх програм
        if (drawerProgress > 0.001f) {
            val alpha = (drawerProgress * 228f).toInt().coerceIn(0, 235)
            drawerOverlayPaint.color = Color.argb(alpha, 5, 7, 12)
            canvas.drawRect(0f, 0f, w, h, drawerOverlayPaint)
        }

        // 3. Живий індикатор горизонтального свайпу (Камера / Стан / Телефон)
        val p = horizontalSwipeProgress
        if (abs(p) > 0.05f && activeSwipeLabel.isNotBlank()) {
            val norm = min(1f, abs(p))
            val triggered = abs(p) >= 1f
            val label = if (p > 0) "→  $activeSwipeLabel" else "$activeSwipeLabel  ←"
            val textW = pillTextPaint.measureText(label)
            val padH = dp(20f)
            val pillW = textW + padH * 2
            val pillH = dp(44f)
            val centerY = h * 0.50f

            val travel = dp(28f) * norm
            val left = if (p > 0) {
                dp(20f) + travel - (1f - norm) * dp(24f)
            } else {
                w - dp(20f) - pillW - travel + (1f - norm) * dp(24f)
            }

            pillRect.set(left, centerY - pillH / 2f, left + pillW, centerY + pillH / 2f)

            val bgAlpha = (norm * if (triggered) 235f else 175f).toInt().coerceIn(0, 240)
            pillPaint.color = if (triggered) {
                Color.argb(bgAlpha, 255, 255, 255)
            } else {
                Color.argb(bgAlpha, 22, 26, 34)
            }
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillPaint)

            pillStrokePaint.color = Color.argb((norm * 110f).toInt(), 255, 255, 255)
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillStrokePaint)

            pillTextPaint.color = if (triggered) {
                Color.argb(255, 10, 12, 16)
            } else {
                Color.argb((norm * 245f).toInt().coerceIn(0, 255), 255, 255, 255)
            }
            val fm = pillTextPaint.fontMetrics
            val textY = pillRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, pillRect.left + padH, textY, pillTextPaint)
        }
    }
}

class AlphabetSidebarView(
    context: Context,
    private val onLetterSelected: (String) -> Unit
) : View(context) {

    private var letters: List<String> = emptyList()
    private var activeIndex: Int = -1
    private var isTouchingSidebar: Boolean = false

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val activeLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setAvailableLetters(newLetters: List<String>) {
        if (letters != newLetters) {
            letters = newLetters
            activeIndex = -1
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val topPad = dp(12f)
        val bottomPad = dp(12f)
        val availH = max(1f, h - topPad - bottomPad)
        val step = availH / letters.size.toFloat()

        val textSize = min(dp(11.5f), step * 0.78f).coerceAtLeast(dp(8.5f))
        letterPaint.textSize = textSize
        activeLetterPaint.textSize = textSize * 1.22f

        val cx = w * 0.62f
        for (i in letters.indices) {
            val cy = topPad + step * (i + 0.5f)
            val paint = if (i == activeIndex && isTouchingSidebar) activeLetterPaint else letterPaint
            val fm = paint.fontMetrics
            val ty = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(letters[i], cx, ty, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isTouchingSidebar = true
                val topPad = dp(12f)
                val bottomPad = dp(12f)
                val availH = max(1f, height - topPad - bottomPad)
                val ratio = ((event.y - topPad) / availH).coerceIn(0f, 0.9999f)
                val idx = (ratio * letters.size).toInt().coerceIn(0, letters.lastIndex)
                if (idx != activeIndex) {
                    activeIndex = idx
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLetterSelected(letters[idx])
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouchingSidebar = false
                activeIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
