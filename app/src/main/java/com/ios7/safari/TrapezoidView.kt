package com.ios7.safari

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「从斜上方俯视」的透视投影：把一张平铺位图画成上宽下窄的倒梯形。
 *
 * 模型：卡片的顶边是最靠近相机的一条边（h = 0，透视系数 1），
 * 越往下 h 越大、离相机越远，于是宽度按 d / (d + h·sinθ) 收窄，
 * 高度按 h·cosθ·d / (d + h·sinθ) 被压缩 —— 这就是 iOS 7 那种挤压变形。
 *
 * θ 相同时所有卡片的倾角完全一致，与卡片尺寸、屏幕密度都无关。
 *
 * 页面 ⇄ 卡片的形变**只用一张位图**：动画中间不会出现两张图交叉淡化
 * （以前试过「页面合成图 + 卡片图」双图层混画，中途会闪一下，已废弃）。
 */
class TrapezoidView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 要绘制的内容（页面合成图或页面截图） */
    var bitmap: Bitmap? = null

    /** 内容横向缩放：顶边宽 = bitmap.width * k */
    var k = 1f

    /** 可见内容的上、下沿在三维空间中的高度（顶边 h = 0） */
    var hStart = 0f
    var hEnd = 0f

    /** 倾角（度） */
    var theta = 0f

    /**
     * 叠在上面的「卡片标题条」：从顶边按透视一条一条吐出来。
     * 它和主位图用**同一个 d**（也就是同一帧的同一个投影），只是绘制范围被裁到
     * `overlayFrac × 自身高度` —— 视觉上就是从卡片上沿那条线里长出一个矩形，
     * 动画收尾时正好盖满整条地址栏，和卡片自己的标题条严丝合缝。
     */
    var overlay: Bitmap? = null

    /** 0 → 完全不显示，1 → 整条标题条都出来了 */
    var overlayFrac = 0f

    /** 相机距离 = dOverH * hEnd */
    var dOverH = Trap.D_OVER_H

    /**
     * 相机距离的**参考高度**：0 = 用 `hEnd`（历史行为，英雄视图用这个）。
     *
     * 卡片必须设成 `cardH`（它自己的静止高度）。为什么：`d = dOverH × hRef`，
     * 如果 hRef 跟着 `hEnd` 走，那么"把卡片沿平面推远"时相机会同步后退，
     * 卡片在屏幕上**几乎不会变小**（实测推远 1.26×cardH 只缩到 0.876）——
     * 等于相机在追着卡片跑。固定参考高度之后，推远才是真正的"近大远小"。
     */
    var hRefBase = 0f

    /** 这一帧真正用的相机参考高度 */
    val hRef: Float get() = if (hRefBase > 0f) hRefBase else hEnd

    /**
     * 沿**卡片自身平面**的 3D 位移（单位同 `hEnd`，正 = 往远处走，也就是屏幕上下方）。
     *
     * 卡片是一块竖着斜放的平面，人从斜上方俯视；所以"卡片往下挪"不是屏幕上的平移，
     * 而是**顺着它自己的平面往远处走**：屏幕上往下、同时因为离相机更远而变小（近大远小）。
     * 直接让 `hStart/hEnd` 一起加 `hShift` 交给 `Trap.draw`，投影公式本身就会算出这个效果，
     * 不需要在平面上做假缩放。
     *
     * `hRef` 必须仍传原来的 `hEnd`：相机距离只跟卡片本身的尺寸有关 ——
     * 跟着 hShift 变的话整张卡的透视强度会被一起改掉（那就不是"挪动"而是"变形"了）。
     */
    /**
     * **屏幕空间**的纵向位移（px，正 = 向下）。
     *
     * 和 [hShift] 分工：
     *  - `screenShift`：换位时"往上一格 / 往下一格"这一步。卡片在屏幕上是平移的，
     *    **大小不变** —— 因为整摞静止时每张卡的渲染尺寸本来就一样（都按 hShift=0 画）。
     *  - `hShift`：只用于"让开"那一下的 3D 下沉（近大远小）。
     *
     * 为什么不能把整格位移也放进 hShift：hShift<0 是往相机方向挪，投影会让卡片
     * **变大**（一格约 +12% 高、+6% 宽）。拖完松手 hShift 归零，卡片就"啪"地缩回去。
     */
    var screenShift = 0f
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /**
     * 被前面那张卡遮住的光：从卡片上沿（正常亮度）往下越来越暗。
     *
     * [shadowDepth] = 从"完全不暗"到"最暗"的距离（屏幕 px），一般就等于 cardStep ——
     * 也就是这一张露出来的那一条的高度：正好在下一张卡的上沿处压到最暗。
     * 最底下那张（没有卡压在它上面）设 0 = 不画阴影。
     */
    var shadowDepth = 0f
        set(value) { if (field != value) { field = value; invalidate() } }

    /** 最暗处的黑度（0~1） */
    var shadowAlpha = 0.45f

    /**
     * 遮光的**强度系数**：0 = 完全不画，1 = 按 [shadowAlpha] 画满。
     *
     * 为什么要有它：进任务视图的那 400ms 里，卡片是从「一整页长方形」慢慢立成倒梯形的，
     * 起步那一帧前面**还没有任何卡片压上来**（其余卡片刚 alpha=0 地飞入），
     * 所以那时候根本不该有"被挡住的光"。阴影必须跟着形变一起从无到有地长出来；
     * 退场（倒梯形 → 长方形）同理，要一路退到没有。
     * 只改 [shadowAlpha] 会让"强度和深度"绑死，改不动深度那条曲线的缓存，所以单独开一个系数。
     */
    var shadowFrac = 1f
        set(value) { if (field != value) { field = value; invalidate() } }

    var hShift = 0f
        set(value) {
            // 亚像素的改动不值得重画（每画一次 = 56 条 drawBitmap；手指慢速移动时这类改动占大多数）
            val v = if (kotlin.math.abs(value) < 0.5f) 0f else value
            if (field == v || kotlin.math.abs(field - v) < 0.5f) return
            field = v
            invalidate()
        }

    /**
     * 缩放的**低通显示值**（滚动时由 `updateTransforms()` 维护）。
     *
     * `scaleX/scaleY` 写的是目标值，每帧的位移都是手指位置直接算出来的 —— 逐帧采样一条
     * 再平滑的曲线也会带进一点点"顿"。这里存一个指数趋近后的值，真正写进 `scaleX/scaleY` 的是它：
     * 卡片的大小会轻轻地拖在位置上后面，松手后自己滑到位（就是"东西有重量"）。
     *
     * 放在 View 上而不是 SparseArray 里：卡片重建时它就是新对象，不需要清理，
     * 也不会出现"下标错位"（换位后下标和标签不再一一对应）。
     */
    var scaleSmooth = 1f

    override fun onDraw(canvas: Canvas) {
        // 还没被布局（宽高 0）时**绝对不能画**：
        // 卡片列表/浮层都设了 clipChildren=false，0 宽的视图不会被裁掉，照样能把位图画出去；
        // 而 cx = width/2 = 0 → 绘制起点变成 left = -bitmapWidth/2，
        // 看上去就是「左上角闪出一张截图」。第一次进任务视图时英雄视图刚 addView，正是这个状态。
        if (width <= 0 || height <= 0) return
        val b1 = bitmap?.takeIf { !it.isRecycled } ?: return
        val cx = width / 2f
        val sh = hShift
        if (screenShift != 0f) canvas.translate(0f, screenShift)
        val href = hRef
        val y0 = if (hStart == 0f) 0f else hStart
        Trap.draw(canvas, b1, k, hStart + sh, hEnd + sh, theta, dOverH, cx, hRef = href, y0 = y0)
        // 标题条：宽度和主位图对齐（k 按两者宽度比换算），高度按 frac 裁
        val ov = overlay?.takeIf { !it.isRecycled }
        val f = overlayFrac
        if (ov != null && f > 0.004f && b1.width > 0 && ov.width > 0) {
            val kOv = k * b1.width.toFloat() / ov.width.toFloat()
            // 标题条必须长在**卡片此刻的上沿**（= hStart）上：推远之后如果还从 h=0 开始画，
            // 它会飘到卡片上面去、而且宽度是没推远时的宽度 —— 退场第一帧就会"错一下"。
            Trap.draw(
                canvas, ov, kOv, hStart + sh, hStart + sh + f * ov.height * kOv, theta, dOverH, cx,
                hRef = href, y0 = y0
            )
        }
        // 遮光画在标题条**之后**：卡片自己的标题条本来就在阴影范围里
        // （普通卡片没有 overlay，阴影一直都是盖整张的）。放在前面的话，
        // 英雄视图的标题条会在阴影下面"亮着"，和动画结束时真正的卡片对不上。
        drawShade(canvas, b1, cx, sh)
    }

    /**
     * 上亮下暗的遮光：用和主位图**同一个投影**算出这一段的梯形四条边，
     * 再用一条竖直渐变填进去（顶边透明 → shadowDepth 处最黑，之后保持）。
     * 因为梯形的两条侧边在透视里仍是直线，这里直接画四边形，不用逐条带。
     */
    private fun drawShade(canvas: Canvas, bmp: Bitmap, cx: Float, sh: Float) {
        val sd = shadowDepth
        val strength = shadowAlpha * shadowFrac
        if (sd <= 1f || strength <= 0.002f) return
        val hS = hStart + sh
        val hE = hEnd + sh
        if (hE <= hS + 0.5f) return
        val href = hRef
        val off = if (hStart == 0f) 0f else -Trap.projY(hStart, theta, dOverH, href)
        val yT = Trap.projY(hS, theta, dOverH, href) + off
        val yB = Trap.projY(hE, theta, dOverH, href) + off
        val wT = bmp.width * k * Trap.widthScale(hS, theta, dOverH, href)
        val wB = bmp.width * k * Trap.widthScale(hE, theta, dOverH, href)
        if (wT <= 1f || yB <= yT) return
        // 深度**不再夹到卡片下沿**：挡光那张还在很远的地方时（进场/退场途中），
        // 渐变会被拉得很长、卡片底部只是淡淡的暗 —— 这比"整片压到最暗"符合物理，
        // 也不会在渐变中途出现硬边（CLAMP 之外保持同一个值，是连续的）。
        val end = yT + sd
        if (end <= yT + 1f) return
        val a = (strength * 255f).toInt().coerceIn(0, 255)
        if (shadePaint.shader == null || shadeY != yT || shadeEnd != end || shadeA != a) {
            // 三段渐变 ≈ 平方曲线：上段几乎不变暗，越接近下一张卡的上沿暗得越快
            // （纯线性会让卡片中段就灰掉，看着不像被挡住的光）
            shadePaint.shader = LinearGradient(
                0f, yT, 0f, end,
                intArrayOf(Color.TRANSPARENT, Color.argb((a * 0.10f).toInt(), 0, 0, 0), Color.argb(a, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            shadeY = yT; shadeEnd = end; shadeA = a
        }
        shadePath.reset()
        shadePath.moveTo(cx - wT * 0.5f, yT)
        shadePath.lineTo(cx + wT * 0.5f, yT)
        shadePath.lineTo(cx + wB * 0.5f, yB)
        shadePath.lineTo(cx - wB * 0.5f, yB)
        shadePath.close()
        canvas.drawPath(shadePath, shadePaint)
    }

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePath = Path()
    private var shadeY = Float.NaN
    private var shadeEnd = Float.NaN
    private var shadeA = -1
}

object Trap {

    const val D_OVER_H = 2.6f

    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** drawBitmapMesh 的顶点缓冲（复用，避免每帧 new） */
    private var mesh = FloatArray(0)

    /** 下沿投影后的高度，也就是卡片渲染出来后的真实高度 */
    fun projectedHeight(hEnd: Float, theta: Float, dOverH: Float): Float {
        if (hEnd <= 0f) return 0f
        val rad = Math.toRadians(theta.toDouble())
        val d = (dOverH * hEnd).coerceAtLeast(1f)
        return hEnd * cos(rad).toFloat() * d / (d + hEnd * sin(rad).toFloat())
    }

    /**
     * 3D 坐标 `h` 投到屏幕上的纵向偏移（相机距离 = dOverH × hRef，和 `draw()` 里一致）。
     * `projY(0) = 0`；用来把「沿卡片自身平面挪动」换算成屏幕位移 —— 也就是近大远小的分母来源。
     */
    fun projY(h: Float, theta: Float, dOverH: Float, hRef: Float): Float {
        if (h == 0f) return 0f
        val rad = Math.toRadians(theta.toDouble())
        val d = (dOverH * hRef.coerceAtLeast(1f)).coerceAtLeast(1f)
        return h * cos(rad).toFloat() * d / (d + h * sin(rad).toFloat())
    }

    /** 高度 h 处的横向缩放（顶边 = 长度 1）：就是收窄系数 d / (d + h·sinθ) */
    fun widthScale(h: Float, theta: Float, dOverH: Float, hRef: Float): Float {
        val rad = Math.toRadians(theta.toDouble())
        val d = (dOverH * hRef.coerceAtLeast(1f)).coerceAtLeast(1f)
        return d / (d + h * sin(rad).toFloat())
    }

    /**
     * `projY` 的反解：想让卡片在屏幕上纵向挪 `dy`，它得沿自身平面挪多远（3D 单位）。
     * projY(h) = dy ⇒ h = dy·d / (cosθ·d − dy·sinθ)
     */
    fun hShiftForDy(dy: Float, theta: Float, dOverH: Float, hRef: Float): Float {
        if (dy == 0f) return 0f
        val rad = Math.toRadians(theta.toDouble())
        val d = (dOverH * hRef.coerceAtLeast(1f)).coerceAtLeast(1f)
        val den = cos(rad).toFloat() * d - dy * sin(rad).toFloat()
        if (den <= 1f) return dy          // 视角接近水平时退化，退回屏幕位移
        return dy * d / den
    }

    /**
     * 梯形两条对角线的交点，距顶边的距离占「渲染高度」的比例（≈0.536）。
     * 卡片缩小（沉到前面卡片下面）时以这个点为心，视觉上最稳。
     */
    fun diagCrossRatio(theta: Float, dOverH: Float): Float {
        val rad = Math.toRadians(theta.toDouble())
        val ratio = dOverH / (dOverH + sin(rad).toFloat())   // 底边宽 / 顶边宽
        return 1f / (1f + ratio)
    }

    /** 把 bmp 的 [hStart, hEnd] 这一段按透视画出来；cx 是水平中心 */
    fun draw(
        canvas: Canvas, bmp: Bitmap,
        k: Float, hStart: Float, hEnd: Float,
        theta: Float, dOverH: Float, cx: Float,
        n: Int = 56, alpha: Float = 1f,
        /** 用来算相机距离 d 的参考高度；0 = 用 hEnd。想「只画其中一段、但保持同一个投影」时传整段高度 */
        hRef: Float = 0f,
        /**
         * 纵向原点补偿：绘制时把 `projY(y0)` 从每条带的位置里减掉。
         *
         * 卡片"沿平面推远"时，`hStart` 变大会让投影位置整体往下移（最多上千像素）。
         * 如果照原样画，画面会跑到视图自身的边界之外 —— 视觉上能靠 `translationY` 补回来，
         * 但**触摸区域是按视图边界算的**，于是点不中卡片。
         * 传 `y0 = projY(hStart)` 之后，绘制从视图上沿开始，视图边界 = 卡片边界。
         */
        y0: Float = 0f
    ) {
        val off = if (y0 == 0f) 0f else -projY(y0, theta, dOverH, if (hRef > 0f) hRef else hEnd)
        if (alpha <= 0.004f) return
        if (k <= 0f || hEnd <= hStart || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return
        val rad = Math.toRadians(theta.toDouble())
        val sn = sin(rad).toFloat()
        val cs = cos(rad).toFloat()
        val d = (dOverH * (if (hRef > 0f) hRef else hEnd)).coerceAtLeast(1f)
        val span = hEnd - hStart
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        // **一次 drawBitmapMesh 画完整个梯形**，不再切成几十条带子逐条 drawBitmap。
        //
        // 为什么要换：逐条画的时候，相邻两条各自独立采样（源行边界、滤波都在自己那一条里算），
        // 接缝处必然差半个像素 —— 条数越多缝越密，表现为**卡片上一条条细横线，滑动时还会跟着动**。
        // 网格是一次性的：顶点按投影排好，内部由 Skia 连续插值，接缝根本不存在。
        // 顺带把"每条带一次 drawBitmap"变成"整张卡一次调用"，绘制开销也小一个量级。
        //
        // 网格条数按梯形斜边的总跨度自适应：台阶 = 跨度/条数，做到亚像素才看不出锯齿；
        // θ≈0（整页姿态 / 放平后的卡片）跨度为 0，取最小值，不浪费。
        val wTop = bmp.width * k * (d / (d + hStart * sn))
        val wBot = bmp.width * k * (d / (d + hEnd * sn))
        val spanX = kotlin.math.abs(wBot - wTop)
        val bands = spanX.toInt().coerceIn(n, 260)
        if (mesh.size < (bands + 1) * 4) mesh = FloatArray((bands + 1) * 4)
        val step = span / bands
        var i = 0
        while (i <= bands) {
            val h = hStart + step * i
            val y = h * cs * d / (d + h * sn) + off
            val w = bmp.width * k * (d / (d + h * sn))
            mesh[i * 4] = cx - w * 0.5f
            mesh[i * 4 + 1] = y
            mesh[i * 4 + 2] = cx + w * 0.5f
            mesh[i * 4 + 3] = y
            i++
        }
        canvas.drawBitmapMesh(bmp, 1, bands, mesh, 0, null, 0, paint)
        paint.alpha = 255
    }
}
