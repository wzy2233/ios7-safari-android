package com.ios7.safari

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Choreographer
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import java.net.HttpURLConnection
import java.net.URLEncoder

class MainActivity : Activity() {

    companion object {
        /** iPhone iOS 7.0.2 上的 Safari 真实 UA —— 让网站也返回 iOS 版页面 */
        private const val UA_IPHONE_IOS7 =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 7_0_2 like Mac OS X) AppleWebKit/537.51.1 " +
                    "(KHTML, like Gecko) Version/7.0 Mobile/11A501 Safari/9537.53"
        private const val START_BASE = "https://favorites.ios7/"
        /**
         * 标签页卡片的基础倾角：所有卡片完全一致。
         * 角度越大 = 用户俯视越陡（卡片越"躺平"、梯形收得越厉害）。
         * 注意：θ 是卡片平面相对屏幕的倾角，俯角 = 90° − θ。
         * θ 越大卡片竖直方向压得越狠（高度 = H·cosθ·d/(d+H·sinθ)），
         * 60° 时只剩 37% 高，卡片会变成一条"带子"，所以基准定在 42°。
         */
        /**
         * 卡片倾角（相对屏幕）。42° 是手感调出来的值。
         *
         * 附：真机视频里量出来的斜边斜率对应 θ≈54°（卡片宽高比 1.25，比 42° 时更扁），
         * 试过之后手感偏扁，已按用户意见回到 42°。想再试就改这一个数。
         */
        private const val TILT_BASE = 42f
        /** 标签页越多，俯视角度越大（最多再陡这么多度 → 75°） */
        private const val TILT_EXTRA = 8f
        /** 达到最大俯角所需的标签数 */
        private const val TILT_FULL_N = 6
        /** 相机距离 = D_OVER_H × 卡片高度，决定透视收窄的强度 */
        private const val D_OVER_H = 2.6f
        /** 屏幕内允许同时看见的最大标签页数（含最后半张）—— 间距不能比这更密 */
        /** 一屏大约能看到几张卡（真机视频里约 5 张 / 19.9%，这里按手感取 4） */
        private const val MAX_VISIBLE_CARDS = 4f
        /** 标签很少时的最大间距：屏幕高度的 40% */
        private const val STEP_MAX_RATIO = 0.40f
        /** 叠压时每张卡片露出的高度（标签少时的基准） */
        private const val CARD_PEEK_DP = 88f
        /**
         * 卡片左右留白。iOS 7 任务视图里，卡片（倒梯形）的**上沿**几乎顶到屏幕两边，
         * 只有一点点边距；本项原来 18dp 明显偏宽，收到 8dp。
         * 注意它同时决定 cardW / cardHeaderH / 起飞帧的落点，只改这一处即可全局一致。
         */
        private const val CARD_SIDE_DP = 8f
        /** 卡片上沿被推到状态栏下沿时的缩放（1 → 0.8，两段都走半余弦缓动） */
        private const val SHRINK_EDGE = 0.8f
        /** 再往上推「卡片渲染高度的这个比例」，缩放继续从 0.8 缩到 SHRINK_MIN */
        private const val SHRINK_SPAN_RATIO = 0.30f
        /** 缩到这个比例后不再变小，被前面的卡片盖住 */
        private const val SHRINK_MIN = 0.6f
        /**
         * 「滚过最高点」：软钉爬升量的上限 = 第二段行程 × 这个比例。
         *
         * 卡片顶到状态栏下沿之后，顶边先按指数曲线**再往上爬**一点点、逐渐停住 —— 这就是转盘的
         * 最高点。爬升过程本身是"到顶"的减速段（over=0 处斜率正好 1，和上一段"上沿跟手"接上），
         * 爬到顶之后由 [SHRINK_ROLL] 接手往下滚。
         */
        private const val SHRINK_CREST = 0.22f
        /**
         * 「滚下」的行程 = 卡片间距 × 这个比例。
         *
         * 缩到 [SHRINK_MIN] 之后，手指继续往上滑，这张卡不再钉着不动，而是**往下走**
         * （像齿轮转过最高点、往背面转下去，被前面那张卡一点点挡住）。
         * 手指往回滑它就原路升回来，回到"刚好 0.6"的位置之后才重新变大 —— 全程只是位置的函数，
         * 所以正反两个方向天然对称，不需要任何状态机。
         */
        private const val SHRINK_ROLL = 0.45f
        /**
         * 缩放的低通时间常数（ms）：越大越"拖"，越小越跟手。
         * 35ms ≈ 两帧 —— 够抹掉逐帧采样带来的"顿"，又不至于看起来像卡了。
         */
        private const val SHRINK_SMOOTH_TAU_MS = 35f
        /**
         * 滑到尽头继续拖：按**真实透视**把卡片「俯下去」。
         *  - 俯角额外 +STEEP_TILT 度（真实倾斜，横向变窄 + 纵向按 cos 压缩，比例一致，不再像果冻）；
         *  - 相机同时拉近 STEEP_D（透视更强），两者叠加才是「明信片朝你翻过来」的样子。
         * 注意：hEnd 保持 = cardH，也就是**整张截图都画**，只是被透视压短，不是裁掉下半截。
         */
        private const val STEEP_TILT = 10f
        private const val STEEP_D = 0.25f
        /** 越界拖动的阻尼行程：拖得越多，增幅越小（橡皮筋） */
        private const val STEEP_RUBBER_DP = 150f
        /**
         * 退场时「其他卡片让路」的总预算（ms）。
         *
         * 英雄视图 70ms 起、420ms 长 → 490ms 收尾；卡片必须在这之前全部飞干净。
         * 取 300ms（留 190ms 余量）：设备帧率低时，"页面铺满"之前的最后几帧可能只渲染出
         * 卡片飞行的 90% 位置，余量太小就会留下一张没飞完的卡。
         */
        private const val EXIT_CARDS_BUDGET_MS = 600L
        /** 往上让时沿平面往下走多少格（>1 才能真的被下一张盖住） */
        /**
         * 往下让的额外深度，单位＝「一格」（cardStep）。
         *
         * 几何下限是 `H_held + (0.5 − sm)·step ≈ H_held + 0.065·step`（推导见 applyDragRestack），
         * 这里是**在它之上再加**的量：太小会掉一帧就翻早，太大会白白多沉一段。
         */
        private const val DRAG_DIP_DOWN_EXTRA = 0.45f
        /** 是否允许「强制双指缩放」（无视网页的 user-scalable=no） */
        private const val FORCE_PINCH = true
        /**
         * 拖动换位的触发阈值（单位＝格）：越过 `ON` 才换到下一格，退回 `OFF` 以下才撤销。
         * 两个值不同＝滞回，临界线上来回抖不会反复触发。以前用 `round(s)`（0.5 格就换），
         * 长按后轻轻一拖就触发，太敏感。
         */
        private const val DRAG_SWAP_ON = 0.62f
        private const val DRAG_SWAP_OFF = 0.42f
        /**
         * 被让开的卡片的三个相位阈值（单位＝它自己那一格过渡的 0..1）：
         *  `YIELD_AT` 手里那张"挨得近"了 → 开始让开；`YIELD_BACK` 退回来才取消；
         *  `FILL_AT` 手里那张到它原来的格子附近了 → 开始补位；`FILL_BACK` 退回才回让开态。
         */
        private const val DRAG_YIELD_AT = 0.30f
        private const val DRAG_YIELD_BACK = 0.10f
        private const val DRAG_FILL_AT = 0.85f
        private const val DRAG_FILL_BACK = 0.60f
        /** 让开 / 补位各自的时长（ms）—— 卡片自己的节奏，与手指速度无关 */
        private const val DRAG_YIELD_MS = 600L
        private const val DRAG_FILL_MS = 640L
        /** 按下地址栏：加深时长 / 最大加深透明度 */
        private const val PRESS_MS = 220L
        private const val PRESS_MAX_ALPHA = 0.075f
        /** 自己判定的长按时长（框架的 long-click 在 EditText 聚焦时不可靠） */
        private const val FIELD_LONG_PRESS_MS = 420L
        /** 地址栏左移（编辑态进场）的时长 —— 加深的渐浅也用它，保证同时收尾 */
        private const val FIELD_ENTER_MS = 280L
        /** 磁贴页 / 搜索推荐关闭时的渐隐时长（离开编辑态：返回键、回车都走它） */
        private const val SUGGEST_EXIT_MS = 240L
        /** 分享面板升起 / 落下的时长 */
        private const val SHARE_MS = 300L
        /** 书签页上滑进 / 下滑出的时长（原来 300 / 260，出场偏快；现在一进一出同长） */
        private const val BOOKMARK_MS = 330L
        /** 收藏磁贴的出现动画：时长 / 起始缩放（渐显与放大同一时长，同时到位） */
        private const val FAV_TILE_IN_MS = 1400L
        private const val FAV_TILE_IN_SCALE = 0.7f
        /** 手里那张卡跟随手指的时间常数（秒）：越大越"拖得动"、越有重量 */
        private const val DRAG_FOLLOW_TAU = 0.06f
        /** 松手后"自己归位"的时长（没触发换位时） */
        private const val DRAG_SETTLE_MS = 520L

        // ---- 固定 viewport 的三个几何常量（三者必须自洽，改一个就要一起改）----
        /**
         * 栏全部收起时顶栏的高度。`webContainer` 的 `layout_marginTop` 必须等于它 ——
         * 它是 viewport 的「最小上沿线」，也是 webContainer 布局上沿。
         */
        private const val BAR_H_COLLAPSED_DP = 24f
        /** 栏展开态顶栏高度（44dp）与收起态的差：viewport 跟着栏下移这么多（translationY） */
        private const val BAR_SLIDE_DP = 20f
        /** 栏展开态底栏高度 */
        private const val BAR_H_EXPANDED_DP = 44f
        /**
         * 页面里要补的底部内边距（dp）= 底栏 44 + viewport 伸到屏幕外的那 20。
         *
         * 固定 viewport 的代价：viewport 比「看得见的那一块」高 BAR_H_EXPANDED_DP + BAR_SLIDE_DP，
         * 栏展开时页面底部这一截被底栏盖住/在屏幕外。不补的话，**短页面**（滚不动、栏也就收不起来）
         * 最下面一截永远看不到。所以注入 `body{padding-bottom:64px}` 把它顶上来
         * （CSS px 在 device-width 页面上就等于 dp，见 injectBottomInset 里的换算）。
         */
        private const val BOTTOM_INSET_DP = BAR_H_EXPANDED_DP + BAR_SLIDE_DP
        /** 图层高度（elevation）：英雄视图插在「它后面」和「它前面」的卡片之间，
         *  动画全程不变 —— 卡片不会突然被穿到身后，也不会突然盖到身前。 */
        private const val CARD_BACK_Z = 0f
        /** 进场总长（原 400ms，用户要求放慢一倍） */
        private const val ENTER_MS = 800L
        /** 退场总长 / 起始延迟（原 420 / 70ms） */
        private const val EXIT_MS = 840L
        private const val EXIT_DELAY_MS = 140L

