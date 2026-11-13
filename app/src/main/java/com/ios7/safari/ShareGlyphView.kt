package com.ios7.safari

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * iOS 7 分享面板里「功能」那一行的图标：**灰色细线圆角矩形 + 灰色细线图形**。
 *
 * 颜色跟行间分隔线同色（`GRAY`），不是蓝色 —— 真机截图里那一行就是灰的。
 * 五种图形都在一个 24×24 的坐标系里定义，再按控件尺寸等比放大，
 * 所以任何屏幕密度下线条粗细、留白都一致。
 */
class ShareGlyphView(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val BOOKMARK = 0      // 书签：摊开的书
        const val READING = 1       // 阅读清单：眼镜
        const val HOME = 2          // 添加到主屏幕：方框里的加号
        const val COPY = 3          // 复制：两张斜错开的纸
        const val PRINT = 4         // 打印：打印机

        /** 细线颜色 = 分享面板行间分隔线的灰（比发丝线深一档，否则 1.2dp 的细线会发白） */
        const val GRAY = 0xFFB8B7BE.toInt()

        /** 外框线宽 = 图标边长 × 这个比例（60dp 图标 → 1.2dp，和面板里的分隔线同宽） */
        const val FRAME_STROKE_RATIO = 0.02f
    }

    /** 线条颜色（灰） */
    var strokeColor: Int = GRAY
        set(value) { field = value; invalidate() }

    /** 圆角矩形的填充色 —— 填成面板底色，看起来就是"透明"的；复制图标里还要拿它遮住后面那张纸 */
    var fillColor: Int = Color.TRANSPARENT
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rr = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // iOS 的 app 图标是圆角矩形：圆角 ≈ 边长的 0.2235
        val r = w * 0.2235f
        rr.set(0f, 0f, w, h)

        if (fillColor != Color.TRANSPARENT) {
            fill.color = fillColor
            canvas.drawRoundRect(rr, r, r, fill)
        }

        line.color = strokeColor
        // 外框：细线（60dp 图标上约 1.2dp），随尺寸缩放
        val sw = w * FRAME_STROKE_RATIO
        line.strokeWidth = sw
        canvas.drawRoundRect(RectF(rr).also { it.inset(sw / 2f, sw / 2f) }, r, r, line)

        // 里面的图形：按 24×24 设计，四周留 16% 的边距
        val pad = w * 0.16f
        val s = (w - pad * 2f) / 24f
        canvas.save()
        canvas.translate(pad, pad)
        canvas.scale(s, s)
        // 设计坐标会被缩放 s 倍，所以这里要除回 s —— 保证内部图形与外框**物理线宽完全一致**
        line.strokeWidth = sw / s
        when (kind) {
            BOOKMARK -> drawBook(canvas)
            READING -> drawGlasses(canvas)
            HOME -> drawPlusSquare(canvas)
            COPY -> drawCopy(canvas)
            PRINT -> drawPrinter(canvas)
        }
        canvas.restore()
    }

    /** 摊开的书：两页 + 中缝 */
    private fun drawBook(c: Canvas) {
        path.reset()
        path.moveTo(3f, 5.5f)
        path.cubicTo(6.5f, 3.8f, 9.5f, 4.6f, 12f, 6.2f)
        path.cubicTo(14.5f, 4.6f, 17.5f, 3.8f, 21f, 5.5f)
        path.lineTo(21f, 18.5f)
        path.cubicTo(17.5f, 16.8f, 14.5f, 17.6f, 12f, 19.2f)
        path.cubicTo(9.5f, 17.6f, 6.5f, 16.8f, 3f, 18.5f)
        path.close()
        c.drawPath(path, line)
        c.drawLine(12f, 6.2f, 12f, 19.2f, line)
    }

    /** 眼镜（iOS 的"添加到阅读列表"就是一副眼镜） */
    private fun drawGlasses(c: Canvas) {
        c.drawCircle(6.5f, 13f, 4.2f, line)
        c.drawCircle(17.5f, 13f, 4.2f, line)
        c.drawLine(10.7f, 12.4f, 13.3f, 12.4f, line)   // 鼻梁
        c.drawLine(2.3f, 11.4f, 3.6f, 12.2f, line)     // 左镜腿
        c.drawLine(21.7f, 11.4f, 20.4f, 12.2f, line)   // 右镜腿
    }

    /** 方框里的加号（添加到主屏幕） */
    private fun drawPlusSquare(c: Canvas) {
        rr.set(3.5f, 3.5f, 20.5f, 20.5f)
        c.drawRoundRect(rr, 4.5f, 4.5f, line)
        c.drawLine(12f, 8.5f, 12f, 15.5f, line)
        c.drawLine(8.5f, 12f, 15.5f, 12f, line)
    }

    /**
     * 复制：**两张纸斜着错开** —— 后面那张整体偏右上一点点；
     * 两张纸的**右上角都折了一个角**（斜切 + 一条折痕），和真机图标一致。
     */
    private fun drawCopy(c: Canvas) {
        // 后面那张（偏右上）
        pagePath(6.2f, 3.6f, 18.2f, 16.4f, 3.2f)
        c.drawPath(path, line)

        // 前面那张：先用底色把重叠部分盖掉，再描线 —— 否则会看到后面那张穿过来
        pagePath(3.4f, 7.4f, 15.4f, 20.4f, 3.2f)
        mask.color = fillColor
        if (fillColor != Color.TRANSPARENT) c.drawPath(path, mask)
        c.drawPath(path, line)
    }

    /** 图标左上角折角的纸片轮廓：(x0,y0) 左上、(x1,y1) 右下、fold = 折角边长 */
    private fun pagePath(x0: Float, y0: Float, x1: Float, y1: Float, fold: Float) {
        path.reset()
        path.moveTo(x0, y0)
        path.lineTo(x1 - fold, y0)        // 上边
        path.lineTo(x1, y0 + fold)        // 斜切（折角）
        path.lineTo(x1, y1)               // 右边
        path.lineTo(x0, y1)               // 下边
        path.close()
        // 折痕：从折角的两个端点往内收一点，画一条短斜线
        val inner = 0.55f
        path.moveTo(x1 - fold + fold * inner, y0 + 0.2f)
        path.lineTo(x1 - 0.2f, y0 + fold - fold * inner)
    }

    /** 打印机：上面那张纸要**扁一点**，下面是机身和出纸口 */
    private fun drawPrinter(c: Canvas) {
        // 上面那张纸（扁）
        c.drawRect(6.5f, 5f, 17.5f, 8.6f, line)
        // 机身
        rr.set(3f, 8.6f, 21f, 16.2f)
        c.drawRoundRect(rr, 2f, 2f, line)
        // 出纸
        c.drawRect(6.5f, 13.2f, 17.5f, 20.2f, line)
        // 指示灯
        fill.color = strokeColor
        c.drawCircle(18f, 11.3f, 0.85f, fill)
    }
}
