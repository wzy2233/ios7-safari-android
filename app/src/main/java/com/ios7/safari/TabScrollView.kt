package com.ios7.safari

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * 标签页列表的滚动容器。
 *
 * 除了正常滚动，它还负责识别「已经滑到尽头了还继续拽」这段越界行程，
 * 并把它报告给外面 —— 卡片会据此变稀疏 / 改变俯角 / 整摞让位；
 * 手指拖动松手时由外面的软弹簧弹回，惯性甩过头的则**不回位**，停在那儿。
 *
 * 惯性滚动（fling）是自己实现的，不用系统的 OverScroller：
 *  - 初速打折 + 指数衰减，滚得更慢更稳，不会一甩就飞出去；
 *  - 撞到边界时不会「高速 → 0」硬停：把剩余速度的一部分塞进越界位移（橡皮筋），
 *    然后让速度快速衰减耗尽，所以到边界是「顺势被吃掉」，不是急刹。
 */
class TabScrollView(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {

    /** dy > 0：在顶部继续往下拽；dy < 0：在底部继续往上拽 */
    var onOverscroll: ((Float) -> Unit)? = null

    /**
     * 越界结束（手指离开 / 惯性速度耗尽时回调）。
     *
     * 以前是「onOverscroll(0f) 既表示越界结束、又表示松手」，于是手指只要停顿一帧
     * （dy == 0）或者反向一帧，橡皮筋就被当成松手弹回去 —— 实测拖 900px 只攒到 86px。
     * 现在把「结束」单独拆出来，只有 ACTION_UP / CANCEL 或惯性耗尽才触发。
     *
     * 参数 `fromFling`：这次越界是「手指拖动」造成的（false）还是「惯性甩过头」造成的（true）。
     * 后者**不回位**（甩到底就停住，再弹一下很别扭），只有前者交给软弹簧。
     */
    var onOverscrollEnd: ((fromFling: Boolean) -> Unit)? = null

    /** 进出场动画期间锁死滚动：英雄视图是列表的子视图，一滚动它就会跟着内容跑 */
    var blocked: (() -> Boolean)? = null

    private var lastY = Float.NaN
    private var pulling = false

    // ---------- 自定义惯性滚动 ----------
    /** 当前甩动速度（px/s，正 = 内容向上走、scrollY 增大） */
    private var flingV = 0f
    private var flingT = 0L
    private var flinging = false
    /** 惯性甩到边界、正在「冲过头」；速度耗尽后就停在那儿（外面不回位） */
    private var edgeBouncing = false

    private companion object {
        /** 初速打折：越小越慢稳 */
        const val FLING_SCALE = 0.62f
        /** 每秒衰减系数：越大停得越快（系统大约 4.5~6） */
        const val FLING_DECAY = 3.4f
        /** 低于这个速度就停（px/s） */
        const val FLING_MIN = 60f
        /** 撞边时，剩余位移有多少转成「冲过头」的位移（0~1） */
        const val EDGE_FEED = 0.55f
        /**
         * 撞边后的衰减系数（比正常大很多）。
         * 已经到边界了，那股速度没地方去，留着只会让「冲过头」拖到一秒多、最后还要顿一下；
         * 这里让它 0.3~0.4 秒内就吃完，停在冲过去的那一点位置上。
         */
        const val EDGE_DECAY = 9f
    }

    private val flingTick = object : Runnable {
        override fun run() {
            if (stepFling()) postOnAnimation(this)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (blocked?.invoke() == true) return true
        track(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // 手指按住就先停下惯性；如果正卡在"冲过头"里，通知外面「这段结束了」
            // （按惯性处理：外面不回位，卡片就停在当前这一口气上）
            stopFling()
            if (edgeBouncing) {
                edgeBouncing = false
                onOverscrollEnd?.invoke(true)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun fling(velocityY: Int) {
        if (abs(velocityY) < FLING_MIN) return
        flingV = velocityY * FLING_SCALE
        flingT = SystemClock.uptimeMillis()
        flinging = true
        edgeBouncing = false
        removeCallbacks(flingTick)
        postOnAnimation(flingTick)
    }

    private fun stopFling() {
        removeCallbacks(flingTick)
        flinging = false
        flingV = 0f
    }

    /** 走一帧惯性；返回 false 表示已停 */
    private fun stepFling(): Boolean {
        if (!flinging) return false
        // 进出场动画期间停掉惯性：以前只挡住了触摸（dispatchTouchEvent），
        // 已经在跑的惯性还会继续 scrollBy —— 卡片会在英雄动画下面偷偷位移，
        // 退场算好的落点全错，会有一张卡停在页面上。
        if (blocked?.invoke() == true) {
            stopFling()
            return false
        }
        val now = SystemClock.uptimeMillis()
        var dt = (now - flingT) / 1000f
        flingT = now
        if (dt <= 0f) return true
        dt = dt.coerceAtMost(0.032f)              // 掉帧时别一下冲太远

        val maxY = max(0, (getChildAt(0)?.height ?: 0) - height)
        val target = scrollY + flingV * dt
        val clamped = target.coerceIn(0f, maxY.toFloat())

        if (clamped != target) {
            // 撞到边界：位置停在边界，多出来的这一段变成「冲过头」的位移（只去不回），
            // 速度快速衰减，耗尽后停在那儿。
            if (scrollY != clamped.toInt()) scrollTo(0, clamped.toInt())
            val over = target - clamped
            onOverscroll?.invoke(-over * EDGE_FEED)
            edgeBouncing = true
        } else if (scrollY != target.toInt()) {
            scrollTo(0, target.toInt())
        }

        flingV *= exp(-(if (edgeBouncing) EDGE_DECAY else FLING_DECAY) * dt)
        if (abs(flingV) < FLING_MIN) {
            stopFling()
            if (edgeBouncing) {
                edgeBouncing = false
                onOverscrollEnd?.invoke(true)      // 惯性造成的越界 → 不回位（外面直接 return）
            }
            return false
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopFling()
        super.onDetachedFromWindow()
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = ev.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (lastY.isNaN()) {
                    lastY = ev.y
                    return
                }
                val dy = ev.y - lastY
                lastY = ev.y
                val content = getChildAt(0)?.height ?: 0
                val maxY = max(0, content - height)
                when {
                    scrollY <= 0 && dy > 0f -> {
                        pulling = true
                        onOverscroll?.invoke(dy)
                    }
                    scrollY >= maxY && dy < 0f -> {
                        pulling = true
                        onOverscroll?.invoke(dy)
                    }
                    // 方向反转/位移为 0：只是不再越界累积，**不算松手**
                    pulling -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastY = Float.NaN
                if (pulling) {
                    pulling = false
                    onOverscrollEnd?.invoke(false)
                }
            }
        }
    }
}
