package com.jadegenesis.mobile.screen

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jadegenesis.mobile.MainActivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FocusCropActivity : Activity() {

    private lateinit var repository: ScreenObserverRepository
    private var sourceBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ScreenObserverRepository(this)
        val bitmap = repository.latestBitmap()
        if (bitmap == null) {
            Toast.makeText(this, "Aucune image récente à cadrer.", Toast.LENGTH_LONG).show()
            openJadeAndFinish()
            return
        }
        sourceBitmap = bitmap

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(
            TextView(this).apply {
                text = "Choisis ce que Jade doit regarder"
                textSize = 22f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            TextView(this).apply {
                text = "Glisse un rectangle sur l'image. Tu peux aussi garder tout l'écran puis préciser ta demande en dessous."
                textSize = 15f
                setPadding(0, dp(6), 0, dp(10))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val cropView = CropSelectionView(this, bitmap)
        root.addView(
            cropView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val instruction = EditText(this).apply {
            hint = "Ex : regarde uniquement cette erreur et identifie sa cause"
            minLines = 2
            maxLines = 4
            setText(repository.latestFocusInstruction())
        }
        root.addView(
            instruction,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val fullButton = Button(this).apply {
            text = "Tout l'écran"
            setOnClickListener { cropView.selectAll() }
        }
        val cancelButton = Button(this).apply {
            text = "Annuler"
            setOnClickListener { openJadeAndFinish() }
        }
        val useButton = Button(this).apply {
            text = "Utiliser cette zone"
            setOnClickListener {
                runCatching {
                    val rect = cropView.selectedBitmapRect()
                    require(rect.width() >= 8 && rect.height() >= 8) {
                        "La zone sélectionnée est trop petite."
                    }
                    val focused = Bitmap.createBitmap(
                        bitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                    try {
                        repository.saveBitmap(
                            bitmap = focused,
                            source = repository.latestSource().ifBlank { "focused_image" },
                            focusInstruction = instruction.text?.toString().orEmpty()
                        )
                    } finally {
                        focused.recycle()
                    }
                }.onSuccess {
                    Toast.makeText(
                                  this@FocusCropActivity,
                                  "Zone prête. Dans Jade, touche « Analyser l'image ciblée ».",
                        Toast.LENGTH_LONG
                    ).show()
                    openJadeAndFinish()
                }.onFailure { error ->
                    Toast.makeText(
                                  this@FocusCropActivity,
                                  error.message ?: "Impossible de préparer cette zone.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        buttons.addView(
            fullButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttons.addView(
            cancelButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttons.addView(
            useButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
        )
        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        setContentView(root)
    }

    private fun openJadeAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    override fun onDestroy() {
        sourceBitmap?.recycle()
        sourceBitmap = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}

private class CropSelectionView(
    context: android.content.Context,
    private val bitmap: Bitmap
) : View(context) {

    private val imageRect = RectF()
    private val selection = RectF()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val shadePaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateImageRect(w.toFloat(), h.toFloat())
        selectAll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(bitmap, null, imageRect, null)
        if (selection.width() <= 0f || selection.height() <= 0f) return

        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, selection.top, shadePaint)
        canvas.drawRect(imageRect.left, selection.bottom, imageRect.right, imageRect.bottom, shadePaint)
        canvas.drawRect(imageRect.left, selection.top, selection.left, selection.bottom, shadePaint)
        canvas.drawRect(selection.right, selection.top, imageRect.right, selection.bottom, shadePaint)
        canvas.drawRect(selection, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!imageRect.contains(event.x, event.y)) {
            if (event.action == MotionEvent.ACTION_DOWN) return false
        }
        val x = event.x.coerceIn(imageRect.left, imageRect.right)
        val y = event.y.coerceIn(imageRect.top, imageRect.bottom)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                selection.set(x, y, x, y)
                dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                selection.set(
                    min(downX, x),
                    min(downY, y),
                    max(downX, x),
                    max(downY, y)
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (selection.width() < 20f || selection.height() < 20f) selectAll()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun selectAll() {
        selection.set(imageRect)
        invalidate()
    }

    fun selectedBitmapRect(): Rect {
        val selected = if (selection.width() > 0f && selection.height() > 0f) {
            selection
        } else {
            imageRect
        }
        val left = (((selected.left - imageRect.left) / imageRect.width()) * bitmap.width)
            .roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (((selected.top - imageRect.top) / imageRect.height()) * bitmap.height)
            .roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (((selected.right - imageRect.left) / imageRect.width()) * bitmap.width)
            .roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (((selected.bottom - imageRect.top) / imageRect.height()) * bitmap.height)
            .roundToInt().coerceIn(top + 1, bitmap.height)
        return Rect(left, top, right, bottom)
    }

    private fun calculateImageRect(viewWidth: Float, viewHeight: Float) {
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val viewRatio = viewWidth / viewHeight.coerceAtLeast(1f)
        if (bitmapRatio > viewRatio) {
            val drawnWidth = viewWidth
            val drawnHeight = drawnWidth / bitmapRatio
            val top = (viewHeight - drawnHeight) / 2f
            imageRect.set(0f, top, drawnWidth, top + drawnHeight)
        } else {
            val drawnHeight = viewHeight
            val drawnWidth = drawnHeight * bitmapRatio
            val left = (viewWidth - drawnWidth) / 2f
            imageRect.set(left, 0f, left + drawnWidth, drawnHeight)
        }
    }
}
