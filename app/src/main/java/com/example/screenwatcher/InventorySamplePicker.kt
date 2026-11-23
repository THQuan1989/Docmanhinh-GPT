package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Overlay cho người dùng chạm chọn trực tiếp các ô vật phẩm làm mẫu GIỮ. */
class InventorySamplePicker(
    private val ctx: Context,
    private val frame: Bitmap,
    private val ruleId: String,
    regions: List<Region>,
    private val onDone: (Int) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val size = InventoryVision.cellSizePx(ctx)
    private val cells = buildList {
        val regs = regions
        regs.forEach { rg ->
            val l = rg.l.coerceIn(0, frame.width); val t = rg.t.coerceIn(0, frame.height)
            val r = rg.r.coerceIn(l, frame.width); val b = rg.b.coerceIn(t, frame.height)
            var y = t
            while (y + size <= b) {
                var x = l
                while (x + size <= r) { add(Rect(x, y, x + size, y + size)); x += size }
                y += size
            }
        }
    }
    private val selected = LinkedHashSet<Int>()
    private val view = GridView()
    private val bar = LinearLayout(ctx)
    private val status = TextView(ctx)
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun params(h: Int, gravity: Int, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags, PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    private fun button(label: String, weight: Float, action: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        setOnClickListener { action() }
    }

    fun show() {
        bar.orientation = LinearLayout.VERTICAL; bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        bar.background = GradientDrawable().apply { setColor(Color.parseColor("#E6202020")) }
        status.setTextColor(Color.WHITE); status.textSize = 13f
        bar.addView(status)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("💾 Lưu mẫu", 1.2f) {
            var n = 0
            selected.forEach { if (InventoryVision.addSample(ctx, ruleId, frame, cells[it])) n++ }
            hide(); onDone(n)
        })
        row.addView(button("Bỏ chọn", 0.9f) { selected.clear(); refresh() })
        row.addView(button("Huỷ", 0.8f) { hide(); onCancel() })
        bar.addView(row); refresh()
        wm.addView(view, params( WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN))
        wm.addView(bar, params(WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply { y = dp(40) })
    }
    fun hide() { runCatching { wm.removeView(view) }; runCatching { wm.removeView(bar) } }
    private fun refresh() { status.text = "Chạm vào từng ô vật phẩm muốn GIỮ (xanh). Đã chọn: ${selected.size}. Ô còn lại sẽ được đưa vào JOB."; view.invalidate() }

    private inner class GridView : View(ctx) {
        private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private var downX = 0f; private var downY = 0f
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x33000000)
            val loc = IntArray(2); getLocationOnScreen(loc); canvas.save(); canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            cells.forEachIndexed { index, rect ->
                val keep = index in selected
                val color = if (keep) Color.rgb(46, 204, 113) else Color.rgb(255, 152, 0)
                fill.color = color; fill.alpha = if (keep) 70 else 28
                canvas.drawRect(RectF(rect), fill)
                stroke.color = color; canvas.drawRect(RectF(rect), stroke)
            }
            canvas.restore()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY }
                MotionEvent.ACTION_UP -> if (kotlin.math.hypot((event.rawX-downX).toDouble(), (event.rawY-downY).toDouble()) < dp(16)) {
                    val hit = cells.indices.firstOrNull { cells[it].contains(event.rawX.toInt(), event.rawY.toInt()) }
                    if (hit != null) { if (!selected.add(hit)) selected.remove(hit); refresh() }
                }
            }
            return true
        }
    }
}
