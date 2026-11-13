package com.ios7.safari

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

/**
 * 地址栏用的 EditText：**抢在输入法之前**接住返回键。
 *
 * 为什么需要它：键盘弹着的时候，返回键会先被输入法（IME）吃掉 —— 只收键盘、
 * `Activity.onBackPressed()` 根本不会被调用，于是"按一次返回就退出编辑态"做不到
 * （实测：按一次返回，`onBackPressed` 一次都没进，但键盘已经收了）。
 *
 * `onKeyPreIme` 是系统在把按键交给输入法**之前**给当前焦点视图的回调，
 * 在这里接住 BACK（DOWN、UP 都吞），键盘就不会自己先收，一次按键能把整套退出动作做完。
 *
 * 注意：**不是无条件吞**。`onBackKey` 返回 false（当前没什么可收的，比如已经不
 * 在编辑态）时仍然交回给系统，否则会把"网页后退 / 退出应用"的返回键一起吞掉。
 */
class UrlEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    /** 返回键：返回 true = 我处理了（吞掉按键、键盘不许自己收）；false = 交回系统 */
    var onBackKey: (() -> Boolean)? = null

    private var backHandled = false

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val h = onBackKey ?: return super.onKeyPreIme(keyCode, event)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    backHandled = h()
                    if (backHandled) return true
                    // 不处理：交给输入法（照常收键盘 / 正常返回）
                    return super.onKeyPreIme(keyCode, event)
                }
                KeyEvent.ACTION_UP -> if (backHandled) {
                    backHandled = false
                    return true
                }
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
