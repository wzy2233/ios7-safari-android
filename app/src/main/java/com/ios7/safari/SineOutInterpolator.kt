package com.ios7.safari

import android.view.animation.Interpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * iOS 7 地址栏网址左移用的缓动：f(t) = sin(t · π / 2)。
 *
 * 速度为导数 cos(t · π / 2)：t=0 时是 1（最快），t=1 时是 0（停住），
 * 也就是「先快后慢」—— 正好对应 y=sin(x) 在 x∈[0, π/2] 上斜率由 1 衰减到 0 的那一段。
 */
class SineOutInterpolator : Interpolator {
    override fun getInterpolation(input: Float): Float =
        sin((input * PI / 2.0).toFloat()).toFloat()
}
