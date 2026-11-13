package com.ios7.safari

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.exp

/**
 * 能把内部滚动回调出来的 WebView —— 用于实现 iOS 7 Safari 的「下滑收起工具栏」，
 * 以及和任务视图同一套的惯性滚动。
 *
 * 惯性部分：WebView 自己的 fling 是 native 的、没有 API 能调曲线，所以这里在「快速甩动」时接管：
 * 把 ACTION_UP 换成 ACTION_CANCEL 交给 WebView（它的手势正常收尾、页面也会收到 touchcancel），
 * 于是它不会自己起惯性；随后用和 `TabScrollView` 完全相同的衰减参数跑我们自己的 fling。
 * 慢拖、点击、双指缩放都不接管（速度阈值 + 单指 + 纵向为主，三重判定）。
 */
class ObservableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    /** (当前滚动位置, 本次位移) */
    var onScroll: ((top: Int, delta: Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke(t, t - oldt)
    }

    // ---------- 自己实现的惯性（参数与 TabScrollView 一致） ----------
    private var vt: VelocityTracker? = null
    private var flingV = 0f
    private var flingT = 0L
    private var flinging = false
    /** 不足 1px 的余量，攒够再滚，避免低速时抖 */
    private var carry = 0f
    /** 这次手势里出现过双指 —— 出现就不接管（缩放/平移交回 WebView） */
    private var multiTouch = false

    private companion object {
        /** 初速打折：网页比任务视图留得多一点（网页要滚得动） */
        const val FLING_SCALE = 0.85f
        /** 每秒衰减系数：网页小一点，否则「速度掉太快」手感发沉 */
        const val FLING_DECAY = 1.8f
        /** 低于这个速度就停（px/s） */
        const val FLING_MIN = 60f
        /** 低于这个速度不接管（慢拖 / 点击 / 小幅拖动完全走原生逻辑） */
        const val TAKE_OVER_MIN = 1100f
    }

    private val flingTick = object : Runnable {
        override fun run() {
            if (stepFling()) postOnAnimation(this)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 1) multiTouch = true
        val tracker = vt ?: VelocityTracker.obtain().also { vt = it }
        tracker.addMovement(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                stopFling()
            }
            MotionEvent.ACTION_UP -> {
                tracker.computeCurrentVelocity(1000)
                val vx = tracker.xVelocity
                val vy = tracker.yVelocity
                releaseTracker()
                if (!multiTouch && abs(vy) > TAKE_OVER_MIN && abs(vy) > abs(vx) * 1.5f) {
                    // UP 换成 CANCEL 喂给 WebView：手势正常收尾，但不会起它自己的惯性
                    val c = MotionEvent.obtain(e)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(c)
                    c.recycle()
                    startFling(vy)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseTracker()
        }
        return super.onTouchEvent(e)
    }

    private fun releaseTracker() {
        vt?.recycle()
        vt = null
    }

    private fun startFling(vy: Float) {
        // VelocityTracker 给的是「手指」速度：手指上滑 = vy<0，而滚动位置要增大。
        // 所以这里取反（系统 ScrollView 内部也是 fling(-v)）。
        flingV = -vy * FLING_SCALE
        flingT = SystemClock.uptimeMillis()
        carry = 0f
        flinging = true
        removeCallbacks(flingTick)
        postOnAnimation(flingTick)
    }

    /** 外部要页面立刻停下惯性滚动（比如点地址栏展开栏时） */
    fun stopFlingNow() = stopFling()

    private fun stopFling() {
        removeCallbacks(flingTick)
        flinging = false
        flingV = 0f
        carry = 0f
    }

    /** 走一帧；false = 停 */
    private fun stepFling(): Boolean {
        if (!flinging) return false
        val now = SystemClock.uptimeMillis()
        var dt = (now - flingT) / 1000f
        flingT = now
        if (dt <= 0f) return true
        dt = dt.coerceAtMost(0.032f)

        carry += flingV * dt
        val step = carry.toInt()
        if (step != 0) {
            carry -= step
            scrollBy(0, step)
            // 到顶 / 到底：直接停，不回弹
            if ((step > 0 && !canScrollVertically(1)) ||
                (step < 0 && !canScrollVertically(-1))
            ) {
                stopFling()
                return false
            }
        }
        flingV *= exp(-FLING_DECAY * dt)
        if (abs(flingV) < FLING_MIN) {
            stopFling()
            return false
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopFling()
        releaseTracker()
        super.onDetachedFromWindow()
    }
}