        private const val HERO_Z = 1f
        private const val CARD_FRONT_Z = 2f
        /** 首字母磁贴配色（与起始页 HTML 里的调色板保持一致） */
        private val FAV_PALETTE = arrayOf(
            intArrayOf(0xFFFFB340.toInt(), 0xFFFF8A00.toInt()),
            intArrayOf(0xFF5AC8FA.toInt(), 0xFF0A7CFF.toInt()),
            intArrayOf(0xFF7D7AFF.toInt(), 0xFF4B48D6.toInt()),
            intArrayOf(0xFF4CD964.toInt(), 0xFF12A150.toInt()),
            intArrayOf(0xFFFF6961.toInt(), 0xFFD70015.toInt()),
            intArrayOf(0xFF64D2FF.toInt(), 0xFF0A84FF.toInt())
        )
    }

    private class Tab {
        var title: String = ""
        var url: String = ""
        var thumb: Bitmap? = null
        var web: ObservableWebView? = null
        /** 截图版本号：变了才需要重画卡片 */
        var thumbVer = 0
        /** 这一页是否已经 onPageFinished（飞入前要等它，否则拍到的是空白页） */
        var pageDone = false
        val id: Int = nextId++
        companion object { private var nextId = 1 }
    }

    private val tabs = mutableListOf<Tab>()
    private var currentIndex = 0
    private var privateMode = false
    private var compact = 0f
    private var barAnim: ValueAnimator? = null
    private var lastScrollTop = 0
    /** 滚动累积位移（滞回用）：向下为正 */
    private var barAccum = 0f
    /** 地址栏状态切换的冷却时间，避免连续触发动画 */
    private var barLockUntil = 0L
    /** 上一次设进地址栏的字号：没变就不重复 setTextSize（会触发文本重排） */
    private var lastBarTextSp = -1f
    /** 手指正按在网页上（此时栏的收缩由手指驱动，不再听滚动回调，避免同一次位移被算两遍） */
    private var barFinger = false
    /** 上一次 MOVE 的 y，用来算单帧位移 */
    private var barFingerY = 0f

    // ---------- 主界面 ----------
    private lateinit var rootView: FrameLayout
    private lateinit var topBar: FrameLayout
    private lateinit var fieldBox: FrameLayout
    private lateinit var urlField: UrlEditText
    private lateinit var btnReload: ImageView
    private lateinit var btnCancel: TextView
    private var fieldEnterAnim: ValueAnimator? = null
    /** 本次退场里模糊层是否已经开始淡出（只触发一次） */
    private var backdropFading = false
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var bottomWrap: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnShare: ImageView
    private lateinit var btnBookmarks: ImageView
    private lateinit var btnTabs: ImageView

    // ---------- 地址栏：网址居中/左对齐 + 蓝色丝带选区 ----------
    /** 输入框原始 paddingStart（26dp），「左对齐」就是回到这个值 */
    private var padStartBase = 0
    private var padEndBase = 0
    private var padAnim: ValueAnimator? = null
    private var ribbonAnim: ValueAnimator? = null
    private var fieldPopup: PopupWindow? = null
    /** 最近一次手指按下的横坐标，长按选词时要用 */
    private var lastDownX = 0f
    /**
     * 是否是「用户真的点了地址栏」。隐藏浮层 / 摘掉 WebView 时，系统会把焦点顺手丢给窗口里
     * 第一个可聚焦控件（就是地址栏），那种被动获得的焦点一律立刻还回去，绝不弹建议层。
     */
    private var fieldTouched = false

    // ---------- 标签页浮层 ----------
    private lateinit var overlayTabs: View
    /** 模糊背景层（永远在英雄视图与所有卡片之下，进出场时单独淡入淡出） */
    private lateinit var tabChrome: View
    private lateinit var tabScroll: TabScrollView
    private lateinit var tabList: LinearLayout
    private lateinit var tabBottomWrap: View
    private lateinit var tabBgBlur: ImageView
    private lateinit var tabBarBlur: ImageView
    private lateinit var hero: TrapezoidView
    private var cardW = 0
    private var cardH = 0
    private var cardRenderH = 0
    /** 卡片平面图上「标题条」的高度 —— 必须等于顶栏高度 × (cardW/pageW)，取景差 objet */
    private var cardHeaderH = 0
    /** 当前这一摞标签页的倾角（标签越多越陡） */
    private var tiltNow = TILT_BASE
    /** 相邻卡片上沿的间距（叠压露出的高度），标签多时会自适应压缩 */
    private var cardStep = 0
    /** 上一帧 `updateTransforms()` 的时刻（ns）：给缩放低通算真实帧间隔用 */
    private var lastTransformNs = 0L
    /** 进场的"起立"动画（没跑完就被打断时要能取消，否则它会继续往旧卡片上写状态） */
    private var standUpAnim: ValueAnimator? = null
    /** 英雄视图当前顶替的是第几张卡（遮光要靠它找"挡光的那张"） */
    private var heroStackIndex = 0
    private var tabClosing = false
    /** 当前正在跑的英雄视图动画（同一时刻只允许一个） */
    private var heroAnim: ValueAnimator? = null
    /** 动画代号：新一代开始时代号 +1，旧动画的回调据此自我作废 */
    private var heroAnimId = 0
    /**
     * 退场代号：每次 `startExit` / `fadeOutTabs` +1，`startTabsEnter` 也 +1。
     * 用来给「退场看门狗」认人 —— 只有「还是同一次退场、浮层还开着」时才强制收尾，
     * 期间用户又点开了任务视图（进了新一轮）就不会被误杀。
     */
    private var exitSeq = 0
    private var tabAnimating = false
    /** 滑到尽头还继续拖：0 → 正常，1 → 梯形最陡（橡皮筋） */
    private var steep = 0f
    private var steepAnim: ValueAnimator? = null
    private var steepTouchY = 0f
    private var steepExcess = 0f
    private var steepActive = false

    /** 拖到尽头后整摞卡片的额外位移（px，正=下移）；松手会弹回 0 */
    private var stackShift = 0f
    /** 位移的累积量（px，带方向），用来做阻尼 */
    private var shiftExcess = 0f
    private var shiftAnim: ValueAnimator? = null

    /** 卡片平面图的 LRU 缓存：key = tab.id，value = (几何指纹, 位图) */
    private val cardCache = LinkedHashMap<Int, Pair<String, Bitmap>>()
    /** 缓存上限：一次只看得见五张半，留七张足够滚动时顺滑 */
    private val cardCacheMax = 7

    // ---- 长按拖动：左滑删除 / 上下重排 ----
    private var dragView: View? = null
    private var dragFrom = 0             // 按下时它在列表里的位置
    private var dragTo = 0               // 拖动过程中它想去的槽位
    private var dragStartY = 0f
    private var dragStartX = 0f
    private var dragAxis = 0             // 0 未定 / 1 横向（删除）/ 2 纵向（重排）
    private var dragBaseY = 0f           // 被拖卡片原本的 translationY
    /** 手里那张卡"应该"去的位置（跟手指走）；实际位置由 followTicker 逐帧追过去 */
    private var dragTargetY = 0f
    private var followLast = 0L
    /**
     * 手里那张卡的**跟随**循环：每帧朝 `dragTargetY` 指数趋近。
     *
     * 为什么不是直接把 translationY 设成手指位置：那样卡片和手指刚性绑定，
     * 一拖就走、走得和手指一样快，没有重量。指数趋近的速率正比于「手指和卡片之间的距离」——
     * 刚起手时距离小 → 走得慢；手指拖远了 → 追得越来越快（**先慢后快**）；
     * 手指停下 → 差一点一点收掉、自己稳稳落住。同一个常数对帧率不敏感：
     * `k = 1 − e^(−dt/τ)`。
     */
    private val followTicker = object : Runnable {
        override fun run() {
            val v = dragView ?: return
            if (dragAxis == 1) return                  // 左滑删除：不跟随，整张跟着手指走
            if (dragAxis != 2) { v.postOnAnimation(this); return }   // 方向还没定，继续等
            val now = android.os.SystemClock.uptimeMillis()
            var dt = if (followLast == 0L) 0.016f else (now - followLast) / 1000f
            followLast = now
            dt = dt.coerceIn(0.004f, 0.05f)
            val k = 1f - exp(-dt / DRAG_FOLLOW_TAU)
            val cur = v.translationY
            var gap = dragTargetY - cur
            // 落后太多时给个上限，别让卡片飘得太远
            val cap = dp(120f).toFloat()
            if (gap > cap) gap = cap else if (gap < -cap) gap = -cap
            val ty = cur + gap * k
            if (abs(ty - cur) > 0.05f) v.translationY = ty
            v.postOnAnimation(this)
        }
    }
    /** 松手后正在"自己归位"补间的那张卡（updateTransforms 要跳过它） */
    private var settlingView: View? = null
    /** 每张"被让开"的卡片当前的相位（0 原地 / 1 让开沉底 / 2 补位）与它自己的补间 */
    private val yieldPhase = HashMap<View, Int>()
    private val yieldAnim = HashMap<View, ValueAnimator>()
    /** 本帧最新的拖动进度（单位＝格），以及"这一帧已经排过队"的标记 */
    private var dragS = 0f
    private var restackPending = false
    private var downX = 0f
    private var downY = 0f
    private lateinit var btnPrivate: TextView
    private lateinit var btnDone: TextView
    private lateinit var btnNewTab: ImageView
    private lateinit var tabBottomBar: LinearLayout
    /** 任务视图底栏的蒙版：压在毛玻璃上，普通模式白、私密模式深 */
    private lateinit var tabBarScrim: View

    // ---------- 书签浮层 ----------
    private lateinit var overlayBookmarks: View
    private lateinit var bmListHost: FrameLayout

    /**
     * 「书签 / 阅读列表 / 历史记录」三张列表**各有一份自己的 ListView**，
     * 第一次用到时建好并填一次数据；之后切换分段只改可见性，
     * 不重新 inflate 行、不重新 setText、也不让 ListView 重新绑定 —— 这才是"立即切换"的关键。
     */
    private val bmLists = arrayOfNulls<ListView>(3)
    private val bmAdapters = arrayOfNulls<LinkAdapter>(3)
    private lateinit var segBookmarks: Button
    private lateinit var segReading: Button
    private lateinit var segHistory: Button
    private var bmMode = 0

    /** 书签页的列表：一个 adapter 复用到底，切换分段只换数据（不重新 inflate 行） */
    private var bmAdapter: LinkAdapter? = null
    private var bmItems: List<Link> = emptyList()

    /** 三种模式的列表缓存 + 对应的 Store 版本号（版本变了才重读） */
    private val bmCache = HashMap<Int, List<Link>>()
    private var bmCacheVer = -1

    /** 分段按钮的三种背景（选中、未选中、位置），建一次反复用 */
    private var segBgs: Map<Int, GradientDrawable>? = null
    /** 编辑模式：每行右侧露出红色「删除」 */
    private var bmEditing = false

    // ---------- 分享面板（iOS 7：底部半张卡片） ----------
    private lateinit var overlayShare: View
    private lateinit var shareScrim: View
    private lateinit var shareSheet: View
    /** 分享面板第一行的一项：已按圆角裁好的图标位图 + 包名 + 目标 activity */
    private class ShareTarget(
        val label: String,
        val tile: Bitmap?,
        val pkg: String,
        val cn: ComponentName
    )

    /** 后台加载好的可分享 app 列表（首次打开时可能还在加载，onCreate 里已经预取） */
    @Volatile private var shareTargets: List<ShareTarget>? = null
    @Volatile private var shareTargetsLoading = false

    /** 已经建好的两行对应哪份数据 / 哪种主题；不一致才重建（点分享时通常一次都不用重建） */
    private var shareRowsTargetsRef: List<ShareTarget>? = null
    private var shareRowsPrivate: Boolean? = null

    /** 面板的布局是否已经预热过（预热在启动时做，点分享时就不用再量一遍 20 多个子 View） */
    private var shareSheetPreMeasured = false

    private lateinit var shareAppsRow: LinearLayout
    private lateinit var shareFuncRow: LinearLayout
    private lateinit var shareCancel: TextView
    private var shareAnim: ValueAnimator? = null

    // ---------- 建议浮层 ----------
    private lateinit var overlaySuggest: View
    private lateinit var sugScroll: View
    /** 编辑态里出现在圆角矩形右端的小「圆盘叉」：清空输入 */
    private lateinit var btnClear: ImageView
    /** 编辑态进度 0..1（刷新按钮的淡出/反补位移、圆盘叉的淡入都跟着它） */
    private var fieldEditT = 0f
    /** 按住时地址栏"加深"的程度 0..1（iOS：按下先加深一点，再决定是点击还是长按） */
    private var fieldPressT = 0f
    /** 按下时的加深层（铺在圆角矩形里、压在文字下面） */
    private lateinit var fieldPressScrim: View
    /** 这次按住被判定为长按（松手才弹"复制"，且不展开） */
    private var fieldPressLong = false
    /** 自己计时的长按（不依赖框架的 long-click：EditText 聚焦时它常被系统的选择逻辑吃掉） */
    private val fieldLongPress = Runnable {
        fieldPressLong = true
        // 空白地址栏没有"全选/复制"可给，按住就直接弹「粘贴」，不用等松手
        if (urlField.text.isEmpty()) {
            fieldMenuEarly = true
            showFieldMenu()
        }
    }
    /** 空白地址栏长按时，菜单在"按住"阶段就弹了（松手时不要重复弹） */
    private var fieldMenuEarly = false
    /** 粘贴后展开：进编辑态并直接显示「个人收藏」磁贴页（不切搜索推荐） */
    private var fieldEnterShowTiles = false
    /** 地址栏是否已经展开（编辑态） */
    private var fieldExpanded = false
    /** 磁贴页正在渐隐关闭（避免同一个动作被重复触发、渐隐被重启） */
    private var suggestClosing = false
    /** 按下加深的动画（退出时要能取消，否则它会把颜色又调深） */
    private var pressAnim: ValueAnimator? = null
    private lateinit var favGrid: GridLayout
    private lateinit var suggestBox: LinearLayout

    /**
     * 起始页（个人收藏 Web 页）第一排磁贴上沿，到建议浮层第一排磁贴上沿的距离 ——
     * 也就是「点地址栏时磁贴要往上挪多少」。
     *
     * 两处磁贴的几何现在完全一致（50dp 图标、6dp 图标-标题间距、92dp 行距、左右 8dp），
     * 差的只有顶上那一块：起始页有「个人收藏」标签，浮层没有。
     *   起始页：64dp（状态栏 20 + 顶栏 44）+ 26（.h 上边距）+ 18.7（13px 标签行高，实测）+ 12（.h 下边距）
     *   浮层：  64dp + 8（favGrid marginTop）+ 8（item paddingTop）
     * → 相差 40.7dp。
     * 这个数必须准：入场从它滑到 0、退场从 0 滑回它，两端都要和「另一个界面」的磁贴严格重合，
     * 否则衔接帧会看到磁贴跳一下（大小不一致时更明显）。
     */
    private fun suggestEnterOffset(): Float = dp(40.7f).toFloat()

    private var loading = false
    /** 进度条收尾序号：新页面开始 / 新的收尾会作废上一次的延时隐藏 */
    private var progressSeq = 0

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun currentTab(): Tab {
        if (tabs.isEmpty()) tabs.add(Tab())
        if (currentIndex !in tabs.indices) currentIndex = tabs.lastIndex
        return tabs[currentIndex]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_main)

        Store.seedDefaults(this)
        CookieManager.getInstance().setAcceptCookie(true)

        bindViews()
        applyTheme()
        setupKeyboardPadding()
        setupField()
        setupBottomBar()
        setupOverlays()

        newTab("")
        handleIntent(intent)

        // 预取"可分享 app"（查包管理器 + 解码图标）：放后台线程，别占启动和点分享的时间
        ensureShareTargets()

        // 启动时就在横屏的话，直接进"全屏浏览"（见 applyOrientationChrome）
        rootView.post { applyOrientationChrome() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.dataString
        if (!url.isNullOrBlank() && Intent.ACTION_VIEW == intent?.action) {
            newTab(url)
        }
    }

    // ------------------------------------------------------------------
    // 视图绑定
    // ------------------------------------------------------------------
    private fun bindViews() {
        rootView = findViewById(R.id.rootView)
        topBar = findViewById(R.id.topBar)
        fieldBox = findViewById(R.id.fieldBox)
        urlField = findViewById(R.id.urlField)
        btnReload = findViewById(R.id.btnReload)
        btnCancel = findViewById(R.id.btnCancel)
        progress = findViewById(R.id.progress)
        webContainer = findViewById(R.id.webContainer)
        // 固定 viewport 的初始位置：布局上沿 24dp（收起态栏高）+ translationY 20dp = 44dp（展开态下沿）
        webContainer.translationY = webTyPx(compact)
        bottomWrap = findViewById(R.id.bottomWrap)
        bottomBar = findViewById(R.id.bottomBar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnShare = findViewById(R.id.btnShare)
        btnBookmarks = findViewById(R.id.btnBookmarks)
        btnTabs = findViewById(R.id.btnTabs)

        overlayTabs = findViewById(R.id.overlayTabs)
        tabChrome = findViewById(R.id.tabChrome)
        tabScroll = findViewById(R.id.tabScroll)
        tabScroll.blocked = { tabAnimating }
        // 卡片带 elevation（Z>0），`clipChildren=true` 对它们不生效 —— 卡片栈比视口长时
        // 会画到 1830 以下、盖住浏览页底栏（任务视图底栏一淡出就露出"下面那张卡片"）。
        // clipBounds 是直接裁这一层的绘制，与 Z 无关，最稳。
        tabScroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val r = Rect(0, 0, v.width, v.height)
            if (v.clipBounds != r) v.clipBounds = r
        }
        tabList = findViewById(R.id.tabList)
        tabBottomWrap = findViewById(R.id.tabBottomWrap)
        tabBgBlur = findViewById(R.id.tabBgBlur)
        tabBarBlur = findViewById(R.id.tabBarBlur)
        hero = findViewById(R.id.heroCard)
        btnPrivate = findViewById(R.id.btnPrivate)
        btnDone = findViewById(R.id.btnDone)
        btnNewTab = findViewById(R.id.btnNewTab)
        tabBottomBar = findViewById(R.id.tabBottomBar)
        tabBarScrim = findViewById(R.id.tabBarScrim)
        // 网页可见底的两种来源：窗口/键盘变了（容器高度变）或者栏收起展开（底栏高度变）。
        // 任务视图的模糊层高度跟着它走，免得拿着一根过期高度铺模糊图（底栏那一条会露出一截）。
        webContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, oldBottom ->
            if (webBottomVisual() != oldBottom && overlayTabs.visibility != View.GONE) {
                applyChromeHeight()
            }
        }
        bottomWrap.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (overlayTabs.visibility != View.GONE) applyChromeHeight()
        }

        overlayBookmarks = findViewById(R.id.overlayBookmarks)
        bmListHost = findViewById(R.id.bmListHost)
        segBookmarks = findViewById(R.id.segBookmarks)
        segReading = findViewById(R.id.segReading)
        segHistory = findViewById(R.id.segHistory)

        overlayShare = findViewById(R.id.overlayShare)
        shareScrim = findViewById(R.id.shareScrim)
        shareSheet = findViewById(R.id.shareSheet)
        shareAppsRow = findViewById(R.id.shareAppsRow)
        shareFuncRow = findViewById(R.id.shareFuncRow)
        shareCancel = findViewById(R.id.shareCancel)
        shareCancel.setOnClickListener { closeShare() }
        shareScrim.setOnClickListener { closeShare() }

        overlaySuggest = findViewById(R.id.overlaySuggest)
        sugScroll = findViewById(R.id.sugScroll)
        favGrid = findViewById(R.id.favGrid)
        suggestBox = findViewById(R.id.suggestBox)
        setupClearButton()          // 圆盘叉：要赶在下面的 applyTheme() 之前建好
    }

    /**
     * 编辑态的小「圆盘叉」（清空输入）。**必须在 `applyTheme()` 之前创建** ——
     * 主题里会给它上色，晚于 applyTheme 就会 lateinit 崩溃。
     */
    private fun setupClearButton() {
        btnClear = ImageView(this)
        btnClear.setImageResource(R.drawable.ic_stop)
        btnClear.alpha = 0f
        btnClear.visibility = View.INVISIBLE
        FrameLayout.LayoutParams(dp(17f), dp(17f)).let {
            it.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            it.marginEnd = dp(7f)
            fieldBox.addView(btnClear, it)
        }
        btnClear.setOnClickListener {
            // 编辑态里清空文字：**保持左对齐**（`centerField` 是"未编辑态"的居中逻辑，
            // 在这里用会把光标/输入区拉到中间，和 iOS 不一致）
            urlField.setText("")
            urlField.setSelection(0)
            alignFieldLeft(animated = false)
        }

        // 按下"加深"层：铺满圆角矩形、压在文字下面（index 0 = 最底下）
        fieldPressScrim = View(this)
        fieldPressScrim.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f).toFloat()
            setColor(Color.BLACK)
        }
        fieldPressScrim.alpha = 0f
        fieldBox.addView(
            fieldPressScrim, 0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ------------------------------------------------------------------
    // 主题（普通 / 私密浏览）
    // ------------------------------------------------------------------
    private fun applyTheme() {
        val barBg = if (privateMode) Color.parseColor("#3A3A3A") else Color.parseColor("#F9F9F9")
        val fieldBg = if (privateMode) Color.parseColor("#565656") else Color.parseColor("#E9E9EA")
        val tint = if (privateMode) Color.WHITE else Color.parseColor("#007AFF")
        val text = if (privateMode) Color.WHITE else Color.BLACK
        val sub = if (privateMode) 0x99FFFFFF.toInt() else Color.parseColor("#8E8E93")

        // 状态栏交给系统：底色跟着顶栏走，浅色模式要深色图标（iOS 7 就是浅底黑字）
        window.statusBarColor = barBg
        setStatusBarIconsWhite(privateMode)
        topBar.setBackgroundColor(barBg)
        bottomBar.setBackgroundColor(barBg)
        // 任务视图底栏**不能**设不透明底色：它盖在 tabBarBlur（高斯模糊）+ 蒙版之上，
        // 一设底色，prepareBlur() 专门裁出来的那条毛玻璃就完全看不见了。
        // 底色交给 tabBarScrim（半透明蒙版），私密模式下换成深色。
        // 深色底栏（iOS 7 任务视图就是深色的）：普通模式 72% 黑、私密模式再深一点，
        // 都留出足够透明度，底下那条 tabBarBlur 毛玻璃照样透出来。
        tabBarScrim.setBackgroundColor(if (privateMode) 0xCC000000.toInt() else 0xB8000000.toInt())
        // 任务视图根布局**不能有不透明底色**：打开的第一帧卡片还没布局好（英雄视图是刚 addView 的），
        // 整层只有这个底色可见 —— 屏幕上就会白/灰地闪一下。底色交给模糊层（tabChrome）负责，
        // 它从 0 淡入，第一帧露出来的是底下那一页，接得严丝合缝。
        overlayTabs.setBackgroundColor(Color.TRANSPARENT)
        fieldBox.background = rounded(fieldBg, dp(8f).toFloat())   // 高度 34dp，圆角同比例
        fieldBox.background?.alpha = ((1f - compact) * 255f).toInt().coerceIn(0, 255)

        urlField.setTextColor(text)
        urlField.setHintTextColor(sub)
        btnReload.setColorFilter(text, PorterDuff.Mode.SRC_IN)
        btnCancel.setTextColor(tint)
        btnBack.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        btnForward.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        btnShare.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        btnBookmarks.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        btnTabs.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        // 任务视图底栏现在是深色的：蓝色（#007AFF）在深底上对比太低，
        // iOS 7 这张底栏本来就是白色文字 + 白色「＋」，所以这里单独用白（两种模式都一样）。
        btnNewTab.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        btnPrivate.setTextColor(Color.WHITE)
        btnDone.setTextColor(Color.WHITE)

        // 分享面板：普通模式浅灰、私密模式深灰
        shareSheet.setBackgroundColor(if (privateMode) Color.parseColor("#2C2C2E") else Color.parseColor("#F7F7F7"))
        shareCancel.setTextColor(tint)

        btnPrivate.text = if (privateMode) "退出私密" else getString(R.string.private_mode)
        // 圆盘叉：**圆盘 = 占位文字色（sub）**、**叉 = 圆角矩形填充色（fieldBg）**
        // 也就是"深色圆盘 + 浅色小叉"；私密模式两个颜色自动反过来
        btnClear.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(sub)
            // 再压淡一层：原来的实心底色在浅色栏上有点抢眼，半透明之后更像 iOS 的"淡淡一枚"
            alpha = 150
        }
        btnClear.setColorFilter(fieldBg, PorterDuff.Mode.SRC_IN)
    }

    /**
     * 状态栏图标：`white = true` 用白色图标（深色底），false 用深色图标（浅色底）。
     *
     * 任务视图整片背景是深黑的，如果还留着「浅色主题的深色图标」，左上角的
     * 时间 / 电量在深底上几乎看不见 —— 所以进任务视图要临时换成白色图标，退出时按主题还原。
     */
    private fun setStatusBarIconsWhite(white: Boolean) {
        val flags = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility =
            if (white) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radius
        return d
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // 键盘（全屏模式下手动 resize）
    // ------------------------------------------------------------------
    private fun setupKeyboardPadding() {
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val hidden = rootView.height - r.bottom
            val pad = if (urlField.hasFocus() && hidden > dp(60f)) hidden else 0
            if (rootView.paddingBottom != pad) rootView.setPadding(0, 0, 0, pad)
        }
    }

    // ------------------------------------------------------------------
    // 地址栏
    // ------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    private fun setupField() {
        padStartBase = urlField.paddingStart   // 26dp：左对齐时回到这里
        padEndBase = urlField.paddingEnd

        // iOS 7 的选区只有一条蓝色丝带 + 两根光标，没有 Android 那套 Material 浮动工具栏和拖动手柄。
        // 这里把默认的选中菜单彻底挡掉（onCreateActionMode 返回 false = 不创建），改用自己的 iOS 7 菜单。
        val blockMode = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        urlField.setCustomSelectionActionModeCallback(blockMode)
        urlField.setCustomInsertionActionModeCallback(blockMode)

        // 地址栏平时「不许抢焦点」：系统把带焦点的视图从窗口里摘掉时（切换标签就是把 WebView 从容器里
        // remove 掉），会把焦点随手塞给窗口里第一个可聚焦控件 —— 也就是这个 EditText。它一拿到焦点，
        // 焦点回调就会弹出建议浮层盖住整页（表现就是大片白屏、顶部还露着标签页视图）。
        // 所以只有用户真的点到地址栏，才临时允许它获取焦点。
        urlField.isFocusableInTouchMode = false
        urlField.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                lastDownX = e.x          // 长按选词要知道手指按在哪个字符上
                fieldTouched = true
                if (!v.isFocusableInTouchMode) {
                    v.isFocusable = true
                    v.isFocusableInTouchMode = true
                    v.requestFocus()
                }
            }
            false
        }

        urlField.setOnFocusChangeListener { _, has ->
            if (has) {
                // 标签页浮层开着、或者焦点是被系统「塞」过来的（用户根本没点地址栏），立刻还回去
                if (overlayTabs.visibility == View.VISIBLE || !fieldTouched) {
                    // 光 clearFocus 不够：窗口里没有别的可聚焦控件时，系统会把焦点又塞回来，
                    // 于是「清掉 → 被塞回 → 再清掉」死循环。先把地址栏本身设成不可聚焦再清。
                    urlField.post { releaseFieldFocus() }
                    return@setOnFocusChangeListener
                }
                // 展开 / 左移 / 键盘 / 磁贴的顺序交给触摸逻辑（见 startFieldEdit）：
                // 要先等"按下加深"结束，而且长按是**不展开**的。
                if (fieldExpanded) return@setOnFocusChangeListener
            } else {
                collapseFieldVisuals()
            }
        }

        // 长按只**标记**（真正的菜单等松手再弹，和 iOS 一致）；吞掉事件免得弹出系统选择栏
        // 吞掉框架的长按，免得弹出系统的选择手柄 / 复制工具栏
        urlField.setOnLongClickListener { true }
        // 键盘不要跟着焦点自动弹：要等左移全部结束才由 showKeyboard() 弹出（iOS 的顺序）
        urlField.showSoftInputOnFocus = false
        // 返回键要**抢在输入法之前**接住（否则键盘先被收掉、onBackPressed 不会被调用），
        // 见 UrlEditText.onKeyPreIme：一次返回就收键盘 + 关磁贴页 + 地址栏回常态。
        urlField.onBackKey = { exitFieldEdit() }

        // iOS 7 的按下序列：按下 → 地址栏颜色**加深一点**；然后分两种
        //   ① 松手很快（点击）：加深结束后才开始左移，左移期间颜色同步渐浅、到位时正好回到原色，
        //      然后才出键盘 + 磁贴页浅色背景渐显；
        //   ② 按住不动（长按）：一直保持加深、**不展开**，松手才渐浅回原色并弹出「复制」。
        urlField.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fieldTouched = true
                    fieldPressLong = false
                    fieldMenuEarly = false
                    urlField.isFocusableInTouchMode = true
                    // 点一下就把光标关掉：这一下可能只是"把栏展开"（不进入编辑），
                    // 而 EditText 一拿到焦点就会画光标 → 展开过程中闪一下。
                    // 真正进入编辑时（startFieldEdit 的正式路径）再打开。
                    // ⚠ 只在"还没在编辑"时关：编辑中途点进文本里是要挪光标的，那时不能关。
                    if (!fieldExpanded) urlField.isCursorVisible = false
                    animateFieldPress(1f, PRESS_MS)
                    urlField.removeCallbacks(fieldLongPress)
                    urlField.postDelayed(fieldLongPress, FIELD_LONG_PRESS_MS)
                }
                MotionEvent.ACTION_UP -> {
                    urlField.removeCallbacks(fieldLongPress)
                    if (fieldPressLong) {
                        animateFieldPress(0f, PRESS_MS)     // 长按：渐浅 + 菜单，不展开
                        if (!fieldMenuEarly) showFieldMenu()
                        fieldMenuEarly = false
                    } else if (!fieldExpanded && compact > 0.25f) {
                        // 收起态点的这一下**只是把栏展开**：立刻开始，不等"按下加深"跑完。
                        // 等的话会先看到地址栏变深、过一会儿才展开，而且延迟随按住时长变化，
                        // 手感上就是"随机迟滞"。
                        startFieldEdit()
                    } else if (fieldPressT > 0.01f) {
                        fieldPressScrim.postDelayed({ startFieldEdit() }, PRESS_MS.toLong())
                    } else {
                        startFieldEdit()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    urlField.removeCallbacks(fieldLongPress)
                    animateFieldPress(0f, PRESS_MS)
                }
            }
            false      // 不吞事件：EditText 自己还要处理光标/滚动
        }

        urlField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (urlField.hasFocus()) {
                    val q = s?.toString().orEmpty()
                    // 空 → 磁贴页；开始输入字符 → 搜索推荐
                    if (q.isEmpty()) renderFavTiles() else renderSuggestions(q)
                }
                if (fieldPopup != null) dismissFieldMenu()   // 内容变了，菜单已经过期
            }
        })

        // 各种输入法（含中文输入法）的回车都会走到这里，不能只认 actionGo
        urlField.setOnEditorActionListener { _, actionId, event ->
            val enterKey = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_NULL ||
                enterKey
            ) {
                go(urlField.text.toString())
                true
            } else false
        }

        urlField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                go(urlField.text.toString())
                true
            } else false
        }

        btnCancel.setOnClickListener {
            exitFieldEdit()       // 失焦回调里会把网址恢复成域名并滑回居中
        }

        btnReload.setOnClickListener {
            val t = currentTab()
            if (isOnStartPage()) {
                showStartPage(t)              // 起始页的"刷新"= 重新渲染一页（书签可能刚改过）
            } else if (loading) {
                t.web?.stopLoading()
            } else {
                t.web?.reload()
            }
        }

        // 首次布局完成后把网址摆到居中位置
        urlField.post { updateField() }
    }

    // ================= 地址栏：网址居中 ↔ 左对齐 =================

    /** 当前文字（没有文字时按 hint 算）的宽度 */
    private fun fieldTextWidth(): Float {
        val t = urlField.text?.toString().orEmpty()
        val s = t.ifEmpty { urlField.hint?.toString().orEmpty() }
        return if (s.isEmpty()) 0f else urlField.paint.measureText(s)
    }

    /** 让当前文字水平居中所需的 paddingStart。
     *
     *  `w` 必须传「这一行稳定之后的宽度」。退出编辑态时圆角矩形是一边变宽一边做补间的，
     *  此刻 `urlField.width` 还是窄的那个值（少一个「取消」宽，实测 110px），
     *  拿它算出来的中心落在「窄帧」的正中，等宽度涨完，文字就整体偏左半个取消宽（≈20dp）。
     *  所以非编辑态一律按终值宽度算（见 idleFieldW()）。
     */
    private fun centerPad(w: Int = urlField.width): Int {
        // 按圆角矩形「整宽」对称居中：左边距 = (w - 文字宽)/2。
        // 左右内边距不相等（左 14dp 给编辑态贴左，右 30dp 让开刷新按钮）也能居中；
        // 网址太长时再夹到「不压住刷新按钮」的安全区里。
        val tw = fieldTextWidth()
        val left = ((w - tw) / 2f).toInt()
        val maxLeft = (w - padEndBase - tw).toInt().coerceAtLeast(padStartBase)
        return left.coerceIn(padStartBase, maxLeft)
    }

    /** 非编辑态下输入框最终的宽度：取消已隐藏，整行都是它的（= 顶栏内宽） */
    private fun idleFieldW(): Int = fieldAvailW()

    private fun setFieldPad(p: Int) =
        urlField.setPadding(p, urlField.paddingTop, padEndBase, urlField.paddingBottom)

    private fun animateFieldPadTo(target: Int, dur: Long = 280L) {
        padAnim?.cancel()
        val from = urlField.paddingStart
        if (abs(from - target) <= 1) { setFieldPad(target); return }
        val a = ValueAnimator.ofFloat(from.toFloat(), target.toFloat())
        a.duration = dur
        a.interpolator = apple                  // 苹果默认曲线（黏起 → 中段快 → 长收尾）
        a.addUpdateListener { setFieldPad((it.animatedValue as Float).toInt()) }
        padAnim = a
        a.start()
    }

    private fun centerField(animated: Boolean) {
        // 编辑态用实时宽度（此时地址栏是左对齐的，中心值用不上）；
        // 非编辑态用终值宽度，避免在宽度补间途中算出偏左的中心
        val t = centerPad(if (urlField.hasFocus()) urlField.width else idleFieldW())
        if (animated) animateFieldPadTo(t) else { padAnim?.cancel(); setFieldPad(t) }
    }

    /**
     * 等这一次布局真正跑完，再用新的宽度算居中。
     *
     * 退出编辑态时「取消」按钮会被隐藏，输入框跟着变宽（实测 111px）。此刻 urlField.width
     * 拿到的还是编辑态的旧值，直接算会把网址摆在旧宽度的正中 —— 相对新宽度就偏左了半个按钮宽。
     * 所以排到下一帧布局完成、绘制开始之前再算，那时宽度已经是终值。
     */
    private fun centerFieldAfterLayout() {
        val vto = urlField.viewTreeObserver
        if (!vto.isAlive) { centerField(animated = true); return }
        vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val o = urlField.viewTreeObserver
                if (o.isAlive) o.removeOnPreDrawListener(this)
                centerField(animated = true)
                return true
            }
        })
    }

    /**
     * 交还焦点。只调 clearFocus() 是不够的：窗口里没有别的可聚焦控件时，系统会把焦点又塞回
     * 第一个可聚焦控件（就是地址栏），于是变成「清掉 → 被塞回 → 再清掉」的死循环。
     * 这里先把地址栏本身设成不可聚焦，再让根视图接住焦点。用户下次点地址栏时会重新放开。
     */
    // ===== 地址栏编辑态：「取消」从屏幕右侧外左移推入，圆角矩形（fieldBox）右边沿 + 刷新随之左移让位 =====
    /**
     * 顶栏内横向可用宽（去掉左右内边距）。
     * 优先用「地址栏那一行」的真实宽度和真实 padding —— 写死 16dp 只要左右内边距改一次
     * （或者套了别的东西）就会多算，多出来的那点宽度全被「取消」吃掉。
     */
    private fun fieldAvailW(): Int {
        val row = fieldBox.parent as? ViewGroup
        if (row != null && row.width > 0) {
            return (row.width - row.paddingStart - row.paddingEnd).coerceAtLeast(0)
        }
        val dens = resources.displayMetrics.density
        return (rootView.width - 16 * dens).toInt().coerceAtLeast(0)
    }

    /**
     * 量出「取消」需要的宽度，并把它的 layoutParams.width 锁成这个值。
     *
     * 为什么不能让它保持 wrap_content（竖排「取消」的根因）：
     * 进场时 fieldBox 先占满整行（取消还在屏幕外、还没让位），出场时 fieldBox 又一路涨回整行。
     * 这两个瞬间 LinearLayout 分给「取消」的剩余宽度都小于文字本身需要的宽度，
     * 于是 wrap_content 的 TextView 把「取消」折成上下两行（取在上、消在下）——
     * 而且一折行行高就被撑大，之后即使宽度够了也回不到一行。
     * 显式 width = Wc 之后，LinearLayout 给它的 MeasureSpec 恒为 EXACTLY Wc，
     * 与父容器还剩多少空间无关；XML 里再加 singleLine/maxLines=1 兜底。
     */
    private fun prepareCancelWidth(): Int {
        btnCancel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // 大字号 / 已折行过的情况下 width 可能更大，取两者的最大值，避免把文字截掉
        val wc = maxOf(btnCancel.measuredWidth, btnCancel.width).coerceAtLeast(1)
        btnCancel.measure(
            View.MeasureSpec.makeMeasureSpec(wc, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val lp = btnCancel.layoutParams
        if (lp != null && lp.width != wc) {
            lp.width = wc
            btnCancel.layoutParams = lp
        }
        return wc
    }

    /**
     * 只改 fieldBox 的宽度（跳过没变化的帧，少一次 measure/layout）。
     * 「取消」不单独做 translationX —— 它的左边缘永远贴在圆角矩形的右边缘上，
     * 由布局推着走。之前既改宽度又给它叠一个 translationX，两段位移一起走，
     * 取消的行程是地址栏右边缘的两倍，看着就是「越过地址栏往前冲一下」，很毛糙。
     */
    private fun setFieldBoxW(w: Int) {
        val nlp = fieldBox.layoutParams as LinearLayout.LayoutParams
        val ww = w.coerceAtLeast(0)
        if (nlp.width == ww) return
        nlp.width = ww
        fieldBox.layoutParams = nlp
    }

    /**
     * 进入编辑态：圆角矩形右边界左移（宽度收短），里面的刷新按钮跟着左移，
     * 「取消」从右边界外被推入 —— 三者是同一块刚性整体，位移完全同步。
     * fieldBox 平时是 weight=1 占满，这里临时改成显式宽度并做宽度补间，结束保持「占满 - 取消宽」。
     */
    /** 按下加深 / 渐浅（`0..1`） */
    private fun animateFieldPress(target: Float, dur: Long) {
        pressAnim?.cancel()          // 旧动画必须停：否则它会把刚设好的颜色又拉走
        if (dur <= 0) {
            fieldPressT = target
            if (::fieldPressScrim.isInitialized) fieldPressScrim.alpha = PRESS_MAX_ALPHA * target
            return
        }
        pressAnim = ValueAnimator.ofFloat(fieldPressT, target).apply {
            duration = dur
            interpolator = apple
            addUpdateListener {
                fieldPressT = it.animatedValue as Float
                fieldPressScrim.alpha = PRESS_MAX_ALPHA * fieldPressT
            }
            start()
        }
    }

    /**
     * 点击确认后真正展开（iOS 的顺序）：左移 / 矩形收窄 / 丝带铺开**同时**把加深渐浅回去，
     * 三者同长同曲线 → 到位时颜色正好回到原色；左移结束的瞬间才出键盘、磁贴页背景才渐显。
     */
    private fun startFieldEdit(blank: Boolean = false, keepText: Boolean = false) {
        if (fieldExpanded) return
        // 横屏全屏（barLocked）时点地址栏 = 用户明确要界面了：解除锁定，
        // 否则横屏里地址栏永远点不开，连网址都输不了（只有转回竖屏一条路）。
        // 状态栏仍然保持隐藏（横屏本来就不显示），转回竖屏时 applyOrientationChrome 会还原。
        if (barLocked) barLocked = false
        // 栏处于收起态（往下滚过）时，**第一次点网址条只把栏展开**，不进入编辑 ——
        // 对应 iOS 7「点收缩后的地址栏，先把整条还原回来」。再点一次才编辑。
        // （长按菜单进来的 blank/keepText 两种不算，否则"粘贴"会被这一步挡掉。）
        //
        // ⚠ 这一步必须把"按下的痕迹"全部退干净，否则会卡在中间态：
        //   ① `animateFieldPress(0)`：ACTION_DOWN 时已经把地址栏加深了，不退回去
        //      就一直是深色，看起来像编辑态，而且再点也没反应；
        //   ② `releaseFieldFocus()`：手指点 EditText 会顺带把焦点给它，而
        //      `feedBar()` 见到 urlField 有焦点就**不再收栏** —— 于是滚页也收不回去；
        //      同时 `exitFieldEdit()` 在 `!fieldExpanded` 时直接返回，返回键也退不出来。
        if (!blank && !keepText && compact > 0.25f) {
            // 页面还在惯性滚动时点地址条：必须先把惯性停掉，否则滚动回调会继续喂
            // `feedBar()`，刚展开的栏立刻又被收回去（"展开一下又缩回去"）。
            currentTab().web?.stopFlingNow()
            barAccum = 0f
            barLockUntil = android.os.SystemClock.uptimeMillis() + 600
            animateFieldPress(0f, PRESS_MS)
            dismissFieldMenu()
            releaseFieldFocus()
            // releaseFieldFocus() 会把地址栏设成"不可聚焦"（它本来是给"进任务视图前甩掉焦点"
            // 用的）。这里只是暂时不要焦点，**必须把可聚焦还回去** —— 否则下一次点击
            // 根本聚焦不上，编辑态就永远进不去（等于把地址栏废掉了）。
            urlField.isFocusable = true
            urlField.isFocusableInTouchMode = true
            animateBars(0f)
            return
        }
        fieldExpanded = true
        urlField.isCursorVisible = true          // 真进编辑了，光标回来
        animateBars(0f)
        dismissFieldMenu()
        animateFieldPress(0f, FIELD_ENTER_MS)
        animateFieldEnter()
        // blank = 长按菜单点「删除」进来的；keepText = 粘贴进来的 —— 这两种都不回填网址
        if (blank) {
            urlField.setText("")
        } else if (!keepText) {
            urlField.setText(if (isOnStartPage()) "" else currentTab().url)
        }
        alignFieldLeft()
        if (!blank && !keepText) growRibbon()
    }

    /**
     * 编辑态的"刷新按钮 → 小圆盘叉"交接（iOS 7 的细节）。
     *
     * - **刷新按钮不动**：`fieldBox` 右沿左移 `Wc` 时，它是 box 的子视图会被一起带走，
     *   所以这里反补同样的位移（`+Wc·f`）让它**停在屏幕原位**，同时渐隐；
     *   圆角矩形的右沿会从它身上扫过去（超出 box 的部分被裁掉），所以"被挡住"不突兀。
     * - **小圆盘叉渐显**：它是 box 的子视图，右沿左移它自然跟着走，正好落在刷新按钮原来的位置上；
     *   比刷新按钮小一圈，从透明到实心。
     */
    private fun applyFieldEdit(f: Float, shift: Int) {
        fieldEditT = f
        btnReload.translationX = shift * f
        btnReload.alpha = (1f - f) * (1f - compact)
        btnClear.alpha = f
        btnClear.visibility = if (f > 0.005f) View.VISIBLE else View.INVISIBLE
    }

    private fun animateFieldEnter() {
        fieldEnterAnim?.cancel()
        btnCancel.visibility = View.VISIBLE
        val Wc = prepareCancelWidth()          // 先锁宽：整段动画里它都不可能被折成两行
        val total = fieldAvailW()
        val lp = fieldBox.layoutParams as LinearLayout.LayoutParams
        lp.weight = 0f
        lp.width = total
        fieldBox.layoutParams = lp
        btnCancel.translationX = 0f
        fieldEnterAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = apple                    // 与网址左移同一条曲线，一起收尾
            addUpdateListener { p ->
                val f = (p as ValueAnimator).animatedValue as Float
                setFieldBoxW((total - Wc * f).toInt())
                applyFieldEdit(f, Wc)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    setFieldBoxW(total - Wc)
                    // 左移全部结束 → 这时才出键盘、才让磁贴页的浅色背景渐显（磁贴自身的动画不再重播）
                    showKeyboard()
                    showSuggestions()
                    fadeInSuggestBg()
                    // 粘贴进来的：按 iOS 的做法只给「个人收藏」磁贴页，不切搜索推荐
                    if (fieldEnterShowTiles) {
                        fieldEnterShowTiles = false
                        renderFavTiles(force = true)
                    }
                }
            })
            start()
        }
    }

    /**
     * 退出编辑态：圆角矩形右边界右移回位，「取消」被同一块整体推回屏幕右侧外；
     * 结束把 fieldBox 交还给 weight=1 占满，并隐藏取消。
     */
    private fun animateFieldExit() {
        fieldEnterAnim?.cancel()
        if (btnCancel.visibility != View.VISIBLE) { btnCancel.visibility = View.GONE; return }
        // 用「量出来的宽」而不是 btnCancel.width：出场时 fieldBox 一路涨回整行，
        // 剩余空间被压到 0，此时 width 读到的是被挤扁的值（甚至 0），动画会瞬间走完。
        val Wc = prepareCancelWidth()
        val total = fieldAvailW()
        btnCancel.translationX = 0f
        fieldEnterAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = apple                    // 出场也用同一条苹果曲线，一去一回手感一致
            addUpdateListener { p ->
                val f = (p as ValueAnimator).animatedValue as Float
                setFieldBoxW(((total - Wc) + Wc * f).toInt())
                applyFieldEdit(1f - f, Wc)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    val nlp = fieldBox.layoutParams as LinearLayout.LayoutParams
                    nlp.width = 0
                    nlp.weight = 1f
                    fieldBox.layoutParams = nlp
                    btnCancel.visibility = View.GONE
                    btnCancel.translationX = 0f
                    // 收起来之后把宽度还给 wrap_content，下次进场再重新量、重新锁
                    val clp = btnCancel.layoutParams
                    if (clp != null && clp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        clp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        btnCancel.layoutParams = clp
                    }
                }
            })
            start()
        }
    }

    /**
     * 把地址栏还原成「常态」的全套动作（幂等）：退场动画 → 网址复原 → 居中 → 磁贴页渐隐关闭。
     *
     * 失焦回调、返回键、取消按钮都走这里，保证只有一套收尾逻辑。
     */
    private fun collapseFieldVisuals() {
        // 已经是常态就什么都不做（例如连续两次退出）
        if (!fieldExpanded && overlaySuggest.visibility != View.VISIBLE) return
        fieldExpanded = false
        fieldPressLong = false
        fieldMenuEarly = false
        fieldEnterShowTiles = false
        animateFieldPress(0f, 0)
        fieldTouched = false
        animateFieldExit()
        ribbonAnim?.cancel()
        dismissFieldMenu()
        urlField.setText(displayUrl(currentTab().url))
        urlField.setSelection(0)
        centerFieldAfterLayout()   // 宽度变完再居中，否则会偏左半个「取消」按钮
        hideSuggestions()
        urlField.isFocusableInTouchMode = false
    }

    /**
     * **一次**退出地址栏编辑态：收键盘 → 磁贴页渐隐 → 地址栏回常态。
     *
     * 焦点还在时交给 [releaseFieldFocus]，由失焦回调统一收尾（不写两套逻辑）；
     * 焦点已经不在（比如长按菜单把焦点弄丢）就直接补一次 [collapseFieldVisuals]。
     */
    private fun exitFieldEdit(): Boolean {
        // 没什么可收的（不在编辑态、磁贴页也没挂着）→ 交回系统，
        // 否则地址栏只要还持有焦点就会把"网页后退 / 退出"的返回键一起吞掉。
        if (!fieldExpanded && overlaySuggest.visibility != View.VISIBLE) return false
        dismissFieldMenu()
        hideKeyboard()
        val focused = urlField.hasFocus()
        releaseFieldFocus()
        if (!focused) collapseFieldVisuals()
        return true
    }

    private fun releaseFieldFocus() {
        fieldTouched = false
        urlField.isFocusableInTouchMode = false
        urlField.isFocusable = false
        urlField.clearFocus()
        rootView.isFocusableInTouchMode = true
        rootView.requestFocus()
    }

    /** 点进地址栏：网址滑到左对齐 */
    private fun alignFieldLeft(animated: Boolean = true) {
        if (animated) animateFieldPadTo(padStartBase) else { padAnim?.cancel(); setFieldPad(padStartBase) }
    }

    /** 蓝色丝带从第一个字母前的细光标，向右铺开到最后一个字符后面 → 全选 */
    private fun growRibbon() {
        ribbonAnim?.cancel()
        val len = urlField.text.length
        if (len == 0) { urlField.setSelection(0); return }
        urlField.setSelection(0)
        val a = ValueAnimator.ofInt(0, len)
        a.duration = 300
        a.interpolator = apple                      // 丝带和上面两段同步收尾
        a.addUpdateListener { urlField.setSelection(0, it.animatedValue as Int) }
        ribbonAnim = a
        a.start()
    }

    // ================= 地址栏：iOS 7 文本编辑菜单 =================

    private data class FieldAction(val label: String, val run: () -> Unit)

    private fun clipboardText(): CharSequence? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val t = clip.getItemAt(0).coerceToText(this)
        return t.takeIf { it.isNotEmpty() }
    }

    private fun selRange(): Pair<Int, Int> {
        val a = urlField.selectionStart.coerceAtLeast(0)
        val b = urlField.selectionEnd.coerceAtLeast(0)
        return minOf(a, b) to maxOf(a, b)
    }

    private fun copySelection() {
        val (s, e) = selRange()
        if (e > s) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("URL", urlField.text.subSequence(s, e)))
        }
        urlField.setSelection(e)     // 丝带收起，只留光标
    }

    private fun pasteInto() {
        val t = clipboardText() ?: return
        val (s, e) = selRange()
        urlField.text.replace(s, e, t)
        urlField.setSelection((s + t.length).coerceAtMost(urlField.text.length))
    }

    private fun deleteSelection() {
        val (s, e) = selRange()
        if (e > s) urlField.text.delete(s, e)
        urlField.setSelection(s)
    }

    private fun selectAllField() {
        urlField.setSelection(0, urlField.text.length)
        showSelectedMenu(withDelete = true)   // 全选后菜单变成「复制 / 粘贴 / 删除」
    }

    private fun offsetAtX(x: Float): Int {
        val l = urlField.layout ?: return 0
        val hx = (x + urlField.scrollX - urlField.paddingStart).coerceAtLeast(0f)
        return l.getOffsetForHorizontal(0, hx).coerceIn(0, urlField.text.length)
    }

    /** 长按（或全选后）：按当前是否有丝带决定菜单内容 */
    /**
     * 长按松手后的菜单（iOS 7）：**先把整条地址全选**，所以菜单里**不再给「全选」**；
     * 给「复制 / 粘贴（剪贴板有内容才给）/ 删除」。
     * 点「删除」= 直接进入空白的编辑态（左对齐 + 键盘 + 磁贴页）。
     */
    private fun showFieldMenu() {
        dismissFieldMenu()
        val items = mutableListOf<FieldAction>()
        // 粘贴后把光标放回开头：不然 EditText 会滚到末尾，长网址只剩右半截露在外面
        val paste = {
            pasteInto()
            // 粘贴后：左对齐显示（折叠态是居中布局，长网址会被裁成右半截）+ 光标回开头
            alignFieldLeft(animated = false)
            urlField.setSelection(0)
            urlField.post {
                alignFieldLeft(animated = false)
                urlField.setSelection(0)
                urlField.scrollX = 0
            }
            // 然后**直接进编辑态**：键盘 + 「个人收藏」磁贴页（和 iOS 一致）
            if (fieldExpanded) {
                showKeyboard()
                renderFavTiles(force = true)
            } else {
                fieldEnterShowTiles = true
                startFieldEdit(keepText = true)
            }
        }
        if (urlField.text.isEmpty()) {
            // 空白地址栏：只给「粘贴」。**剪贴板为空也照样弹**（点了给个提示），
            // 否则用户按住半天什么都没出现，会以为长按失效。
            val hasClip = clipboardText() != null
            items += FieldAction("粘贴") { if (hasClip) paste() else toast("剪贴板是空的") }
            buildAndShowMenu(items)
            return
        }
        selectAllField()
        items += FieldAction("复制") { copySelection() }
        if (clipboardText() != null) items += FieldAction("粘贴") { paste() }
        items += FieldAction("删除") {
            urlField.setText("")
            urlField.setSelection(0)
            startFieldEdit(blank = true)
        }
        buildAndShowMenu(items)
    }

    /** 已经有丝带时：复制（+粘贴）；全选之后再多一个「删除」 */
    private fun showSelectedMenu(withDelete: Boolean) {
        val items = mutableListOf<FieldAction>()
        items += FieldAction("复制") { copySelection() }
        if (clipboardText() != null) items += FieldAction("粘贴") { pasteInto() }
        if (withDelete) items += FieldAction("删除") { deleteSelection() }
        buildAndShowMenu(items)
    }

    private fun buildAndShowMenu(items: List<FieldAction>) {
        dismissFieldMenu()
        // 注意：**不能**在这里判 `urlField.text.isEmpty()` —— 空白地址栏的「粘贴」菜单正是空文本时的场景，
        // 以前这行会把整个菜单拦掉（看着像"长按没反应"）。
        if (items.isEmpty()) return
        val v = layoutInflater.inflate(R.layout.popup_text_menu, null)
        val row = v.findViewById<LinearLayout>(R.id.menuRow)
        items.forEachIndexed { i, a ->
            if (i > 0) {
                val d = View(this)
                d.layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
                d.setBackgroundColor(0x55FFFFFF.toInt())
                row.addView(d)
            }
            val tv = TextView(this)
            tv.text = a.label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(13f), 0, dp(13f), 0)
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            tv.setOnClickListener { dismissFieldMenu(); a.run() }
            row.addView(tv)
        }

        v.measure(0, 0)
        val w = v.measuredWidth
        val h = v.measuredHeight
        val loc = IntArray(2)
        urlField.getLocationOnScreen(loc)
        val anchorX = (loc[0] + selectionCenterX())
            .coerceIn(dp(12f).toFloat(), (resources.displayMetrics.widthPixels - dp(12f)).toFloat())
        val x = (anchorX - w / 2f).toInt()
            .coerceIn(dp(8f), (resources.displayMetrics.widthPixels - w - dp(8f)).coerceAtLeast(dp(8f)))
        val y = loc[1] + urlField.height + dp(6f)
        // 箭头始终指着选区
        v.findViewById<ImageView>(R.id.menuArrow).translationX =
            (anchorX - x - dp(5f)).toFloat().coerceIn(dp(6f).toFloat(), (w - dp(16f)).toFloat())

        val pop = PopupWindow(v, w, h, false)
        pop.isOutsideTouchable = true      // 点别处自动消失
        pop.isFocusable = false           // 不抢焦点，键盘保持弹出
        pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.elevation = dp(8f).toFloat()
        pop.setOnDismissListener { fieldPopup = null }
        pop.showAtLocation(rootView, Gravity.TOP or Gravity.START, x, y)
        fieldPopup = pop
    }

    /** 选区中心在输入框内的横坐标（没有选区就用输入框中心） */
    private fun selectionCenterX(): Float {
        val l = urlField.layout ?: return urlField.width / 2f
        val s = urlField.selectionStart.coerceAtLeast(0)
        val e = urlField.selectionEnd.coerceAtLeast(0)
        val mid = (l.getPrimaryHorizontal(s) + l.getPrimaryHorizontal(e)) / 2f +
                urlField.paddingStart - urlField.scrollX
        return mid.coerceIn(
            urlField.paddingStart.toFloat(),
            (urlField.width - urlField.paddingEnd).toFloat()
        )
    }

    private fun dismissFieldMenu() {
        val p = fieldPopup ?: return
        fieldPopup = null
        runCatching { p.dismiss() }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(urlField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun isStartPage(url: String) = url.startsWith(START_BASE)

    private fun displayUrl(url: String): String {
        if (url.isBlank() || isStartPage(url)) return ""
        return try {
            val host = Uri.parse(url).host ?: return url
            if (host.startsWith("www.")) host.substring(4) else host
        } catch (e: Exception) {
            url
        }
    }

    private fun hostOf(url: String): String =
        try {
            Uri.parse(url).host ?: ""
        } catch (e: Exception) {
            ""
        }

    private fun looksLikeUrl(s: String): Boolean {
        if (s.contains(" ")) return false
        val l = s.lowercase()
        if (l.startsWith("http://") || l.startsWith("https://") ||
            l.startsWith("file://") || l.startsWith("about:")
        ) return true
        if (Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?([/?#].*)?$").matches(s)) return true
        return Regex("^localhost(:\\d+)?([/?#].*)?$").matches(s)
    }

    private fun normalizeUrl(s: String): String {
        val l = s.lowercase()
        return if (l.startsWith("http://") || l.startsWith("https://") ||
            l.startsWith("file://") || l.startsWith("about:")
        ) s else "https://$s"
    }

    /** 默认搜索引擎（必应）。改这里就能换搜索引擎，建议浮层里的那一行文案见 strings.xml 的 search_engine */
    private fun searchUrl(q: String): String =
        "https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8")

    private fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val url = if (looksLikeUrl(q)) normalizeUrl(q) else searchUrl(q)
        // 先把目标地址写进 tab，再清焦点，避免焦点回调把地址栏刷回上一个页面
        currentTab().url = url
        dismissFieldMenu()
        hideSuggestions()
        hideKeyboard()
        releaseFieldFocus()
        currentTab().web?.loadUrl(url)
    }

    // ------------------------------------------------------------------
    // 底部工具栏
    // ------------------------------------------------------------------
    private fun setupBottomBar() {
        btnBack.setOnClickListener { currentTab().web?.goBack() }
        btnForward.setOnClickListener { currentTab().web?.goForward() }
        btnShare.setOnClickListener { shareMenu() }
        btnBookmarks.setOnClickListener { openBookmarks(0) }
        btnTabs.setOnClickListener { openTabs() }
    }

    private fun updateNavButtons() {
        val w = currentTab().web
        btnBack.alpha = if (w?.canGoBack() == true) 1f else 0.35f
        btnForward.alpha = if (w?.canGoForward() == true) 1f else 0.35f
        syncReloadIcon()
    }

    /**
     * 地址栏右边的刷新/停止图标。
     *
     * **起始页也显示"刷新"**（它是本地 HTML，没有"正在加载"这回事）；只有真的在加载网页时才显示"✕"。
     * 以前只看全局的 `loading`，起始页刚打开时那个标记可能是上一页留下的 true，于是常驻一个 ✕。
     */
    private fun syncReloadIcon() {
        val busy = loading && !isOnStartPage()
        btnReload.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_reload)
        btnReload.setColorFilter(
            if (privateMode) Color.WHITE else Color.BLACK, PorterDuff.Mode.SRC_IN
        )
    }

    /**
     * iOS 7 的分享面板：点「分享」从屏幕下方升起一张**半截卡片**。
     *
     *  - 第一行：系统里**真的能接收分享**的 app（`PackageManager` 查 ACTION_SEND，
     *    不是写死的假图标），图标统一裁成一样大的圆角矩形，名字在下面；
     *  - 第二行：浏览器功能 —— 书签 / 添加到阅读清单 / 添加到主屏幕 / 复制 / 打印，
     *    图标是「蓝线圆角矩形 + 蓝线图形」（见 `ShareGlyphView`）；
     *  - 两行都横向滑动，**一屏正好 4 个**（每项宽 = 屏宽/4），多了往左划。
     */
    private fun shareMenu() {
        val t = currentTab()
        if (t.url.isBlank() || isStartPage(t.url)) {
            toast("还没有打开网页")
            return
        }
        if (overlayShare.visibility == View.VISIBLE) return
        // 编辑态先收干净：否则键盘还占着下半屏，卡片会被顶上去
        if (urlField.hasFocus() || fieldExpanded) exitFieldEdit()
        ensureShareRows()          // 通常已经建好，这里是空操作
        shareAnim?.cancel()
        // ⚠ 必须**先把卡片推到屏幕下方，再让它可见**。
        // 以前是 `visibility = VISIBLE` 之后才在 post 里设 translationY，
        // 中间那帧卡片停在"已经到位"的位置 —— 就是你说的"啪"地闪一下终态。
        // 这里用屏幕高度先把它整块推到屏幕外（比卡片自身高度大，肯定全出去），
        // 等布局完再夹回真实高度，然后才开始升。
        shareSheet.translationY = resources.displayMetrics.heightPixels.toFloat()
        shareScrim.alpha = 0f
        overlayShare.visibility = View.VISIBLE
        shareSheet.post {
            val hgt = shareSheet.height.toFloat().coerceAtLeast(1f)
            if (shareSheet.translationY < hgt) shareSheet.translationY = hgt
            // 先把卡片放进硬件层、并且**留一帧**让这一层建好、图标纹理也传上去；
            // 这一帧卡片还整块在屏幕外（看不见），所以那点开销完全隐形 ——
            // 真正开始升的时候就不会再卡一下。代价只是动画晚一帧（约 16ms）开始。
            shareSheet.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            shareSheet.post { startShareEnterAnim() }
        }
    }

    /** 面板升起：卡片 translationY 归零 + 蒙版淡入，同一条苹果曲线、同一时长 */
    private fun startShareEnterAnim() {
        if (overlayShare.visibility != View.VISIBLE) return
        shareScrim.animate().alpha(1f).setDuration(SHARE_MS).setInterpolator(apple).start()
        shareAnim = ValueAnimator.ofFloat(shareSheet.translationY, 0f).apply {
            duration = SHARE_MS
            interpolator = apple
            addUpdateListener { shareSheet.translationY = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    shareSheet.setLayerType(View.LAYER_TYPE_NONE, null)
                }

                override fun onAnimationCancel(animation: Animator) {
                    shareSheet.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            start()
        }
    }

    /** 收起分享面板：整块滑回屏幕下方，滑完再 GONE */
    private fun closeShare() {
        if (overlayShare.visibility != View.VISIBLE) return
        shareAnim?.cancel()
        val hgt = shareSheet.height.toFloat().coerceAtLeast(1f)
        shareScrim.animate().alpha(0f).setDuration(SHARE_MS).setInterpolator(apple).start()
        shareSheet.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ValueAnimator.ofFloat(shareSheet.translationY, hgt).apply {
            duration = SHARE_MS
            interpolator = apple
            addUpdateListener { shareSheet.translationY = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlayShare.visibility = View.GONE
                    shareSheet.translationY = 0f
                    shareScrim.alpha = 1f
                    shareSheet.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            start()
        }
    }

    /** 两行内容：上=可分享 app（系统真实列表），下=浏览器功能 */
    /** 分享面板第一行里优先排在最左边的 app（按包名），不在表里的按系统顺序排在后面 */
    private val SHARE_PREFERRED = listOf(
        "com.tencent.mm",               // 微信
        "com.tencent.mobileqq",         // QQ
        "com.tencent.tim",              // TIM
        "com.sina.weibo",               // 微博
        "com.ss.android.ugc.aweme",     // 抖音
        "com.xingin.xhs",               // 小红书
        "com.alibaba.android.rimet",    // 钉钉
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.netease.cloudmusic",       // 网易云音乐
        "com.tencent.weread"            // 微信读书
    )

    /**
     * 第一行用缓存填充。缓存还没好就留空、等后台回来再填 ——
     * 这样"点分享按钮"这一下**不等包管理器、不等图标解码**，卡片立刻就能升起来。
     */
    private fun fillShareApps() {
        shareAppsRow.removeAllViews()
        val list = shareTargets
        if (list == null) {
            ensureShareTargets()      // 后台还在查，回来后会重建这两行
            return
        }
        if (list.isEmpty()) {
            shareAppsRow.addView(shareItem(icon = null, glyph = null, label = "没有可分享的应用") { })
            return
        }
        list.take(20).forEach { st ->
            shareAppsRow.addView(shareItem(bitmap = st.tile, glyph = null, label = st.label) {
                closeShare()
                runCatching {
                    val t = currentTab()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, t.title.ifBlank { displayUrl(t.url) })
                        putExtra(Intent.EXTRA_TEXT, t.url)
                    }
                    startActivity(send.setComponent(st.cn))
                }.onFailure { toast("打不开这个应用") }
            })
        }
    }

    /** 后台把"能收分享的 app"查好、图标裁好，主线程只负责加 View */
    private fun ensureShareTargets() {
        if (shareTargets != null || shareTargetsLoading) return
        shareTargetsLoading = true
        Thread({
            val list = loadShareTargets()
            runOnUiThread {
                shareTargets = list
                shareTargetsLoading = false
                // 启动时就把两行建好（面板还不可见也一样建）：
                // 这样用户第一次点分享时不需要再建任何 View，按下去就能立刻升
                ensureShareRows()
            }
        }, "share-targets").start()
    }

    /** 纯后台：查可分享 app、读名称、把图标裁成和功能图标一样的圆角矩形 */
    private fun loadShareTargets(): List<ShareTarget> {
        val pm = packageManager
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val seen = HashSet<String>()
        val before = ArrayList<ShareTarget>()
        runCatching {
            pm.queryIntentActivities(send, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) return@forEach
                // 名称取**应用名**（微信 / QQ / 网易云音乐…），不是分享入口自己的 label
                // —— 后者会显示成「发送给朋友」「发送给好友」这种动作名
                val lbl = runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                }.getOrElse { runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg) }
                val tile = runCatching { ri.loadIcon(pm) }.getOrNull()?.let { roundedTile(it, dp(60f)) }
                before.add(ShareTarget(lbl, tile, pkg, ComponentName(pkg, ri.activityInfo.name)))
            }
        }
        // 常用 app 提到最左（iOS 的「相似 / 更相关的排最左」），其余保持系统顺序跟在后面
        return before.sortedBy { st ->
            val i = SHARE_PREFERRED.indexOf(st.pkg)
            if (i < 0) SHARE_PREFERRED.size else i
        }
    }

    /**
     * 建分享面板的两行内容 —— **只在必要时建一次**。
     *
     * 点分享按钮时这里通常是零成本（启动时已经预建好），所以按下就能立刻开始升；
     * 各项的点击回调都用 `currentTab()` **现取**网址，所以复用旧的行也不会分享到过期地址。
     */
    private fun ensureShareRows() {
        val list = shareTargets
        val fresh = shareFuncRow.childCount > 0 && shareAppsRow.childCount > 0 &&
            shareRowsTargetsRef === list && shareRowsPrivate == privateMode
        if (fresh) return
        shareRowsTargetsRef = list
        shareRowsPrivate = privateMode
        // 内容变了要重新预热（面板正开着时不能手动 measure/layout，跳过）
        if (overlayShare.visibility != View.VISIBLE) shareSheetPreMeasured = false

        // ---- 第二行：浏览器功能（内容固定，建一次就够）----
        shareFuncRow.removeAllViews()
        shareFuncRow.addView(shareItem(glyph = ShareGlyphView.BOOKMARK, label = "书签") {
            val t = currentTab()
            Store.addBookmark(this, Link(t.title.ifBlank { displayUrl(t.url) }, t.url))
            closeShare()
            toast("已添加到书签")
        })
        shareFuncRow.addView(shareItem(glyph = ShareGlyphView.READING, label = "添加到阅读清单") {
            val t = currentTab()
            Store.addReading(this, Link(t.title.ifBlank { displayUrl(t.url) }, t.url))
            closeShare()
            toast("已添加到阅读清单")
        })
        shareFuncRow.addView(shareItem(glyph = ShareGlyphView.HOME, label = "添加到主屏幕") {
            val t = currentTab()
            closeShare()
            pinToHome(t, t.title.ifBlank { displayUrl(t.url) })
        })
        shareFuncRow.addView(shareItem(glyph = ShareGlyphView.COPY, label = "复制") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", currentTab().url))
            closeShare()
            toast("已复制")
        })
        shareFuncRow.addView(shareItem(glyph = ShareGlyphView.PRINT, label = "打印") {
            closeShare()
            printPage()
        })

        // ---- 第一行：真实的可分享 app（**只用缓存里的数据**，主线程不查包、不加载图标）----
        fillShareApps()

        preMeasureShareSheet()
    }

    /**
     * 启动时把面板「量一遍、摆一遍」（它还是 GONE，不会被画出来）。
     * 20 多个子 View 的文字排版（StaticLayout）在这一步就做完了并被缓存，
     * 于是点分享时那一帧只剩显示，不会出现按下后先卡一顿再升。
     */
    private fun preMeasureShareSheet() {
        if (shareSheetPreMeasured) return
        rootView.post {
            if (shareSheetPreMeasured) return@post
            val w = rootView.width
            val h = rootView.height
            if (w <= 0 || h <= 0) return@post
            shareSheetPreMeasured = true
            val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
            shareSheet.measure(wSpec, hSpec)
            shareSheet.layout(0, 0, shareSheet.measuredWidth, shareSheet.measuredHeight)
        }
    }

    /**
     * 一行里的一个图标 + 名称。
     * 宽度固定为屏宽/4 → 一屏正好露出 4 个，多的往右滑（和 iOS 一致）。
     */
    private fun shareItem(
        icon: Drawable? = null,
        bitmap: Bitmap? = null,
        glyph: Int? = null,
        label: String,
        onClick: () -> Unit
    ): View {
        val colW = resources.displayMetrics.widthPixels / 4
        val side = dp(60f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(colW, LinearLayout.LayoutParams.WRAP_CONTENT)
            // 上下内边距必须对称 —— 否则「图标 + 名称」这一组在行内会偏上/偏下
            setPadding(dp(3f), 0, dp(3f), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (glyph != null) {
            col.addView(ShareGlyphView(this, glyph).apply {
                // 真机截图：功能图标是**灰色细线**（和行间分隔线同色），不是蓝色
                strokeColor = ShareGlyphView.GRAY
                // 填成面板底色 → 看起来"透明"；复制图标靠它遮掉后面那张纸
                fillColor = if (privateMode) Color.parseColor("#2C2C2E") else Color.parseColor("#F7F7F7")
                layoutParams = LinearLayout.LayoutParams(side, side)
            })
        } else {
            // 后台已经裁好的直接用；没有就现裁（拿不到图标时退回本机图标，同样走圆角裁切）
            val tile = bitmap
                ?: (icon ?: getDrawable(R.mipmap.ic_launcher))?.let { roundedTile(it, side) }
            col.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(side, side)
                scaleType = ImageView.ScaleType.FIT_XY
                if (tile != null) setImageBitmap(tile) else setImageResource(R.mipmap.ic_launcher)
            })
        }
        col.addView(TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            // 一律按两行高度排版：这样同一行里每一项「图标 + 名称」总高度一致，
            // 图标在同一条基线上、整组在行内天然垂直居中（单行名称的第二行留空）
            minLines = 2
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(if (privateMode) Color.WHITE else Color.parseColor("#3C3C43"))
            layoutParams = LinearLayout.LayoutParams(colW - dp(8f), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(3f) }   // 图标与名称贴紧一点（原来是 6dp，显得散）
        })
        return col
    }

    /** 把任意 app 图标裁成「一样大的圆角矩形」（圆角 = 边长的 0.2235，iOS 图标的比例） */
    /**
     * 把 app 图标裁成**和功能图标完全相同的圆角矩形**：
     * 尺寸同 [size]、圆角同 `ShareGlyphView` 的 0.2235·边长、内容铺满整块。
     *
     * 有些 app 的图标自带透明边距（旧版图标、带内边距的矢量图标），
     * 直接铺满会显得比功能图标「小一圈」。所以先量出不透明内容的包围盒再放大到铺满
     * （最多放大 1.35 倍，避免把留白很大的图标吹得过大）。
     */
    private fun roundedTile(src: Drawable, size: Int): Bitmap? = try {
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(raw).also { c ->
            src.setBounds(0, 0, size, size)
            src.draw(c)
        }
        val box = opaqueBounds(raw)
        val s = size.toFloat()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = s * 0.2235f                       // 与 ShareGlyphView 的外框圆角一致
        val rr = RectF(0f, 0f, s, s)
        c.save()
        c.clipPath(Path().apply { addRoundRect(rr, r, r, Path.Direction.CW) })
        if (box != null && box.width() > 0 && box.height() > 0) {
            val k = minOf(s / box.width(), s / box.height()).coerceAtMost(1.35f)
            val dw = box.width() * k
            val dh = box.height() * k
            c.drawBitmap(
                raw,
                Rect(box.left, box.top, box.right, box.bottom),
                RectF((s - dw) / 2f, (s - dh) / 2f, (s + dw) / 2f, (s + dh) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } else {
            c.drawBitmap(raw, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        c.restore()
        raw.recycle()
        out
    } catch (e: Throwable) {
        null
    }

    /** 位图里不透明内容的包围盒（alpha > 24 算不透明）；整张全透明时返回 null */
    private fun opaqueBounds(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var top = -1
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > 24) {
                    if (top < 0) top = y
                    bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (top < 0 || right < 0) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    /** 添加到主屏幕：用系统的「固定快捷方式」，点了直接打开这个网址 */
    private fun pinToHome(t: Tab, label: String) {
        if (Build.VERSION.SDK_INT < 26) { toast("当前系统不支持"); return }
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) { toast("当前桌面不支持添加快捷方式"); return }
        runCatching {
            val sc = ShortcutInfo.Builder(this, "web_" + t.url.hashCode())
                .setShortLabel(label.take(12))
                .setLongLabel(label.take(24))
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(t.url))
                )
                .build()
            sm.requestPinShortcut(sc, null)
        }.onFailure { toast("添加失败") }
    }

    /** 打印当前网页（走系统打印服务） */
    @Suppress("DEPRECATION")
    private fun printPage() {
        val w = currentTab().web ?: return
        val mgr = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        runCatching {
            val adapter = w.createPrintDocumentAdapter(currentTab().title.ifBlank { "页面" })
            mgr.print(currentTab().title.ifBlank { "页面" }, adapter, PrintAttributes.Builder().build())
        }.onFailure { toast("这个页面无法打印") }
    }

    // ------------------------------------------------------------------
    // 工具栏收起 / 展开（iOS 7 下滑收起）
    // ------------------------------------------------------------------
    /**
     * iOS 7：**横屏浏览是完全全屏** —— 一条 UI 都不留，连状态栏都收起来。
     * 竖屏恢复成正常（状态栏回来、栏展开）。
     */
    private var landscapeFull = false
    /** 横屏全屏期间锁死栏高度：滚动不许把它拉回来 */
    private var barLocked = false

    private fun applyOrientationChrome() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        landscapeFull = land
        // 每次都把状态**直接写终值**，不做 early-return、也不走动画：
        // 旋转后宽高、insets 全变了，动画中途的值只会留下半截状态（上一版就是用
        // `if (land == landscapeFull) return` + animateBars，旋转回来栏卡在收起态出不来）。
        barAnim?.cancel()
        barAccum = 0f
        barLockUntil = 0L
        hideStatusBar(land)
        barLocked = land
        setBars(if (land) 1f else 0f)
        // 尺寸变了：网址要重新居中（编辑态下是左对齐，不能动），图标也要重新同步
        rootView.requestLayout()
        rootView.post {
            if (!fieldExpanded) centerField(animated = false)
            syncReloadIcon()
            rebuildTaskViewAfterRotation()
        }
    }

    /**
     * 任务视图开着的时候旋转屏幕：整摞必须**按新尺寸重建**。
     *
     * 卡片的几何（`cardW`/`cardH`/`cardRenderH`/`cardStep`）和每张卡的位图都是按旧屏宽
     * 算好、缓存好的：横竖屏一换，卡片还是旧宽度，位置也全错 —— 看起来就是"乱了"。
     * 重建一次（`refreshTabs()` 内部会重新 `ensureCardGeometry()` + 重渲染位图）即可，
     * 代价是丢一帧的动画状态（旋转本来就会打断动画，可以接受）。
     */
    private fun rebuildTaskViewAfterRotation(depth: Int = 0) {
        if (overlayTabs.visibility != View.VISIBLE) return
        // `onConfigurationChanged()` 回调时 rootView 还是**旧尺寸**（布局要下一帧才跑）。
        // 这时候算出来的 cardW/cardH 是竖屏的，卡片会按旧宽度排进横屏列表 —— 更乱。
        // 所以先等新尺寸真的落到 rootView 上（最多重试 4 帧）。
        if (depth < 4 && rootView.width != resources.displayMetrics.widthPixels) {
            rootView.post { rebuildTaskViewAfterRotation(depth + 1) }
            return
        }
        endHero()                       // 收掉英雄视图、停掉动画、恢复 chrome
        dragView = null
        settlingView = null
        // 尺寸变了：几何必须**作废重算**。`ensureCardGeometry()` 是"算过就不再算"的，
        // 不清零的话它会原样返回竖屏那一套。
        cardW = 0
        cardH = 0
        cardHeaderH = 0
        cardRenderH = 0
        // 起飞帧/模糊源是旧尺寸的整屏截图，留着会让下一次进场按错的宽高比铺开
        heroComposite?.let { if (!it.isRecycled) it.recycle() }
        heroComposite = null
        applyChromeHeight()
        alignBlurMatrix()
        refreshTabs()
        afterLayout {
            if (overlayTabs.visibility != View.VISIBLE) return@afterLayout
            alignCurrentCardToRest()
            for (k in 0 until tabList.childCount) {
                val c = tabList.getChildAt(k)
                if (c !== hero) c.alpha = 1f
            }
        }
    }

    private fun hideStatusBar(hide: Boolean) {
        val v = window.decorView
        val f = v.systemUiVisibility
        v.systemUiVisibility = if (hide) f or View.SYSTEM_UI_FLAG_FULLSCREEN
        else f and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationChrome()
    }

    private fun animateBars(target: Float) {
        if (compact == target) return
        barAnim?.cancel()
        val from = compact
        barAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = 220
            // 苹果默认曲线：起步带一点黏、中段快、尾巴长而软 —— 收缩/舒张都更"顺"
            interpolator = apple
            addUpdateListener {
                setBars(it.animatedValue as Float)
            }
            start()
        }
    }

    /**
     * 立刻把顶栏/底栏复位成展开态（不做动画）。
     *
     * 起飞帧的取景高度、卡片标题条高度（cardHeaderH）都是按**展开态**算的；
     * 如果截帧时栏还在收缩态或者正在动画中（高度是中间值），
     * 截出来的图就会和卡片差几十像素 —— 动画里看到的就是「先往下抽一下再正常」。
     */
    private fun snapBarsExpanded() {
        barAnim?.cancel()
        if (compact != 0f) setBars(0f)
    }

    /**
     * 把一次「内容位移」喂给顶栏/底栏的滞回逻辑。
     *
     * @param delta >0 = 页面内容往上走（手指上滑）→ 顶栏收起；<0 反之。
     *
     * 两个驱动源共用这一段：手指（`trackBarByFinger`）和滚动回调（`w.onScroll` 兜底，
     * 负责惯性/JS 自己滚动的情况）。手指按着时不用滚动回调，避免同一段位移被算两遍。
     */
    private fun feedBar(delta: Float) {
        if (delta == 0f) return
        // 任务视图开着 / 进出动画中：不要让网页的滚动去驱动顶栏底栏收缩。
        // 否则飞入动画中途底栏被收成 0 高度，而英雄视图的取景高度是起飞时定死的，
        // 屏幕最下方就会出现一条「没有底栏」的真空带。
        if (overlayTabs.visibility == View.VISIBLE || tabAnimating) return
        // 只有在**真的在编辑**时才不收栏；只看 `hasFocus()` 会把"点一下展开栏"留下的
        // 焦点也算进去，结果滚页永远收不回去（这就是收缩失效那个 bug）。
        if (fieldExpanded) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now < barLockUntil) return
        // 单侧累加 + 滞回：只累计「当前想去的方向」，方向一反就归零。
        // 手指微抖 / 惯性小抖动 / 捏合缩放产生的零碎滚动都到不了阈值，
        // 不会出现「要缩不缩」的中间状态。阈值换算成像素：收缩 20dp、展开 8dp。
        if (delta > 0f) {
            if (compact >= 0.5f) return
            barAccum = (barAccum + delta).coerceIn(0f, dp(24f).toFloat())
            if (barAccum >= dp(20f)) {
                barAccum = 0f
                barLockUntil = now + 450
                animateBars(1f)
            }
        } else {
            if (compact < 0.5f) return
            barAccum = (barAccum + delta).coerceIn(-dp(24f).toFloat(), 0f)
            if (barAccum <= -dp(8f)) {
                barAccum = 0f
                barLockUntil = now + 450
                animateBars(0f)
            }
        }
    }

    /**
     * 用**手指**的位移驱动顶栏/底栏，而不是等 `onScrollChanged`。
     *
     * 为什么要这样：`onScrollChanged` 是页面真正滚起来之后才回调的，重页面一帧要 30~50ms，
     * 合成器滚动还会合并帧 —— 栏的收缩因此总是"落后半拍"，看起来就像跟着网页一起卡住。
     * 触摸事件是输入管线直接分发的，节奏稳定、和页面渲染解耦，栏的动画就顺了。
     * （惯性甩动阶段手指已经抬起，那时继续由滚动回调驱动。）
     */
    private fun trackBarByFinger(e: MotionEvent, w: WebView) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                barFinger = true
                barFingerY = e.y
            }
            // 双指 = 缩放 / 平移，不参与栏的收缩
            MotionEvent.ACTION_POINTER_DOWN -> barFinger = false
            MotionEvent.ACTION_MOVE -> {
                if (!barFinger) return
                if (e.pointerCount > 1) {
                    barFinger = false
                    return
                }
                val y = e.y
                val dy = y - barFingerY
                barFingerY = y
                if (abs(dy) < 0.5f) return
                // 收起方向要真的还能滚：短页面 / 已经到顶时继续拖，不该把栏收起来
                if (dy < 0f && !w.canScrollVertically(1)) return
                feedBar(-dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> barFinger = false
        }
    }

    /** 顶栏此刻的（整数）高度：44dp 展开 ↔ 24dp 收起 */
    private fun barBottomY(p: Float): Int = dp(BAR_H_EXPANDED_DP - BAR_SLIDE_DP * p)

    /**
     * 网页层要跟到的 translationY。
     *
     * **必须是整数，而且和顶栏底边严格相等**（= 顶栏高 − 布局上沿 24dp）。
     * 以前这里写的是 `dp(20f) * (1 - p)` —— 两个毛病：
     *  ① 它是**小数**，WebView 这个巨大的层每帧被亚像素平移 → 整页重采样，文字发毛；
     *  ② 和顶栏高度各自取整，最多差 1px → 页面顶边和栏之间每帧差一格。
     * 网页里 `position: sticky` 的顶栏一般会被提升成独立的合成层，它的对齐是**按整数像素**
     * 吸附的：外层在亚像素移动、它自己在整数位跳，两者错开就是用户看到的"网页顶栏抽搐"。
     */
    private fun webTyPx(p: Float): Float = (barBottomY(p) - dp(24f)).toFloat()

    private fun setBars(p: Float) {
        if (barLocked && p < 1f) return      // 横屏全屏：栏锁死，滚动不许把它拉回来
        compact = p
        // 高度/字号只在「真的变了」时才写：每次写 layoutParams 都会触发一次 measure/layout。
        // （改造成固定 viewport 之后，这里的高度变化只影响「栏层」那 3 个 View，
        //   已经不会再去改 WebView 的 viewport 了 —— 这是这套改动的意义所在。）
        val topH = barBottomY(p)
        if (topBar.layoutParams.height != topH) {
            topBar.layoutParams.height = topH
            topBar.layoutParams = topBar.layoutParams
        }
        // 收到底时「框高 == 栏高」：网址在整条里上下等宽，左右也居中
        val fieldH = dp(34f - 10f * p)
        if (fieldBox.layoutParams.height != fieldH) {
            fieldBox.layoutParams.height = fieldH
            fieldBox.layoutParams = fieldBox.layoutParams
        }
        val bottomH = dp(BAR_H_EXPANDED_DP * (1f - p))
        if (bottomWrap.layoutParams.height != bottomH) {
            bottomWrap.layoutParams.height = bottomH
            bottomWrap.layoutParams = bottomWrap.layoutParams
        }
        bottomWrap.alpha = 1f - p * 0.9f
        // 网页容器是**固定 viewport**，不跟着栏改高度（见 activity_main.xml 的注释）：
        // 它只用 translationY 跟着顶栏下沿走。上沿 = 24dp(布局) + 20dp(1−p) = 44−20p，
        // 正好等于顶栏下沿；下沿则伸到屏幕以外（被底栏盖住/出屏），永远不会有缝。
        val webTy = webTyPx(p)
        if (webContainer.translationY != webTy) webContainer.translationY = webTy
        // iOS 7：往上滑网页时，地址栏的圆角矩形和刷新按钮一起淡出，
        // 只留网址本身（字号同时收小）—— 视线落在网址上，浏览区也腾出来了；
        // 往下滑就整段反着来：淡入 + 长回去。
        fieldBox.background?.alpha = ((1f - p) * 255f).toInt().coerceIn(0, 255)
        // 编辑态里刷新按钮已经淡到 0（由 applyFieldEdit 管），这里乘上 (1−fieldEditT) 免得被栏动画写回 1
        btnReload.alpha = (1f - p) * (1f - fieldEditT)
        val tsp = 18f - 4f * p
        if (abs(lastBarTextSp - tsp) > 0.05f) {
            lastBarTextSp = tsp
            urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, tsp)
            // 字号变了，居中要跟着重算，否则缩起顶栏时网址会偏一点
            if (!urlField.hasFocus()) centerField(animated = false)
        }
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): ObservableWebView {
        val w = ObservableWebView(this)
        w.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = UA_IPHONE_IOS7
        }

        w.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return if (request.isForMainFrame) handleUrl(request.url.toString()) else false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleUrl(url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loading = true
                progress.animate().cancel()
                progressSeq++
                progress.alpha = 1f
                progress.visibility = View.VISIBLE
                progress.progress = 0
                syncReloadIcon()
                val tab = tabOf(view)
                if (tab != null) {
                    tab.url = url
                    tab.pageDone = false
                }
                if (tab === currentTab()) updateField()
                updateNavButtons()
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading = false
                finishProgress()
                syncReloadIcon()
                val tab = tabOf(view) ?: return
                tab.url = url
                tab.pageDone = true
                // 起始页（loadData）的 title 是 about:blank / 空，别把已经设好的页面名冲掉
                val t = view.title?.trim().orEmpty()
                if (t.isNotEmpty() && !t.equals("about:blank", true)) tab.title = t
                if (tab === currentTab()) {
                    updateField()
                    updateNavButtons()
                }
                if (!privateMode && url.startsWith("http") && !isStartPage(url)) {
                    Store.addHistory(this@MainActivity, Link(tab.title.ifBlank { displayUrl(url) }, url))
                }
                forcePinch(view)
                injectBottomInset(view)   // 固定 viewport：页面最下面被栏盖住的那一截要补回来
                view.postDelayed({
                    capture(tab)
                    warmUpTabView()       // 首屏就绪后把任务视图要用的重活先干完（只做一次）
                }, 600)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (!request.isForMainFrame) return
                view.loadDataWithBaseURL(
                    null, errorPage(request.url.toString()), "text/html", "utf-8", null
                )
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                // 100 不要立刻隐藏（否则满格那一帧根本画不出来），交给 finishProgress 收尾
                if (newProgress >= 100) {
                    finishProgress()
                } else {
                    progress.animate().cancel()
                    progress.alpha = 1f
                    progress.visibility = View.VISIBLE
                    progress.progress = newProgress
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val tab = tabOf(view) ?: return
                val t = title?.trim().orEmpty()
                // loadDataWithBaseURL 加载起始页时会回报 about:blank，别拿它当页面名称
                if (t.isEmpty() || t.equals("about:blank", true)) return
                tab.title = t
            }

            override fun onJsAlert(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("好") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val tab = newTab("")
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = tab.web
                resultMsg.sendToTarget()
                return true
            }
        }

        w.setDownloadListener { url, _, _, _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        w.setOnLongClickListener {
            val res = w.hitTestResult
            val url = res.extra ?: ""
            if ((res.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                        res.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                url.isNotBlank()
            ) {
                showLinkMenu(url)
                true
            } else false
        }

        w.onScroll = { top, delta ->
            // 到顶一律展开（这一条只能从滚动回调里判断，手指位移不知道页面滚到哪了）
            if (top <= dp(4f)) {
                if (!urlField.hasFocus() && compact != 0f &&
                    overlayTabs.visibility != View.VISIBLE && !tabAnimating
                ) {
                    barAccum = 0f
                    barLockUntil = android.os.SystemClock.uptimeMillis() + 250
                    animateBars(0f)
                }
            } else if (!barFinger) {
                // 手指按着的时候由 trackBarByFinger() 驱动（同一段位移别算两遍）；
                // 手指抬起来之后的惯性滚动、以及 JS 自己滚动的页面，都走这条兜底
                feedBar(delta.toFloat())
            }
            lastScrollTop = top
        }

        attachEdgeGesture(w)
        return w
    }

    /**
     * 给页面底部补一段内边距，把「被栏盖住」的那一截顶上来。
     *
     * 为什么要补：改成固定 viewport 之后，viewport 的高度是「栏全部收起」时的尺寸
     * （屏幕 − 24dp），而栏展开时看得见的只有 [44dp, 屏幕−44dp]。差出来的
     * `BOTTOM_INSET_DP = 44 + 20 = 64dp` 就落在底栏后面 / 屏幕外。
     *
     * 长页面无所谓（往下滑栏就收起来了，最后一截就露出来了），但**短页面**滚不动、
     * 栏也收不起来，最下面那 64dp 会永远看不到 —— 补上 padding 之后它就变成"可以滑上来的空白"，
     * 于是内容都够得着（Safari 自己也是这么干的）。
     *
     * 单位换算：device-width 的页面里 1 CSS px = 1 dp，所以 CSS 的 px 值 = 设备像素 / devicePixelRatio。
     * 用 `body.style.paddingBottom` 叠加而不是覆盖，避免踩掉网站自己的底部留白；
     * 用 `data-ios7pad` 记一下补过多少，重复注入（同一页多次 onPageFinished）不会越补越大。
     */
    private fun injectBottomInset(view: WebView) {
        val devicePx = dp(BOTTOM_INSET_DP)
        if (devicePx <= 0) return
        val js = "(function(){" +
                "var b=document.body;if(!b)return;" +
                "var r=window.devicePixelRatio||1;" +
                "var pad=Math.round(" + devicePx + "/r);" +
                "if(b.getAttribute('data-ios7pad')===String(pad))return;" +
                "b.setAttribute('data-ios7pad',String(pad));" +
                "var cur=parseFloat(getComputedStyle(b).paddingBottom)||0;" +
                "b.style.paddingBottom=(cur+pad)+'px';})()"
        try {
            view.evaluateJavascript(js, null)
        } catch (e: Exception) {
            // 页面已经销毁就忽略
        }
    }

    /**
     * 强制双指缩放：不少网站把 viewport 写成 user-scalable=no / maximum-scale=1，
     * WebView 就会连双指都不给缩放。这里在页面加载完后把 viewport 改回来，
     * 让「捏合放大」在任何页面上都生效 —— 这正是 iOS 7 Safari 的行为。
     */
    private fun forcePinch(view: WebView) {
        if (!FORCE_PINCH) return
        // 只放宽缩放的上下限，保留网站原本的 width / initial-scale，避免把桌面版页面撑爆
        val js = "(function(){" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                "m.content='width=device-width,initial-scale=1';" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "var c=m.getAttribute('content')||'';" +
                "var parts=c.split(','),out=[],hasMax=false,hasMin=false,hasUser=false;" +
                "for(var i=0;i<parts.length;i++){var p=parts[i].replace(/^ +| +$/g,'');" +
                "if(!p){continue;}var k=p.split('=')[0].toLowerCase();" +
                "if(k=='maximum-scale'){out.push('maximum-scale=6');hasMax=true;}" +
                "else if(k=='minimum-scale'){out.push('minimum-scale=0.25');hasMin=true;}" +
                "else if(k=='user-scalable'){out.push('user-scalable=yes');hasUser=true;}" +
                "else{out.push(p);}}" +
                "if(!hasMax){out.push('maximum-scale=6');}" +
                "if(!hasMin){out.push('minimum-scale=0.25');}" +
                "if(!hasUser){out.push('user-scalable=yes');}" +
                "m.setAttribute('content',out.join(','));})()"
        try {
            view.evaluateJavascript(js, null)
        } catch (e: Exception) {
            // 页面已经销毁就忽略
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachEdgeGesture(w: ObservableWebView) {
        w.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var armed = false
            var consumed = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                // 顶栏/底栏收缩跟手指走（见 trackBarByFinger 的说明）
                trackBarByFinger(e, w)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.x
                        sy = e.y
                        armed = sx < dp(36f) || sx > v.width - dp(36f)
                        consumed = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (armed && !consumed) {
                            val dx = e.x - sx
                            val dy = e.y - sy
                            if (abs(dx) > dp(55f) && abs(dx) > abs(dy) * 1.5f) {
                                if (sx < dp(36f) && dx > 0) {
                                    if (w.canGoBack()) w.goBack()
                                    consumed = true
                                } else if (sx > v.width - dp(36f) && dx < 0) {
                                    if (w.canGoForward()) w.goForward()
                                    consumed = true
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        armed = false
                        consumed = false
                    }
                }
                return consumed
            }
        })
    }

    private fun handleUrl(url: String): Boolean {
        val scheme = Uri.parse(url).scheme ?: ""
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "file") return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            true
        }
    }

    private fun showLinkMenu(url: String) {
        val items = arrayOf("打开", "在新标签页中打开", "拷贝链接地址")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> currentTab().web?.loadUrl(url)
                    1 -> newTab(url)
                    2 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", url))
                        toast("已拷贝")
                    }
                }
            }
            .show()
    }

    private fun tabOf(view: WebView): Tab? = tabs.firstOrNull { it.web === view }

    private fun errorPage(url: String): String =
        "<html><body style='font-family:sans-serif;padding:40px 20px;text-align:center;color:#333'>" +
                "<div style='font-size:17px;margin-bottom:10px'>无法打开网页</div>" +
                "<div style='font-size:13px;color:#8e8e93'>${esc(url)}</div></body></html>"

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    // ------------------------------------------------------------------
    // 标签页
    // ------------------------------------------------------------------
    private fun newTab(url: String): Tab {
        val tab = Tab()
        tab.web = createWebView()
        tabs.add(tab)
        currentIndex = tabs.lastIndex
        attach(tab)
        if (url.isBlank()) showStartPage(tab) else tab.web?.loadUrl(normalizeUrl(url))
        updateField()
        updateNavButtons()
        return tab
    }

    private fun attach(tab: Tab) {
        webContainer.removeAllViews()
        tab.web?.let { webContainer.addView(it) }
    }

    private fun selectTab(i: Int) {
        if (i !in tabs.indices) return
        // 这里以前会 capture(currentTab())（把旧页软件绘制一遍，实测 10~70ms）。
        // 没必要：进任务视图时 updateThumbFromFrame() 已经用屏幕真实像素刷新过当前页的缩略图，
        // 所以旧页的 thumb 本来就是最新的。去掉它，「点卡片 → 动画开始」少一个重活。
        currentIndex = i
        // 已经是这一页就别再 remove/add 了：反复装卸 WebView 会白闪一下
        if (webContainer.getChildAt(0) !== tabs[i].web) attach(tabs[i])
        updateField()
        updateNavButtons()
        animateBars(0f)
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val k = childOfTab(i)
        val card = if (k in 0 until tabList.childCount) tabList.getChildAt(k) else null
        // 左滑 / 点叉：卡片先向左飞出，再让下面的卡片滑上来补位
        if (card != null && overlayTabs.visibility == View.VISIBLE && !tabClosing) {
            tabClosing = true
            card.animate().cancel()
            card.animate().translationX(-(cardW + dp(40f)).toFloat()).alpha(0f)
                .setDuration(230).setInterpolator(accel)
                .withEndAction { tabClosing = false; removeTabAt(i) }
                .start()
        } else removeTabAt(i)
    }

    private fun removeTabAt(i: Int) {
        if (i !in tabs.indices) return
        // 先在「还没删」的时候记下每张卡的位置，否则下标一挪位置就对不上了
        val before = snapshotPositions()
        val tab = tabs[i]
        webContainer.removeView(tab.web)
        tab.web?.destroy()
        if (tab.thumb === hero.bitmap) hero.bitmap = null
        tab.thumb?.recycle()
        tab.thumb = null
        tabs.removeAt(i)
        // 删掉的是「当前页前面」的标签：当前页在列表里整体前移了一位，
        // 索引必须跟着减 1，否则 attach() 装上去的是后面那一页
        // —— 表现为「关掉旁边一个标签，再点完成，进的是另一页」。
        if (i < currentIndex) currentIndex--
        if (tabs.isEmpty()) {
            newTab("")
            if (overlayTabs.visibility == View.VISIBLE) {
                // 最后一个标签页被删掉：不再让新卡片「啪」地出现在原地，
                // 而是走和「＋」完全一样的一套 —— 新页从屏幕下方以倒梯形飞进来、
                // 一路拉成长方形铺满整屏，结束后直接进到这一页。
                // 卡片全清掉（而不是重建一张）：留一张卡会在飞入时跟着露出来，
                // 变成「两张纸叠在一起」的重影。
                tabList.removeAllViews()
                viewOrder = mutableListOf()
                nextFrame(2) { playNewTabFly() }
            }
            return
        }
        // **同步维护 viewOrder**：删掉的那个 tab 下标要从顺序表里去掉，后面的整体前移一位。
        // 不维护的话（数量对不上）refreshTabs 会回退到"当前页在最上面"的旋转 ——
        // 你刚拖好的顺序会在删一张标签之后整体重排一次（看着就是"跳一下"）。
        // 注意：只在这里维护；newTab / selectTab **仍然走旋转**（当前页回到整摞最上面），
        // 那条旋转是刻意的，摆位补间由 rebuildAnimated 负责。
        val removedChild = viewOrder.indexOf(i)
        if (removedChild >= 0) {
            viewOrder.removeAt(removedChild)
            for (k in viewOrder.indices) if (viewOrder[k] > i) viewOrder[k] = viewOrder[k] - 1
        }
        currentIndex = currentIndex.coerceIn(tabs.indices)
        attach(tabs[currentIndex])
        updateField()
        updateNavButtons()
        if (overlayTabs.visibility != View.VISIBLE) return
        // 重建后让每张卡从原来的位置滑过来：先快后慢，像被轻轻推上去一样
        rebuildAnimated(before)
    }

    /**
     * 把当前网页画成缩略图。
     *
     * 注意：WebView 是 GPU 合成的，`draw(Canvas)` 偶尔会**一个像素都拿不到**
     * （整张位图 alpha 全 0）。这种图一旦当成缩略图存下去，卡片上那块网页区域就是空白/发黑的
     * —— 表现就是「这个标签页没有页面截图」。所以这里先判空：空的直接丢掉重试，
     * 重试几次还不行就沿用上一次的旧图（旧图至少是张正常的页面）。
     */
    private fun capture(tab: Tab, retry: Int = 0) {
        val w = tab.web ?: return
        if (w.width <= 0 || w.height <= 0) return
        // 任务视图动画期间**不要**软件绘制 WebView：一次 30~100ms（复杂页面更久），
        // 正好砸在动画帧上（实测新建标签后 500~840ms 的那两次 capture 就压在飞入动画里，
        // 是「压力大时飞入掉帧」的直接原因）。延后 200ms 再来，retry 有上限不会死循环。
        if (tabAnimating && retry < 5) {
            w.postDelayed({ if (tabs.contains(tab)) capture(tab, retry + 1) }, 200)
            return
        }
        val h = minOf(w.height, 3200)
        val bmp = try {
            Bitmap.createBitmap(w.width, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            return
        }
        try {
            w.draw(Canvas(bmp))
        } catch (e: Exception) {
            // 画失败也走下面的判空
        }
        if (isBlankBitmap(bmp)) {
            bmp.recycle()
            // 页面刚画完那一瞬间 WebView 可能还没提交这一帧，隔一会儿再来；
            // 最后再补一次更晚的，避免后台标签页拿到一张空白图
            if (retry < 3) w.postDelayed({
                if (tabs.contains(tab)) capture(tab, retry + 1)
            }, if (retry < 2) 220L else 700L)
            return
        }
        val old = tab.thumb
        // hero / 卡片缓存可能还引用着旧截图，先松手再回收，否则会画到已回收的位图
        if (old === hero.bitmap) hero.bitmap = null
        old?.recycle()
        tab.thumb = bmp
        tab.thumbVer++
        // 刚补上来的图：任务视图如果正开着，只把对应那张卡换成新位图。
        // （以前这里走 rebuildAnimated() —— 那会把整个列表 removeAllViews 重建，
        //  所有卡片瞬间变 0×0 等布局，正好撞上进出场动画就会算错落点。）
        refreshCardBitmaps()
    }

    /**
     * 采样判断这张位图是不是「根本没画上」。
     *
     * 两种都算没画上：
     *  ① 整张 alpha=0（WebView 还没提交这帧）；
     *  ② 整张一个颜色（最常见是纯白 —— 后台/刚摘下来的 WebView 软件绘制经常这样）。
     * 两者都必须按**比例**判，不能按「任意一个采样点」判：
     * 页面只要有一小块透明区域（圆角、阴影、透明 iframe、半透明遮罩），
     * 「任意一点透明就整张作废」会让这张图永远被判无效 —— 重试几次后放弃，
     * thumb 一直是旧图或 null，卡片那块就空白了。
     */
    private fun isBlankBitmap(bmp: Bitmap): Boolean {
        if (bmp.isRecycled) return true
        // 按「整行」取像素再抽样：getPixel 是逐点 JNI，一张 1080×1885 要 1200 次调用（约 10ms）；
        // 按行 getPixels 只要 46 次，快一个数量级，而且不用分配整张图的 IntArray。
        val iw = bmp.width
        val ih = bmp.height
        if (iw <= 0 || ih <= 0) return true
        val row = IntArray(iw)
        var total = 0
        var clear = 0
        var same = 0
        var first = -1
        var y = 0
        while (y < ih) {
            try {
                bmp.getPixels(row, 0, iw, 0, y, iw, 1)
            } catch (e: Throwable) {
                return false
            }
            var x = 0
            while (x < iw) {
                val c = row[x]
                total++
                if ((c ushr 24) == 0) clear++
                if (first == -1) first = c else if (c == first) same++
                x += 41
            }
            y += 41
        }
        if (total == 0) return true
        // ① 整张（≥95% 采样点）透明 → WebView 这一帧没提交
        if (clear * 100 / total >= 95) return true
        // ② 整张（≥99% 采样点）同一个颜色 → 纯白/纯黑空页
        return same * 100 / total >= 99
    }

    fun updateField() {
        // 只有在**真的在编辑**时才不收栏；只看 `hasFocus()` 会把"点一下展开栏"留下的
        // 焦点也算进去，结果滚页永远收不回去（这就是收缩失效那个 bug）。
        if (fieldExpanded) return
        urlField.setText(displayUrl(currentTab().url))
        centerField(animated = false)   // 未聚焦时网址始终居中
    }

    private fun showStartPage(tab: Tab) {
        tab.url = ""
        tab.title = getString(R.string.favorites)
        tab.web?.loadDataWithBaseURL(START_BASE, startPageHtml(), "text/html", "utf-8", null)
        // 新标签刚建出来时还没有缩略图，等收藏页画完补拍一张，卡片才不会一片空白
        tab.web?.postDelayed({
            if (!tabs.contains(tab)) return@postDelayed
            if (tab.thumb == null) {
                capture(tab)
                if (overlayTabs.visibility == View.VISIBLE && !tabAnimating) rebuildAnimated()
            }
            refreshStartPageCache(tab)
        }, 420)
        // 420ms 那一下有可能拍到的还是白页（起始页首次渲染要 400ms 上下），晚一拍再刷一次缓存
        tab.web?.postDelayed({
            if (!tabs.contains(tab)) return@postDelayed
            refreshStartPageCache(tab)
        }, 1100)
    }

    private fun startPageHtml(): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
        sb.append("<style>")
        sb.append("body{margin:0;padding:0;background:#fff;color:#000;font-family:-apple-system,Helvetica,sans-serif}")
        sb.append(".h{font-size:13px;color:#8e8e93;text-align:center;margin:26px 0 12px}")
        sb.append(".g{display:flex;flex-wrap:wrap;padding:0 8px}")
        sb.append(".i{width:33.33%;text-align:center;margin-bottom:20px;text-decoration:none;color:#000;font-size:11px}")
        sb.append(".ic{width:50px;height:50px;border-radius:9px;margin:0 auto 6px;position:relative;overflow:hidden;")
        sb.append("display:flex;align-items:center;justify-content:center;color:#fff;font-size:25px;font-weight:300;")
        sb.append("background:linear-gradient(180deg,var(--c1),var(--c2))}")
        sb.append(".ic::before{content:attr(data-l)}")
        sb.append(".ic img{position:absolute;inset:0;width:100%;height:100%;border-radius:9px}")
        sb.append("</style></head><body>")
        sb.append("<div class='h'>个人收藏</div><div class='g'>")
        // 图标先用首字母 + iOS 7 风格渐变色块兜底，真实 favicon 加载成功后再盖上去；
        // 加载失败（国内访问不到 Google）时会 remove 掉 img，露出字母磁贴，不会出现裂图。
        val palette = arrayOf(
            "#FFB340" to "#FF8A00", "#5AC8FA" to "#0A7CFF", "#7D7AFF" to "#4B48D6",
            "#4CD964" to "#12A150", "#FF6961" to "#D70015", "#64D2FF" to "#0A84FF"
        )
        Store.bookmarks(this).take(9).forEachIndexed { idx, bm ->
            val c = palette[idx % palette.size]
            val letter = bm.title.trim().firstOrNull()?.toString()?.uppercase() ?: "&#8226;"
            sb.append("<a class='i' href='${esc(bm.url)}'>")
            sb.append("<span class='ic' data-l='$letter' style='--c1:${c.first};--c2:${c.second}'>")
            sb.append("<img src='https://www.google.com/s2/favicons?domain=${esc(hostOf(bm.url))}&sz=64' onerror='this.remove()'>")
            sb.append("</span>")
            sb.append(esc(bm.title)).append("</a>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 浮层：标签页
    // ------------------------------------------------------------------
    // ---- 动画曲线（Material 标准缓动，接近 iOS 的 ease-in-out）----
    private val ease get() = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val decel get() = PathInterpolator(0f, 0f, 0.2f, 1f)
    private val accel get() = PathInterpolator(0.4f, 0f, 1f, 1f)
    /**
     * 苹果 CoreAnimation 的默认时间曲线 `kCAMediaTimingFunctionDefault`
     * = `cubic-bezier(0.25, 0.1, 0.25, 1)`：起步带一点"黏"，中段最快，**收尾又长又软**。
     * 地址栏进入编辑态那一整套（网址左移、圆角矩形收窄、取消按钮、蓝色丝带）都用它。
     */
    private val apple get() = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    /** 等布局真正完成后再跑（此时才拿得到 ScrollView 的真实高度） */
    private fun afterLayout(run: () -> Unit) {
        tabList.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    tabList.viewTreeObserver.removeOnPreDrawListener(this)
                    run()
                    return true
                }
            }
        )
    }

    // 英雄视图起飞帧：把「顶栏 + 网页」合成进一张位图，
    // 这样进场时那条灰色顶栏（含居中的网址）会随透视一起被挤扁，
    // 再与卡片的标题条交叉淡化 —— 网址就这样过渡成了标签页的居中名称。
    private var heroComposite: Bitmap? = null
    /** 英雄视图布局之后「应当在」的 top（tabList 坐标系）。位置按它反解，不读 hero.top：
     *  列表滚动过之后 hero.top 要等下一次 layout 才更新，先读会读到旧值 →
     *  起飞第一帧比卡片低 scrollY 像素，看着就是「往下抽一下」。 */
    private var heroBaseY = 0f

    /** heroComposite 是哪个标签页的画面（tab.id）。关过标签页 / 换过页就会失效，
     *  退场前必须比对 —— 否则动画会拿着别的标签页的截图起飞（串页）。 */
    private var heroCompositeId: Int = -1

    /** 等 n 个完整帧过去再跑：切完网页必须等它真的画出来，截图才不会是上一页 */
    private fun nextFrame(times: Int, run: () -> Unit) {
        if (times <= 0) {
            run()
            return
        }
        Choreographer.getInstance().postFrameCallback { nextFrame(times - 1, run) }
    }

    /** 模糊背景 + 底栏的淡入淡出（卡片层不整体淡出，卡片各自飞入 / 飞出） */
    private fun fadeTabsChrome(
        to: Float, dur: Long, delay: Long = 0L,
        interp: TimeInterpolator = decel, end: (() -> Unit)? = null
    ) {
        tabChrome.animate().cancel()
        tabBottomWrap.animate().cancel()
        tabChrome.animate().alpha(to).setDuration(dur).setStartDelay(delay)
            .setInterpolator(interp).withEndAction { end?.invoke() }.start()
        tabBottomWrap.animate().alpha(to).setDuration(dur).setStartDelay(delay)
            .setInterpolator(interp).start()
    }

    /**
     * 只让标签页底栏淡出。
     *
     * 模糊背景**不能**跟着一起淡：它一透明，透出来的就是底下真正的那一页，
     * 而英雄视图这时还没铺满，于是卡纸上沿到状态栏之间会露出半截「不属于这张纸」的画面 ——
     * 看起来就像模糊层压在了拉伸动画的上层。背景一律留到最后一帧，
     * 等英雄视图和真实页面逐像素重合时，整层一起撤掉（看不出接缝）。
     * 底栏则可以单独淡出：那底下是浏览器自己的工具栏，两个工具栏交叉淡化很自然。
     */
    private fun fadeTabBottomBar(delay: Long = 180L) {
        tabBottomWrap.animate().cancel()
        tabBottomWrap.animate().alpha(0f).setDuration(200).setStartDelay(delay)
            .setInterpolator(accel).start()
    }

