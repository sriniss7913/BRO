package com.example.bro

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: View
    private lateinit var bubbleParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        bubble = LayoutInflater.from(this)
            .inflate(R.layout.bubble_layout, null)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.x = 100
        bubbleParams.y = 300

        try {
            windowManager.addView(bubble, bubbleParams)
            setupBubble()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun setupBubble() {

        bubble.setOnTouchListener(object : View.OnTouchListener {

            private var initialX = 0
            private var initialY = 0

            private var initialTouchX = 0f
            private var initialTouchY = 0f

            private var moved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        moved = false

                        initialX = bubbleParams.x
                        initialY = bubbleParams.y

                        initialTouchX = event.rawX
                        initialTouchY = event.rawY

                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {

                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (kotlin.math.abs(dx) > 10 ||
                            kotlin.math.abs(dy) > 10
                        ) {
                            moved = true
                        }

                        bubbleParams.x = initialX + dx
                        bubbleParams.y = initialY + dy

                        windowManager.updateViewLayout(
                            bubble,
                            bubbleParams
                        )

                        return true
                    }

                    MotionEvent.ACTION_UP -> {

                        if (!moved) {

                            val intent = Intent(
                                this@OverlayService,
                                MainActivity::class.java
                            )

                            intent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )

                            startActivity(intent)
                        }

                        return true
                    }
                }

                return false
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()

        if (bubble.parent != null) {
            windowManager.removeView(bubble)
        }
    }
}