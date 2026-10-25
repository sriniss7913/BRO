package com.example.bro.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.bro.accessibility.BroAccessibilityService

/**
 * Manages an ambient glowing edge overlay around the phone display perimeter
 * while BRO is executing automated tasks in the foreground/background.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY from BroAccessibilityService (touch-through, no focus).
 */
object EdgeGlowOverlayManager {

    private const val TAG = "EdgeGlowOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var overlayView: EdgeGlowView? = null
    @Volatile
    private var windowManager: WindowManager? = null
    @Volatile
    private var isShowing = false

    fun show(context: Context) {
        mainHandler.post {
            if (isShowing) return@post

            val wm = getWindowManager(context) ?: run {
                Log.w(TAG, "Cannot show edge glow: WindowManager unavailable")
                return@post
            }

            val overlayType = if (BroAccessibilityService.instance != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(context)) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                Log.d(TAG, "Overlay permission / accessibility overlay unavailable")
                return@post
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )

            try {
                val view = EdgeGlowView(context)
                wm.addView(view, params)
                overlayView = view
                windowManager = wm
                isShowing = true
                view.startGlowAnimation()
                Log.d(TAG, "Edge glow overlay displayed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach edge glow overlay", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            if (!isShowing) return@post
            try {
                overlayView?.let { view ->
                    view.stopGlowAnimation()
                    windowManager?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing edge glow overlay", e)
            } finally {
                overlayView = null
                windowManager = null
                isShowing = false
                Log.d(TAG, "Edge glow overlay removed")
            }
        }
    }

    private fun getWindowManager(context: Context): WindowManager? {
        val service = BroAccessibilityService.instance
        if (service != null) {
            return service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        return context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    /**
     * Custom View that paints a pulsating neon glow around the 4 display edges.
     */
    private class EdgeGlowView(context: Context) : View(context) {

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowColor = Color.argb(255, 0, 229, 255) // Cyberpunk Jarvis Cyan
        private val glowColorAccent = Color.argb(255, 0, 140, 255) // Electric Blue

        private var pulseFraction = 0.5f
        private var animator: ValueAnimator? = null
        private val glowThickness = (10 * resources.displayMetrics.density)

        init {
            // Touch-through guarantee
            isClickable = false
            isFocusable = false
        }

        fun startGlowAnimation() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
                duration = 1100
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    pulseFraction = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopGlowAnimation() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val thickness = glowThickness * (0.85f + 0.3f * pulseFraction)
            val alphaInt = (220 * pulseFraction).toInt().coerceIn(0, 255)
            val currentPrimary = Color.argb(alphaInt, 0, 229, 255)
            val currentAccent = Color.argb((alphaInt * 0.7f).toInt(), 0, 140, 255)
            val transparentColor = Color.TRANSPARENT

            // 1. Top Edge
            glowPaint.shader = LinearGradient(
                0f, 0f, 0f, thickness,
                intArrayOf(currentPrimary, currentAccent, transparentColor),
                floatArrayOf(0f, 0.4f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, thickness, glowPaint)

            // 2. Bottom Edge
            glowPaint.shader = LinearGradient(
                0f, h, 0f, h - thickness,
                intArrayOf(currentPrimary, currentAccent, transparentColor),
                floatArrayOf(0f, 0.4f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h - thickness, w, h, glowPaint)

            // 3. Left Edge
            glowPaint.shader = LinearGradient(
                0f, 0f, thickness, 0f,
                intArrayOf(currentPrimary, currentAccent, transparentColor),
                floatArrayOf(0f, 0.4f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, thickness, h, glowPaint)

            // 4. Right Edge
            glowPaint.shader = LinearGradient(
                w, 0f, w - thickness, 0f,
                intArrayOf(currentPrimary, currentAccent, transparentColor),
                floatArrayOf(0f, 0.4f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(w - thickness, 0f, w, h, glowPaint)

            // 5. Corner Accents (Radial Gradients for seamless rounded display borders)
            val cornerRadius = thickness * 2f
            glowPaint.shader = RadialGradient(
                0f, 0f, cornerRadius,
                currentPrimary, transparentColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, cornerRadius, cornerRadius, glowPaint)

            glowPaint.shader = RadialGradient(
                w, 0f, cornerRadius,
                currentPrimary, transparentColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(w - cornerRadius, 0f, w, cornerRadius, glowPaint)

            glowPaint.shader = RadialGradient(
                0f, h, cornerRadius,
                currentPrimary, transparentColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h - cornerRadius, cornerRadius, h, glowPaint)

            glowPaint.shader = RadialGradient(
                w, h, cornerRadius,
                currentPrimary, transparentColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(w - cornerRadius, h - cornerRadius, w, h, glowPaint)
        }
    }
}