/**
 * 模糊背景**最后**才淡：英雄视图此时已经铺满整个取景区（含状态栏），
 * 淡出后露出来的是真实页面，与英雄视图逐像素重合，看不出接缝。
 * 淡出时机必须晚于 hero 铺满的那一帧，否则中途会透出半截真实页面。
 */
private fun fadeTabBackdrop(delay: Long, dur: Long = 130L) {
    tabChrome.animate().cancel()
    tabChrome.animate().alpha(0f).setDuration(dur).setStartDelay(delay)
        .setInterpolator(accel).start()
}

    private fun pageW() = webContainer.width.takeIf { it > 0 } ?: rootView.width

    /** 网页的 viewport 高度（**固定** = 屏幕高 − 24dp），不等于可见高度，见下面两个函数 */
    private fun pageH() = webContainer.height.takeIf { it > 0 } ?: rootView.height

    /**
     * 网页**可见区**的上沿（rootView 坐标）。
     *
     * 容器布局上沿固定是 24dp（收起态顶栏高度），展开态靠 `translationY = 20dp·(1−p)` 往下推，
     * 于是可见上沿恒等于顶栏下沿 `44−20p`。**不要**直接读 `webContainer.top`（那是布局位置，
     * 永远 24dp）；起飞帧取景、缩略图裁切都必须用这个可见上沿。
     */
    private fun webTopVisual(): Int =
        (webContainer.top + webContainer.translationY).roundToInt()

    /** 网页**可见区**的下沿：屏幕底部减掉键盘让位和底栏的布局高度（底栏收起时它就等于屏幕底部） */
    private fun webBottomVisual(): Int =
        (rootView.height - rootView.paddingBottom - bottomWrap.height).coerceAtLeast(1)

    /** 网页**可见区**高度（= viewport 高度 − 被栏盖住的那一截） */
    private fun webVisibleH(): Int =
        (webBottomVisual() - webTopVisual()).coerceAtLeast(1)

    private fun pageTop() = webTopVisual()

    /** 取景框（顶栏 + 网页）的上沿 / 高度 */
    private fun chromeTop() = 0
    private fun chromeBarH() = topBar.height.takeIf { it > 0 } ?: 0

    /**
     * 取景框高度 = 从屏幕顶部到「网页可见底」。
     *
     * 以前是 `webContainer.bottom - chromeTop()` —— 那时容器的底就是网页可见底。
     * 改成固定 viewport 之后容器的底伸到屏幕以外了，必须换成 `webBottomVisual()`，
     * 否则英雄视图会比真实页面高一整截（底边压进工具栏）。
     */
    private fun chromeH() = webBottomVisual().coerceAtLeast(1)

    /**
     * 英雄视图的取景高度 = 浏览区高度 + 状态栏高度。
     *
     * 关键：必须**包含状态栏那一条**。浏览区的上沿是 chromeTop（= 状态栏高度，实测 55px），
     * 如果英雄视图只覆盖 chromeTop 以下，状态栏那条就会一直露出底下标签视图的模糊层 ——
     * 看上去就是「倒梯形上方、状态栏之下永远挂着半截模糊层」。
     * 把取景上沿改成整屏顶部（0），拉伸终点就正好和真实页面逐像素重合，接缝消失。
     */
    private fun heroTopY(): Int = 0

    /** 英雄视图铺满后的高度：状态栏 + 顶栏 + 网页 */
    private fun heroFullH(): Int = (chromeTop() + chromeH()).coerceAtLeast(1)

    /**
     * 把「顶栏 + 当前网页」画进一张位图。只在换页、新标签飞入这类「屏幕本来就要变」的时机用；
     * 进任务视图的起飞帧走 [grabScreenFrame]（读真实屏幕像素）。
     */
    private fun captureComposite(includePage: Boolean = true): Bitmap? {
        val w = pageW()
        val h = heroFullH()
        if (w <= 0 || h <= 0 || rootView.childCount == 0) return null
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(if (privateMode) Color.parseColor("#3A3A3A") else Color.parseColor("#F9F9F9"))
            c.save()
            c.translate(0f, -heroTopY().toFloat())
            // 从「一层」变成「网页层 + 栏层」两层了，两层都得画；
            // 任务视图 / 书签 / 建议浮层跳过（它们不属于页面内容）。
            // 注意 `View.draw()` 不会带上自己的 translation，要手动加上 ——
            // 网页层就是靠 translationY 跟着栏走的，漏了它会整体偏高 20dp。
            for (i in 0 until rootView.childCount) {
                val ch = rootView.getChildAt(i)
                if (ch.visibility != View.VISIBLE) continue
                if (ch.id == R.id.overlayTabs || ch.id == R.id.overlayBookmarks ||
                    ch.id == R.id.overlaySuggest
                ) continue
                // 便宜版：不画网页层（`WebView.draw()` 实测 50ms 上下），只留底栏/顶栏 + 底色，
                // 用来"立刻起飞"。真图等页面画完再换上去（见 upgradeNewTabFrame）。
                if (!includePage && ch.id == R.id.webContainer) continue
                c.save()
                c.translate(ch.left + ch.translationX, ch.top + ch.translationY)
                ch.draw(c)
                c.restore()
            }
            c.restore()
            out
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 一摞卡片的几何
    // ------------------------------------------------------------------

    /**
     * 子视图 k → 标签页下标。
     * 最后一张永远是「当前页」：它压在整摞的最前面（最靠下），
     * 其余标签按原有顺序排在它上面 —— 这就是 iOS 7「当前页是最前那张」的排法。
     */
    private var viewOrder = mutableListOf<Int>()

    private fun childOfTab(i: Int): Int = viewOrder.indexOf(i)
    private fun tabOfChild(k: Int): Int = viewOrder.getOrNull(k) ?: -1

    /**
     * 卡片上沿在屏幕上的 y（不含下面算出来的缩放 / 平移）。
     *
     * 这里把 `tabList.translationY`（越界拖拽的橡皮筋位移）也算进来：位移一变，
     * 卡片相对「上 1/9 线」的位置就变了，缩放要跟着重算 —— 于是拖到尽头继续拖时，
     * 卡片一边被整体带走、一边按「越过 1/9 → 顶到 0.8 → 再往上 0.6」缩小，
     * 整摞看起来就像在旋转/翻过去。
     */
    private fun cardTopY(c: View): Float =
        (tabScroll.top + c.top - tabScroll.scrollY + tabList.translationY).toFloat()

    /**
     * 卡片此刻在屏幕上**真正画出来的**上沿（rootView 坐标）。
     *
     * 新模型（见 `iOS7Safari-卡片变换架构.md`）里这条式子**就是精确的**，没有任何补偿项：
     * `Trap` 从视图上沿开始画（`hStart` 静止时为 0），所以
     * 视觉上沿 = 布局上沿 + translationY + 那两种临时位移。
     * 缩小只改 `k`/`hEnd`（等比缩放**不动上沿**），所以这里也不需要再乘什么。
     *
     * 凡是「按屏幕位置算距离」的地方都必须用它：
     *  - `cardState()` 的起飞/落点；
     *  - 退场时「飞出屏幕」要移动多少；
     *  - 补位动画快照的起点。
     * 以前这里多一个 `pivotY·(1−s)`（最多 277px），正是"残卡/瞬移/抽搐"那一串 bug 的根。
     */
    private fun cardVisualTop(v: View): Float {
        val tv = v as? TrapezoidView
        if (tv == null) return cardTopY(v) + v.translationY
        return cardTopY(v) + v.translationY + tv.screenShift + hShiftDy(tv)
    }

    /**
     * `hShift`（拖动让开时沿卡片平面下沉）造成的**屏幕位移增量**。
     *
     * 注意是"增量"不是绝对值：卡片被推远之后 `hStart` 本身就有上千像素的投影偏移，
     * 但那段已经由 `Trap.draw(y0=…)` 抵掉了（绘制从视图上沿开始），
     * 所以这里只算 `hStart + hShift` 相对 `hStart` 多出来的那一点。
     */
    private fun hShiftDy(tv: TrapezoidView): Float {
        if (tv.hShift == 0f) return 0f
        return Trap.projY(tv.hStart + tv.hShift, tv.theta, tv.dOverH, tv.hRef) -
            Trap.projY(tv.hStart, tv.theta, tv.dOverH, tv.hRef)
    }

    /**
     * 拖动给这张卡带来的**屏幕纵向位移** = 屏幕平移（screenShift，整格位移）
     * + 3D 位移折算（projY(hShift)，只用于让开那一下的下沉）。
     */
    private fun shiftScreenDy(v: View): Float {
        if (v !is TrapezoidView) return 0f
        return v.screenShift + hShiftDy(v)
    }

    /** 卡片此刻真正画出来的高度（= 投影出来的高度，相机参考用卡片自己的 `hRef`） */
    private fun cardVisualH(v: View): Float {
        val tv = v as? TrapezoidView ?: return cardRenderH.toFloat()
        val sh = tv.hShift
        return Trap.projY(tv.hEnd + sh, tv.theta, tv.dOverH, tv.hRef) -
            Trap.projY(tv.hStart + sh, tv.theta, tv.dOverH, tv.hRef)
    }

    /**
     * **唯一**的"改卡片大小"入口。
     *
     * 现在走的是**真透视**：不是把图缩小，而是把卡片**沿它自己的平面往远处推** `h` ——
     * `hStart = h`、`hEnd = h + cardH`、`k = 1`。相机参考高度固定成 `cardH`（`hRefBase`），
     * 所以推远是真的"近大远小"：高度按二次衰减、宽度按一次衰减。
     *
     * 副作用（物理上正确、但和等比缩放不同）：同样是 0.6 高，卡片会比原来**宽约 26%**，
     * 倒梯形的收窄也会变浅 —— 因为远处的物体透视畸变更小。
     *
     * `s > 1` 只有拖动"拿起"那 2% 会用到：那种情况不推远，直接用 `k` 放大。
     */
    private fun applyCardScale(v: View, s: Float) {
        val tv = v as? TrapezoidView ?: return
        val h: Float
        val k: Float
        if (s >= 1f) {
            h = 0f
            k = s.coerceIn(1f, 1.2f)
        } else {
            h = recedeFor(s, tv.theta, tv.dOverH) * cardH
            k = 1f
        }
        val hEnd = h + cardH
        // 低通的"上次写的值"跟着一起落盘：任何外部写入（起立/放平/补位/拖动）都会
        // 自动同步平滑器，`updateTransforms()` 就不需要再靠"回读视图 + 比对"去猜了
        // —— 那套回读在真透视下会反过来喂自己（俯角一变尺寸就变），正是抖动的来源。
        tv.scaleSmooth = s
        if (tv.k != k || tv.hStart != h || tv.hEnd != hEnd) {
            tv.k = k
            tv.hStart = h
            tv.hEnd = hEnd
            tv.invalidate()
        }
    }

    /**
     * 按**真实遮挡关系**重算每张卡的遮光。
     *
     *  · 深度 = 挡光那张（下一张卡）的视觉上沿 − 自己这张的视觉上沿；
     *  · 强度 = 挡光那张的 alpha（半透明只挡一半光 —— 物理上就是透光率）。
     *
     * 所以进场/退场时旁边那张一动，阴影就跟着它走（depth 变），不再靠 `shadowFrac` 的
     * 全局渐隐渐显去"假装"。
     */
    private fun refreshShadows() {
        val n = tabList.childCount
        if (n == 0) return
        val heroAttached = hero.parent === tabList
        val cards = n - (if (heroAttached) 1 else 0)
        if (cards <= 0) return
        // 复用数组：这个函数每帧都跑，不能每帧 new
        if (shadowTops.size < cards) {
            shadowTops = FloatArray(cards + 4)
            shadowAls = FloatArray(cards + 4)
            shadowHts = FloatArray(cards + 4)
        }
        val tops = shadowTops
        val als = shadowAls
        val hts = shadowHts
        for (i in 0 until cards) {
            val v = tabList.getChildAt(i)
            tops[i] = cardVisualTop(v)
            als[i] = v.alpha
            hts[i] = cardVisualH(v)
        }
        // 一张卡只有在**真被下一张压住**（两张框有重叠）时才会被挡光：
        // 重叠越浅，挡得越少 —— 所以进场时两张卡还离得很远的话，这里天然给 0，
        // 不会出现"凭空一段暗带"（那就是用户说的"原有阴影没删掉、还打架"）。
        fun shadeOf(idx: Int, top: Float, h: Float, tv: TrapezoidView) {
            if (idx + 1 >= cards) {          // 最后一张：没有卡压着它
                tv.shadowDepth = 0f
                tv.shadowFrac = 1f
                return
            }
            val gap = tops[idx + 1] - top
            val overlap = h - gap            // >0 才是"被压住"
            // 量化到 1px：滚动时这个值每帧都在变半个像素，不量化就会每帧 invalidate 一次，
            // 整张卡（网格 + 渐变 + 路径）跟着重录 —— 卡片一多这是实打实的开销，
            // 而 1px 的深度差异肉眼看不出来。
            tv.shadowDepth = if (overlap <= 0.5f) 0f
            else ((gap.coerceAtLeast(0f) + 0.5f).toInt()).toFloat()
            tv.shadowFrac = if (overlap <= 0.5f) 1f
            else als[idx + 1] * (overlap / cardStep).coerceIn(0f, 1f)
        }
        val vpH = rootView.height.toFloat()
        for (i in 0 until cards) {
            val tv = tabList.getChildAt(i) as? TrapezoidView ?: continue
            // 视口外很远的卡片：位图都是懒渲染的（没有），遮光算了也没人看，跳过省一次 invalidate
            if (tv.bitmap == null) continue
            if (tops[i] + hts[i] < -vpH || tops[i] > vpH * 2f) continue
            shadeOf(i, tops[i], hts[i], tv)
        }
        // 英雄视图顶替的是第 heroStackIndex 张卡，挡光的是它后面那张
        val hs = heroStackIndex
        if (heroAttached && hs in 0 until cards) {
            shadeOf(hs, cardVisualTop(hero), cardVisualH(hero), hero)
        }
    }

    /** 进场/退场途中每帧刷一次遮光（那几段动画不经过 `updateTransforms()`） */
    private var shadowTops = FloatArray(0)
    private var shadowAls = FloatArray(0)
    private var shadowHts = FloatArray(0)
    private var shadowTicking = false
    private val shadowTick = object : Runnable {
        override fun run() {
            if (!shadowTicking) return
            refreshShadows()
            tabList.postOnAnimation(this)
        }
    }

    private fun startShadowTick() {
        if (shadowTicking) return
        shadowTicking = true
        tabList.postOnAnimation(shadowTick)
    }

    private fun stopShadowTick() {
        shadowTicking = false
        tabList.removeCallbacks(shadowTick)
    }

    /**
     * 卡片**当前**的视觉高度比（0.6 / 0.8 / 1.0 …）。
     *
     * 真透视下 `k` 恒为 1，尺寸全在 `hStart` 上 —— 所以不能再读 `k` 当"大小"。
     * 这个值等于"画出来的高度 ÷ 静止时的渲染高度"，也就是曲线里那个 s。
     * ⚠ 只在卡片处于静止倾角时用它（`cardRenderH` 是按 `tiltNow` 算的）。
     */
    private fun cardScale(v: View): Float {
        if (cardRenderH <= 0) return 1f
        return (cardVisualH(v) / cardRenderH).coerceIn(0.05f, 1.3f)
    }

    /**
     * 由"想要的视觉高度比 s"反解"要沿卡片平面推远多少"（单位：cardH）。
     *
     * 投影里，卡片从 `h` 到 `h+H` 的渲染高度比是
     * `s(u) = (1+a) / ((1+u·a)(1+(u+1)·a))`，其中 `a = sinθ / dOverH`、`u = h/H`。
     * 反解是一元二次方程：`a²u² + a(2+a)u + (1+a)(1 − 1/s) = 0`。
     */
    private fun recedeFor(s: Float, theta: Float, dOverH: Float): Float {
        val a = sin(Math.toRadians(theta.toDouble())).toFloat() / dOverH.coerceAtLeast(0.01f)
        if (a <= 1e-4f) return 0f            // 完全平放：没有透视，推远也不会变小
        val b = (1f + a) * (1f - 1f / s.coerceIn(0.05f, 1f))
        val disc = (2f + a) * (2f + a) - 4f * b
        if (disc <= 0f) return 0f
        return ((-(2f + a) + sqrt(disc)) / (2f * a)).coerceAtLeast(0f)
    }

    /**
     * 画面可用区的最上沿。
     *
     * 状态栏改用系统之后，内容区的 y=0 就是顶栏的上沿，所以这里恒为 0。
     */
    private fun contentTopY(): Float = 0f

    /**
     * 收缩分界线：卡片上沿越过这里才开始缩小（现在是 **上 1/9**，原先是 1/8）。
     *
     * 这条线是「一发动全身」的源头，下面三处全部由它推导，改这一个数就够了：
     *  ① `updateTransforms()`：第一段缩放的行程 `span = 这条线 − 画面顶`；
     *  ② `refreshTabs()` 的 `restTop`：整摞卡片的上内边距，保证静止时当前页正好停在线下面 2dp（不缩放）；
     *  ③ 进场/退场的落点：`cardState()` 读的是卡片的真实位置与缩放，所以终点自动跟着走。
     */
    private fun shrinkLineY(): Float =
        (rootView.height / 9f).coerceAtLeast(contentTopY() + 1f)

    /**
     * 梯形两条对角线交点距卡片上沿的距离 —— 第二段缩小以它为心。
     *
     * 必须传**当前这一帧**的相机距离：越界俯身时 θ 和 d 都在变（`applySteep()` / `updateTransforms()`
     * 里是 `dOverH × (1 − steep × STEEP_D)`），交点会跟着移动。以前这里写死 `D_OVER_H`，
     * 于是「越界下拉」和「卡片缩到 0.8 以下」同时发生时（顶边下拉正好是这种情况），
     * 缩放支点比实际对角线交点偏了一点，卡片会轻微地"滑一下"。
     */
    private fun diagCrossY(dOverH: Float = D_OVER_H): Float =
        cardRenderH * Trap.diagCrossRatio(tiltNow, dOverH)

    /** 当前页是整摞最上面（最后面）那张，所以滚到顶部就能看到它 */
    /**
     * 进任务视图时要滚到哪。
     *
     * 以前恒为 0（= 整摞顶部）。现在第 0 格不再固定是当前页（换位可以把任何一张放到任何一格），
     * 所以按「当前页那张卡在第几格」滚过去 —— 否则起飞帧会从屏幕外飞进来。
     */
    private fun desiredScroll(): Int {
        val k = childOfTab(currentIndex)
        if (k <= 0) return 0
        return (k * cardStep).coerceAtLeast(0)
    }

    /**
     * 进场时把**当前页那张卡**精确对到静止位（上 1/9 分界线下方 2dp）。
     *
     * 只按 `desiredScroll()` 粗滚一次不够：上一轮如果滑到整摞最下面、当前页那张被滚到线上方
     * 已经缩小了（`s < 1`），视觉上沿还带着 `pivotY·(1−s)` 的补偿，落点会偏。
     * 所以先粗滚，再**实测**一次视觉上沿与目标位的差，按差值补滚一次（幂等、最多两趟）。
     */
    private fun alignCurrentCardToRest() {
        tabScroll.scrollTo(0, desiredScroll())
        updateTransforms()
        val k = childOfTab(currentIndex)
        if (k < 0) return
        val child = tabList.getChildAt(k) ?: return
        val want = shrinkLineY() + dp(2f)          // 当前页那张卡的静止上沿
        repeat(2) {
            val d = cardVisualTop(child) - want
            if (kotlin.math.abs(d) < 1f) return
            val maxS = (tabList.height - tabScroll.height).coerceAtLeast(0)
            val ny = (tabScroll.scrollY + d.roundToInt()).coerceIn(0, maxS)
            if (ny == tabScroll.scrollY) return   // 滚不动了，别死循环
            tabScroll.scrollTo(0, ny)
            updateTransforms()
        }
    }

    /**
     * 卡片列表是否已经真正走过一次 layout。
     *
     * `refreshTabs()` 里刚 addView 的那些卡，在下一帧 layout 之前 width/height 都是 0；
     * 这时候去读 `cardTopY()` / `cardState()` 得到的落点是错的，动画会从莫名其妙的位置起飞。
     */
    private fun listLaidOut(): Boolean {
        var any = false
        for (i in 0 until tabList.childCount) {
            val c = tabList.getChildAt(i)
            if (c === hero) continue
            any = true
            if (c.width <= 0 || c.height <= 0) return false
        }
        return any
    }

    /**
     * 某张卡片当前的状态，**直接就是卡片自己的参数**（新模型的好处：不需要任何换算）。
     *
     * 返回 `[k, hStart, hEnd, theta, 相对英雄视图顶边的位移]`，英雄视图拿这份数据就能
     * 从**逐像素重合**的那一帧起飞 —— 以前这里要把 `scaleX` 换算成 `k = cardW·s/pageW`
     * 和 `hEnd = cardH·s`，和真实画出来的状态永远差一层（缩小态尤其明显）。
     * 位移基准用 heroTopY()（= 0），和英雄视图的取景坐标系保持一致。
     */
    private fun cardState(k: Int): FloatArray? {
        if (k !in 0 until tabList.childCount || pageW() <= 0) return null
        val c = tabList.getChildAt(k)
        val tv = c as? TrapezoidView ?: return null
        val top = cardVisualTop(c)
        // 卡片的位图宽 cardW、英雄视图的位图宽 pageW —— k 要按两者宽度比换算
        return floatArrayOf(
            tv.k * cardW / pageW().toFloat(),   // 0 k
            tv.hStart,                          // 1 hStart（推远量）
            tv.hEnd,                            // 2 hEnd
            tv.theta,                           // 3 theta
            (top - heroTopY()),                 // 4 相对英雄视图顶边的位移
            tv.hRef                             // 5 相机参考高度
        )
    }

    private fun applyHero(st: FloatArray) {
        var changed = false
        if (hero.k != st[0]) {
            hero.k = st[0]
            changed = true
        }
        if (hero.hStart != st[1]) {
            hero.hStart = st[1]
            changed = true
        }
        if (hero.hEnd != st[2]) {
            hero.hEnd = st[2]
            changed = true
        }
        if (hero.theta != st[3]) {
            hero.theta = st[3]
            changed = true
        }
        // 英雄视图的屏幕位置 = tabScroll.top + (top - scrollY) + translationY。
        // 这里用 heroAsPage() 算好的 heroBaseY（= top），不读 hero.top ——
        // 列表滚动过时 hero.top 要等下一帧 layout 才更新，读它会晚一帧、低 scrollY 像素。
        // 减去 tabList.translationY：甩动留下的位移会让整个列表（连同英雄视图）一起偏移，
        // 这里扣掉它，英雄视图才会精确落在「卡片所在的那一屏位置」上。
        val tyHero = st[4] - tabScroll.top + tabScroll.scrollY - heroBaseY - tabList.translationY
        if (hero.translationY != tyHero) {
            hero.translationY = tyHero
            changed = true
        }
        // 标题条吐出进度：0 = 还没有，1 = 整条盖满地址栏
        val frac = if (st.size > 5) st[5].coerceIn(0f, 1f) else 0f
        if (hero.overlayFrac != frac) {
            hero.overlayFrac = frac
            changed = true
        }
        // 遮光强度（st[6]）不再由动画传进来：统一由 `refreshShadows()` 按遮挡关系算
        // 相机参考高度：0 = 跟着 hEnd 走（整页 → 卡片那种"整张在变"的动画）
        val hr = if (st.size > 7) st[7].coerceAtLeast(0f) else 0f
        if (hero.hRefBase != hr) {
            hero.hRefBase = hr
            changed = true
        }
        if (changed) hero.invalidate()
    }

    /**
     * 取「屏幕上真实显示的那一帧」当起飞帧。
     *
     * 为什么不用 `WebView.draw(Canvas)` / `View.draw(Canvas)`：
     * 实测 WebView 的软件绘制在某些帧里会把页面内容画偏，
     * 拿它当整页动画的第一帧，画面里就会闪出一张位置不对的截图。
     * PixelCopy 读的是 GPU 合成后、真正显示出来的像素，和屏幕逐像素一致。
     *
     * 代价是异步（约一帧），所以 openTabs 改成了「先取帧，再开动画」。
     */
    private fun grabScreenFrame(onDone: (Bitmap?) -> Unit) {
        val w = pageW()
        val h = heroFullH()
        if (w <= 0 || h <= 0) {
            onDone(null)
            return
        }
        val out = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            onDone(null)
            return
        }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { b: Bitmap? ->
            if (done.compareAndSet(false, true)) {
                if (b == null && !out.isRecycled) out.recycle()
                onDone(b)
            }
        }
        try {
            val loc = IntArray(2)
            rootView.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            android.view.PixelCopy.request(
                window, rect, out,
                { r -> finish(if (r == android.view.PixelCopy.SUCCESS) out else null) },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        } catch (e: Throwable) {
            finish(null)
        }
    }

    /**
     * 用「顶栏 + 这一页已有的截图」拼一张整页图（退场起飞帧）。
     *
     * 为什么不用 `captureComposite()`：那个会把 WebView 软件绘制一遍（实测 20~100ms，
     * 复杂页面更久）。点卡片时这段就压在手势和动画之间 —— 表现就是「点一下，快一秒动画才起来」。
     * 这一页本来就有 `thumb`（卡片上显示的就是它），把顶栏画上去、再把 thumb 按 1:1 贴进网页区，
     * 只要几毫秒；而且动画里的画面和卡片缩略图必然一致，不会出现「卡片一张图、动画另一张图」。
     */
    private fun composeFrame(tab: Tab): Bitmap? {
        val thumb = tab.thumb?.takeIf { !it.isRecycled } ?: return null
        return composeFrame(thumb)
    }

    /** 用**页面区缩略图**拼「顶栏 + 页面」的整页图（几何按当前栏状态算，所以缓存可以只存页面区） */
    private fun composeFrame(thumb: Bitmap): Bitmap? {
        val w = pageW()
        val h = heroFullH()
        if (w <= 0 || h <= 0 || thumb.width <= 0) return null
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(if (privateMode) Color.parseColor("#3A3A3A") else Color.parseColor("#F9F9F9"))
            val save = c.save()
            topBar.draw(c)                  // 顶栏是普通 View，不碰 WebView
            c.restoreToCount(save)
            val top = webTopVisual()          // 可见上沿（= 顶栏下沿），不是容器的布局 top
            val dstH = (thumb.height.toLong() * w / thumb.width).toInt()
            val dst = Rect(0, top, w, (top + dstH).coerceAtMost(h))
            if (dst.bottom > dst.top) {
                c.drawBitmap(thumb, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            out
        } catch (e: Throwable) {
            null
        }
    }

    private fun openTabs() {
        // 极端情况下某个动画没走完会把标记留在这，别让它把整个标签视图锁死
        if (tabAnimating && hero.visibility != View.VISIBLE) tabAnimating = false
        if (tabAnimating) return
        // 橡皮筋还没弹回去就点了任务视图按钮：先把位移归零，否则整摞会带着偏移出现
        if (stackShift != 0f) {
            shiftAnim?.cancel()
            shiftExcess = 0f
            setStackShift(0f)
        }
        tabAnimating = true               // 取帧是异步的，期间锁住，避免连点
        // 任务视图的背景是深黑的 → 状态栏换白色图标（退出时按主题还原）
        setStatusBarIconsWhite(true)
        // 地址栏若仍持焦，建议浮层会藏在标签视图下面，退场后突然冒出来
        releaseFieldFocus()
        hideKeyboard()
        hideSuggestions()
        dismissFieldMenu()
        // 模糊背景不再在这里算：这一刻还没有「整屏帧」，只能靠软件绘制整棵树（WebView 不可信、还慢）。
        // 改成等下面 grabScreenFrame 拍到帧之后再算 —— 那一帧就是用户看到的画面，又准又快。
        // 进任务视图这一刻必须用「展开态」的顶栏：
        //  ① 卡片标题条高度 cardHeaderH 是按 44dp 算的（首次打开后就固定了）；
        //  ② 起飞帧是截屏，如果栏还收着，动画最后一帧标题条会「长出来」。
        // 和 iOS 一样：点任务视图先把栏复原，隔一帧再截。
        val expandFirst = compact != 0f
        if (expandFirst) snapBarsExpanded()
        val take = {
            grabScreenFrame { bmp ->
                if (overlayTabs.visibility == View.VISIBLE) {
                    tabAnimating = false
                    return@grabScreenFrame
                }
                val old = heroComposite
                if (hero.visibility != View.VISIBLE && old != null && old !== bmp && !old.isRecycled) old.recycle()
                heroComposite = bmp
                heroCompositeId = currentTab().id
                updateThumbFromFrame(bmp)      // 卡片缩略图和起飞帧用同一份像素
                tabAnimating = false
                // 起飞帧顺便当模糊源：后台算正式版（1/4 × 16），算完替换。
                // 在它之前 startTabsEnter() 会先同步铺一张「快速版」，任何一帧都不会出现"背景还没模糊"。
                prepareBlur(bmp)
                startTabsEnter()
            }
        }
        if (expandFirst) nextFrame(2) { take() } else take()
    }

    /**
     * 用起飞帧里的「网页那一块」更新当前标签的缩略图。
     *
     * 以前卡片缩略图只在页面加载完时拍一次，用户在页面里划走之后再进任务视图，
     * 卡片上还是「刚打开时」那一屏。而这一帧本来就已经截好了（起飞帧），
     * 直接裁出网页区域当缩略图 —— 卡片和动画里看到的就是同一份像素，永远不会对不上，
     * 也顺带解决了「卡片截图偶尔空白」（PixelCopy 不会画成透明）。
     */
    private fun updateThumbFromFrame(frame: Bitmap?) {
        if (frame == null || frame.isRecycled) return
        // 裁的是屏幕上**看得见**的那一块：上沿用可见上沿（= 顶栏下沿），
        // 高度用可见高度。用容器的高度会多裁「被底栏盖住 + 出屏」的那一截，直接越界。
        val top = webTopVisual()
        val h = minOf(webVisibleH(), frame.height - top)
        if (top < 0 || h <= 0) return
        val thumb = try {
            Bitmap.createBitmap(frame, 0, top, frame.width, h)
        } catch (e: Throwable) {
            return
        }
        val tab = currentTab()
        val old = tab.thumb
        if (old != null && old !== thumb && old !== hero.bitmap && !old.isRecycled) old.recycle()
        tab.thumb = thumb
        tab.thumbVer++
        refreshStartPageCache(tab)          // 起始页：这一帧就是最准的起始页画面，直接存下来
    }

    /** 取出起飞帧之后的真正进场：摆好图层 → 建卡片 → 一整页 1:1 就位 → 开动画 */
    /**
     * 让模糊图和屏幕 1:1 对齐。
     *
     * 模糊图是 1/4 分辨率的整屏截图，原来用 `centerCrop` 铺满：一旦把 `tabChrome` 裁到
     * 「网页底」那一条，centerCrop 会按高度居中裁切，图像整体往上偏约 60px ——
     * 淡入过程中看起来就是「那张截图往下闪了一下」。
     * 改成 MATRIX + 缩放（1/4 图放大 4 倍），裁到任意高度都和页面严丝合缝、没有位移。
     */
    private fun alignBlurMatrix() {
        val d = tabBgBlur.drawable ?: return
        val iw = d.intrinsicWidth
        val w = rootView.width
        if (iw <= 0 || w <= 0) return
        val k = w.toFloat() / iw
        val m = Matrix()
        m.setScale(k, k)
        tabBgBlur.scaleType = ImageView.ScaleType.MATRIX
        tabBgBlur.imageMatrix = m
    }

    /** 模糊层只铺到「网页可见底」，把底栏那一条让给真正的浏览页底栏 */
    private fun applyChromeHeight() {
        val h = webBottomVisual()
        if (h <= 0) return
        val lp = tabChrome.layoutParams
        if (lp.height != h) {
            lp.height = h
            tabChrome.layoutParams = lp
        }
    }

    private fun startTabsEnter() {
        // 新一轮进场：作废可能还挂着的「退场看门狗」（否则它会把刚打开的浮层收掉）
        exitSeq++
        // 模糊层只铺到「网页底」为止，**不要盖住底栏那一条**：
        // 任务视图的底栏(tabBottomWrap)淡出时，底下就是真正的浏览页底栏，
        // 两者交叉淡入淡出。以前模糊层盖满全屏，底栏那条显示的是「模糊图里的底栏」，
        // 于是任务视图底栏淡完 → 空一条灰带 → 收尾时真底栏啪一下出现。
        applyChromeHeight()
        // 背景**必须**在浮层显示之前就是模糊的。
        // 正式版（1/4 × 16）在后台线程上，冷启动第一遍要一百多毫秒，以前不等它 ——
        // 于是「长方形已经拉成倒梯形」的那半截里，背景仍是 #80D9D9DE 蒙版底下的清晰页面，
        // 到动画中途才"啪"地糊上去（第一次进任务视图特别明显）。
        // 这里同步铺一张「快速版」（1/8 × 8，等效屏幕 64px，和正式版同样糊），
        // 正式版算完再替换 —— 两版糊的程度一样，换上去看不出跳变。
        ensureBackdrop(heroComposite)
        alignBlurMatrix()
        overlayTabs.visibility = View.VISIBLE
        tabChrome.alpha = 0f
        tabBottomWrap.alpha = 0f
        hero.visibility = View.GONE
        tabClosing = false
        refreshTabs()
        for (k in 0 until tabList.childCount) tabList.getChildAt(k).alpha = 0f
        afterLayout {
            // 当前页那张卡必须精确落在「上 1/9 分界线」下方 2dp：
            // 上一轮滑到最下面、它被滚到线上方缩过的话，光 scrollTo(desiredScroll) 对不准
            alignCurrentCardToRest()
            // updateTransforms() 会把每张卡的 alpha 恢复成 1（那是给「滚动/拖动结束」用的）。
            // 进场这一帧卡片必须是隐藏的，否则会话里会先闪出一张卡片截图。
            for (k in 0 until tabList.childCount) {
                val c = tabList.getChildAt(k)
                if (c !== hero) c.alpha = 0f
            }
            // 英雄视图是刚 addView 进列表的，这一帧它还没有真正的布局（0×0），
            // 先把它摆成「整页 1:1」——和屏幕上那一页逐像素重合，看不出替换——
            // 等它完成一次真正的布局再开始淡入 + 飞入。
            startShadowTick()
            val bmp0 = heroComposite ?: tabs.getOrNull(currentIndex)?.thumb
            if (bmp0 != null && heroAsPage(bmp0, childOfTab(currentIndex).coerceAtLeast(0))) {
                applyHero(floatArrayOf(1f, 0f, heroFullH().toFloat(), 0f, 0f, 0f))
                nextFrame(1) {
                    if (overlayTabs.visibility == View.VISIBLE) {
                        fadeTabsChrome(1f, 240)
                        playEnter()
                    }
                }
            } else {
                fadeTabsChrome(1f, 240)
                playEnter()
            }
        }
    }

    private fun heroAsPage(bmp: Bitmap?, k: Int): Boolean {
        if (pageW() <= 0 || heroFullH() <= 0) return false
        if (hero.parent !== tabList) {
            (hero.parent as? ViewGroup)?.removeView(hero)
            tabList.addView(hero)                    // 末尾：卡片下标 0..n-1 保持不变
        }
        // 列表里「英雄视图之前」那一摞卡片排完之后的位置。
        //
        // 这里**不能**用 `c.height` 去累加：列表刚被 refreshTabs() 重建、还没走过一次
        // layout 时，每张卡的 height 都是 0，累加结果少了一整张卡（实测少 955px）。
        // 负的 topMargin 于是少让出这么多，英雄视图被推到屏幕下半截去画 ——
        // 表现就是「动画突然卡在下半屏播放」。卡片高度/间距本来就是 refreshTabs()
        // 用同一套常量算出来的（第 1 张 8dp，其余每张 cardStep），这里直接用常量。
        val cardsBefore = tabList.childCount - (if (tabList.indexOfChild(hero) >= 0) 1 else 0)
        var y = tabList.paddingTop
        if (cardsBefore > 0) {
            y += dp(8f) + cardRenderH + (cardsBefore - 1).coerceAtLeast(0) * cardStep
        }
        // 「整屏取景」在列表内容坐标系里的上沿（考虑当前滚动量）
        val contentTop = heroTopY() - tabScroll.top + tabScroll.scrollY
        heroBaseY = contentTop.toFloat()
        val lp = LinearLayout.LayoutParams(pageW(), heroFullH())
        lp.setMargins(webContainer.left - tabScroll.left, contentTop - y, 0, 0)
        hero.layoutParams = lp
        // 立刻按这套参数把 hero 的 frame 也摆好。
        // 列表的 layout 要等下一帧才会跑，而 applyHero() 这一帧就把 translationY 算好了、
        // 也可能这一帧就画 —— 那时 hero.top 还是上一次的旧值（比如上次停在 scrollY=800 的位置），
        // 于是会闪出一帧「位置偏下的截图」。这里直接 layout() 一次，父容器随后再布局结果也一样。
        hero.layout(
            lp.leftMargin,
            contentTop,
            lp.leftMargin + pageW(),
            contentTop + heroFullH()
        )
        hero.bitmap = bmp
        heroStackIndex = k.coerceAtLeast(0)
        hero.overlayFrac = 0f
        hero.hRefBase = 0f          // 默认"相机跟着 hEnd"，需要的话 applyHero 会改
        // 遮光不在这里定死了：由 `refreshShadows()` 每帧按"挡光那张卡在哪"算
        hero.shadowDepth = 0f
        hero.shadowFrac = 1f
        setHeroOverlay(tabList.getChildAt(k) as? TrapezoidView)
        hero.visibility = View.VISIBLE
        hero.alpha = 1f
        hero.translationX = 0f
        hero.elevation = HERO_Z
        for (i in 0 until tabList.childCount) {
            val c = tabList.getChildAt(i)
            if (c === hero) continue
            c.elevation = if (i < k) CARD_BACK_Z else CARD_FRONT_Z
        }
        return true
    }

    /**
     * 给英雄视图准备「卡片标题条」（含居中标题和左上角的叉）。
     *
     * 取的是那张卡片平面图的**顶部 cardHeaderH 行**，拷一份出来（不动缓存里的位图）。
     * 进场时它从 0 长到满、盖住地址栏；退场时反过来缩回去 —— 两端都和卡片自己的标题条对得上。
     */
    private fun setHeroOverlay(card: TrapezoidView?) {
        val old = hero.overlay
        hero.overlay = null
        if (old != null && !old.isRecycled) old.recycle()
        if (card == null || card === hero) return      // 不要把英雄视图自己当成卡片
        val cb = card.bitmap ?: return
        if (cardHeaderH <= 0 || cardHeaderH >= cb.height || cb.isRecycled) return
        hero.overlay = try {
            Bitmap.createBitmap(cb, 0, 0, cb.width, cardHeaderH)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 进场：当前页被挤成倒梯形，落到整摞最上面（屏幕内最后面）那一格；
     * 其余卡片压在它前面，从屏幕下方以倒梯形的样子飞入，先快后慢地滑进各自的槽位。
     * 只用一张「整页（含地址栏）」位图，单层做形变 —— 双层交叉淡化会在中途闪一下。
     */
    /**
     * 其余卡片：**一边起立、一边往当前卡片靠**，和 hero 同一段动画、同一条曲线、不分先后。
     *
     * 没进任务视图时，整摞标签是一叠**自上而下平铺的页面**（没有 3D）：每张卡就是一整页的长方形，
     * 相邻两张的间距 = **一整张卡片的高度**（`cardH`）—— 平铺时绝不重叠
     * （第 1 张的下沿永远在第 2 张的上沿之上，依此类推）。
     *
     * 点「任务视图」时，每张卡片绕**自己的上沿**转起来（θ: 0 → tiltNow，长方形变倒梯形），
     * 同时把间距从「一整张卡」压到 `cardStep`（一屏 4 个）—— 否则立起来之后相邻卡片
     * 还是隔着一整页，屏幕里根本放不下几张。
     *
     * 上沿是旋转轴、位置不动，所以"还差多少没靠拢"用 `translationY` 表达：
     * 起点 `dy = (k − skip) × (cardH − cardStep)`，终点 = 它静止时的 `ty`。
     * 当前页**之前**的卡片是负值（平铺状态下它们一整页一整页地在上方，得往下靠）。
     *
     * **缩放也要一起从 1 补到静止值**：当前页上方那几张卡在静止态是被「越过 1/9 线」那套
     * 缩小过的（实测 s 只有 0.6 / 0.68），而当前页下方那几张是 1.0。如果动画期间不把
     * 它们统一成 1.0、只在最后啪一下缩小，那几张看起来就会"角度/透视和别的不一样"
     * （它们其实是同一个 θ，只是被 View 的 scale 又缩了一次）。现在三者都从 1.0 平滑过去。
     */
    private fun standUpCards(skip: Int) {
        val cards = mutableListOf<TrapezoidView>()
        val fromDy = mutableListOf<Float>()
        val toDy = mutableListOf<Float>()
        val toS = mutableListOf<Float>()
        val step = cardStep.coerceAtLeast(1)
        val gapFlat = (cardH - step).toFloat()      // 平铺间距 − 3D 间距：每张要收掉这么多
        for (k in 0 until tabList.childCount) {
            val c = tabList.getChildAt(k)
            // hero 也是 TrapezoidView，而且已经 addView 到列表末尾（它在演「整页 → 卡片」）：
            // 一定要排掉，否则这里会把它的 theta / translationY 一起改掉，动画就花了。
            if (k == skip || c === hero || c !is TrapezoidView) continue
            c.animate().cancel()        // 万一上一轮还有别的动画挂着
            // 静止态的值先记下来（此时整摞已经摆好：k/hEnd 是"越过 1/9 线"缩小后的值）
            toS += cardScale(c)
            toDy += c.translationY
            // 位移方向 = **由远拉近**：整摞的"远处"就是屏幕上方（透视里越往上越远）。
            // 当前页**之上**那几张 k−skip < 0 → 起点在更上方（更远），往下靠过来；
            // 之下那几张 → 起点在下方，往上靠过来。两者都是"往自己在的那一格收"，
            // 而旋转方向/角度/缩放曲线对每张卡都完全相同。
            val dy = (k - skip) * gapFlat
            // 起点：平铺的长方形 —— 全尺寸、无位移、θ = 0
            applyCardScale(c, 1f)
            c.translationY = dy
            c.theta = 0f
            c.alpha = 1f                // 不渐显：卡片是实心的，进场时直接就"在"
            c.invalidate()
            cards += c
            fromDy += dy
        }
        if (cards.isEmpty()) return
        standUpAnim?.cancel()
        standUpAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ENTER_MS         // 与 hero 那张完全同步（同起点、同终点）
            interpolator = apple
            addUpdateListener {
                val p = it.animatedValue as Float
                val th = tiltNow * p
                for (i in cards.indices) {
                    val c = cards[i]
                    c.theta = th
                    c.translationY = fromDy[i] + (toDy[i] - fromDy[i]) * p   // 边转边靠拢
                    val s = 1f + (toS[i] - 1f) * p                          // 尺寸也一起过渡
                    applyCardScale(c, s)
                    c.alpha = 1f
                    c.invalidate()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    for (i in cards.indices) {
                        val c = cards[i]
                        c.theta = tiltNow
                        c.translationY = toDy[i]
                        applyCardScale(c, toS[i])
                        c.alpha = 1f
                        c.invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun playEnter(depth: Int = 0) {
        // 当前页那张卡在哪一格要用 childOfTab 查：换位之后它不一定还在第 0 格
        // （第 0 格只是"整摞的最上面"，不再专属于当前页）
        val front = childOfTab(currentIndex).coerceAtLeast(0)
        if (tabList.childCount == 0) {
            endHero()
            return
        }
        // 列表刚重建过（比如截图回填）时卡片还是 0×0，等它布局完再起飞（最多等 3 帧，不能卡死）
        if (!listLaidOut()) {
            if (depth < 3) {
                tabList.post { if (overlayTabs.visibility == View.VISIBLE && !tabAnimating) playEnter(depth + 1) }
            } else {
                for (k in 0 until tabList.childCount) tabList.getChildAt(k).alpha = 1f
                endHero()
            }
            return
        }
        val card = tabList.getChildAt(front)
        val st = cardState(front)
        val bmp = heroComposite ?: tabs.getOrNull(currentIndex)?.thumb
        if (st == null || bmp == null || heroFullH() <= 0) {
            for (k in 0 until tabList.childCount) tabList.getChildAt(k).alpha = 1f
            endHero()
            return
        }
        tabAnimating = true
        card.alpha = 0f
        if (!heroAsPage(bmp, front)) {
            for (k in 0 until tabList.childCount) tabList.getChildAt(k).alpha = 1f
            endHero()
            return
        }
        applyHero(floatArrayOf(1f, 0f, heroFullH().toFloat(), 0f, 0f, 0f))
        ValueAnimator.ofFloat(0f, 1f).apply {
            // 当前页：整页 → 倒梯形（其余卡片在 standUpCards() 里同步原地起立）
            duration = ENTER_MS
            interpolator = apple
            val animId = beginHeroAnim(this)
            addUpdateListener {
                val p = it.animatedValue as Float
                applyHero(
                    floatArrayOf(
                        1f + (st[0] - 1f) * p,
                        st[1] * p,
                        heroFullH() + (st[2] - heroFullH()) * p,
                        st[3] * p,
                        st[4] * p,
                        p,                      // 标题条随进度从卡片上沿吐出来
                        // 遮光 0 → 1，而且用 p² 让它在后半段才压下来：
                        // 刚起步时这张还是一整页长方形、前面没有卡压着，那一段绝不能带阴影；
                        // 等它立起来、后一张卡也快落位了，阴影才明显地铺上去。
                        p * p,
                        // 相机参考高度：整页时 = 整页高（"相机跟着这张图走"），
                        // 落到卡片时 = 卡片的静止高度 —— 终点必须和目标卡片**逐像素一致**
                        heroFullH() + (st[5] - heroFullH()) * p
                    )
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!heroAnimCurrent(animId)) return
                    card.alpha = 1f
                    endHero()
                }
                override fun onAnimationCancel(animation: Animator) {
                    if (!heroAnimCurrent(animId)) return
                    card.alpha = 1f
                    endHero()
                }
            })
            start()
        }
        standUpCards(front)

    }

    /**
     * 起一个新的英雄动画，并**停掉上一个**。
     *
     * 连点「＋」时走的是 `endHero()` + 新的一轮动画。以前旧动画没法取消：
     * 它跑到自己的 `onAnimationEnd` 会调 `finishDismiss()` —— 把 overlay 关掉、把 hero 的位图置空。
     * 新一轮动画于是画在一张已经隐藏的浮层上（日志实测 `ov=8 / bmp=false`），
     * 表现就是「任务视图整个消失 → 空白一下 → 浏览页底栏才出现」。
     * 现在用代号作废旧动画：它继续跑完也无害，所有收尾回调都会被丢弃。
     */
    private fun beginHeroAnim(a: ValueAnimator): Int {
        val id = ++heroAnimId
        val old = heroAnim
        heroAnim = a
        if (old != null && old !== a && old.isRunning) old.cancel()
        return id
    }

    /** 这个动画是否还是「当前那一个」；不是就说明已被新一轮取代，收尾动作必须丢弃 */
    private fun heroAnimCurrent(id: Int): Boolean {
        if (id != heroAnimId) return false
        heroAnim = null
        return true
    }

    /** 英雄视图的收尾：卡片已经完整地待在下面，直接撤掉即可，不做交叉淡入（否则会闪） */
    private fun endHero() {
        // 先作废可能在跑的动画，免得它稍后的收尾又把浮层/位图动一遍
        standUpAnim?.cancel()
        standUpAnim = null
        heroAnimId++
        heroAnim?.cancel()
        heroAnim = null
        val ov = hero.overlay
        hero.overlay = null
        hero.overlayFrac = 0f
        if (ov != null && !ov.isRecycled) ov.recycle()
        hero.visibility = View.GONE
        hero.alpha = 1f
        hero.shadowFrac = 1f            // 下次复用英雄视图时从满遮光起步，别继承退场时的 0
        hero.bitmap = null
        hero.elevation = CARD_BACK_Z
        detachHero()
        stopShadowTick()
        tabAnimating = false
        // 中途被「＋」掐断时浮层还开着：把模糊层 / 底栏 / 卡片层恢复成「静止态」，
        // 否则会停在动画的半透明中途（看起来就像底栏又空了一截）。
        if (overlayTabs.visibility == View.VISIBLE) {
            tabChrome.alpha = 1f
            tabBottomWrap.alpha = 1f
        }
    }

    /** 动画收尾后把英雄视图从卡片列表里摘掉：留着会让列表多出一个子视图，
     *  拖拽换位时的下标范围就会算错（能拖到「英雄视图」那一格去）。 */
    private fun detachHero() {
        (hero.parent as? ViewGroup)?.removeView(hero)
    }

    /**
     * 其余卡片：从屏幕下方飞入，先快后慢（正弦缓动）。
     * 它们排在英雄视图之后（图层更高），所以是「飞上来压在当前页前面」，
     * 而不是从当前页身上穿过去 —— 顺序上也让当前页先折好再飞入。
     */
    /** 退场：点中的倒梯形被拉伸成整页长方形，最后一帧正好覆盖整个浏览区 */
    private fun dismissTabs(selected: Int) {
        if (overlayTabs.visibility != View.VISIBLE || tabAnimating) return
        val idx = if (selected in tabs.indices) selected else currentIndex
        if (idx !in tabs.indices) {
            fadeOutTabs()
            return
        }
        val k = childOfTab(idx)
        if (k < 0) {
            selectTab(idx)
            fadeOutTabs()
            return
        }
        tabAnimating = true
        // 只有「截图确实是这一页、而且这一页正是当前页」时才直接起飞。
        // 关过标签页之后 currentIndex 会变，缓存的合成图就可能是被关掉那一页 —— 会串页。
        // 进场时取的就是「当前这一页」的画面；只有点的是别的标签页时才需要重拍
        val cached = heroComposite
        val fresh = cached != null && !cached.isRecycled &&
                heroCompositeId == tabs[idx].id && idx == currentIndex
        if (fresh) {
            startExit(k, heroComposite)
        } else {
            // 先把网页切到这一页；起飞帧优先用「顶栏 + 该页已有的截图」现拼（毫秒级），
            // 只有这一页还没有截图时才退回软件绘制整棵树。
            selectTab(idx)
            // selectTab 里的 animateBars(0f) 是带 220ms 动画的，截帧必须等它「真的到位」，
            // 否则截到的是中间高度 → 起飞帧比卡片矮一截 → 动画开头会往下一抽。
            snapBarsExpanded()
            nextFrame(2) {
                val t = tabs.getOrNull(idx)
                startExit(k, (if (t != null) composeFrame(t) else null) ?: captureComposite())
            }
        }
    }

    /** 退场动画本体：src 是「地址栏 + 该页网页」的整页合成图 */
    private fun startExit(k: Int, src: Bitmap?, depth: Int = 0) {
        if (overlayTabs.visibility != View.VISIBLE || src == null || heroFullH() <= 0) {
            fadeOutTabs()
            return
        }
        if (!listLaidOut() && depth < 3) {
            tabList.post { startExit(k, src, depth + 1) }
            return
        }
        val st = cardState(k)
        if (st == null) {
            fadeOutTabs()
            return
        }
        // 补位动画可能还在跑：英雄动画要接管卡片位置，先把它停掉（否则两个都在写 translationY）
        reflowAnim?.cancel()
        reflowAnim = null
        if (src !== heroComposite) {
            heroComposite?.recycle()
            heroComposite = src
            heroCompositeId = currentTab().id
        }
        val kScale = pageW().toFloat() / src.width.toFloat()
        if (!heroAsPage(src, k)) {
            fadeOutTabs()
            return
        }
        // 起点必须和那张卡片**逐像素一致**：包括遮光（1 = 画满）和相机参考高度（st[5]）
        applyHero(floatArrayOf(st[0] * kScale, st[1], st[2], st[3], st[4], 1f, 1f, st[5]))
        val kEnd = kScale
        backdropFading = false
        startShadowTick()

        // 让路：压在它前面的卡片向下飞出屏幕，压在它后面的向上飞出。
        //
        // **英雄视图保持在 HERO_Z（它自己那一层）**，不往上提：它要等前面的卡片让开才慢慢露出来，
        // 这就是「抽出那块木板」的手感。曾经试过退场时把它提到 CARD_FRONT_Z+1 直接盖住整摞，
        // 结果是「旁边的卡片还没走完、和当前卡片还没分开，当前卡就啪地一下跳到了最上层」——
        // 层叠关系比飞行时间重要，不要动这里。
        //
        // 时间预算（"卡片还没飞完，页面已经铺好"就是超了这里）：
        //   英雄视图 70ms 开始、420ms 长度 → 490ms 结束。
        //   卡片这边**按张数动态定速**：张数越多，单张越快、错峰越密，
        //   全部飞完的时间恒定控制在 EXIT_CARDS_BUDGET_MS(300ms) 以内 —— 给页面留 190ms 余量。
        //   余量必须这么大：设备帧率低的时候，"页面铺好"之前的最后一帧可能就落在余量里，
        //   余量小的话那一帧正好拍到一张还没飞完的卡片（看着就是"总差一帧"）。
        //   视口外的卡片直接 alpha=0 撤掉（本来就看不见），只有真看得见的那几张才参与计时。
        val h = rootView.height.toFloat()
        val vpTop = tabScroll.top.toFloat() - dp(8f)
        val vpBottom = (tabScroll.top + tabScroll.height).toFloat() + dp(8f)
        fun onScreen(v: View): Boolean {
            // 用**视觉**上沿 + 缩放后的高度：缩小状态的卡片外框比画出来的大一圈，
            // 用外框判断会把"其实在视口外"的算成要飞。
            val vis = cardVisualTop(v)
            return vis + cardVisualH(v) > vpTop && vis < vpBottom
        }
        var movers = 0
        for (c in 0 until tabList.childCount) {
            val v = tabList.getChildAt(c)
            if (v === hero || c == k) continue
            if (onScreen(v)) movers++
        }
        val budget = EXIT_CARDS_BUDGET_MS
        val stepMs = (budget * 0.4f / movers.coerceAtLeast(1)).toLong().coerceIn(6L, 28L)
        val flyMs = (budget - stepMs * (movers - 1).coerceAtLeast(0))
            .coerceIn(300L, 600L)
        for (c in 0 until tabList.childCount) {
            val v = tabList.getChildAt(c)
            if (v === hero) continue
            v.animate().cancel()
            // 飞出去的**距离**必须按「视觉上沿」算，不能按布局上沿：
            // 缩小状态的卡片（s=0.6）视觉上沿比布局上沿低 pivotY·(1−s) ≈ 277px，
            // 按布局算就会少飞这 277px —— 动画跑完了卡片却还停在屏幕里（就是"残卡"）。
            val visual = cardVisualTop(v)
            val visualH = cardVisualH(v)
            val idx = (if (c > k) c - k else k - c) - 1
            // 延迟也要夹在预算里：idx 是「离当前卡片的格数」，标签多时它会远大于 movers，
            // 不夹的话远处那张能拖到 600ms 以后 —— 又变成"页面铺好了卡片还在飞"。
            val dly = (stepMs * idx.coerceAtLeast(0)).coerceAtMost((budget - flyMs).coerceAtLeast(0L))
            // 被点中的那张卡：英雄视图已经严丝合缝地替上，**直接撤掉**。
            // （这一条不能少：现在它也会走到下面的"飞出"逻辑里，会在英雄视图边缘露出来一闪。）
            if (c == k) { v.alpha = 0f; continue }
            if (!onScreen(v)) { v.alpha = 0f; continue }      // 视口外：不用飞，直接撤
            // **统一**：所有非当前卡片走**同一套**运动规律 —— 同一个行程、同一个曲线、
            // 同一个错峰规则，唯一的区别是各自的起始大小不同（小的那张就小着飞）。
            //
            // 这里**不能**像以前那样先 `applyCardScale(v, 1f)`：那会让缩小过的卡
            // 在退场第一帧"啪"地涨回原尺寸 —— 大卡和小卡的动作就不一样了。
            // "长大"这件事交给下面 flatCards 那段（和放平同一段动画，也就是进场的镜像）。
            val from = v.translationY
            val dist = h + cardRenderH + dp(40f)             // 所有卡片同一个行程
            v.animate()
                .translationY(if (c > k) from + dist else from - dist)
                .setDuration(flyMs).setStartDelay(dly)
                .setInterpolator(accel).start()
        }
        // 退场时**所有卡片一起放平**（θ: tiltNow → 0），和英雄视图同一条曲线、同一段时长。
        // 否则只有被点的那张在"站起来"，旁边几张还是斜着飞出去 —— 看起来就是"旋转方向不一样"。
        val flatCards = ArrayList<TrapezoidView>()
        val flatFromS = ArrayList<Float>()
        for (c in 0 until tabList.childCount) {
            val v = tabList.getChildAt(c)
            if (v === hero || v !is TrapezoidView || v.alpha <= 0.01f) continue
            if (v.theta <= 0.5f) continue          // 本来就是平的就别再动
            flatCards.add(v)
            // 当前视觉大小（0.6 / 1.0 …）：进场时它是"从 1.0 缩到这个值"，
            // 退场必须**原路长回去** —— 否则小卡不是"放平飞走"，而是"先弹回原大小再飞"。
            val sNow = if (cardRenderH > 0) cardVisualH(v) / cardRenderH else 1f
            flatFromS.add(sNow.coerceIn(0.05f, 1.2f))
        }
        if (flatCards.isNotEmpty()) {
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = EXIT_MS
                startDelay = EXIT_DELAY_MS             // 与英雄视图同一节奏
                interpolator = apple
                addUpdateListener { a ->
                    val q = a.animatedValue as Float
                    val th = tiltNow * q
                    for (i in flatCards.indices) {
                        val c = flatCards[i]
                        if (c.theta != th) { c.theta = th; c.invalidate() }
                        applyCardScale(c, flatFromS[i] + (1f - flatFromS[i]) * (1f - q))
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        for (c in flatCards) {
                            c.theta = 0f
                            applyCardScale(c, 1f)
                            c.invalidate()
                        }
                    }
                })
                start()
            }
        }
        ValueAnimator.ofFloat(1f, 0f).apply {
            // 先慢后快：等前面的卡片让开了再舒展开（物理上就是「抽出那块木板」）
            duration = EXIT_MS
            setStartDelay(EXIT_DELAY_MS)
            interpolator = apple                    // 退场（倒梯形→长方形）同样用苹果曲线
            val animId = beginHeroAnim(this)
            addUpdateListener {
                val p = it.animatedValue as Float
                val hFull = heroFullH().toFloat()
                applyHero(
                    floatArrayOf(
                        kEnd + (st[0] - kEnd) * p,
                        0f,
                        hFull + (st[2] - hFull) * p,
                        st[3] * p,
                        st[4] * p,
                        p,                      // 标题条逐条缩回去，露出地址栏
                        // 遮光 1 → 0：卡片的遮挡在退场时被"抽走"了，
                        // 所以这层被挡住的暗要跟着一起退干净，不能留到整页上。
                        // 同样用 p²：平铺成整页的最后一段里，阴影早就该看不见了。
                        p * p,
                        // 相机参考高度：从卡片的静止高（st[5]）回到整页高 —— 和进场完全对称
                        st[5] + (hFull - st[5]) * p
                    )
                )
                // 模糊层跟着英雄视图的进度一起淡：只有当它真的快铺满（p 很小、
                // 位移和高度都接近终值）时才开淡，否则中途就会露出半截真实页面。
                if (!backdropFading && p < 0.22f) {
                    backdropFading = true
                    fadeTabBackdrop(0L, 90L)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!heroAnimCurrent(animId)) return
                    finishDismiss()
                }
            })
            start()
        }
        scheduleExitWatchdog()
        // 兜底：到「卡片预算 + 80ms」这一刻，剩下的卡片一律撤掉。
        // 设备帧率低的时候，ViewPropertyAnimator 的最后一帧不一定能被渲染出来
        // （看到的就是"总差一帧、留着一张没飞完的卡"）。这里不依赖动画是否画到终点，
        // 直接按时间把 alpha 归零 —— 页面铺满那一刻绝对不会再有卡片浮在上面。
        val exitToken = exitSeq
        rootView.postDelayed({
            if (exitSeq != exitToken || overlayTabs.visibility != View.VISIBLE) return@postDelayed
            for (c in 0 until tabList.childCount) {
                val v = tabList.getChildAt(c)
                if (v === hero) continue
                if (v.alpha != 0f) v.alpha = 0f
            }
        }, budget + 160)
        // 模糊背景的淡出由动画进度驱动（见下面的 p < 0.22f 分支），不靠固定毫秒数猜时机。
        fadeTabBottomBar(150)
    }

    /**
     * 退场看门狗：900ms 后如果浮层还开着，强制收尾。
     *
     * 为什么需要：退出动画总长 490ms，一旦英雄动画被谁掐掉（`onAnimationEnd` 不回来）、
     * 或者走的是 `fadeOutTabs()` 那条兜底路径而它的收尾又被取消，浮层就会一直挂着 ——
     * 它虽然已经全透明，**但仍然吃触摸**，看起来就是"整个界面点不动"。
     * 用 `exitSeq` 认人：期间用户又点开了任务视图（新一轮）就自动作废，不会误杀。
     */
    private fun scheduleExitWatchdog() {
        val token = ++exitSeq
        rootView.postDelayed({
            if (exitSeq == token && overlayTabs.visibility == View.VISIBLE) {
                finishDismiss()
            }
        }, 1800)
    }

    private fun fadeOutTabs() {
        scheduleExitWatchdog()
        fadeTabsChrome(0f, 220, 0, accel) { finishDismiss() }
    }

    private fun finishDismiss() {
        val ov = hero.overlay
        hero.overlay = null
        hero.overlayFrac = 0f
        if (ov != null && !ov.isRecycled) ov.recycle()
        hero.visibility = View.GONE
        hero.alpha = 1f
        hero.shadowFrac = 1f            // 下次复用英雄视图时从满遮光起步，别继承退场时的 0
        hero.bitmap = null            // 断开与 tab.thumb 的引用，避免后续 recycle 后仍被绘制
        hero.elevation = CARD_BACK_Z
        detachHero()
        stopShadowTick()
        overlayTabs.visibility = View.GONE
        // 回到浏览页：状态栏图标跟着主题还原（普通模式深色、私密模式白色）
        setStatusBarIconsWhite(privateMode)
        tabChrome.alpha = 1f
        tabBottomWrap.alpha = 1f
        tabAnimating = false
        // 甩动留下的位移/俯角在这里统一复位（浮层已隐藏，看不到跳变）
        shiftAnim?.cancel()
        shiftExcess = 0f
        setStackShift(0f)
        steepAnim?.cancel()
        steepExcess = 0f
        steep = 0f
        // 起飞帧留着：这一页还在前台，下次进任务视图可以直接复用，
        // 不必再在 UI 线程上把 WebView 软件画一遍（那一下就是「闪一下 + 卡一下」）。
        // 标签视图收起后不该残留搜索建议层
        if (!urlField.hasFocus()) hideSuggestions()
    }

    // ------------------------------------------------------------------
    // 滚动的缩放：越靠近上沿越小
    // ------------------------------------------------------------------

    /**
     * 静止时所有卡片一样大，只有「滑到画面上 1/9 线以上」的卡片才变小：
     *  ① 上沿从「画面 1/8 分界线」推到状态栏下沿：线性 1.0 → 0.8；
     *  ② 再往上：卡片以梯形对角线交点为心定住不动，每多推「卡片高度的 30%」从 0.8 缩到 0.6，
     *     之后不再变小，被前面的卡片盖住。往下滑则完全反过来。
     */
    /**
     * 半余弦缓动 (1 − cos πt)/2：t=0 和 t=1 处斜率都是 0。
     *
     * 用它把分段的缩放"接缝"抹平：原来两段都是线性，t=0 处斜率是常数（从 0 突跳到匀速），
     * 手指匀速滑动时卡片却"咔"一下开始缩、到拼缝处又换一个速度 —— 生硬感就来自这两个拐点。
     * 余弦在两端是"停一下再走"，两段拼起来就是 C1 连续。
     */
    private fun easeHalfCos(t: Float): Float =
        (1f - cos((PI * t).toFloat())) * 0.5f

    private fun updateTransforms() {
        if (cardH <= 0 || tabAnimating) return
        if (tabScroll.height <= 0) return

        val top = contentTopY()
        val line = shrinkLineY()                      // 收缩起点：画面上 1/9 分界线
        val span = (line - top).coerceAtLeast(1f)
        val edge = top                                // 缩到 0.8 的位置（状态栏下沿）
        // 越界继续下拽：整张图照画（hEnd = cardH），靠「加大俯角 + 拉近相机」做真实透视压缩
        val thetaNow = (tiltNow + steep * STEEP_TILT).coerceAtMost(82f)   // 别超过 82°：再大投影就压没了
        val dNow = D_OVER_H * (1f - steep * STEEP_D)
        // 缩放支点要用**这一帧**的相机距离算（越界俯身时 d 变了，对角线交点也跟着动）
        val cross = diagCrossY(dNow)
        val span2 = (cardRenderH * SHRINK_SPAN_RATIO).coerceAtLeast(1f)
        val gap = SHRINK_EDGE - SHRINK_MIN
        // 顶边下拉时整摞变稀疏：越往下的卡片让得越多（间距增量从 0 递增到最后一张最大），
        // 用 1−cos(t·π/2) 做累计位移 —— 它的导数 ∝ sin，正好是「上面最小、下面最大」的非线性
        val pad = if (stackShift > 0f) stackShift else 0f
        val cardN = tabList.childCount - (if (hero.parent === tabList) 1 else 0)
        val spanN = if (cardN > 1) (cardN - 1).toFloat() else 1f
        // 缩放低通的时间步长：必须用**真实帧间隔**，否则帧率一变"拖尾"的长度就变了。
        // 首次（或长时间没跑）按 16ms 起步；夹在 4~40ms 是为了防两种极端：
        // 连调多次（dt≈0 → 完全不动，补位/对齐会算不准）和掉帧（dt=100ms → 一步到位、等于没滤波）。
        val nowNs = System.nanoTime()
        val dtMs = if (lastTransformNs == 0L) 16f
        else ((nowNs - lastTransformNs) / 1_000_000f).coerceIn(4f, 40f)
        lastTransformNs = nowNs
        val kSmooth = 1f - exp(-dtMs / SHRINK_SMOOTH_TAU_MS)
        // 这一帧最多补画几张卡片的位图（每张 7MB，多了会明显掉帧）
        var lazyBudget = 1

        for (i in 0 until tabList.childCount) {
            val c = tabList.getChildAt(i)
            // 手里的卡（dragView）和正在归位补间的那张（settlingView）都不能被这里写位置：
            // 这个函数每帧都在跑（让位/补位补间里也会调），一写就把它们的回归动画抹掉，
            // 表现就是"一松手啪地闪现回原位"。
            if (c === dragView || c === hero || c === settlingView) continue
            var s = 1f
            var ty = 0f
            if (pad > 0.5f && i < cardN) {
                // ×1.2：默认间距从 332 宽到 457，同样的位移要放大一点才看得出"变稀疏"
                ty += pad * 1.2f * (1f - cos((i / spanN) * (PI / 2).toFloat()))
            }
            // 拖动让位期间卡片是沿自身平面挪的（hShift），实际屏幕位置要把这段算进去 ——
            // 否则它升到 1/9 线以上也不会缩小，松手那一下才被补算，看着就是"突然小一圈"。
            val p = cardTopY(c) + shiftScreenDy(c)
            val shrinking = p < line
            if (shrinking) {
                // 两段都用**半余弦** (1−cos πt)/2：它两端斜率都是 0，所以
                //  · 在「1/9 线」这一处，缩放是从 0 速度"轻轻开始"的（原来是 0 直接跳到匀速，
                //    手指匀速滑动时卡片却"咔"一下开始缩）；
                //  · 在「状态栏下沿」这个拼缝上，左右两段的斜率也都是 0，天然接得上（C1 连续）。
                // 锚点 1.0 / 0.8 / 0.6 一个都没动，动的只是中间怎么走。
                s = if (p >= edge) {
                    val t = ((p - edge) / span).coerceIn(0f, 1f)
                    SHRINK_EDGE + (1f - SHRINK_EDGE) * easeHalfCos(t)
                } else {
                    val t = ((edge - p) / span2).coerceIn(0f, 1f)
                    SHRINK_EDGE - gap * easeHalfCos(t)
                }
            }
            // ---- 低通：目标值 → 显示值 ----
            // 单帧跨度 > 0.05（很快的 fling）直接给到目标 —— 大位移上的滞后只会糊成一片。
            // 平滑器的值由 `applyCardScale()` 落盘，所以外部写入（起立/放平/补位/拖动）
            // 会自动同步过来，这里不需要再"回读视图比对"（那套回读会形成反馈环 → 抖动）。
            val tv = c as? TrapezoidView
            if (tv != null) {
                val ds = s - tv.scaleSmooth
                if (abs(ds) > 0.05f) tv.scaleSmooth = s else tv.scaleSmooth += ds * kSmooth
                s = tv.scaleSmooth
            }
            // ---- 位置 ----
            // 新模型：缩小是"绕上沿等比缩小"，**上沿本来就不动**，所以第一段不需要任何补偿
            // （以前那一项 `−cross·(1−s)` 就是"支点在对角线交点"带来的，现在支点没了）。
            // 第二段要把上沿**放到某个指定位置**，于是直接写成"目标上沿 − 布局上沿"。
            if (shrinking && p < edge) {
                val over = edge - p
                // ① 爬升（最高点）：指数趋近，over=0 处斜率正好 1 —— 和上一段"上沿跟手"
                //    的速度无缝接上（C1），过线那一刻不顿；爬到 SHRINK_CREST 就停住。
                val crestMax = (span2 * SHRINK_CREST).coerceAtLeast(1f)
                val crest = crestMax * (1f - exp(-over / crestMax))
                // ② 滚下（背面）：只有缩到 SHRINK_MIN（over ≥ span2）之后才开始，
                //    而且从 0 速度起步（半余弦两端斜率 0）—— 所以"到顶停一下、再往下走"，
                //    就是轮子转过最高点那一下。行程用卡片间距的比例，够它藏到前面那张卡后面。
                val rollMax = (cardStep * SHRINK_ROLL).coerceAtLeast(1f)
                val roll = if (over > span2) {
                    rollMax * easeHalfCos(((over - span2) / span2).coerceIn(0f, 1f))
                } else 0f
                // 目标上沿 = 状态栏下沿 + 这一档的"沉下去量" − 爬升 + 滚下
                val targetTop = edge + (SHRINK_EDGE - s) * cross
                ty += targetTop - crest + roll - p
            }
            // 值没变就不要写：每次 set 都会让这张卡重算变换、标脏重绘；
            // 滚动时每帧对 n 张卡各写 6 个属性，是实打实的开销。
            if (c.translationY != ty) c.translationY = ty
            if (c.alpha != 1f) c.alpha = 1f
            if (c is TrapezoidView) {
                // 必须先写俯角/相机：`applyCardScale()` 里反解"推多远"用的是 sinθ / dOverH，
                // 用上一帧的值会让尺寸和角度错开一帧 —— 连续变化时就是"卡片在抖"。
                if (c.theta != thetaNow) { c.theta = thetaNow; c.invalidate() }
                if (c.dOverH != dNow) { c.dOverH = dNow; c.invalidate() }
            }
            applyCardScale(c, s)
            // 懒渲染：建列表时只为视口附近生成位图，其余的滚到附近再补。
            // 一次最多补一张 —— 单张是 7MB 的分配 + 一次绘制，堆在一起就是一帧掉好久。
            if (lazyBudget > 0 && c is TrapezoidView && c.bitmap == null) {
                val vis = cardVisualTop(c)
                val vh = cardVisualH(c)
                if (vis + vh > -rootView.height * 1.5f && vis < rootView.height * 2.5f) {
                    val tab = tabs.getOrNull(tabOfChild(i))
                    val flat = tab?.let { renderFlat(it) }
                    if (flat != null) {
                        c.bitmap = flat
                        c.invalidate()
                        lazyBudget--
                    }
                }
            }
        }
        refreshShadows()
    }

    // ------------------------------------------------------------------
    // 建卡片
    // ------------------------------------------------------------------

    /**
     * 卡片位图用到的几何：只跟屏幕尺寸 / 顶栏高度有关，与「有几个标签、列表滚到哪」无关。
     *
     * 抽出来是为了能**提前**算好（见 `warmUpTabView()`）：卡片位图的缓存 key 里带着
     * cardW / cardH / cardHeaderH，如果预热时和真正打开时算出来的值不一样，缓存就白热了。
     */
    private fun ensureCardGeometry() {
        if (cardW > 0 && cardH > 0 && cardHeaderH > 0) return
        val hostH = rootView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val hostW = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        // 卡片宽度按**屏幕短边**算：竖屏时短边就是屏宽；横屏时仍取竖屏那个宽度 ——
        // 否则横屏卡片会铺满 2160px 变成一条超宽的扁带（用户明确要求"横屏也按竖屏的卡片宽度"）。
        // 注意下面 cardH 的换算仍然除以 `hostW`（真实页宽），因为卡片里装的是**横屏那一页**，
        // 页宽就是 hostW，宽高比要跟着页面走。
        val basisW = minOf(hostW, hostH)
        val side = dp(CARD_SIDE_DP)
        cardW = (basisW - side * 2).coerceAtLeast(dp(120f))
        // 卡片内容 = 「顶栏 + 整张页面截图」按宽度缩放 —— 也就是**和起飞帧同一份内容，只是小一号**。
        //
        // `cardH = heroFullH × cardW/屏宽`，于是投影时源行数 = cardH/k（k = cardW/屏宽）= heroFullH，
        // 整张图一行不少地画进梯形里（透视压短，不是裁掉下半截）。
        // 以前是 `hostH × 0.62` 的固定高度：位图只有页面**上半截**，卡片自然只剩半个页面。
        val fullH = heroFullH().takeIf { it > 0 } ?: (hostH - dp(44f))
        cardH = (fullH * cardW.toFloat() / hostW).toInt().coerceAtLeast(dp(240f))
        val n = tabs.size.coerceAtLeast(1)
        // 标签越多，俯角越大：看起来每张都被「推远」，挤在一起也不显窄
        val tt = ((n - 2).toFloat() / (TILT_FULL_N - 2).coerceAtLeast(1)).coerceIn(0f, 1f)
        tiltNow = TILT_BASE + TILT_EXTRA * tt
        cardRenderH = Trap.projectedHeight(cardH.toFloat(), tiltNow, D_OVER_H).toInt().coerceAtLeast(1)
        // 卡片标题条与顶栏同一比例，英雄视图的地址栏才能无缝变成居中名称
        val barH = topBar.height.takeIf { it > 0 } ?: dp(44f)
        cardHeaderH = (barH * cardW.toFloat() / hostW.toFloat()).toInt().coerceAtLeast(dp(24f))
    }

    /** 任务视图是否已经预热过（只做一次） */
    private var tabWarmed = false

    /** 删除 / 重排后的补位动画（一个 animator 统一推进所有卡片，见 rebuildAnimated） */
    private var reflowAnim: ValueAnimator? = null

    /** 模糊图对应的「屏幕状态」，一致就直接复用，不必再重画一遍 WebView */
    private var blurKey: String? = null

    /**
     * 首屏就绪后，提前把「第一次点任务视图」要用的重活干完。
     *
     * 第一次打开任务视图那一帧要干两件贵的事：
     *  ① `renderFlat()` —— inflate 卡片布局 + measure/layout + 把整张卡片画进位图；
     *  ② `captureComposite()` —— 把 WebView 画一遍（起飞帧）。
     * 两件事都压在 UI 线程上，第一次（类加载、JIT、位图分配全是冷的）能卡几十到几百毫秒，
     * 正好落在「点了任务视图、动画还没起来」那一帧上 —— 视觉上就是闪一下/卡一下。
     * 这里在首屏画完之后先热一遍①，并把模糊那条链（`warmBlur()`）也在后台跑热。
     * （背景模糊现在改用起飞帧当源，不再需要在 UI 线程上软件绘制整棵树。）
     */
    private var warmTries = 0

    private fun warmUpTabView() {
        if (tabWarmed) return
        // 任务视图开着 / 正在动画时别预热：渲染卡片要抢 UI 线程。等它收起再来（最多等 10 次）。
        if (tabAnimating || overlayTabs.visibility == View.VISIBLE) {
            if (warmTries++ < 10) rootView.postDelayed({ warmUpTabView() }, 300)
            return
        }
        // 视图还没量过就先等等，别把「只做一次」的机会浪费掉（否则第一次点任务视图永远走冷路径）
        if (rootView.width <= 0 || rootView.height <= 0) {
            rootView.postDelayed({ warmUpTabView() }, 100)
            return
        }
        tabWarmed = true
        runCatching {
            ensureCardGeometry()
            renderFlat(currentTab())      // 进 cardCache，第一次 refreshTabs 直接命中
            // 模糊只“热机器”，不碰任何 View：冷启动时第一遍 fastBlur 要一百多毫秒
            // （JIT 没热 + 缓冲要新分配），以前这一下就压在第一次打开任务视图上。
            // 这里拿一张 1/4 尺寸的空白图在后台把同一条路径跑一遍，缓冲也就位了。
            warmBlur()
        }
    }

    /** iOS 7：卡片纵向叠压成一摞，全部是同一个倾角的倒金字塔，只露出每张卡的顶部 */
    private fun refreshTabs() {
        // 排序：**优先沿用 viewOrder**（换位提交后它就是"用户拖出来的顺序"）；
        // 只有在它失效（增删标签后数量/内容对不上）时才回退到「当前页在最上面」的旋转。
        // 以前无条件按 `(currentIndex + k) % n` 旋转，导致换位一旦涉及第 0 格，
        // 提交后整摞会整体旋转一格 —— 松手瞬间所有卡片都动一下、手里的卡还飞到最后面去。
        val keepOrder = if (viewOrder.size == tabs.size &&
            viewOrder.toSet() == tabs.indices.toSet()
        ) viewOrder.toMutableList() else null
        tabList.removeAllViews()
        viewOrder = mutableListOf()
        trimCardCache()
        // 列表重建：正在跑的补位动画写的是马上就要丢弃的那批 View，先停掉
        reflowAnim?.cancel()
        reflowAnim = null
        if (tabs.isEmpty()) return

        val hostH = rootView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val hostW = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val n = tabs.size
        ensureCardGeometry()

        // tabScroll 还没量过（第一次打开时 height 是 0）就用窗口高度减掉底栏。
        // 这里以前还减了 20dp 的「假状态栏」—— 状态栏改用系统之后那条已经不存在，
        // 多减会让第一次算出的 topPad 比之后小 27px，两次打开的卡片位置就不一致。
        val viewportH = tabScroll.height.takeIf { it > 0 } ?: (hostH - dp(44f))
        val areaH = viewportH.toFloat().coerceAtLeast(1f)
        val stepMax = (hostH * STEP_MAX_RATIO).toInt()
        val stepMin = (areaH / MAX_VISIBLE_CARDS).toInt().coerceAtLeast(1)
        // 刚好放得下的间距（三张时间距就由它定，保证整摞都露得出来）
        val fit = ((areaH - cardRenderH) / (n - 1).coerceAtLeast(1)).toInt()
        // 五张填满浏览区
        val want = (areaH / n.coerceAtMost(4)).toInt()
        cardStep = if (n <= 1) dp(CARD_PEEK_DP) else {
            minOf(minOf(want, stepMax), maxOf(fit, stepMin))
        }

        // 整摞最上面那张（当前页）必须停在「上 1/9 分界线」的下面：
        // 这样它静止时是正常大小，进场动画折出来的正常倒梯形也正好落在它身上
        val headMargin = dp(8f)                       // 第一张卡片的上边距
        val restTop = (shrinkLineY() - tabScroll.top - headMargin + dp(2f)).toInt().coerceAtLeast(0)
        // 当前页那张卡的上沿**必须**落在「上 1/9 分界线」（再高就会被缩小那套接管，卡片会变小）。
        // 以前标签少时会把它居中 —— 1~2 张时卡片跑到屏幕中间，和 iOS 7 不符；现在一律用 restTop。
        val topPad = restTop
        tabList.setPadding(0, topPad, 0, dp(70f))

        val order = keepOrder ?: MutableList(n) { (currentIndex + it) % n }
        // 视口附近的判定带（列表坐标系）：只为这一带的卡片生成位图。
        // 每张卡片位图是 cardW×cardH ≈ 7MB，20 个标签就是 140MB —— "一多就卡"主要卡在这儿
        //（分配 + GC），而不是绘制。远处的卡先留空，滚到附近再补（见 updateTransforms）。
        val nearTop = tabScroll.scrollY - areaH
        val nearBot = tabScroll.scrollY + areaH * 2f
        var yPos = topPad
        for (k in 0 until n) {
            val ti = order[k]
            val tab = tabs[ti]
            val topMargin = if (k == 0) dp(8f) else cardStep - cardRenderH
            yPos += topMargin
            val cardTop = yPos
            yPos += cardRenderH
            val near = cardTop + cardRenderH >= nearTop && cardTop <= nearBot
            val flat = if (near) (renderFlat(tab) ?: continue) else null
            viewOrder.add(ti)

            val v = TrapezoidView(this)
            v.bitmap = flat
            v.k = 1f
            v.hStart = 0f
            v.hEnd = cardH.toFloat()
            // 相机参考高度固定成卡片自己的静止高度：这样"沿平面推远"才是真的变小
            //（跟着 hEnd 走的话相机会追着卡片后退，推多远都不缩，见 TrapezoidView.hRefBase）
            v.hRefBase = cardH.toFloat()
            v.theta = tiltNow
            v.dOverH = D_OVER_H
            val lp = LinearLayout.LayoutParams(cardW, cardRenderH)
            // 左右余量 = 屏幕宽减去卡片宽的一半：竖屏时正好等于 CARD_SIDE_DP，
            // 横屏时（卡片按短边算、比列表窄）它就变成了"居中"。
            val side = ((hostW - cardW) / 2).coerceAtLeast(0)
            lp.setMargins(side, if (k == 0) dp(8f) else cardStep - cardRenderH, side, 0)
            v.layoutParams = lp
            // 遮光不在建卡时定死：由 `refreshShadows()` 每帧按遮挡关系算
            v.shadowDepth = 0f
            v.elevation = CARD_BACK_Z       // 默认都在最底层，动画时由 heroAsPage 分配图层
            v.setOnTouchListener(CardTouch(v))
            tabList.addView(v)
        }
        trimCardCache()          // 建完再裁一次：只留"正在用"的那些
        afterLayout { updateTransforms() }
    }

    /** 任务视图开着时，截图补上了：就地换位图，不重建列表 */
    private fun refreshCardBitmaps() {
        if (overlayTabs.visibility != View.VISIBLE || tabAnimating) return
        for (k in 0 until tabList.childCount) {
            val v = tabList.getChildAt(k)
            if (v !is TrapezoidView) continue
            val tab = tabs.getOrNull(tabOfChild(k)) ?: continue
            val flat = renderFlat(tab) ?: continue
            if (v.bitmap !== flat) {
                v.bitmap = flat
                v.invalidate()
            }
        }
    }

    /** 位图缓存失效 / 清理：只留下还在用的标签页 */
    private fun trimCardCache() {
        // 正在被卡片视图引用的位图**绝不能回收**（远处卡片是懒渲染的，回收了就是空白卡）
        val inUse = HashSet<Bitmap>()
        for (i in 0 until tabList.childCount) {
            val b = (tabList.getChildAt(i) as? TrapezoidView)?.bitmap
            if (b != null && !b.isRecycled) inUse.add(b)
        }
        val alive = tabs.map { it.id }.toSet()
        val it = cardCache.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (id in alive) continue
            val b = cardCache[id]?.second
            it.remove()
            if (b != null && !b.isRecycled && b !== hero.bitmap) b.recycle()
        }
        while (cardCache.size > cardCacheMax) {
            val eldest = cardCache.entries.firstOrNull { e ->
                e.value.second !== hero.bitmap && !inUse.contains(e.value.second)
            } ?: break
            cardCache.remove(eldest.key)
            if (!eldest.value.second.isRecycled) eldest.value.second.recycle()
        }
    }

    /** 一张卡片重建前的样子：屏幕上沿的位置 + 缩放 */
    private class CardSnap(val y: Float, val s: Float)

    /**
     * 记下每张卡片此刻在屏幕上的 y（含自身的缩放补偿位移）和缩放，重建之后据此做补位动画。
     *
     * 必须带上 `translationY`：越过 1/9 线的卡片靠它把上沿「钉」在屏幕上，
     * 光记 `cardTopY()` 记到的是布局位置，重建之后算出来的起点会完全不对。
     */
    private fun snapshotPositions(): HashMap<Int, CardSnap> {
        val before = HashMap<Int, CardSnap>()
        for (k in 0 until tabList.childCount) {
            val v = tabList.getChildAt(k)
            if (v === hero) continue
            val ti = tabOfChild(k)
            if (ti !in tabs.indices) continue
            before[tabs[ti].id] = CardSnap(cardVisualTop(v), cardScale(v))
        }
        return before
    }

    /**
     * 重排 / 删除之后重建列表，并让每张卡从「原来的位置」滑到新位置（先快后慢）。
     *
     * 这里踩过两个坑，改的时候别退回老写法：
     *
     * ① **滚动位置必须在算落点之前定下来。**
     *   以前是先给每张卡设好 animator、最后才 `scrollTo(0, desiredScroll())`。
     *   任务视图往上滚过（标签多的时候必然如此）时 `scrollY > 0`，这一下归零会让
     *   所有卡片当场整体往下跳一段 —— 表现就是「补位动画丢了、卡片直接闪现在下方」。
     *   越过 1/9 线的卡片跳得最明显（它们正好在上沿附近）。
     *
     * ② **不要逐张 `view.animate()`。** `ViewPropertyAnimator` 每帧写 `translationY`，
     *   而 `updateTransforms()` 也每帧写同一个属性（谁后写谁赢）—— 带着缩放补偿的卡片
     *   一旦被覆盖，位置就"跳"回布局位置。改成**一个** animator 统一推进所有卡片的
     *   位移与缩放，动画期间没有别人跟它抢。
     */
    private fun rebuildAnimated(snapshot: Map<Int, CardSnap>? = null) {
        val before = snapshot ?: snapshotPositions()
        reflowAnim?.cancel()
        refreshTabs()
        afterLayout {
            // ① 先把滚动位置夹到合法范围（内容变短了 scrollY 可能越界），再算落点
            val maxS = (tabList.height - tabScroll.height).coerceAtLeast(0)
            if (tabScroll.scrollY > maxS) tabScroll.scrollTo(0, maxS)
            updateTransforms()
            val views = ArrayList<View>()
            val fromTy = ArrayList<Float>()
            val toTy = ArrayList<Float>()
            val fromS = ArrayList<Float>()
            val toS = ArrayList<Float>()
            for (k in 0 until tabList.childCount) {
                val v = tabList.getChildAt(k)
                if (v === hero) continue
                val ti = tabOfChild(k)
                if (ti !in tabs.indices) continue
                val snap = before[tabs[ti].id] ?: continue
                val targetTy = v.translationY
                val targetS = cardScale(v)
                // 比较和换算都必须用**视觉上沿**（含缩放支点补偿 `pivotY·(1−s)`），
                // 不能用 `cardTopY + translationY` —— 越过 1/9 线缩小过的卡片，
                // 这一项最多有 277px；用错的话它们每次重建都会被判成"位置不对"，
                // 于是被塞进补间队列、整体抖一下再回去（"抽搐一下"就是这么来的）。
                val nowVisual = cardVisualTop(v)
                if (abs(snap.y - nowVisual) < 1f && abs(snap.s - targetS) < 0.01f) continue
                // 起始缩放先摆好，再按"起始缩放"下的支点补偿算起始 translationY，
                // 这样这一帧看起来正好还停在老位置。
                applyCardScale(v, snap.s)
                // 新模型：缩放不动上沿，起始 translationY 就是"老位置 − 布局位置"（不用再补 pivot）
                val startTy = snap.y - shiftScreenDy(v) - cardTopY(v)
                v.translationY = startTy
                views.add(v)
                fromTy.add(startTy)
                toTy.add(targetTy)
                fromS.add(snap.s)
                toS.add(targetS)
            }
            if (views.isEmpty()) return@afterLayout
            reflowAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520
                interpolator = SineOutInterpolator()
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    for (i in views.indices) {
                        val v = views[i]
                        v.translationY = fromTy[i] + (toTy[i] - fromTy[i]) * p
                        val s = fromS[i] + (toS[i] - fromS[i]) * p
                        applyCardScale(v, s)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 收尾对齐到精确值：别让插值误差把卡片留在差几像素的位置
                        for (i in views.indices) {
                            views[i].translationY = toTy[i]
                            applyCardScale(views[i], toS[i])
                        }
                    }
                })
                start()
            }
        }
    }

    /** 把卡片（标题条 + 网页缩略图）渲染成一张平铺位图（带缓存） */
    private fun renderFlat(tab: Tab): Bitmap? {
        if (cardW <= 0 || cardH <= 0) return null
        val label = cardLabel(tab)
        val key = "$cardW|$cardH|$cardHeaderH|${tab.thumbVer}|$label"
        val hit = cardCache[tab.id]
        if (hit != null && hit.first == key && !hit.second.isRecycled) return hit.second
        if (hit != null) {
            cardCache.remove(tab.id)
            if (!hit.second.isRecycled) hit.second.recycle()
        }
        val bmp = renderCard(tab, cardW, cardH) ?: return null
        cardCache[tab.id] = key to bmp
        return bmp
    }

    private fun cardLabel(tab: Tab): String {
        val raw = tab.title.trim()
        return raw.takeIf { it.isNotEmpty() && !it.equals("about:blank", true) && it != tab.url }
            ?: displayUrl(tab.url).ifBlank { getString(R.string.new_tab) }
    }

    private fun renderCard(tab: Tab, w: Int, h: Int): Bitmap? {
        val label = cardLabel(tab)
        val v = layoutInflater.inflate(R.layout.item_tab_card, null)
        val header = v.findViewById<View>(R.id.cardHeader)
        val title = v.findViewById<TextView>(R.id.cardTitle)
        val thumb = v.findViewById<ImageView>(R.id.cardThumb)
        // 标题不再靠 TextView 渲染：离屏 measure/layout 时它经常一个字都画不出来。
        // 这里把 TextView 隐掉，等卡片画完后用 Canvas 精确居中绘制文字。
        header.layoutParams = header.layoutParams.also { it.height = cardHeaderH }
        title.text = label
        title.visibility = View.INVISIBLE
        thumb.layoutParams = thumb.layoutParams.also {
            it.height = (h - cardHeaderH - dp(1f)).coerceAtLeast(1)
        }
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, w, h)
        val bmp = tab.thumb
        if (bmp != null && thumb.width > 0) {
            thumb.setImageBitmap(bmp)
            val m = Matrix()
            val kk = thumb.width.toFloat() / bmp.width
            m.setScale(kk, kk)          // 宽度铺满、顶部对齐
            thumb.imageMatrix = m
        }
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            v.draw(canvas)
            drawCardTitle(canvas, label, w)
            out
        } catch (e: Exception) {
            null
        }
    }

    private val cardTitlePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sp(13f)
            // 居中：真机视频里那条看起来像"左对齐"，其实是标题太长顶到了 × 右边，
            // 文字本身是居中的（用户确认过）
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
    }

    /** 卡片标题条：黑字 13sp，水平垂直都居中，过长时尾部省略 */
    private fun drawCardTitle(canvas: Canvas, label: String, w: Int) {
        if (label.isBlank()) return
        val maxW = (w - dp(64f)).toFloat()
        var t = label
        if (cardTitlePaint.measureText(t) > maxW) {
            while (t.length > 1 && cardTitlePaint.measureText("$t…") > maxW) t = t.dropLast(1)
            t = "$t…"
        }
        val fm = cardTitlePaint.fontMetrics
        val baseline = cardHeaderH / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(t, w / 2f, baseline, cardTitlePaint)
    }

    // ------------------------------------------------------------------
    // 手势：单击进入 / 长按左滑删除 / 长按上下拖动换位
    // ------------------------------------------------------------------

    private inner class CardTouch(private val v: View) : View.OnTouchListener {

        private val det = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                downX = e.rawX
                downY = e.rawY
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                beginDrag(v)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (dragView != null) return true
                val k = tabList.indexOfChild(v)
                val ti = tabOfChild(k)
                if (ti !in tabs.indices) return true
                if (e.x < dp(38f) && e.y < dp(38f)) {
                    closeTab(ti)
                } else {
                    selectTab(ti)
                    dismissTabs(ti)
                }
                return true
            }
        })

        override fun onTouch(host: View, ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = ev.rawX
                downY = ev.rawY
            }
            if (dragView === v) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> onDragMove(ev)
                    MotionEvent.ACTION_UP -> endDrag(false)
                    MotionEvent.ACTION_CANCEL -> endDrag(true)
                }
                return true
            }
            det.onTouchEvent(ev)
            return true
        }
    }

    private fun beginDrag(v: View) {
        if (tabAnimating || dragView != null) return
        val k = tabList.indexOfChild(v)
        if (k < 0 || k > viewOrder.lastIndex) return
        dragView = v
        dragFrom = k
        dragTo = k
        dragAxis = 0
        dragStartX = downX
        dragStartY = downY
        dragBaseY = v.translationY
        // **必须清零**：上一次拖动留下的 dragS（比如往下拖过 3 格）如果还在，
        // 这次一长按，applyStackOrder() 会以为手里这张已经"压过 3 格"，
        // 直接把它插到很靠前的位置 —— 表现就是"长按一下卡片突然跳到最上层"。
        dragS = 0f
        settlingView = null
        restackPending = false
        clearYieldStates()
        // 起手时目标就是它现在的位置；跟随循环负责"先慢后快"地追上手指
        dragTargetY = v.translationY
        followLast = 0L
        v.removeCallbacks(followTicker)
        v.postOnAnimation(followTicker)
        // 拿起：轻微放大 2%（等比 —— k 与 hEnd 一起动），给"拿在手里"的实感。
        // 不能用 View.scaleX：那会把卡片从"绕上沿缩放"变成"绕轴心缩放"，上沿要跑。
        val popFrom = cardScale(v)
        ValueAnimator.ofFloat(popFrom, popFrom * 1.02f).apply {
            duration = 120
            addUpdateListener { a -> applyCardScale(v, a.animatedValue as Float) }
            start()
        }
        // 层级按"下标顺序"归一化一次（dragS = 0 时手里那张只在自己那一格上 +0.005，
        // 和静止时的层叠完全一致，看不出变化）；拖动过程中再由 applyDragRestack 更新。
        applyStackOrder()
        tabList.requestDisallowInterceptTouchEvent(true)
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun onDragMove(e: MotionEvent) {
        val v = dragView ?: return
        val dx = e.rawX - dragStartX
        val dy = e.rawY - dragStartY
        if (dragAxis == 0) {
            dragAxis = when {
                abs(dx) > dp(14f) && abs(dx) > abs(dy) * 1.3f && dx < 0 -> 1   // 左滑删除
                abs(dy) > dp(12f) -> 2                                          // 上下换位
                else -> 0
            }
        }
        when (dragAxis) {
            1 -> {
                // 左滑过程中**不淡出**：卡片整张跟着手指走，滑过阈值再整张飞出去
                v.translationX = if (dx >= 0f) 0f else dx
                if (v.alpha != 1f) v.alpha = 1f
            }
            2 -> {
                // 只记录"手指希望它去哪"，真正的位移由 followTicker 逐帧追（这样才有重量感）
                dragTargetY = dragBaseY + dy
                // 连续量：拖动进度，单位是「格」
                val s = dy / cardStep.coerceAtLeast(1).toFloat()
                val limit = (tabList.childCount - 1).coerceAtLeast(0)
                // 方向一反，先回到"没换"的状态
                if ((s > 0f && dragTo < dragFrom) || (s < 0f && dragTo > dragFrom)) dragTo = dragFrom
                // 换位判定带**阈值 + 滞回**：往前要拖过 cur+DRAG_SWAP_ON 格才算换下一格，
                // 往回要退到 cur−(1−DRAG_SWAP_OFF) 以下才撤销。以前用 round(s)（0.5 格就换），
                // 长按后轻轻一拖就触发，太敏感。
                val prog = abs(s)
                val cur = abs(dragTo - dragFrom)
                val next = when {
                    prog >= cur + DRAG_SWAP_ON -> cur + 1
                    prog < cur - (1f - DRAG_SWAP_OFF) -> (cur - 1).coerceAtLeast(0)
                    else -> cur
                }
                dragTo = if (s > 0f) (dragFrom + next).coerceIn(0, limit)
                else (dragFrom - next).coerceIn(0, limit)
                // 让位重排**每帧最多算一次**：一帧里可能收到好几个 MOVE，
                // 每个都算一遍的话层级/重绘都白做几倍（手指来回抖时尤其明显）。
                dragS = s
                if (!restackPending) {
                    restackPending = true
                    tabList.postOnAnimation {
                        restackPending = false
                        if (dragView != null) applyDragRestack(dragS)
                    }
                }
            }
        }
    }

    /**
     * 拖拽换位途中，被「让开」的卡片怎么动 —— 像真的木板 / 明信片在交换位置。
     *
     * 手里的卡片靠近某一格时：
     *   ① 那一格原来的卡先**向下滑出视线**（把位置让出来）；
     *   ② 等手里的卡把它原来的位置腾开，它再从下面**插回**腾出来的那一格。
     *
     * 全程由拖动进度 `s`（单位＝格）驱动，不是"过线就跳一格"的离散动画，所以：
     *  - 手往哪走、木板往哪走，随时反向都连续；
     *  - 一格只有一张卡处在过渡中（相邻卡的 p 正好差 1），画面上永远只有一张在让开。
     *
     * 曲线：位移部分用 smoothstep（两端速度为 0，落位不撞），
     *       额外叠一条 `sin(πp)` 的"让开波"（中间最深）。
     *
     * **往上让（dir=-1）**（去接上面那一格）：整个让开波都要把它往**后面**压一层，
     * 否则它沉下去时会压在下面那张**前面**，居中标题一路露着、怎么也"看不见"不了。
     *
     * **往下让（dir=+1）**（去填下面那一格）：**不能有让开波**，也不能往后压。
     * 它的目标位置本来就在**前面**，层级会在两张卡「顶边正好重合」的那一瞬翻转 ——
     * 那一刻两张卡完全叠在一起，翻转是看不见的。但只要再叠一条让开波，它翻到前面之后
     * 还会继续往下沉，那一段就变成"一张卡突然蹦出来盖住手里这张"（往上拖换位时的错乱就是这么来的）。
     */
    /**
     * 拖动换位时"被让开"的卡片怎么动 —— **各动各的**，不跟手指逐帧对应。
     *
     * 三态（`yieldPhase`）：
     *  - 0 原地：什么都不做；
     *  - 1 让开：手里的卡拖到"挨得近"（p ≥ DRAG_YIELD_AT）→ 它**按自己的速度**（200ms）
     *    沿自身平面沉到手里那张下面停住等着（此时它被完全遮住）；
     *  - 2 补位：手里的卡拖到它原来的格子附近（p ≥ DRAG_FILL_AT）→ 它**按自己的速度**（240ms）
     *    滑进腾出来的那一格，然后停住。
     *
     * 为什么不再用"逐帧按 p 插值"：那样两张卡的运动被死死绑在一起，手指一停对面也停、
     * 手指快对面就快，看起来像是被手指"推着走"。现在各自补间，手感更像真的木板自己让开 / 自己归位。
     */
    private fun applyDragRestack(s: Float) {
        val stepPx = cardStep.coerceAtLeast(1).toFloat()
        val hRef = cardH.toFloat()
        // 让开的深度：必须沉到**手里那张的下沿之下**（见 7.15 的推导），这里与方向无关
        val heldH = dragView?.let { cardVisualH(it) } ?: cardRenderH.toFloat()
        val dipShift = Trap.hShiftForDy(heldH + DRAG_DIP_DOWN_EXTRA * stepPx, tiltNow, D_OVER_H, hRef)

        for (k in 0 until tabList.childCount) {
            val c = tabList.getChildAt(k)
            if (c === dragView || c === hero || c !is TrapezoidView) continue
            var dir = 0
            var p = 0f
            if (s > 0f && k > dragFrom) {          // 往下拖：后面的卡往上补一格
                dir = -1
                p = (s - (k - dragFrom - 1)).coerceIn(0f, 1f)
            } else if (s < 0f && k < dragFrom) {   // 往上拖：前面的卡往下补一格
                dir = 1
                p = (-s - (dragFrom - k - 1)).coerceIn(0f, 1f)
            }
            val cur = yieldPhase[c] ?: 0
            // 带滞回的相位推进：往前要过 ON 阈值，往回要掉到 OFF 以下才退回去
            val next = when (cur) {
                0 -> if (p >= DRAG_YIELD_AT) 1 else 0
                1 -> when {
                    p >= DRAG_FILL_AT -> 2
                    p < DRAG_YIELD_BACK -> 0
                    else -> 1
                }
                else -> if (p < DRAG_FILL_BACK) 1 else 2
            }
            if (next != cur) startYield(c, next, dir, stepPx, dipShift)
        }
        updateTransforms()
        applyStackOrder()
    }

    /** 把某张让开的卡切到目标相位（自己补间，不看手指） */
    private fun startYield(
        c: TrapezoidView, phase: Int, dir: Int, stepPx: Float, dipShift: Float
    ) {
        yieldPhase[c] = phase
        val toScreen = if (phase == 2) dir * stepPx else 0f
        val toH = if (phase == 1) dipShift else 0f
        val fromScreen = c.screenShift
        val fromH = c.hShift
        if (abs(fromScreen - toScreen) < 0.5f && abs(fromH - toH) < 0.5f) return
        yieldAnim[c]?.cancel()
        val dur = if (phase == 1) DRAG_YIELD_MS else DRAG_FILL_MS
        yieldAnim[c] = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            // 让开（往下沉）：**先慢后快** —— 一开始像还"顶着"、被挤得不太情愿，
            // 越往下越快，最后沉到手里那张下面（慢起快落，才有重量感；用 Decelerate 会显得轻飘飘）。
            // 补位（往上滑进格子）：温柔一点 —— 慢起、缓缓加速、长一点的收尾，
            // 不要过冲（过冲会"磕"一下，显得积极、犀利）。和让开那条"慢起快落"正好接得上。
            interpolator = if (phase == 1) AccelerateInterpolator(1.6f)
            else PathInterpolator(0.32f, 0f, 0.16f, 1f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                c.screenShift = fromScreen + (toScreen - fromScreen) * f
                c.hShift = fromH + (toH - fromH) * f
                // 位置一变，它相对 1/9 线的缩放/支点补偿要跟着重算
                updateTransforms()
            }
            start()
        }
    }

    /** 拖动结束：把所有让开的卡的状态清掉（补位归 settle / 重建负责） */
    private fun clearYieldStates() {
        for ((_, a) in yieldAnim) a.cancel()
        yieldAnim.clear()
        yieldPhase.clear()
    }

    /**
     * 拖动期间的层级：**只动手里那张，其他卡片一律保持原来的层级**。
     *
     * 关键约束（用户提的）：换位只该改变"这两张之间"的先后，不能影响它们和别人的关系 ——
     * 所以绝不能"无脑提到最上层 / 压到最下层"，那会让它盖住、或让无关的卡片盖住它。
     *
     * 做法：`elevation = (下标 + 1) × 0.01` 就是静止时的层叠顺序（下标大的压着下标小的）。
     * 手里那张按**它现在走过的格数**插进去：
     *  - 往下拖：已经压过 `dragFrom + round(s)` 那几张 → `z = (那张下标 + 1) × 0.01 + 0.005`
     *  - 往上拖：已经让过 `dragFrom + round(s)` 那几张 → `z = 那张下标 × 0.01 + 0.005`
     * 其他卡片的 z 一动不动，它们彼此的遮盖关系因此完全不变。
     *
     * 翻转时机：`round(s)` 每变一次＝"手里那张的顶边越过那一张的顶边"的时刻；
     * 而那一刻那张卡正好在让开波的最高点（缩下去、被下面几张挡着），所以翻转看不见。
     */
    private fun applyStackOrder() {
        val n = tabList.childCount
        if (n == 0) return
        // 用 dragTo（带阈值/滞回的那个）而不是 round(dragS)：层级翻转必须和"换位判定"同一时刻，
        // 否则会出现"卡片还没换位、层级先翻"。翻转时那张卡正在让开波底部，看不见。
        val passed = dragTo.coerceIn(0, n - 1)
        for (k in 0 until n) {
            val c = tabList.getChildAt(k)
            if (c === hero) continue
            val e = if (c === dragView) {
                if (dragS >= 0f) (passed + 1) * 0.01f + 0.005f else passed * 0.01f + 0.005f
            } else {
                (k + 1) * 0.01f
            }
            if (abs(c.elevation - e) > 0.001f) c.elevation = e
        }
    }

    /** 恢复"下标顺序"：所有卡片回到 CARD_BACK_Z，绘制顺序交还给子视图顺序 */
    private fun resetStackOrder() {
        for (k in 0 until tabList.childCount) {
            val c = tabList.getChildAt(k)
            if (c === hero) continue
            if (c.elevation != CARD_BACK_Z) c.elevation = CARD_BACK_Z
        }
    }

    private fun endDrag(cancelled: Boolean) {
        val v = dragView ?: return
        v.removeCallbacks(followTicker)
        dragView = null
        dragS = 0f
        restackPending = false
        clearYieldStates()
        val axis = dragAxis
        dragAxis = 0
        when (axis) {
            1 -> {
                if (!cancelled && v.translationX < -cardW * 0.35f) {
                    val ti = tabOfChild(tabList.indexOfChild(v))
                    v.animate().translationX(-(cardW + dp(60f)).toFloat())
                        .setDuration(200).setInterpolator(accel)
                        .withEndAction { if (ti in tabs.indices) removeTabAt(ti) }.start()
                } else {
                    v.animate().translationX(0f).alpha(1f)
                        .setDuration(240).setInterpolator(SineOutInterpolator()).start()
                    settle(v)                 // 缩放也要补间，否则松手会「跳」一下
                }
            }
            2 -> {
                if (dragTo != dragFrom) commitReorder(v) else settle(v)
            }
            else -> settle(v)
        }
    }

    /**
     * 松手后把卡片弹回它该在的位置（位置与大小都由缩放规则算，动画依旧是先快后慢）。
     * 缩放也要一起补间 —— 只补位移而把 scale 硬 set 成终值，卡片会在松手那一帧
     * 突然缩一下，看着就像「莫名其妙向上弹一下」。
     *
     * 另外**不能**简单调一次 `updateTransforms()` 就完事：那会把其它卡片的
     * `translationY` / `alpha` 瞬间写成终值 —— 拖拽途中正在"让开"的那张就从屏幕下方
     * 啪地闪回原位。这里先把别人的当前值存下来，`updateTransforms()` 之后再放回去，
     * 让它们各自补间归位。
     */
    private fun settle(v: View) {
        val cur = v.translationY
        val curS = cardScale(v)
        updateTransforms()
        val target = v.translationY
        val targetS = cardScale(v)
        v.translationY = cur
        applyCardScale(v, curS)
        val moved = abs(cur - target) > 1f || abs(curS - targetS) > 0.004f
        if (moved) {
            // 归位：先快后慢（Decelerate），像被轻轻按回原位，不要弹。
            // 位置和"大小"（k + hEnd）必须一起补间 —— 只补位置、把 k 硬 set 成终值，
            // 卡片会在松手那一帧突然缩一下（"莫名其妙向上弹一下"就是这么来的）。
            settlingView = v
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DRAG_SETTLE_MS
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    v.translationY = cur + (target - cur) * p
                    applyCardScale(v, curS + (targetS - curS) * p)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        v.translationY = target
                        applyCardScale(v, targetS)
                        if (settlingView === v) settlingView = null
                    }
                })
                start()
            }
        }
        // 其它卡片：让开的 3D 位移平顺滑回 0。**每帧重算一次 updateTransforms** ——
        // 位置一变，它相对 1/9 线的缩放/补偿就该跟着变，否则滑回去的途中尺寸是错的。
        val starts = ArrayList<Triple<TrapezoidView, Float, Float>>()
        for (k in 0 until tabList.childCount) {
            val c = tabList.getChildAt(k)
            if (c === v || c === hero || c !is TrapezoidView) continue
            if (c.hShift != 0f || c.screenShift != 0f) starts.add(Triple(c, c.hShift, c.screenShift))
        }
        if (starts.isNotEmpty()) {
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 440
                interpolator = SineOutInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    for ((c, h0, d0) in starts) {
                        c.hShift = h0 * f
                        c.screenShift = d0 * f
                    }
                    updateTransforms()
                }
                start()
            }
        }
        // 归位动画期间继续按位置排序，跑完再交还给子视图顺序（否则归位途中层次会忽然反一次）
        rootView.postDelayed({ if (dragView == null) resetStackOrder() }, 240)
    }

    private fun commitReorder(v: View) {
        val from = dragFrom
        val to = dragTo
        if (from == to) return
        // 松手前先记下每张卡"视觉位置 + 缩放"：重建之后从这里补间过去。
        // 注意**必须带缩放** —— 换位会让某几张卡跨过 1/9 线，缩放会变；
        // 只补 translationY 的话，缩放会在重建那一帧直接跳到终值（就是"抖闪一下"）。
        val before = snapshotPositions()
        val order = viewOrder.toMutableList()
        if (from !in order.indices || to !in order.indices) return
        val cur = tabs.getOrNull(currentIndex)
        val moved = order.removeAt(from)
        order.add(to, moved)
        viewOrder = order
        // 视觉顺序就是新的标签顺序；当前页还是原来那一页，只是卡片位置变了
        val newTabs = order.map { tabs[it] }
        tabs.clear()
        tabs.addAll(newTabs)
        // **tabs 本身已经是新的视觉顺序了**，viewOrder 必须变成恒等映射（0,1,2…）。
        // 留成 `order`（那是"旧下标"的排列）的话，refreshTabs 会把同一个排列再套一次 → 顺序彻底乱。
        viewOrder = MutableList(tabs.size) { it }
        currentIndex = cur?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        // 和删除标签后的补位共用同一套：重建 → 先定 scrollY → 一个 animator 同时推
        // translationY 和 scale（见 rebuildAnimated 的注释）
        rebuildAnimated(before)
    }

    // ------------------------------------------------------------------
    // 滑到尽头继续往下拽：整摞梯形压得更陡（橡皮筋）
    // ------------------------------------------------------------------

    /**
     * 越界结束。
     *
     * `fromFling` = 这次越界是「惯性甩过头」造成的（不是手指拖动）。
     * 甩过头**不回位**（用户要的效果）：整摞就停在那儿。状态不会自己复位，
     * 而是在下一次「进任务视图（`openTabs`）/ 点卡片退出（`finishDismiss`）」时统一归零 ——
     * 那时浮层要么刚打开、要么已经隐藏，看不到跳变。
     *
     * 所以这里只剩下「手指拖动松手」这一条：交给软弹簧慢慢回去。
     * （以前还有一条 `fromFling` 的 320ms 快回位分支，甩动不回位之后就永远走不到了，已删。）
     */
    private fun onOverscrollEnd(fromFling: Boolean) {
        if (fromFling) return
        releaseSteep()
        releaseStackShift()
    }

    private fun onOverscroll(dy: Float) {
        if (tabAnimating || dragView != null) return
        if (dy == 0f) return
        // 橡皮筋：整摞顺着手指再走一段（越拖越拖不动），松手弹回原位。
        // 顶边下拉 dy>0 → 往下让；底边上滑 dy<0 → 往上让。
        val maxShift = dp(120f).toFloat()
        shiftExcess = (shiftExcess + dy)
            .coerceIn(-maxShift * 4f, maxShift * 4f)
        val mag = maxShift * (1f - exp(-abs(shiftExcess) / maxShift))
        shiftAnim?.cancel()
        setStackShift(if (shiftExcess >= 0f) mag else -mag)

        steepExcess = (steepExcess + dy).coerceAtLeast(0f)
        val k = (STEEP_RUBBER_DP * resources.displayMetrics.density).coerceAtLeast(1f)
        val target = (1f - exp(-steepExcess / k)).coerceIn(0f, 1f)
        if (abs(target - steep) < 0.002f) return
        steep = target
        steepAnim?.cancel()
        applySteep()
    }

    /** 只挪整摞（tabList 的 translationY），不改任何卡片的布局；位移变了要重算缩放 */
    private fun setStackShift(v: Float) {
        if (stackShift == v) return
        stackShift = v
        tabList.translationY = v
        // 位置变了 → 按「1/9 线 / 顶端 / 0.8 / 0.6」重算每张卡的缩放，
        // 拖动中和松手回弹的每一帧都会走到这里，所以两个阶段都能看到"旋转"。
        updateTransforms()
    }

    /** 回位：手指拖动松手 → 软弹簧（540ms，略微过冲）。惯性甩过头不回位，走不到这里 */
    private fun releaseStackShift() {
        // 必须复位：否则下一次拖拽会从上一段的累积值起跳（一帧内直接弹到最大 → 卡片集体瞬移）
        shiftExcess = 0f
        if (abs(stackShift) < 1f) {
            shiftAnim?.cancel()
            setStackShift(0f)
            return
        }
        shiftAnim?.cancel()
        shiftAnim = ValueAnimator.ofFloat(stackShift, 0f).apply {
            duration = 540
            interpolator = android.view.animation.OvershootInterpolator(1.15f)
            addUpdateListener { setStackShift(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setStackShift(0f)
                    updateTransforms()
                }
            })
            start()
        }
    }

    private fun releaseSteep() {
        steepExcess = 0f
        if (steep <= 0.001f) return
        steepAnim?.cancel()
        steepAnim = ValueAnimator.ofFloat(steep, 0f).apply {
            duration = 540
            interpolator = SineOutInterpolator()
            addUpdateListener {
                steep = it.animatedValue as Float
                applySteep()
            }
            start()
        }
    }

    private fun applySteep() {
        val th = (tiltNow + steep * STEEP_TILT).coerceAtMost(82f)
        val dd = D_OVER_H * (1f - steep * STEEP_D)
        for (k in 0 until tabList.childCount) {
            val v = tabList.getChildAt(k)
            if (v is TrapezoidView && v !== hero) {
                // ⚠ 这里**不能**再写 `hEnd = cardH`：新模型下 `hEnd` 就是"这张卡当前多大"
                //   （`hEnd = k × cardH`），写死成 cardH 会把缩小的卡片瞬间放大回原尺寸。
                //   "整张图照画、不裁内容"这件事现在由 `hEnd` 本身保证（它和 `k` 同比）。
                v.theta = th                      // 真实俯角
                v.dOverH = dd                     // 相机拉近
                v.invalidate()
            }
        }
        // 俯角变了 → "缩到 s 需要推多远"也要跟着重算（反解里带着 sinθ）。
        // 不重算的话，越界俯身时缩小过的卡片尺寸会和曲线算出来的 s 对不上。
        updateTransforms()
    }

    // ------------------------------------------------------------------
    // 点「＋」：新标签页从屏幕下方飞入，一边飞一边由倒梯形拉成长方形
    // ------------------------------------------------------------------

    /**
     * 等新标签页真的画出来，再开始「飞入」。
     *
     * `playNewTabFly()` 会把「顶栏 + 网页」拍成一张站位合成图，整段飞入动画画的就是这张图。
     * 以前点「＋」是 `newTab("")` 之后**立刻**拍 —— 那一刻 WebView 刚 `loadDataWithBaseURL`，
     * 一个像素都还没画，拍到的就是一张纯白空页，于是整段动画看不到「个人收藏」的磁贴。
     * （删掉最后一个标签页走的是 `nextFrame(2)`，拍的时候页面已经画好了，所以那条路径一直正常。）
     * 现在改成：等 `onPageFinished` + 再等两帧真正绘制，最多等 500ms 兜底（网络/机型慢时也不至于卡住）。
     */
    /** 「＋」的序号：连点时只有最后一次会真的飞入，避免两次飞入互相打断 */
    private var flySeq = 0

    /**
     * 用**任意一张已经拍好的起始页**拼出"顶栏 + 收藏磁贴"的整页图。
     *
     * 所有新标签页的画面完全一样（我们自己的起始页），所以不必等新开的这个 WebView 画完 ——
     * 它自己画完要 400ms 以上，那段时间动画里就是一张空白页。
     * 返回 null 表示当前没有任何可用的起始页缩略图（比如所有标签都在真实网页上）。
     */
    /**
     * 起始页的**预缓存**：只存「页面区缩略图」（顶栏在拼帧时才画，几何永远是最新的）。
     *
     * 为什么需要：新标签页是**我们自己的起始页**，所有新标签页画面完全一样。
     * 而它自己那个 WebView 要 400ms 以上才画出来 —— 那段时间飞入动画里就是一张空白页。
     * 这里提前存一份"长得一模一样的"页面区图，飞入第一帧直接拿来用。
     */
    private var startPageThumb: Bitmap? = null
    /** 缓存是从哪一张 thumb 缩出来的：同一张就别重复处理，但**换了新图一定要重做** */
    private var startPageThumbSrc: Bitmap? = null

    /** 任何一次拍到起始页，都把页面区图刷进缓存（半分辨率，约 1MB） */
    private fun refreshStartPageCache(tab: Tab) {
        if (!tab.url.isBlank()) return
        val th = tab.thumb?.takeIf { !it.isRecycled && it.width > 0 } ?: return
        if (th === startPageThumbSrc) return
        startPageThumbSrc = th
        val old = startPageThumb
        startPageThumb = try {
            Bitmap.createScaledBitmap(th, (th.width / 2).coerceAtLeast(1), (th.height / 2).coerceAtLeast(1), true)
        } catch (e: Throwable) {
            null
        }
        if (old != null && old !== startPageThumb && !old.isRecycled) old.recycle()
    }

    /** 飞入第一帧：优先用缓存，其次现找一个起始页标签借 thumb（并顺手缓存） */
    private fun composeFrameFromAnyStartPage(): Bitmap? {
        startPageThumb?.takeIf { !it.isRecycled }?.let { return composeFrame(it) }
        val src = tabs.firstOrNull { it.url.isBlank() && it.thumb?.isRecycled == false } ?: return null
        refreshStartPageCache(src)
        val th = src.thumb?.takeIf { !it.isRecycled } ?: return null
        return composeFrame(th)
    }

    /**
     * 新页飞入途中：等它真的画出来，把便宜版的合成图换成真图（尺寸一样，直接换 bitmap）。
     * 换的时候卡片正在长大，视觉上就是"页面内容填上了"。
     */
    private fun upgradeNewTabFrame(tab: Tab, waited: Long) {
        if (overlayTabs.visibility != View.VISIBLE || !tabs.contains(tab)) return
        if (hero.visibility != View.VISIBLE) return
        if (tab.thumb?.isRecycled == false || tab.pageDone || waited >= 700L) {
            // 有缩略图就拼帧（几毫秒），比 `captureComposite()` 重画一遍 WebView（~50ms）划算
            val real = composeFrame(tab) ?: captureComposite()
            if (real != null && hero.visibility == View.VISIBLE) {
                val old = heroComposite
                heroComposite = real
                heroCompositeId = tab.id
                hero.bitmap = real
                hero.invalidate()
                if (old != null && old !== real && !old.isRecycled) old.recycle()
            }
            if (!tab.pageDone) return
            return
        }
        rootView.postDelayed({ upgradeNewTabFrame(tab, waited + 32L) }, 32)
    }

    private fun flyNewTabWhenReady(tab: Tab, waited: Long = 0L, seq: Int = 0) {
        if (overlayTabs.visibility != View.VISIBLE || !tabs.contains(tab)) {
            tabAnimating = false
            return
        }
        // 等待期间锁住卡片列表，避免连点「＋」重复起飞
        tabAnimating = true
        // 又点了一次「＋」：这一次作废，交给最新那次
        if (seq != 0 && seq != flySeq) {
            tabAnimating = false
            return
        }
        if (tab.pageDone || waited >= 500L) {
            nextFrame(2) {
                if (overlayTabs.visibility == View.VISIBLE && tabs.contains(tab) && (seq == 0 || seq == flySeq)) playNewTabFly()
                else tabAnimating = false
            }
            return
        }
        rootView.postDelayed({ flyNewTabWhenReady(tab, waited + 32L, seq) }, 32)
    }

    /**
     * 点「＋」：新页从屏幕下方以窄梯形飞入，一边上升一边拉宽、拉高、把倾角归位，
     * 飞到满屏的那一帧正好覆盖整个浏览区 —— 直接进入新标签页，不需要再点一次卡片。
     */
    /**
     * 点「＋」后的飞入。**立刻起飞**，不等页面画完。
     *
     * 原来的顺序是"轮询等 `pageDone`（实测新标签页要 500ms 才等到、而且常常等满超时）
     * → 再等 2 帧 → 再 `captureComposite()`（50ms）才开动画"，用户点一下要干等半秒多。
     * 现在：先用一张**便宜版**合成图（只有顶栏 + 底色，不画 WebView）当第一帧，
     * 动画马上开始；页面真画完了再把 `hero.bitmap` 换成真图 —— 换图的时刻卡片正在长大，
     * 看起来就是"页面在加载后填上了"，比干等 0.5 秒好得多。
     */
    private fun playNewTabFly(tab: Tab? = null) {
        if (heroFullH() <= 0 || pageW() <= 0) {
            finishDismiss()
            return
        }
        heroComposite?.recycle()
        // 第一帧优先用**已经渲染好的起始页**：
        // 新标签页就是我们自己的起始页（同一套收藏磁贴），随便找一张已经拍好的 thumb 拼起来即可，
        // 不用等这个新 WebView 自己画完（实测要 400ms+，那就是"动画里页面是空的"）。
        heroComposite = composeFrameFromAnyStartPage()
            ?: captureComposite(includePage = false)
            ?: captureComposite()
        heroCompositeId = currentTab().id
        val bmp = heroComposite
        if (bmp == null) {
            finishDismiss()
            return
        }
        tabAnimating = true
        // 新页是从整摞最前面飞进来的，所以图层高于所有卡片（k = 卡片数）
        if (!heroAsPage(bmp, tabList.childCount)) {
            finishDismiss()
            return
        }
        // 底边**钉在工具栏上沿**（= heroFullH() = 顶栏 + 网页），只在上面长高。
        //
        // 以前是「从屏幕外（rootView.height + 10dp）飞上来」，ty 和 hEnd 各自线性补间，
        // 两者相加在中途会冲到 1994px —— 比工具栏上沿 1830 深 164px，而工具栏只有 121px：
        // 整条底栏被新页内容盖住，等英雄视图收回去才重新露出来，
        // 视觉上就是「底栏淡出 → 空白一下 → 啪地出现」。
        // 退场那条路径没有这个问题，正因为它的底边始终 ≤ 1830。
        // 现在让底边全程恒等于 1830：ty = fullH − 本帧投影高度，
        // 两条路径的底边行为就完全一致了（底栏可以照常交叉淡变）。
        if (tab != null) upgradeNewTabFrame(tab, 0L)
        val h0 = dp(12f).toFloat()          // 起始是一条贴着工具栏的细边，不会「啪」地冒出一张卡片
        val k0 = 0.52f
        val th0 = tiltNow + 10f
        backdropFading = false
        applyHero(floatArrayOf(k0, 0f, h0, th0, heroFullH() - Trap.projectedHeight(h0, th0, D_OVER_H)))
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = apple                        // 长方形从工具栏上沿长出来：苹果曲线收得更稳
            val animId = beginHeroAnim(this)
            addUpdateListener {
                val p = it.animatedValue as Float
                // 每帧现取：万一这一帧内底栏/键盘导致浏览区高度变了，底边也会跟着贴住工具栏上沿，
                // 不会留一条露出真实页面的缝。
                val fullH = heroFullH().toFloat()
                val hEnd = h0 + (fullH - h0) * p
                val th = th0 * (1f - p)
                // 底边永远贴着工具栏上沿：ty 由本帧的投影高度反算，不再单独补间
                applyHero(
                    floatArrayOf(
                        k0 + (1f - k0) * p,
                        0f,
                        hEnd,
                        th,
                        fullH - Trap.projectedHeight(hEnd, th, D_OVER_H)
                    )
                )
                // 同退场：模糊层等新页快铺满时才淡
                if (!backdropFading && p > 0.86f) {
                    backdropFading = true
                    fadeTabBackdrop(0L, 90L)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!heroAnimCurrent(animId)) return
                    finishDismiss()
                    updateField()
                }
            })
            start()
        }
        // 与 startExit() 完全相同的一套：任务视图底栏独立淡出（200ms，delay 150，accel）。
        // 底栏下面就是浏览页真正的底栏，所以这一段淡出 = 「任务视图底栏渐隐 + 浏览页底栏渐显」，
        // 两个工具栏是一起交叉淡变的（不是硬切）。
        // 注：底边已经钉在工具栏上沿（见上面），新页永远不会盖住这 121px，淡变全程可见。
        fadeTabBottomBar(150)
    }

    // ---- 高斯模糊：自己实现的快速 box blur，与系统版本无关 ----
    /**
     * 模糊图的后台线程：只做纯像素运算，绝不碰 View。
     * 线程优先级压到 BACKGROUND —— 它和进场动画是并行的，如果按默认优先级跑，
     * 会跟渲染线程抢 CPU，动画反而更容易掉帧（实测 p90 从 25ms 涨到 29ms）。
     */
    private val blurWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "blur-worker")
    }
    /** 每次重算 +1，用来丢弃过期结果 */
    private var blurSeq = 0
    /** 正在后台算的那一轮的 key：同一份状态只跑一次，不把在飞的结果按 seq 丢掉 */
    private var blurPendingKey: String? = null

    private fun blurKeyOf(): String =
        "${currentTab().url}|$lastScrollTop|${rootView.width}|${rootView.height}|${topBar.height}"

    /**
     * 纯热身：后台把 fastBlur 跑一遍（空白图，结果直接扔掉）。
     * 只为了让 JIT 编译那几层循环、把 1/4 尺寸的两个 IntArray 缓冲分配好 ——
     * 冷启动时第一遍要一百多毫秒，不预热的话正好压在第一次打开任务视图上。
     * 注意这里**不碰任何 View**，也不写缓存 key，纯粹是把机器跑热。
     */
    private fun warmBlur() {
        val w = (rootView.width / 4).coerceAtLeast(1)
        val h = (rootView.height / 4).coerceAtLeast(1)
        runCatching {
            blurWorker.execute {
                try {
                    val blank = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val out = fastBlur(blank, 16, fullBufs)
                    blank.recycle()
                    if (out !== blank && !out.isRecycled) out.recycle()
                } catch (e: Throwable) {
                }
            }
        }
    }

    /**
     * 生成任务视图的背景模糊（1/4 分辨率 + 两遍 box blur），算完在 UI 线程替换。
     *
     * `frame` 有值就直接拿它当源 —— 那是这次 PixelCopy 到的整屏，和用户看到的逐像素一致：
     *  ① 比「把整棵树软件绘制一遍」准（WebView 的软件绘制会返回全白/全透明/偏移，不可信）；
     *  ② 省掉那次 UI 线程上的重活（多标签时能省几十毫秒）。
     * `frame` 为空时才退回软件绘制整棵树。
     */
    private fun prepareBlur(frame: Bitmap? = null) {
        val w = rootView.width
        val h = rootView.height
        if (w <= 0 || h <= 0) return
        val key = blurKeyOf()
        if (key == blurKey && tabBgBlur.drawable != null) return
        if (key == blurPendingKey) return        // 这一份已经在算了，等它
        val sw = (w / 4).coerceAtLeast(1)
        val sh = (h / 4).coerceAtLeast(1)
        val shot = if (frame != null && !frame.isRecycled) {
            try {
                Bitmap.createScaledBitmap(frame, sw, sh, true)
            } catch (e: Throwable) {
                treeShot(sw, sh)
            }
        } else treeShot(sw, sh)
        if (shot == null) return
        // ② 后台线程：blur + 裁底栏。纯像素运算（1/4 图约 270x540），搬到后台之后
        //    点任务视图那一帧就不用等它。
        val barH = tabBottomWrap.height.takeIf { it > 0 } ?: dp(44f)
        val seq = ++blurSeq
        blurPendingKey = key
        runCatching { blurWorker.execute {
            val blurred = try {
                fastBlur(shot, 16, fullBufs)
            } catch (e: Throwable) {
                null
            }
            shot.recycle()
            if (blurred == null) {
                // 算失败也要把这个"在飞"标记清掉，否则这一份状态以后永远命中
                // blurPendingKey 而不再重算（背景就一直是上一张）
                rootView.post { if (blurPendingKey == key) blurPendingKey = null }
                return@execute
            }
            val bar = cropBarStrip(blurred, barH, h)
            rootView.post {
                if (blurPendingKey == key) blurPendingKey = null
                if (isFinishing || isDestroyed) {
                    if (!blurred.isRecycled) blurred.recycle()
                    bar?.recycle()
                    return@post
                }
                if (seq != blurSeq) {                 // 已经有更新的一轮，丢掉这张
                    if (!blurred.isRecycled) blurred.recycle()
                    bar?.recycle()
                    return@post
                }
                val old = (tabBgBlur.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                tabBgBlur.setImageBitmap(blurred)
                if (old != null && old !== blurred && !old.isRecycled) old.recycle()
                if (bar != null) tabBarBlur.setImageBitmap(bar)
                blurKey = key
                alignBlurMatrix()
            }
        } }
    }

    /**
     * 从模糊图里裁出「任务视图底栏」那一条毛玻璃。
     * 坐标按整屏比例换算，所以 1/4 图和 1/8 图都能用同一个公式。
     */
    private fun cropBarStrip(blurred: Bitmap, barH: Int, screenH: Int): Bitmap? {
        if (barH <= 0 || screenH <= 0 || blurred.isRecycled) return null
        val y0 = ((screenH - barH).toFloat() / screenH * blurred.height).toInt()
            .coerceIn(0, (blurred.height - 1).coerceAtLeast(0))
        val bh = (barH.toFloat() / screenH * blurred.height).toInt().coerceAtLeast(1)
            .coerceAtMost(blurred.height - y0)
        if (bh <= 0) return null
        return try {
            Bitmap.createBitmap(blurred, 0, y0, blurred.width, bh)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 「柔性」背景：把整屏帧直接缩到 1/8、跑一遍半径 8 的 box blur。
     *
     * 等效到屏幕上约 64px 的模糊半径，和正式版（1/4 × 16，同样等效 64px）糊的程度一样，
     * 所以正式版算完换上去看不出跳变；而它只有 36k 个像素，热路径 1~2ms，
     * 冷启动也就几毫秒 —— 可以**同步**在浮层显示之前出图，保证第一帧背景就是模糊的。
     */
    private fun backdropQuick(frame: Bitmap): Pair<Bitmap, Bitmap?>? {
        val w = rootView.width
        val h = rootView.height
        if (w <= 0 || h <= 0 || frame.isRecycled) return null
        return try {
            val sw = (w / 8).coerceAtLeast(1)
            val sh = (h / 8).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(frame, sw, sh, true)
            val blurred = fastBlur(small, 8, quickBufs)
            if (small !== blurred && !small.isRecycled) small.recycle()
            val barH = tabBottomWrap.height.takeIf { it > 0 } ?: dp(44f)
            Pair(blurred, cropBarStrip(blurred, barH, h))
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 浮层显示之前调：如果当前状态还没有模糊图，就用这一帧同步铺一张「快速版」。
     * 不写 `blurKey` —— 正式版到了要能覆盖它。
     */
    private fun ensureBackdrop(frame: Bitmap?) {
        if (tabBgBlur.drawable != null && blurKey == blurKeyOf()) return
        if (frame == null || frame.isRecycled) return
        val q = backdropQuick(frame) ?: return
        tabBgBlur.setImageBitmap(q.first)
        q.second?.let { tabBarBlur.setImageBitmap(it) }
        alignBlurMatrix()
    }

    /** 模糊用的两个像素缓冲：按线程各一套，复用避免每次分配（1/4 图约 0.6MB） */
    private class BlurBufs {
        var a: IntArray? = null
        var b: IntArray? = null
    }

    /** 只在 blurWorker 上用 */
    private val fullBufs = BlurBufs()

    /** 只在 UI 线程上用（1/8 快速版） */
    private val quickBufs = BlurBufs()

    private fun fastBlur(src: Bitmap, radius: Int, bufs: BlurBufs): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        var a = bufs.a
        if (a == null || a.size != n) {
            a = IntArray(n)
            bufs.a = a
        }
        var b = bufs.b
        if (b == null || b.size != n) {
            b = IntArray(n)
            bufs.b = b
        }
        src.getPixels(a, 0, w, 0, 0, w, h)
        // 两遍（H+V）就够：1/4 分辨率、半径 16、最后再放大 4 倍，
        // 观感和四遍没有区别，CPU 少一半。
        blurH(a, b, w, h, radius)
        blurV(b, a, w, h, radius)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    /** 软件绘制整棵内容树，缩到 sw×sh（没有整屏帧时的兜底，也是首屏预热用的） */
    private fun treeShot(sw: Int, sh: Int): Bitmap? {
        val w = rootView.width
        val h = rootView.height
        if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0) return null
        return try {
            val shot = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val c = Canvas(shot)
            c.scale(sw.toFloat() / w, sh.toFloat() / h)
            for (i in 0 until rootView.childCount) {
                val ch = rootView.getChildAt(i)
                if (ch.visibility != View.VISIBLE) continue
                if (ch.id == R.id.overlayTabs || ch.id == R.id.overlayBookmarks ||
                    ch.id == R.id.overlaySuggest
                ) continue
                c.save()
                c.translate(ch.left.toFloat(), ch.top.toFloat())
                ch.draw(c)
                c.restore()
            }
            shot
        } catch (e: Throwable) {
            // 少数机型上 WebView 截不到内容，退化为纯色背景即可
            null
        }
    }

    private fun blurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sa += (p ushr 24) and 0xFF
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or
                        ((sg / div) shl 8) or (sb / div)
                val xo = (x - r).coerceIn(0, w - 1)
                val xi = (x + r + 1).coerceIn(0, w - 1)
                if (xi != xo) {
                    val po = src[row + xo]
                    val pi = src[row + xi]
                    sa += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                    sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                    sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                    sb += (pi and 0xFF) - (po and 0xFF)
                }
            }
        }
    }

    private fun blurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sa += (p ushr 24) and 0xFF
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or
                        ((sg / div) shl 8) or (sb / div)
                val yo = (y - r).coerceIn(0, h - 1)
                val yi = (y + r + 1).coerceIn(0, h - 1)
                if (yi != yo) {
                    val po = src[yo * w + x]
                    val pi = src[yi * w + x]
                    sa += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                    sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                    sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                    sb += (pi and 0xFF) - (po and 0xFF)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 浮层：书签 / 阅读列表 / 历史
    // ------------------------------------------------------------------
    /**
     * 书签页从屏幕下方平移飞入（纯位移，不做透视拉伸），像一张从底下推上来的纸。
     * 收起时原路平移回去，滑出后再隐藏。
     */
    private fun openBookmarks(mode: Int) {
        bmMode = mode
        bmEditing = false
        syncEditBtn()
        dismissFieldMenu()
        // 地址栏焦点 / 建议浮层要先收干净，否则会和书签页同时挂着（键盘还会把布局顶上去）
        if (urlField.hasFocus() || urlField.isFocusable) {
            releaseFieldFocus()
            hideKeyboard()
        }
        hideSuggestions()
        refreshBookmarks()
        overlayBookmarks.animate().cancel()
        overlayBookmarks.visibility = View.VISIBLE
        overlayBookmarks.translationY = overlayBookmarks.height.toFloat().takeIf { it > 0f }
            ?: rootView.height.toFloat()
        overlayBookmarks.animate()
            .translationY(0f)
            .setDuration(BOOKMARK_MS)
            .setInterpolator(apple)                 // 苹果默认曲线：进场尾巴更软
            .start()
        // 进场动画期间把另外两段也读进缓存：之后来回切分段零解析
        bmListHost.post { preloadBookmarkCaches() }
        // 进场动画结束后就把三张列表都建好（GONE 状态，不画也不量），
        // 这样用户第一次点某一段时不需要再 new ListView / 装箱数据
        bmListHost.postDelayed({ for (m in 0..2) ensureBookmarkList(m) }, BOOKMARK_MS + 80L)
    }

    private fun closeBookmarks() {
        if (overlayBookmarks.visibility != View.VISIBLE) return
        val h = overlayBookmarks.height.toFloat().takeIf { it > 0f } ?: rootView.height.toFloat()
        overlayBookmarks.animate().cancel()
        overlayBookmarks.animate()
            .translationY(h)
            .setDuration(BOOKMARK_MS)
            .setInterpolator(apple)                 // 出场同样用苹果曲线，一进一出对称且同长
            .withEndAction {
                overlayBookmarks.visibility = View.GONE
                overlayBookmarks.translationY = 0f
            }
            .start()
    }

    override fun onPause() {
        dismissFieldMenu()
        super.onPause()
    }

    /** 先把某一模式的列表读出来（只在缓存过期时才真的解析 JSON） */
    private fun loadBookmarkList(mode: Int): List<Link> = when (mode) {
        0 -> Store.bookmarks(this)
        1 -> Store.reading(this)
        else -> Store.history(this)
    }

    /** 缓存过期（Store 有新写入）就整批清掉，下次读时重新加载 */
    private fun ensureBookmarkCache() {
        if (bmCacheVer == Store.version) return
        bmCache.clear()
        bmCacheVer = Store.version
    }

    /** 打开面板后顺手把另外两段也读进缓存，这样切换时一次解析都不用做 */
    private fun preloadBookmarkCaches() {
        ensureBookmarkCache()
        for (m in 0..2) if (!bmCache.containsKey(m)) bmCache[m] = loadBookmarkList(m)
    }

    private fun refreshBookmarks() {
        ensureBookmarkCache()
        val mode = bmMode
        bmItems = bmCache.getOrPut(mode) { loadBookmarkList(mode) }
        for (m in 0..2) if (m == mode) ensureBookmarkList(m)
        for (m in 0..2) bmLists[m]?.visibility = if (m == mode) View.VISIBLE else View.GONE
        bmAdapters[mode]?.setData(bmItems, bmEditing)   // 数据/编辑态没变时它自己会早退
        bmLists[mode]?.setSelection(0)                  // 换分段回到顶部，和 iOS 一致
        updateSegment()
    }

    /** 某一段的 ListView：第一次用到时创建、填一次数据，之后一直留着复用 */
    private fun ensureBookmarkList(mode: Int) {
        if (bmLists[mode] != null) return
        val lv = ListView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        val ad = LinkAdapter()
        ad.setData(bmCache.getOrPut(mode) { loadBookmarkList(mode) }, bmEditing)
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, position, _ ->
            if (bmEditing) return@setOnItemClickListener
            val link = ad.itemAt(position) ?: return@setOnItemClickListener
            go(link.url)
            closeBookmarks()
        }
        lv.setOnItemLongClickListener { _, _, position, _ ->
            val link = ad.itemAt(position) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setMessage("删除「${link.title}」？")
                .setPositiveButton("删除") { _, _ ->
                    when (mode) {
                        0 -> Store.removeBookmark(this, link.url)
                        1 -> Store.removeReading(this, link.url)
                        else -> Store.history(this).toMutableList()
                            .apply { removeAll { it.url == link.url } }
                            .let { Store.clearHistory(this@MainActivity); it.forEach { l -> Store.addHistory(this@MainActivity, l) } }
                    }
                    refreshBookmarks()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        bmListHost.addView(lv)
        bmLists[mode] = lv
        bmAdapters[mode] = ad
        // 还藏着的时候先量一遍、摆一遍：行会在这一步就 inflate/绑定好，
        // 用户第一次切到这一段时 ListView 只是把已有的行露出来（省掉首帧 100ms+ 的首次布局）
        bmListHost.post {
            val w = bmListHost.width
            val h = bmListHost.height
            if (w > 0 && h > 0 && lv.width == 0) {
                lv.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                lv.layout(0, 0, w, h)
            }
        }
    }

    private fun updateSegment() {
        val btns = listOf(segBookmarks, segReading, segHistory)
        val blue = Color.parseColor("#007AFF")
        // 6 种组合（选中/未选中 × 左中右）建一次就够，切换时不再 new GradientDrawable
        val bgs = segBgs ?: HashMap<Int, GradientDrawable>().also { m ->
            for (i in 0..2) {
                m[i * 2] = segBg(false, i)
                m[i * 2 + 1] = segBg(true, i)
            }
            segBgs = m
        }
        btns.forEachIndexed { i, b ->
            b.background = bgs[i * 2 + if (i == bmMode) 1 else 0]
            b.setTextColor(if (i == bmMode) Color.WHITE else blue)
        }
    }

    private fun segBg(selected: Boolean, pos: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(if (selected) Color.parseColor("#007AFF") else Color.TRANSPARENT)
        d.setStroke(dp(1f), Color.parseColor("#007AFF"))
        val r = dp(5f).toFloat()
        d.cornerRadii = when (pos) {
            0 -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            2 -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        return d
    }

    private inner class LinkAdapter : BaseAdapter() {
        private var items: List<Link> = emptyList()
        private var editing = false

        fun itemAt(position: Int): Link? = items.getOrNull(position)

        /**
         * 换数据 / 换编辑态。**内容没变就直接返回** ——
         * 切分段时数据还是同一份（走缓存），于是 ListView 完全不用重新绑定任何行。
         */
        fun setData(list: List<Link>, edit: Boolean) {
            if (list === items && edit == editing) return
            items = list
            editing = edit
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_link, parent, false)
            val link = items[position]
            v.findViewById<TextView>(R.id.linkTitle).text = link.title
            v.findViewById<TextView>(R.id.linkUrl).text = link.url
            // 编辑模式：正文右侧让出「删除」的宽度（删除是浮在行尾的，不再用 weight 占位）
            val textBox = v.findViewById<LinearLayout>(R.id.linkText)
            val pe = if (editing) dp(84f) else dp(14f)
            if (textBox.paddingEnd != pe) {
                textBox.setPaddingRelative(textBox.paddingStart, textBox.paddingTop, pe, textBox.paddingBottom)
            }
            val del = v.findViewById<TextView>(R.id.btnRowDelete)
            del.visibility = if (editing) View.VISIBLE else View.GONE
            del.setOnClickListener { deleteLink(link) }
            // ListView 复用 item：编辑模式关掉后，旧的删除按钮必须解绑，否则点到会删错
            if (!editing) del.setOnClickListener(null)
            return v
        }

        private fun deleteLink(link: Link) {
            when (bmMode) {
                0 -> Store.removeBookmark(this@MainActivity, link.url)
                1 -> Store.removeReading(this@MainActivity, link.url)
                else -> Store.history(this@MainActivity).toMutableList()
                    .apply { removeAll { it.url == link.url } }
                    .let {
                        Store.clearHistory(this@MainActivity)
                        it.forEach { l -> Store.addHistory(this@MainActivity, l) }
                    }
            }
            refreshBookmarks()
        }
    }

    // ------------------------------------------------------------------
    // 浮层：建议 / 个人收藏
    // ------------------------------------------------------------------
    private fun setupOverlays() {
        btnDone.setOnClickListener { dismissTabs(-1) }
        btnNewTab.setOnClickListener {
            // 新建标签要换 WebView，先把地址栏的焦点和建议浮层收干净，
            // 否则焦点被系统挪回地址栏时会弹出整页建议（看起来就是白屏）
            if (urlField.hasFocus() || urlField.isFocusable) {
                releaseFieldFocus()
                hideKeyboard()
            }
            hideSuggestions()
            // 进场动画没跑完就点加号：先收掉英雄视图和起立动画，再把整摞**直接落到静止态**。
            // 不补这一步的话，卡片会停在"半立起来"的中间状态，接着新页飞入时它们就
            // 直接消失/乱闪（用户看到的就是"缩小的卡片突然没了"）。
            if (tabAnimating) {
                endHero()
                updateTransforms()
            }
            val nt = newTab("")
            val seq = ++flySeq
            // 新页从屏幕下方以倒梯形飞入，一边飞一边拉成长方形，覆盖满浏览区的那一帧就直接进入新页。
            // 但必须等这一页真的画出来再飞：飞入用的是「地址栏 + 网页」的站位合成图，
            // 拍早了网页还是全白（收藏磁贴还没画上去），整段动画就是一张空白页。
            // 删掉最后一个标签页走的是 nextFrame(2)，所以那条路径一直正常 —— 这里对齐成同一套。
            playNewTabFly(nt)
        }
        btnPrivate.setOnClickListener { togglePrivate() }
        tabScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateTransforms() }
        tabScroll.onOverscroll = { dy -> onOverscroll(dy) }
        tabScroll.onOverscrollEnd = { fromFling -> onOverscrollEnd(fromFling) }
        findViewById<TextView>(R.id.btnBmDone).setOnClickListener { closeBookmarks() }
        val btnBmEdit = findViewById<TextView>(R.id.btnBmEdit)
        btnBmEdit.setOnClickListener {
            bmEditing = !bmEditing
            btnBmEdit.setText(if (bmEditing) R.string.done else R.string.edit)
            refreshBookmarks()
        }
        segBookmarks.setOnClickListener { bmMode = 0; bmEditing = false; syncEditBtn(); refreshBookmarks() }
        segReading.setOnClickListener { bmMode = 1; bmEditing = false; syncEditBtn(); refreshBookmarks() }
        segHistory.setOnClickListener { bmMode = 2; bmEditing = false; syncEditBtn(); refreshBookmarks() }
        // 建议浮层「收起来」的静止位置就是下移到底的那一格 —— 也就是磁贴在起始页上的位置。
        // 摆在这里（而不是 0），第一次点地址栏时磁贴才能从页面上那个位置平滑地滑上去。
        sugScroll.translationY = suggestEnterOffset()
    }

    private fun syncEditBtn() {
        findViewById<TextView>(R.id.btnBmEdit)
            .setText(if (bmEditing) R.string.done else R.string.edit)
    }

    /**
     * 切换私密浏览模式。
     *
     * **只换主题，不销毁任何页面**：WebView、标签顺序、每页的滚动位置、前进后退历史都留着。
     * 以前这里 `tabs.forEach { it.web?.destroy() }` + `tabs.clear()` + `newTab("")`，
     * 切一次模式等于把用户手上的页面全清了（这也是 iOS 7 之后被吐槽最多的行为）。
     *
     * 私密模式真正要做的只有两件事：
     *  ① 不再往历史里写（`onPageFinished` 里由 `privateMode` 判断，已经是这样）；
     *  ② 界面换深色（顶栏 / 底栏 / 状态栏 / 任务视图底栏蒙版都由 `applyTheme()` 负责）。
     */
    private fun togglePrivate() {
        val enter = !privateMode
        val msg = if (enter) "进入私密浏览模式？当前页面全部保留，但不再记录浏览历史。"
        else "退出私密浏览模式？当前页面全部保留。"
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ ->
                privateMode = enter
                applyTheme()
                // 模糊图是「屏幕截图 + 栏的底色」算出来的，主题变了就该重算一次
                blurKey = null
                toast(if (enter) "已进入私密浏览模式" else "已退出私密浏览模式")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSuggestions() {
        // 可能正处在渐隐途中（快速点两下地址栏）：把渐隐掐掉、透明度复位，免得越开越淡
        suggestClosing = false
        overlaySuggest.animate().cancel()
        overlaySuggest.alpha = 1f
        overlaySuggest.visibility = View.VISIBLE
        // 点地址栏**一律先给「个人收藏」磁贴页**（不管栏里现在是网址还是空），
        // 只有用户真的开始输入字符时才切成搜索推荐 —— 见 TextWatcher 里那段。
        // 如果当前页本来就是起始页，底下已经有磁贴了：只做「上移/下移」（sugScroll 的位移），
        // 不再跑渐显+放大，否则同一批磁贴会叠着做两次出现动画。
        renderFavTiles(force = true)
        // 起始页上的磁贴比浮层里的低一个「个人收藏」标签的高度 —— 点地址栏时视觉上就是
        // 「它们往上挪了一点」。这里让浮层内容从那个旧位置滑上来，而不是硬跳上去。
        // 关键：不要每次把 translationY 硬设成起点。入场途中又被叫起来（比如快速点两下），
        // 硬设会把内容先弹回起点再往上走；从当前位置接着走才是连贯的。
        val off = suggestEnterOffset()
        sugScroll.animate().cancel()
        if (sugScroll.translationY >= off - 1f) sugScroll.translationY = off
        sugScroll.animate()
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(apple)
            .start()
    }

    /**
     * 关闭磁贴页 / 搜索推荐：**整体渐隐**（不再下滑）。
     *
     * 离开编辑态有两条路（回车进入新页面、返回键退出），两条都用同一个渐隐，观感一致。
     * 入场仍然是"从旧位置滑上来"，所以渐隐结束后要把 `translationY` 复位到"下移到底"，
     * 留给下一次入场（否则下次会从 0 开始，磁贴先出现在最终位置再往上跳）。
     */
    private fun hideSuggestions() {
        if (overlaySuggest.visibility != View.VISIBLE) return
        if (suggestClosing) return                 // 已经在渐隐：不要重来（会闪一下）
        suggestClosing = true
        overlaySuggest.animate().cancel()
        // **起始页特例**：屏幕上本来就有一模一样的磁贴，磁贴页只是把它们抬高了一点点。
        // 退出时如果整体渐隐，看起来就是"磁贴淡出又淡入"；这里改成**原路滑回去**：
        // 内容下移回 `suggestEnterOffset()`（= 页面里那批磁贴的位置），然后直接收起来，
        // 收起来的那一帧和页面完全重合，看不出接缝。
        if (isOnStartPage()) {
            sugScroll.animate().cancel()
            sugScroll.animate()
                .translationY(suggestEnterOffset())
                .setDuration(SUGGEST_EXIT_MS)
                .setInterpolator(apple)
                .withEndAction {
                    suggestClosing = false
                    overlaySuggest.visibility = View.GONE
                    overlaySuggest.alpha = 1f
                }
                .start()
            return
        }
        sugScroll.animate().cancel()               // 入场位移就地停住，交给整体渐隐
        overlaySuggest.animate()
            .alpha(0f)
            .setDuration(SUGGEST_EXIT_MS)
            .setInterpolator(apple)
            .withEndAction {
                suggestClosing = false
                overlaySuggest.visibility = View.GONE
                overlaySuggest.alpha = 1f
                sugScroll.translationY = suggestEnterOffset()   // 复位成「下移到底」，给下次入场
            }
            .start()
    }

    private fun renderSuggestions(q: String) {
        if (q.isBlank()) {
            renderFavTiles()
            return
        }
        suggestBox.removeAllViews()
        favGrid.visibility = View.GONE

        if (looksLikeUrl(q)) {
            addSection(getString(R.string.goto_site))
            addRow(normalizeUrl(q), null) { go(normalizeUrl(q)) }
        } else {
            addSection(getString(R.string.search_engine))
            addRow(q, null) { go(q) }
        }

        val matches = (Store.bookmarks(this) + Store.history(this))
            .filter { it.title.contains(q, true) || it.url.contains(q, true) }
            .distinctBy { it.url }
            .take(6)
        if (matches.isNotEmpty()) {
            addSection(getString(R.string.bm_and_history))
            matches.forEach { addRow(it.title, it.url) { go(it.url) } }
        }
    }

    /**
     * 只显示「个人收藏」磁贴页（点地址栏 / 清空输入时的默认内容）。
     *
     * 磁贴的出现动画：**渐显 + 从 0.8 放大到 1.0 同时进行，一起结束**。
     * 曲线用 `Decelerate(1.6)`（先快后慢），每张同时开始，不做错峰 —— 否则会像"依次加载"。
     */
    /** 磁贴页的"浅色背景"渐显（只淡背景，不动磁贴本身） */
    private fun fadeInSuggestBg() {
        val bg = overlaySuggest.background ?: return
        // 起始页：磁贴页的背景就是页面自己那张，不需要"渐显"—— 保持不透明，只让内容上下移动
        if (isOnStartPage()) {
            bg.alpha = 255
            overlaySuggest.invalidate()
            return
        }
        bg.alpha = 0
        ValueAnimator.ofInt(0, 255).apply {
            duration = 220
            interpolator = apple
            addUpdateListener {
                bg.alpha = it.animatedValue as Int
                overlaySuggest.invalidate()
            }
            start()
        }
    }

    private fun renderFavTiles(force: Boolean = false) {
        // 磁贴**已经在显示**（比如刚进编辑态、还带着全选丝带就按了删除）就什么都不做：
        // 不重建、更不重播"渐显+放大" —— 那批磁贴本来就在屏幕上，重来一遍反而像刷新了。
        if (!force && favGrid.visibility == View.VISIBLE &&
            favGrid.childCount > 0 && suggestBox.childCount == 0
        ) return
        suggestBox.removeAllViews()
        favGrid.visibility = View.VISIBLE
        // 当前页就是起始页时不跑"渐显+放大"：那批磁贴本来就在屏幕上，
        // 只要跟着 sugScroll 上移即可（否则会出现两套出现动画打架）。
        refreshFavGrid(animate = !isOnStartPage())
    }

    /** 当前页是否就是「个人收藏」起始页（此时屏幕上已经有那批磁贴） */
    private fun isOnStartPage(): Boolean {
        val u = currentTab().url
        return u.isBlank() || isStartPage(u)
    }

    private fun addSection(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.parseColor("#8E8E93"))
        tv.textSize = 11f
        tv.setPadding(dp(14f), dp(10f), dp(14f), dp(4f))
        suggestBox.addView(tv)
    }

    private fun addRow(title: String, sub: String?, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_suggestion, suggestBox, false)
        v.findViewById<TextView>(R.id.sugTitle).text = title
        if (!sub.isNullOrBlank()) {
            val s = v.findViewById<TextView>(R.id.sugSub)
            s.text = sub
            s.visibility = View.VISIBLE
            val icon = v.findViewById<ImageView>(R.id.sugIcon)
            icon.setImageResource(R.drawable.ic_bookmarks)
            icon.setColorFilter(Color.parseColor("#8E8E93"), PorterDuff.Mode.SRC_IN)
        }
        val divider = View(this)
        divider.setBackgroundColor(Color.parseColor("#E5E5EA"))
        v.setOnClickListener { onClick() }
        suggestBox.addView(v)
        suggestBox.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(0.5f)
        ).apply { marginStart = dp(14f) })
    }

    private fun refreshFavGrid(animate: Boolean = false) {
        favGrid.removeAllViews()
        Store.bookmarks(this).take(9).forEachIndexed { idx, link ->
            val v = layoutInflater.inflate(R.layout.item_favorite, favGrid, false)
            v.findViewById<TextView>(R.id.favTitle).text = link.title
            val icon = v.findViewById<ImageView>(R.id.favIcon)
            // 先铺首字母磁贴（与起始页同一套配色、同一尺寸 50dp），favicon 取到再覆盖 —— 国内取不到 Google 的图标
            icon.setImageBitmap(letterTile(link.title, dp(50f), idx))
            loadFavicon(link.url, icon)
            v.setOnClickListener { go(link.url) }
            // 注意行列顺序：LayoutParams(rowSpec, columnSpec)，权重必须给到「列」，
            // 配 width = 0 才是三列均分；写到行上会让每格宽度算成 0（推荐网址整片空白）。
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            favGrid.addView(v, lp)
            if (animate) {
                // 渐显 + 0.7→1.0 一起做：同一曲线、同一时长、同时开始，保证同时到位。
                // 2 秒 + 标准 ease（慢起 → 微加速 → 长收尾），出现得很温柔；
                // 不用 Decelerate —— 那样 2 秒里前半秒就走完了，后面像在"卡着不动"。
                v.scaleX = FAV_TILE_IN_SCALE
                v.scaleY = FAV_TILE_IN_SCALE
                v.alpha = 0f
                v.animate().cancel()
                v.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(FAV_TILE_IN_MS)
                    .setInterpolator(ease)
                    .start()
            }
        }
    }

    /** 首字母渐变磁贴（与起始页一致），取不到 favicon 时兜底 */
    private fun letterTile(title: String, size: Int, idx: Int): Bitmap {
        val pal = FAV_PALETTE[idx % FAV_PALETTE.size]
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
        val r = size * 0.18f
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, p)
        p.shader = null
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = size * 0.50f      // 起始页 HTML 里是 25px 字 / 50px 磁贴，比例 0.5
        // 字重必须跟着起始页走：那边 `.ic{font-weight:300}`，WebView 映射到 Roboto Light；
        // 这里默认 Paint 是常规字重（400），同一个字母的墨量多出约 1.4 倍，
        // 两个界面来回切就能看出磁贴里的字「一会儿粗一会儿细」。
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val fm = p.fontMetrics
        val letter = title.trim().firstOrNull()?.toString()?.uppercase() ?: "·"
        c.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }

    /**
     * 进度条收尾：先让 100% 真正画出来，停 200ms，再淡出 180ms 才隐藏。
     *
     * 以前 `onProgressChanged` 一到 100 就 `visibility = GONE`，进度条根本画不出满格，
     * 看起来就是「加载完 → 啪一下消失」。iOS 7 Safari 是走到头、停一下、再淡掉。
     */
    private fun finishProgress() {
        progress.animate().cancel()
        progress.alpha = 1f
        progress.visibility = View.VISIBLE
        progress.progress = 100
        val seq = ++progressSeq
        progress.postDelayed({
            if (seq != progressSeq) return@postDelayed
            progress.animate().alpha(0f).setDuration(180).withEndAction {
                if (seq != progressSeq) return@withEndAction
                progress.visibility = View.GONE
                progress.alpha = 1f
            }.start()
        }, 200)
    }

    private fun loadFavicon(url: String, into: ImageView) {
        val host = hostOf(url)
        if (host.isBlank()) return
        Thread {
            try {
                val conn = java.net.URL(
                    "https://www.google.com/s2/favicons?domain=$host&sz=64"
                ).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                conn.disconnect()
                if (bmp != null) runOnUiThread { into.setImageBitmap(bmp) }
            } catch (e: Exception) {
                // 取不到图标就保留占位图
            }
        }.start()
    }

    // ------------------------------------------------------------------
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            // 分享面板在最上层：先收它
            overlayShare.visibility == View.VISIBLE -> closeShare()
            // 编辑态（磁贴页 / 搜索推荐挂着）：一次返回全部收起
            overlaySuggest.visibility == View.VISIBLE || fieldExpanded -> exitFieldEdit()
            overlayBookmarks.visibility == View.VISIBLE -> closeBookmarks()
            overlayTabs.visibility == View.VISIBLE -> dismissTabs(-1)
            currentTab().web?.canGoBack() == true -> currentTab().web?.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        runCatching { blurWorker.shutdownNow() }
        dismissFieldMenu()
        hero.bitmap = null
        tabs.forEach { it.web?.destroy() }
        super.onDestroy()
    }
}
