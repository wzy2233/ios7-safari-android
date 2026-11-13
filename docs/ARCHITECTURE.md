# iOS 7 Safari 浏览器（Android 复刻）— 架构 · 动画 · 几何 · 需求实现说明

> 本文档描述 [README](../README.md) 对应的这份代码；文中 `MainActivity.kt` 等路径均相对于仓库根目录。
> 包名：`com.ios7.safari`　语言：Kotlin（纯原生 View，无 Compose / 无第三方 UI 库）
> 本文档以**当前代码**为准逐行核对整理，末尾附「已知问题 / 死代码 / 待办」与「修订记录」。
> 目标：需求 → 实现 → 具体数值 → 为什么这么算；改任何一处都能查到会牵连什么。

---

## 0. 运行环境与单位换算

| 项 | 值 |
|---|---|
| 目标设备（实测） | 1080 × 2160 px，density 440 → **1dp = 2.75px** |
| 可用宽度 | 392.7dp |
| 系统状态栏 | 77px（≈28dp），**由系统绘制**，窗口在其下方 |
| 系统导航栏 | 132px（48dp） |
| `rootView`（内容区） | 1080 × **1951** px |
| 另一台测试机（荣耀 BVL-AN00） | 1264 × 2800，density 560（1dp=3.5px），361×800dp |
| 编译 | `gradle assembleDebug --offline`（Gradle 8.9，JAVA_HOME=Android Studio jbr） |

> 所有 dp 值都是设计值；`dp(v)` 是 `toInt()` **截断**，所以 34dp→93px、44dp→121px、18dp→49px。

---

## 1. 一句话架构

**一个 Activity + 三张全屏浮层 + 一个「英雄视图（TrapezoidView）」承载所有页面⇄卡片的形变动画。**

- 主界面是「顶栏 44dp / WebView weight=1 / 底栏 44dp」的垂直三段式；
- 任务视图、书签页、搜索建议层都是 `FrameLayout` 里叠加的 `include` 浮层；
- 「页面变倒梯形卡片」不是对 WebView 做变换，而是：截图 → 交给 `TrapezoidView` 逐条带透视绘制 → 每帧改 `k / hStart / hEnd / theta / translationY / overlayFrac`；
- 因此动画期间 WebView 不动、不重绘，形变是纯位图运算。

---

## 2. 文件职责地图

```
app/src/main/java/com/ios7/safari/
  MainActivity.kt        ~3400 行，全部业务与动画
  TrapezoidView.kt       透视绘制：TrapezoidView（英雄/卡片共用）、Trap（投影数学）、CardTileView（废弃）
  TabScrollView.kt       任务视图滚动容器：越界回调 + 自定义惯性（初速打折、指数衰减、撞边冲过头）
  ObservableWebView.kt   网页：滚动回调 + 同一套自定义惯性（高速甩动时接管）
  Store.kt               书签/阅读列表/历史（SharedPreferences + JSON）
  SineOutInterpolator.kt f(t)=sin(t·π/2)
res/layout/
  activity_main.xml      主界面 + 三个 include 浮层
  overlay_tabs.xml       任务视图：tabChrome(模糊) → tabFade(卡片列表+底栏) → heroCard
  overlay_bookmarks.xml  书签页
  overlay_suggestions.xml 建议层：favGrid(3×3) + suggestBox
  item_tab_card.xml      卡片平面图：标题条(cardHeader) + 缩略图(cardThumb)
  item_favorite.xml      收藏磁贴：50dp 图标 + 6dp 间距
  item_suggestion.xml / item_link.xml / popup_text_menu.xml
res/mipmap-*dpi/         图标：ic_launcher.png / ic_launcher_round.png / ic_launcher_foreground.png
res/mipmap-anydpi-v26/   自适应图标 ic_launcher.xml / ic_launcher_round.xml
```

---

## 3. 图层树与坐标系统

```
rootView (FrameLayout, 1080×1951, y=0 即顶栏上沿)
├── webContainer (FrameLayout, marginTop=24dp, match_parent)   ← **固定 viewport**
│   └── WebView (match_parent)     布局 y: 66 .. 1951（永远不变）
│                                  可见 y: 66+20dp·(1−p) .. 由底栏决定
├── LinearLayout (vertical)  ← 栏层，叠在网页之上；改它的高度碰不到 WebView
│   ├── topBar (44dp→24dp)   y: 0 .. 121        clickable=true（吃掉空白处的触摸）
│   │   ├── LinearLayout(paddingStart/End=4dp)
│   │   │   ├── fieldBox (34dp→24dp, weight=1)
│   │   │   │   ├── urlField (18sp→14sp, padStart 14dp / padEnd 30dp)
│   │   │   │   └── btnReload (22dp, marginEnd 8dp)
│   │   │   └── btnCancel (18sp, padStart 8dp / padEnd 4dp, gone)
│   │   ├── hairline 0.5dp
│   │   └── progress 2.5dp
│   ├── View (weight=1)      ← 只为把底栏顶到底部；**不能 clickable**，否则网页收不到触摸
│   └── bottomWrap (44dp→0)  y: 1830 .. 1951    clickable=true
├── overlayTabs → overlay_tabs.xml（背景透明）
│   ├── tabChrome（高度 = webBottomVisual() = 1830）
│   │   ├── tabBgBlur（1/4 模糊图, MATRIX 1:1 放大）
│   │   └── View #B3000000（压暗蒙版：深黑 70%）
│   ├── tabFade
│   │   └── LinearLayout(vertical)
│   │       ├── TabScrollView (weight=1, clipToPadding=false)
│   │       │   └── tabList (paddingTop=topPad, paddingBottom=70dp)
│   │       │        ├── TrapezoidView(卡片 0..n-1)   ← viewOrder[0] = 当前页
│   │       │        └── heroCard（动画期间 addView 到这里，末尾）
│   │       └── tabBottomWrap (44dp)：tabBarBlur + tabBarScrim + tabBottomBar + btnNewTab + hairline
│   └── heroCard（XML 初始位置）
├── overlayBookmarks → overlay_bookmarks.xml
└── overlaySuggest   → overlay_suggestions.xml (marginTop 44dp)
```

**三套坐标**

| 坐标系 | 原点 | 用在哪 |
|---|---|---|
| 屏幕/窗口 | `rootView` 左上（系统状态栏下方） | `cardTopY()`、`shrinkLineY()`、`heroTopY()`、hero 的 `translationY` |
| `tabList` 内容 | tabList 内容左上（含 paddingTop） | `hero.top`、`heroAsPage()` 的 `y / contentTop` |
| `tabScroll` 视口 | tabScroll 左上 | `cardTopY = tabScroll.top + c.top − scrollY + tabList.translationY` |

换算：`屏幕y = tabScroll.top + (view.top − scrollY) + view.translationY + tabList.translationY`

### 3.1 固定 viewport：栏怎么动都不碰 WebView（2026-10-24 改造）

**问题**：改造前 `webContainer` 是 `LinearLayout` 里 `weight=1` 的节点，顶栏/底栏高度一变它跟着变
—— 也就是**动画的每一帧都在改 WebView 的 viewport**，页面每帧重排一次（重页面 20~50ms/帧）。
这是「地址栏/底栏收缩时跟着网页一起卡」的根，`setBars()` 里那些「值没变就不写」的优化救不了。

**结构**：网页与栏拆成上下两层（图层树见上）。
- `webContainer`：`match_parent` + `layout_marginTop = 24dp`，高度 = 屏高 − 24dp，窗口不缩放期间**恒定**；
- 栏层 `LinearLayout`：`topBar` / `weight=1` 的空 `View` / `bottomWrap`，叠在网页之上。
  `setBars()` 只改这三个 View 的高度，碰不到 WebView。

**值**（`companion object`，四条必须自洽）

| 常量 | 值 | 含义 |
|---|---|---|
| `BAR_H_EXPANDED_DP` | 44 | 展开态顶栏/底栏高度 |
| `BAR_H_COLLAPSED_DP` | 24 | 收起态顶栏高度 = `webContainer` 的 `marginTop` |
| `BAR_SLIDE_DP` | 20 | 44−24：viewport 跟着栏滑动的距离 |
| `BOTTOM_INSET_DP` | 64 | 44+20：页面底部要补的内边距（见"代价"） |
| `EXIT_CARDS_BUDGET_MS` | 300 | 退场时「其他卡片让路」的总时间预算（英雄视图 490ms 收尾） |
| `DRAG_SWAP_ON` / `DRAG_SWAP_OFF` | 0.62 / 0.42 | 换位判定的阈值与回退阈值（滞回，单位＝格） |
| `DRAG_YIELD_AT` / `DRAG_YIELD_BACK` | 0.30 / 0.10 | 被让开的卡开始沉下去 / 退回原位的阈值 |
| `DRAG_FILL_AT` / `DRAG_FILL_BACK` | 0.85 / 0.60 | 它开始补位 / 退回让开态的阈值 |
| `DRAG_YIELD_MS` / `DRAG_FILL_MS` | 300 / 320 | 让开 / 补位各自的补间时长（自己的节奏，与手指速度无关） |
| `DRAG_FOLLOW_TAU` | 0.06 | 手里那张跟手指的时间常数（秒）：越大越有重量 |
| `DRAG_SETTLE_MS` | 260 | 松手后「自己归位」的时长 |
| `DRAG_DIP_DOWN_EXTRA` | 0.45 | 往下让的额外深度（单位＝一格），在几何下限 `H + 0.065·step` 之上再加 |

**算法**

```
setBars(p):
    topBar.height     = dp(44 − 20p)
    bottomWrap.height = dp(44(1 − p))
    webContainer.translationY = dp(20)·(1 − p)     ← 唯一跟随栏的东西，纯变换、不触发布局
        ⇒ 可见上沿 = 66 + 55(1−p) = 121 − 55p = 顶栏下沿 ✓
        ⇒ 可见下沿 = rootView.height − paddingBottom − bottomWrap.height
        ⇒ 布局下沿 = 1951 + 55(1−p) ≥ 可见下沿，永远不露缝
```

**几何口径**（改代码必须按这张表取值，不要直接读 `webContainer.top/bottom/height`）

| 需要 | 用 | 展开 / 收起 | 为什么不能读容器 |
|---|---|---|---|
| 网页可见上沿 | `webTopVisual()` = `webContainer.top + translationY` | 121 / 66 | `webContainer.top` 恒为 66（布局位置） |
| 网页可见下沿 | `webBottomVisual()` = `rootView.height − paddingBottom − bottomWrap.height` | 1830 / 1951 | `webContainer.bottom` = 2006（伸到屏幕外） |
| 网页可见高度 | `webVisibleH()` | 1709 / 1885 | `webContainer.height` 恒为 1885（viewport） |
| 取景框高度 | `chromeH()` = `webBottomVisual()`（`chromeTop()≡0`） | 1830 | 同上 |
| 英雄视图高 | `heroFullH()` = `chromeTop()+chromeH()` | 1830 | 同上 |
| 缩略图裁切 | 上沿 `webTopVisual()`、高 `webVisibleH()` | — | 用容器高度会越界抛异常 |

用到这 6 个口径的地方：`grabScreenFrame`/`captureComposite`/`composeFrame`（取景）、
`updateThumbFromFrame`（缩略图）、`applyChromeHeight`（模糊层高度）、`heroAsPage`（英雄视图尺寸）。

**实测不变量**（2026-10-24 14:26，1080×2160 机器，rootView 1951）

| 观察点 | 展开态 p=0 | 收起态 p=1 |
|---|---|---|
| `topBar` bounds | [0,77]–[1080,198]（121px） | [0,77]–[1080,143]（66px） |
| `webContainer` 可见 bounds | [0,198]–[1080,2028] | [0,143]–[1080,2028] |
| WebView 布局 bounds | [0,198]–[1080,2084] | [0,143]–[1080,2028] |
| `bottomWrap` | [0,1907]–[1080,2028] | 高度 0（不参与层级） |
| `tabChrome` 高 | 1830 | 1830 |

**代价与对策（必须知道的两件事）**

1. **栏展开时页面底部有 64dp 在底栏后面/屏幕外**（viewport 比可见区高）。
   长页面无所谓（往下滑栏就收起来，最后一截就露出来）；但**短页面**滚不动、栏也就收不起来，
   最下面一截会永远看不到。对策：`injectBottomInset()` 在 `onPageFinished` 给 `body` 补
   `padding-bottom: 64dp`（CSS px 在 device-width 页面上等于 dp，用 `devicePixelRatio` 换算；
   `data-ios7pad` 防止重复叠加）。
2. **`position:fixed; bottom:0` 的元素**在栏展开时会贴着 viewport 底部（= 屏幕下方 20dp），被底栏盖住，
   要滑动让栏收起才看得见 —— 这是"内容在栏下面"的必然结果（移动版 Safari / Chrome 同理）。

**触摸必须透过去**：`topBar` / `bottomWrap` 要 `clickable=true`（不透明的条，空白处若不吃事件，
点击会落到下面网页上）；中间那段 `weight=1` 的空 `View` **绝不能** clickable，否则整个浏览区收不到滑动
（错了的表现就是「网页完全不能滚」）。

---

## 4. 状态模型

```kotlin
private class Tab {
    var title: String; var url: String
    var thumb: Bitmap?          // 页面截图（卡片缩略图 + composeFrame 素材）
    var web: ObservableWebView?
    var thumbVer: Int           // 截图版本号 → cardCache key
    var pageDone: Boolean       // onPageFinished（点＋飞入前要等它）
    val id: Int                 // 单调递增，判身份
}
```

| 字段 | 含义 |
|---|---|
| `tabs` / `currentIndex` | 标签列表 / 当前页；视觉顺序 = `viewOrder`，`viewOrder[0]` 永远当前页 |
| `compact` (0..1) | 顶栏/底栏收缩进度 |
| `barAccum` / `barLockUntil` | 滚动滞回累加 / 冷却截止时间 |
| `cardW/H/RenderH/HeaderH` | 1032 / 1755 / 1312 / 115（左右各留 8dp；H = 整页，不裁切） |
| `tiltNow` | 当前倾角（28°..41°，标签越多越陡） |
| `cardStep` | 相邻卡片上沿间距（4 张/屏 → 457） |
| `heroBaseY` | 英雄视图「应当在」的 top（不读 `hero.top`） |
| `heroComposite` / `heroCompositeId` | 起飞帧 / 属于哪个标签（防串页） |
| `heroAnim` / `heroAnimId` | 当前英雄动画 / 代号（连点保护） |
| `blurKey` / `blurSeq` | 模糊图指纹 / 过期令牌 |
| `stackShift` / `shiftExcess` / `shiftAnim` | 橡皮筋位移（px）/ 累积量 / 回位动画 |
| `steep` / `steepExcess` | 越界"俯身"强度 0..1 / 累积量 |
| `flySeq` | 「＋」序号，连点只让最后一次起飞 |
| `progressSeq` | 进度条收尾令牌 |
| `tabAnimating` / `tabClosing` / `dragView` | 动画锁 / 删除锁 / 正在拖的卡片 |

---

## 5. 常量总表

### 5.1 MainActivity

| 常量 | 值 | 作用 |
|---|---|---|
| `TILT_BASE` | **42°** | 基础倾角（静止）。真机视频实测反解是 ≈54°，试过偏扁，按手感留在 42° |
| `TILT_EXTRA` | 13° | 标签变多再加（6 个 → 41°） |
| `TILT_FULL_N` | 6 | 达到最大俯角的标签数 |
| `D_OVER_H` | 2.6 | 相机距离 = 2.6 × 卡片高 |
| `MAX_VISIBLE_CARDS` | **4** | 一屏最多 4 张（决定最小间距 457） |
| `STEP_MAX_RATIO` | 0.40 | 标签少时间距上限 = 屏高 40% |
| `CARD_PEEK_DP` | 88dp | 单标签间距 |
| `SHRINK_EDGE` / `SHRINK_MIN` | 0.8 / 0.6 | 滚出 1/9 线后的两段缩放端点（段内走半余弦，端点值不变） |
| `SHRINK_SPAN_RATIO` | 0.30 | 第二段行程 = 卡片渲染高 30% |
| `SHRINK_SMOOTH_TAU_MS` | 35 | 缩放的低通时间常数（≈两帧），越大越"拖" |
| `SHRINK_CREST` | 0.22 | 转盘最高点的爬升量 = 第二段行程 × 它（顶到头后顶边还能再爬这么多就停住） |
| `SHRINK_ROLL` | 0.45 | 缩到 0.6 之后「滚下」的行程 = `cardStep` × 它（手指再推就往下走，反向原路返回） |
| `STEEP_TILT` / `STEEP_D` | 10° / 0.25 | 越界"俯身"：俯角 +10°、相机拉近 25% |
| `STEEP_RUBBER_DP` | 150dp | 俯身强度的阻尼行程 |
| `FORCE_PINCH` | true | 强制双指缩放 |
| `BAR_H_EXPANDED_DP` | 44 | 展开态顶栏/底栏高（= `webContainer` 收起态也可用的基准） |
| `BAR_H_COLLAPSED_DP` | 24 | 收起态顶栏高 = `webContainer.layout_marginTop`（**两者必须相等**） |
| `BAR_SLIDE_DP` | 20 | 44−24：`webContainer.translationY` 的行程 |
| `BOTTOM_INSET_DP` | 64 | 44+20：注入页面的 `padding-bottom` |
| `CARD_BACK_Z` / `HERO_Z` / `CARD_FRONT_Z` | 0 / 1 / 2 | 三层 elevation |
| `FAV_PALETTE` | 6 组渐变 | 与起始页 HTML 的 palette 一一对应 |

### 5.2 TabScrollView（任务视图惯性）

| 常量 | 值 | 说明 |
|---|---|---|
| `FLING_SCALE` | 0.62 | 初速打折（更慢更稳） |
| `FLING_DECAY` | 3.4 /s | 指数衰减 |
| `FLING_MIN` | 60 px/s | 低于即停 |
| `EDGE_FEED` | 0.55 | 撞边时剩余位移转成"冲过头"的比例 |
| `EDGE_DECAY` | 9 /s | **只在撞边阶段**用的大衰减（0.3~0.4s 吃完） |

### 5.3 ObservableWebView（网页惯性）

| 常量 | 值 | 说明 |
|---|---|---|
| `FLING_SCALE` | 0.85 | 网页留更多初速 |
| `FLING_DECAY` | 1.8 /s | 网页衰减更慢（掉速太快会"发沉"） |
| `FLING_MIN` | 60 px/s | 停止阈值 |
| `TAKE_OVER_MIN` | 1100 px/s | 低于此速度不接管（慢拖/点击走原生） |

### 5.4 其它

| 项 | 值 |
|---|---|
| 地址栏圆角矩形 | 宽 = 行内宽 1058px（385dp），高 34dp→24dp，圆角 8dp |
| 网址字号 | 18sp → 14sp（收缩态） |
| 取消按钮 | 18sp，padStart 8dp + padEnd 4dp（两侧视觉间距都是 8dp） |
| 收藏磁贴 | 50dp 图标 / 6dp 间距 / 92dp 行距 / 左右 8dp / 字号 0.5 / Light 字重 |
| 建议层位移 | `suggestEnterOffset = 40.7dp` |
| 模糊 | 1/4 分辨率、半径 16、**2 遍**（H+V）、后台线程 |
| 进度条 | 到 100% → 停 200ms → 淡出 180ms → GONE |
| 图标 | PNG 48/72/96/144/192 + 自适应前景 108~432（盘面 64/108 画布） |

---

## 6. 功能清单

### 6.1 地址栏

| 功能 | 实现 |
|---|---|
| 只显示 host（去 `www.`） | `displayUrl()`；起始页显示空 |
| 点地址栏：左对齐 + 蓝色丝带铺开 | 触摸放开焦点 → `alignFieldLeft()` + `growRibbon()`（300ms） |
| 非编辑态水平居中 | `centerField()` → `centerPad(w)`，非编辑态用 `idleFieldW()` 终值宽度 |
| 编辑态左对齐 | `padStartBase = 14dp` |
| 判定网址 or 搜索 | `looksLikeUrl()`；默认必应 `searchUrl()` |
| 回车/搜索键/GO 提交 | EditorAction + KeyListener |
| 加载中变「✕」 | `syncReloadIcon()`：`loading && !isOnStartPage()` 才显示 `ic_stop`，否则 `ic_reload` |
| 起始页也显示「刷新」 | 起始页是本地 HTML、没有"正在加载"，所以**常驻 `ic_reload`**；点它 = `showStartPage()` 重新渲染（顺带刷新收藏） |
| 取消按钮 | `animateFieldEnter/Exit()`（见 7.3） |
| iOS 文本菜单 | 自建 `PopupWindow`，屏蔽系统 ActionMode |
| 光标/无手柄 | `bg_cursor` + `handle_none` |

### 6.2 网页

| 功能 | 实现 |
|---|---|
| iOS 7 UA | `settings.userAgentString` |
| 强制双指缩放 | `forcePinch()` 改写 viewport |
| 边缘右滑后退 / 左滑前进 | `attachEdgeGesture()`（边缘 36dp，横向 >55dp 且 >1.5×纵向） |
| 自定义惯性 | `ObservableWebView.onTouchEvent` 接管高速甩动（见 7.9） |
| 长按链接菜单 / target=_blank / 下载 / 外链 | `setOnLongClickListener` / `onCreateWindow` / `setDownloadListener` / `handleUrl()` |
| 加载失败页 | `onReceivedError` → `errorPage()` |
| 历史记录（私密不记） | `onPageFinished` → `Store.addHistory` |
| 进度条 | `onProgressChanged` + `finishProgress()` |

### 6.3 任务视图

| 功能 | 实现 |
|---|---|
| 进入 / 退出 | `openTabs()` → `startTabsEnter()` → `playEnter()`；`dismissTabs()` → `startExit()` |
| 卡片堆叠（当前页在最后面） | `refreshTabs()`，`viewOrder` |
| 标题条 + 居中标题 + 关闭叉 | `renderCard()` 离屏绘制，标题手工 Canvas 绘制 |
| 点卡片 → 拉伸成整页 | `CardTouch.onSingleTapConfirmed` |
| 「完成」 | `btnDone` → `dismissTabs(-1)` |
| 点 × 关闭 | 命中区 `x<38dp && y<38dp` |
| 左滑删除 / 上下重排 | 长按 `beginDrag` → `dragAxis` 1/2 → `endDrag`/`commitReorder`；左滑**不淡出**、整张跟手指；纵向换位是「三态自走」的木板交换（见 7.15） |
| 「＋」新建 | `newTab` → `flyNewTabWhenReady` → `playNewTabFly` |
| 删掉最后一个 = 走「＋」同一套 | `removeTabAt` 清空 → `nextFrame(2){ playNewTabFly() }` |
| 私密模式 | `togglePrivate()` → 只 `applyTheme()`，**页面全部保留**（不销毁 WebView / 不清标签） |
| 删除后的补位 | `snapshotPositions()` → `removeTabAt()` → `rebuildAnimated()` |
| 一屏 4 张 | `MAX_VISIBLE_CARDS=4` + `want=(areaH/n.coerceAtMost(4))` |
| 滚出 1/9 线缩放 | `updateTransforms()`（两段） |
| 拖到极限：整摞让位（橡皮筋） | `onOverscroll` → `stackShift` |
| 顶边下拉：卡片变稀疏（上小下大） | `pad*1.2*(1−cos(i/(n−1)·π/2))` |
| 拖到极限：真实 3D 俯身 | `theta+10°`、`dOverH×(1−0.25·steep)` |
| 惯性滚动 | `TabScrollView` 自定义 fling（撞边冲过头后**不回位**） |

### 6.4 起始页 / 建议 / 书签 / 分享

本地 HTML 起始页（9 磁贴、favicon 失败露字母）、建议层（搜索行 + 书签历史匹配）、
书签三段控件 + 编辑删除、分享 PopupMenu（5 项）。

**建议层显示什么，按「有没有真的输入字符」分**：

| 触发 | 内容 |
|---|---|
| 点地址栏（`showSuggestions()`） | **一律先给「个人收藏」磁贴页**（`renderFavTiles()`），不管栏里现在是网址还是空 |
| 用户输入了字符（`TextWatcher`） | 搜索推荐：`用必应搜索 / 转到网址` + 书签历史匹配（`renderSuggestions(q)`） |
| 用户把内容删空 | 退回磁贴页；**如果磁贴本来就在显示，什么都不做**（`renderFavTiles()` 里判断 `favGrid` 可见且非空就直接 return，不重建、不重播出现动画） |

磁贴出现动画（`refreshFavGrid(animate)`）：每张 **alpha 0→1** 与 **scale 0.7→1.0 同时进行**，
同一曲线同一时长（`FAV_TILE_IN_MS = 1400ms` + 标准 `ease` P(0.4,0,0.2,1)），全部同时开始 ——
保证"渐显"和"变大"一起到位，也不做错峰（错峰看着像依次加载）。

> 一秒以上的量级上不能用 `Decelerate`：那样前半秒就走完了，剩下的时间像卡着不动。
> 标准 `ease`（慢起 → 微加速 → 长收尾）才是"温柔"的曲线。两个量都在常量里：`FAV_TILE_IN_MS` / `FAV_TILE_IN_SCALE`。

**但当前页本来就是起始页时不播这个动画**（`isOnStartPage()` → `animate = false`）：
那时屏幕上已经有那批磁贴，浮层里的磁贴只要跟着 `sugScroll` **上移/下移**就够了 ——
否则同一批磁贴会叠着做两次"出现"，看起来像抖了一下。

---

## 7. 动画系统（驱动、参数、曲线）

曲线：`ease=P(0.4,0,0.2,1)`、`decel=P(0,0,0.2,1)`、`accel=P(0.4,0,1,1)`、`SineOut=sin(t·π/2)`。

### 7.1 顶栏/底栏收起展开 —— `setBars(p)`（220ms）

| 量 | 公式 | p=0 | p=1 |
|---|---|---|---|
| topBar 高 | `44−20p` dp | 121px | **66px** |
| fieldBox 高 | `34−10p` dp | 93px | **66px**（= 栏高，上下等宽） |
| bottomWrap 高 | `44(1−p)` dp | 121px | 0 |
| 圆角矩形底色 alpha | `(1−p)·255` | 255 | 0（淡出） |
| btnReload alpha | `1−p` | 1 | 0（淡出） |
| 网址字号 | `18−4p` sp | 18sp | 14sp |
| **`webContainer.translationY`** | `20(1−p)` dp | 20dp | 0 |

曲线：`apple`（`cubic-bezier(0.25, 0.1, 0.25, 1)`）—— 原来是 ValueAnimator 默认的
AccelerateDecelerate，收尾略硬；换苹果曲线后收缩/舒张的最后一小段是"滑"进去的。

**性能守卫**：高度/字号只在取整后真的变化时才写 `layoutParams` / `setTextSize`。

> **为什么现在不卡了**：网页是**固定 viewport**（见 3.1），栏的高度变化只影响栏层那 3 个 View，
> WebView 的 viewport 一帧都不变，页面不再每帧重排；`webContainer` 只用 `translationY` 跟手，
> 那是纯变换（GPU），不触发布局。改造前这里是 `weight=1`，每帧都在改 WebView 的视口高度。

### 7.2 滚动滞回（防"要缩不缩"）

```
scrollTop ≤ 4dp         → 立刻展开（冷却 250ms）                      ← 只有滚动回调能判断
内容位移 > 0（手指上滑）→ 展开态累计 ≥ 20dp → 收起（冷却 450ms）
内容位移 < 0（手指下滑）→ 收起态累计 ≤ −8dp → 展开（冷却 450ms）
方向一反就归零（单侧累加）
```

**驱动源有两个，手指优先**：

| 场景 | 驱动 | 为什么 |
|---|---|---|
| 手指按在网页上拖动 | `trackBarByFinger()` 直接把 MOVE 的 dy 喂给 `feedBar()` | `onScrollChanged` 要等页面真的滚起来才回调（重页面一帧 30~50ms，合成滚动还会合并帧），栏的动画会明显落后半拍、看着像跟着网页一起卡住；触摸事件是输入管线直接给的，节奏稳定 |
| 手指抬起后的惯性 / JS 自己滚动 | `w.onScroll` → `feedBar(delta)` 兜底 | 没有手指可用，只能听滚动回调 |

手指按着时滚动回调**不再**驱动栏（`barFinger` 标志），否则同一段位移会被算两遍（阈值等于减半）。
收起方向还要求 `w.canScrollVertically(1)`：短页面 / 已经到顶时继续拖不该把栏收起来。
双指（缩放）和编辑态一律不参与。

> 不要用 `WebView.getScale()` 判缩放：该机型返回屏幕密度 2.75，恒 >1。

### 7.3 地址栏编辑态

- **进场**：`prepareCancelWidth()` 量取消宽 `Wc` 并锁 `lp.width`；`fieldBox` 由 weight=1 改显式宽 `total`，280ms 从 `total` 收到 `total−Wc`；取消无独立 translationX（被布局推着走）。
- **出场**：反向，结束 `weight=1,width=0`、取消 GONE、宽度还原 `wrap_content`（出场也用 `apple`，一去一回手感一致）。
- **居中**：`left=(w−textW)/2`，夹在 `[padStart, w−padEnd−textW]`；非编辑态 `w=idleFieldW()`。
- **丝带**：`setSelection(0,n)` 300ms。

**进场这一整套（网址左移 + 圆角矩形收窄 + 取消按钮 + 蓝色丝带）统一用苹果的默认时间曲线**：

```kotlin
private val apple get() = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
// = CoreAnimation kCAMediaTimingFunctionDefault：起步带一点"黏"、中段最快、收尾又长又软
```

三段（280ms / 280ms / 300ms）同曲线，视觉上是一整段动作一起收尾，不会出现某个部件先到位、"啪"地停住。

**按下的时序（iOS 7 的细节）**

按住地址栏先让圆角矩形**加深一点**（`fieldPressScrim` 压在最底层，黑 7.5% alpha，**220ms**（渐浅同速））。之后分两支：

| 分支 | 过程 |
|---|---|
| **点击**（松手早于长按） | 加深结束 → 左移 / 矩形收窄 / 丝带铺开开始，**加深同时以同一条曲线渐浅回去**（两者都 280ms，到位时正好回到原色）→ 左移结束的瞬间才 `showKeyboard()`，同时磁贴页浅色背景 `fadeInSuggestBg()` 渐显（磁贴自身不再重播） |
| **长按**（`setOnLongClickListener` 只置标记） | 不展开、不出键盘；松手才渐浅回原色，并 `showFieldMenu()`：**先把整条网址全选**，菜单因此**不给「全选」**，只给「复制 / 粘贴（剪贴板有内容时）/ 删除」；点「删除」= `startFieldEdit(blank = true)`，直接进空白的左对齐编辑态（左移结束出键盘 + 磁贴页） |
| **长按空白地址栏** | 没有「全选/复制」可给，所以**在按住阶段就弹**（`fieldMenuEarly`），只给「粘贴」；松手不重复弹 |

> **粘贴后要左对齐**（`alignFieldLeft(animated = false)` + `setSelection(0)`，下一帧再补一次）。
> 只调光标/scrollX 治不好：折叠态是「居中」布局，`paddingStart` 很大，长网址从中间开始画、右边溢出，屏幕上永远只剩右半截；换成左对齐才从第一个字符开始显示。
> 长按判定**不能用框架的 OnLongClickListener**：EditText 聚焦时它会被系统的选择逻辑吃掉。改用自带计时器 `fieldLongPress`（DOWN 时 `postDelayed(FIELD_LONG_PRESS_MS = 420)`，UP/CANCEL 时移除），空白地址栏在计时器里**按住就直接弹「粘贴」**。
> **还有一个隐藏的拦路虎**：`buildAndShowMenu()` 开头原本写着 `if (items.isEmpty() || urlField.text.isEmpty()) return` —— 空文本正是「粘贴」菜单的场景，这行会把整个菜单吞掉（现象就是"长按毫无反应"，但地址栏颜色照常变深）。现在只判 `items.isEmpty()`。

配套：`urlField.showSoftInputOnFocus = false`（键盘只由 `showKeyboard()` 在左移结束时弹出）；展开逻辑集中在 `startFieldEdit()`，`onFocusChange` 不再直接展开（否则键盘会先于左移弹出来）。

**退出的时序（返回键 / 取消 / 回车）**

回到常态只有**一套**动作，收在 `exitFieldEdit()` → `collapseFieldVisuals()` 里：

| 触发 | 结果 |
|---|---|
| **返回键**（键盘弹着） | **按一次**就收完：收键盘 → 磁贴页**渐隐**关闭 → 圆角矩形右移回位、取消按钮被推出去、网址复原并居中 |
| **取消按钮** | 同上（`btnCancel` 也改成调 `exitFieldEdit()`，不再自己 `releaseFieldFocus + hideKeyboard`） |
| **回车进新页面** | `go()`：磁贴页同样是**渐隐**关闭（和返回共用同一条 240ms `apple` 渐隐） |

> **返回键必须抢在输入法之前。** 键盘弹着的时候，BACK 会先被 IME 吃掉 —— 只收键盘，`Activity.onBackPressed()` **根本不会被调用**（实测：按一次返回，`onBackPressed` 一次都没进，键盘却已经收了），所以"按一次返回退出编辑态"做不到。
> 现在地址栏是 `UrlEditText`（自定义 `EditText`，XML 里已换成这个类），在 `onKeyPreIme()` 里接住 BACK —— 那是系统把按键交给输入法**之前**给焦点视图的回调。DOWN、UP 都吞掉，键盘就不会自己先收。
> 它**不是无条件吞**：`onBackKey` 返回 false（已经不在编辑态、磁贴页也没挂着）时照常交回系统，否则会把"网页后退 / 退出应用"的返回键一起吞掉。
>
> **磁贴页关闭改成整体渐隐**（`overlaySuggest.alpha 1→0`，`SUGGEST_EXIT_MS = 240ms`，`apple` 曲线），不再下滑。渐隐结束后把 `sugScroll.translationY` 复位成"下移到底"，留给下一次入场（否则下次入场会从 0 开始、磁贴先出现在最终位置再往上跳）。
> `suggestClosing` 标记保证渐隐不会被重复触发（快速点两下地址栏不会闪一下重来）；`showSuggestions()` 里会 `cancel()` 并把 `alpha` 复位成 1。
>
> `collapseFieldVisuals()` 是幂等的（已经常态就直接 return），失焦回调、返回键、取消按钮都走它，只有一套收尾逻辑。实测：起始页点地址栏再按一次返回，**退出后的整屏截图与进入前逐像素完全一致**（md5 相同）。

**刷新按钮 → 小圆盘叉的交接（iOS 7 的细节，`applyFieldEdit(f, Wc)`）**

| 元素 | 编辑态怎么变 |
|---|---|
| `btnReload`（22dp） | `translationX = +Wc·f` 反补位移 → **屏幕位置不动**，`alpha = 1−f` 渐隐；圆角矩形右沿从它身上扫过去（超出 `fieldBox` 的部分被裁掉），所以"被挡住"完全不突兀 |
| `btnClear`（17dp） | `fieldBox` 的子视图，右沿左移时它跟着走（正好落在刷新按钮原来的位置上），`alpha = f` 渐显；✕ 复用加载态那个矢量 `ic_stop`。**配色取自主题**：圆盘 = 地址栏占位文字色（`sub`，`#8E8E93`）再压一层 `alpha=150`（实心太抢眼）、叉 = 圆角矩形填充色（`fieldBg`，`#E9E9EA`），所以是"淡盘浅叉"，私密模式下两色自动反过来 |
| 宽度收缩 `Wc` | `Wc` = 「取消」量出来的宽度，反补量与收缩量严格相等，两者天然对齐 |

`fieldEditT` 就是这个 `f`；`setBars()` 里刷新按钮的 alpha 写成 `(1−p)·(1−fieldEditT)`，
这样栏收缩和编辑态淡出可以叠加，不会互相写回 1。

> `btnClear` 由 `setupClearButton()` 在 **`bindViews()` 末尾**创建 —— 必须赶在 `applyTheme()` 之前，
> 因为主题里要给它上色（晚于 applyTheme 会 `lateinit 未初始化` 崩在启动）。
> 点它是"清空输入"，之后必须 `alignFieldLeft(animated = false)` —— **不能**调 `centerField()`：
> 那个是"未编辑态"的居中逻辑，会把光标和占位文字拉到中间，和 iOS 的左对齐不一致。

### 7.4 任务视图进场（页面 → 倒梯形堆）

```
openTabs()
 ├ 释放焦点 / 收键盘 / 关建议层
 ├ 橡皮筋位移归零
 ├ 若栏收着 → snapBarsExpanded() → 等 2 帧
 ├ grabScreenFrame()（PixelCopy 读真实屏幕）
 │    └ 拿到帧后：updateThumbFromFrame → prepareBlur(帧)（后台线程算正式版）
 └ startTabsEnter()
      ├ applyChromeHeight()
      ├ ensureBackdrop(帧)  ← 同步铺「快速版」模糊（1/8 × r8，1~2ms）
      ├ alignBlurMatrix()
      ├ overlayTabs.VISIBLE, tabChrome.alpha=0
      ├ refreshTabs() + 所有卡 alpha=0
      └ afterLayout：updateTransforms → scrollTo(0,0) → 再清 alpha=0
           ├ heroAsPage(heroComposite, 0) + applyHero(整页 1:1)
           └ nextFrame(1) → fadeTabsChrome(1,240) + playEnter()
```
`playEnter()`：400ms `apple`，`k:1→cardW/pageW`、`hEnd:heroFullH→cardH·s`、`theta:0→tiltNow`、`ty:0→cardTop`、`overlayFrac:0→1`、`shadowFrac:0→1`（用 `p²`，见 §7.16）；
`standUpCards()`：其余卡片**绕上沿起立 + 向当前卡片靠拢**（见下），400ms `apple`，与 hero **同起点、同终点、不分先后**。

**其余卡片：一边「起立」、一边「靠拢」（10-25 两次改）**

没进任务视图时，整摞标签是一叠**自上而下平铺的页面**（没有任何 3D）：每张卡就是一个
整页高（`cardH`）的长方形，相邻两张的间距 = **一整张卡的高度** ——
平铺时绝不重叠（第 1 张的下沿永远在第 2 张的上沿之上）。

点「任务视图」时，**所有卡片一起**动，同一段动画、同一条曲线、不分先后：

| 卡片 | 起 → 止 |
|---|---|
| 当前页（hero） | `k:1→cardW/屏宽`、`hEnd:整页→cardH·s`、`theta:0→tiltNow`、`ty:0→cardTop`（另外还带标题条吐出） |
| **其余卡片** | `theta:0→tiltNow`（**绕上沿旋转**）、`translationY:|k−skip|·(cardH−cardStep) → 静止 ty`（**靠拢**）、`scale:1 → 静止 s`、`alpha:0→1`（前 55% 淡完） |

```kotlin
private fun standUpCards(skip: Int) {
    val gapFlat = (cardH - cardStep).toFloat()      // 平铺间距 − 3D 间距
    for (k in 0 until tabList.childCount) {
        val c = tabList.getChildAt(k)
        // hero 也是 TrapezoidView、已经 addView 到末尾（它在演"整页→卡片"），必须排掉，
        // 否则这里会把它的 theta / translationY 一起改掉，整个动画就花了（这个坑踩过）。
        if (k == skip || c === hero || c !is TrapezoidView) continue
        toS += c.scaleX; toDy += c.translationY     // 先记静止态（含"越过 1/9 线"的补偿）
        c.theta = 0f; c.alpha = 0f
        c.scaleX = 1f; c.scaleY = 1f                // 平铺 = 全尺寸
        c.translationY = abs(k - skip) * gapFlat    // 一律从**下方**升上来
        ...
    }
    // 一条 ValueAnimator 统一推进 theta / translationY / scale / alpha（400ms apple，无 delay、无错峰）
}
```

**两条"统一"（10-25 第三次改，都是"看起来旋转方向不对"的根因）**

1. **方向统一：一律从下方升上来。** 早先 `translationY = (k − skip) · gapFlat`：
   当前页**上方**那几张拿到的是**负**值 —— 它们从屏幕上方往下掉，和别的那几张方向相反，
   又带着"一整页"的行程，看上去就像"旋转方向反了"。改成 `abs(k − skip)` 之后，
   它们先落在当前页后面（`CARD_BACK_Z`），从下方升上来时从当前页背后穿出来、正好落到自己槽位。
2. **缩放统一：`1 → 静止 s`。** 静止态里"当前页上方"那几张是被越过 1/9 线那套缩过的
   （实测 s = 0.6 / 0.68），下方那几张是 1.0。如果动画期间不统一，那几张就是被 View 又缩了一次的
   梯形 —— 角度一样、看起来却"透视不一样"。现在起点统一 1.0，终点回到各自的静止 s。

**退场时所有卡片一起"放平"**

`startExit()` 里除了英雄视图 `theta:tiltNow→0`，其余每张卡也一起走 `tiltNow→0`
（同 420ms、同 70ms delay、同 `apple`）—— 否则只有被点的那张在"站起来"，旁边几张还斜着飞出去，
就是"旋转方向不一样"。飞出去的方向仍然是：在它前面的往下、在它后面的往上（让开那块要铺开的区域）。

- **为什么要靠拢**：卡片是绕**自己的上沿**转的（上沿不动），立起来之后如果间距不缩，
  相邻两张还是隔着一整页，一屏放不下几张；缩到 `cardStep`（≈一屏 4 个上沿）才对。
- **方向有正负**：当前页**之前**的卡片 `k−skip < 0`，起点在上方一整页、要往下靠；
  之后的卡片起点在下方一整页、要往上靠。
- **外观**：因为平铺间距就是一整页，除当前页外的卡片起点基本都在屏幕外 ——
  所以看起来仍然是"从下方（或上方）升上来"，但同时**在旋转**，且**没有错峰**。

> 演进过程（两版都删了）：`revealCards()`（屏底+24 起步、470ms、`delay 140+k·55` 依次飞上来）
> → `standUpCards()` 只旋转不位移 → 现在"旋转 + 靠拢"。

进场的落点还有一道**对齐校正** `alignCurrentCardToRest()`（在 `afterLayout` 里，替代原来的
`scrollTo(0, desiredScroll())`）：

```kotlin
tabScroll.scrollTo(0, desiredScroll())     // 粗滚：把当前页那格带到视口
updateTransforms()
// 实测「当前页那张卡的视觉上沿」与目标位（1/9 线 + 2dp）的差，按差值再补滚一次（最多两趟）
val d = cardVisualTop(child) - (shrinkLineY() + dp(2f))
```

为什么需要它：上一轮如果滑到整摞最下面、当前页那张已经被滚到 1/9 线上方**缩小过**，
它的视觉上沿还带着 `pivotY·(1−s)` 的补偿，光按 `k·cardStep` 粗滚对不准；
补滚一两次之后 `s` 自然回到 1、上沿正好压在线上（实测 `vis = 221.0`、`want = 221.78`）。

`refreshTabs()` 的 `topPad` 也改成只取 `restTop`（不再和"居中"取 max）：
**当前页那张卡的上沿固定落在「上 1/9 分界线」下方 2dp**（再高就会被缩小那套接管）。
以前 1~2 个标签时会走 `centered`，卡片被摆到屏幕中间，和 iOS 7 不符。

**任务视图的配色与卡片留白（10-25 改）**

| 项 | 值 | 说明 |
|---|---|---|
| 背景蒙版 | `#B3000000`（70% 黑） | 压在 `tabBgBlur` 之上：模糊仍然透出来，只是亮部被压暗。原来是 `#80D9D9DE`（浅灰 50%） |
| 底栏蒙版 `tabBarScrim` | 普通 `#B8000000` / 私密 `#CC000000` | 底栏也是深色毛玻璃（原来普通模式是 75% 白） |
| 底栏文字 `btnPrivate` / `btnDone` / `btnNewTab` | **白色** | 深底上蓝色（#007AFF）对比太低；iOS 7 这张底栏本来就是白色文字 + 白色「＋」 |
| 状态栏图标 | 进任务视图 → **白色图标**；退出 → 按主题还原 | 见 `setStatusBarIconsWhite()`：背景深黑了还留深色图标，左上角的时间/电量几乎看不见 |
| 卡片左右留白 `CARD_SIDE_DP` | **8dp**（原 18dp） | 倒梯形的**上沿**几乎顶到屏幕两边，这是 iOS 7 的样子。它同时决定 `cardW` / `cardHeaderH` / 起飞帧落点，只改这一处就全局一致 |

> 状态栏切换放在 `openTabs()` 里（进浮层前）和 `finishDismiss()` 里（浮层真正 `GONE` 之后）——
> 不能放在 `dismissTabs()` 开头，那里退场动画还要放 400ms，中途换回深色图标会在深底上"消失一下"。

### 7.5 任务视图退场（倒梯形 → 整页）

`startExit(k, src)`：420ms、delay 70、`apple`（原 `ease`）；
`k:st0→kScale`、`hEnd:st2→heroFullH`、`theta:tilt→0`、`ty:st4→0`、`overlayFrac:1→0`、`shadowFrac:1→0`（`p²`，见 §7.16）。
其余卡片的 `theta` 也一起 `tilt→0`（420ms、delay 70、`apple`），`shadowFrac` 同步退到 0 —— **所有卡片用同一条曲线放平**，否则就是"只有被点的那张在转"。
**让路（时间按张数动态算）**——这是「卡片还没飞完、页面已经铺好」那条的解法：

```
英雄视图：70ms 起、420ms 长  ⇒ 490ms 结束
卡片预算 EXIT_CARDS_BUDGET_MS = 300ms
  movers = 视口里真正要飞的张数（c≠k 且在视口内）
  step    = clamp(budget·0.4 / movers, 6, 28)                 // 错峰步进：张数越多越密
  flyMs   = clamp(budget − step·(movers−1), 150, 300)
  delay_i = min(step · 第 i 张离当前卡的格数, budget − flyMs)   // 一律夹在预算里
  ⇒ 全部飞完 ≤ 300ms，比英雄视图早 190ms 收干净
```
> `delay` 必须夹：`格数` 用的是「离当前卡几格」，标签多时会远大于 `movers`，
> 不夹的话远处那张能拖到 600ms 以后。这就是"张数多 → 来不及"的数学来源。
>
> 预算为什么是 300 而不是 490 附近的数：设备帧率低时，"页面铺满"之前的最后几帧
> 可能只渲染出卡片飞行的 90% 位置 —— 余量小于 100ms 就会拍到一张"还没飞完的卡"。
> 实测（12 标签、列表滚到底、`scrollY=4057`）：`movers=4`、`step=28`、`fly=216`，
> 也就是只有 4 张真的在飞，其余 8 张直接撤掉。

**硬兜底**：`budget + 80ms` 时把剩下的卡片一律 `alpha = 0`（用 `exitSeq` 认人）。
不依赖"动画最后一帧有没有被渲染" —— 页面铺满那一刻不可能再有卡片浮在上面。

| 卡片 | 处理 |
|---|---|
| `c == k` | `alpha=0`（英雄视图已严丝合缝替上） |
| **不在视口里**（±8dp 容差外，按**视觉**位置判断） | `alpha=0` 立即撤掉 —— 不飞（等价于「减少要飞的卡片数量」，而且本来也看不见） |
| `c > k`（在它前面） | 向**下**飞出屏幕：`translationY = t0 + (屏高 + 40dp − 视觉上沿)`，`flyMs`，**不淡出** |
| `c < k`（在它后面） | 向**上**飞出屏幕：`translationY = t0 − (视觉上沿 + 视觉高度 + 40dp)`，`flyMs` + 淡出 |

**飞出的距离必须用「视觉上沿 / 视觉高度」算**（`cardVisualTop()` / `cardVisualH()`），
不能用 `cardTopY + translationY`：缩小状态的卡片是绕 `pivotY` 缩的，视觉上沿比布局上沿低
`pivotY·(1−s)`（s=0.6 时 **277px**）。按布局算就少飞这 277px —— 动画老老实实跑完了 300ms，
卡片却停在离屏幕边缘 277px 的地方，就成了"残卡"。这就是「移动距离没算准确」的真正来源。
> 注意：**不要**试图让退场的卡片复刻"下滑"那套 0.6→0.8→1.0 的规则 —— 那是 **滚动** 时
> `updateTransforms()` 按 1/9 线算的；退场是"整张飞出屏幕"，比例应当保持不变，
> 只需把**起点和距离**算对。

**起点必须落在卡片的"视觉位置"上**：卡片如果正处在缩小状态（越过 1/9 线），它是**以
`pivotY`（梯形对角线交点）为心**缩放的，画出来的上沿在 `cardTopY + translationY + pivotY·(1−s)`，
比 `layout+translation` 低一截（`s=0.6` 时差 277px）。`cardState()` 已经把这一项算进去了 ——
否则「点完成」的那张卡正在缩小状态（标签多、滑到底时几乎总是如此）时，
英雄视图会从比卡片高一截的地方起飞，衔接处"错一下"。`playEnter()` 的终点也用这个函数，一起受益。

**层叠关系不能动（重要）**：英雄视图全程待在 `HERO_Z`，前面的卡片是 `CARD_FRONT_Z`，
所以是「前面的卡片先让开 → 页面逐段露出来」。曾经试过退场时把英雄视图提到 `CARD_FRONT_Z+1`
一口气盖住整摞（想让"卡片来不及飞完"不再露在页面上），结果是
**旁边的卡片还没走完、和当前卡片还没分开，当前卡片就啪地一下跳到了最上层** ——
用户明确否掉了。层叠秩序比"卡片飞没飞完"重要：真要缩短等待，只能调时长，不能改 z。

模糊层在 `p<0.22` 才 `fadeTabBackdrop(0,90)`；任务视图底栏 `fadeTabBottomBar(150)`（200ms accel）。
**底边硬约束**：英雄视图底边 ≤ `webBottomVisual()`（1830），永不压到工具栏。

**兜底**：`startExit` 末尾挂一个 900ms 的看门狗 —— 万一英雄动画被谁掐掉、`onAnimationEnd` 不回来，
浮层会一直挂着（虽然全透明，但**仍然吃触摸**，看起来像卡死）。看门狗会校验 `heroAnimCurrent(exitAnimId)`
（期间没开新一轮才动手）再强制 `finishDismiss()`。

### 7.6 点「＋」：新页从工具栏上沿长出来

`h0=12dp`、`k0=0.52`、`theta0=tiltNow+10°`，520ms **`apple`**（原 `SineOut`）：
`hEnd=h0+(fullH−h0)p`、`theta=theta0(1−p)`、**`ty=fullH−projectedHeight(hEnd,θ)`**（底边钉在工具栏上沿）。
`p>0.86` 淡出模糊层；底栏 `fadeTabBottomBar(150)`。

### 7.7 卡片缩放（滚出 1/9 线）

```
line  = rootView.height / 9                     edge = contentTopY
cross = cardRenderH × diagCrossRatio(tiltNow, dOverH当前值)   ← 用这一帧的 d，不是常量
p     = 卡片上沿（含整摞位移，不含每张自己的稀疏位移）
span  = line − edge,  span2 = cardRenderH × 0.30
crestMax = span2 × 0.22                                         ← 最高点的爬升量
rollMax  = cardStep × 0.45                                      ← 滚下的行程

p ≥ line      → s(目标) = 1
edge ≤ p < line → t = (p−edge)/span  → s = 0.8 + 0.2·E(t)
p < edge      → t = (edge−p)/span2   → s = 0.8 − 0.2·E(t)      // E 自己夹到 1，之后恒 0.6
E(t) = (1 − cos πt) / 2                                        ← 半余弦

ty 初值 = 7.8 那条稀疏位移，这里必须 +=（以前是 =，会把它整体覆盖掉）
p ≥ edge      → ty 不动                                  // 绕上沿缩小，上沿本来就不动
p <  edge     → over  = edge − p
                crest = crestMax·(1 − e^(−over/crestMax))
                roll  = over > span2 ? rollMax·E((over−span2)/span2) : 0
                ty += (edge + (0.8−s)·cross) − crest + roll − p   // 直接把上沿放到目标位
再叠一层低通：s ← s 的 35ms 指数趋近值（见下）
写入：applyCardScale(v, s)  →  v.k = s, v.hEnd = cardH·s（+ invalidate）
```

> **11-10 重构后的写法**：这里已经**没有 pivot 补偿**了。缩小不再是 `View.scaleX`，而是
> `k` 与 `hEnd` 同比缩放（绕上沿等比缩小，数学上与 `scaleX` 逐像素等价），所以
> 上沿位置只由 `translationY` 决定 —— 完整模型见 **`iOS7Safari-卡片变换架构.md`**。

**为什么用半余弦（11-10 改）**。原来是两段**线性**：过 1/9 线那一刻缩放速度从 0 直接跳到匀速，
到状态栏下沿又换一次斜率 —— 手指匀速滑动时卡片却"咔、咔"两下变速，这就是"生硬"的来源。
`(1−cos πt)/2` 两端斜率都是 0：起步"轻轻地开始缩"、拼缝处两边斜率都是 0 自然接上（C1 连续）。
**三个锚点 1.0 / 0.8 / 0.6 一个都没动**，改的只是中间怎么走 —— 所以 `cardState()`
（进场/退场起飞落点）、`alignCurrentCardToRest()` 这些依赖锚点的地方全部不受影响。

**转盘（`SHRINK_CREST = 0.22` / `SHRINK_ROLL = 0.45`）—— 顶到头之后不是"死"，是"滚过去"**

原来是硬钉：顶边钉死在 `edge`，手指继续推，那张卡一动不动；而且因为支点钉住、缩小继续，
**视觉上沿反而会跟着 `cross·(1−s)` 往下退**（最多 152px），手感像"顶到头之后又往回沉了一下"，
同时还是第三个拐点（上沿速度从 +1 直接翻成负）。11-10 分两步改掉：

| 阶段 | 曲线 | 作用 |
|---|---|---|
| **爬到最高点** | `crest = crestMax·(1 − e^(−over/crestMax))` | `over=0` 处斜率正好 1 → 和上一段"上沿跟手"**C1 接上**，过线不顿；爬到 `crestMax` 渐近停住 = 转盘的最高点 |
| **滚下去** | `roll = rollMax·E((over−span2)/span2)`，只在 `over > span2`（s 已到 0.6）后开始 | 卡片**往下走**，被前面那张卡一点点挡住 —— 齿轮转过顶点往背面走。两端斜率 0，所以是"到顶停一下、再往下" |

关键性质：**全程只是位置的函数**，没有任何状态机 —— 手指往回滑就原路升回来，回到"刚好 0.6"
的位置之后才重新变大（因为 `over` 掉回 `span2` 以下时 `roll` 自动变 0），正反两个方向天然对称。

> `rollMax = cardStep × 0.45` 是"够它藏到前面那张卡后面"的量，不能太大：卡片变矮之后
> （0.6 高）如果滚得太多，它的下沿会从**再下一张**卡的下面探出来。
>
> 另外整摞里那些同样被钉住的卡（`over` 更大的那几张）滚得更多、位置更低，但它们在
> **前面那张卡**和**再下面那张卡**的双重覆盖范围内，不会露出来。

**低通（`SHRINK_SMOOTH_TAU_MS = 35`）**。`scaleX/scaleY` 写的不是目标值，而是目标值的指数趋近：

```
k   = 1 − exp(−dt / 35ms)        // dt 用 System.nanoTime 实测，夹在 4~40ms
cur = 卡片当前的 scaleX
if |cur − 上次写的值| > 0.004  →  认为"别人写过"（起立/补位/退场复位），平滑器直接跟过去
if |目标 − 平滑值| > 0.05      →  跨步太大（快速 fling），直接给到目标
否则 s ← 平滑值 + (目标 − 平滑值)·k
```

两条豁免缺一不可：① 少了它，起立动画 / 补位补间写完 `scaleX` 后会被这里下一帧拽回旧值（闪一下）；
② 少了它，快速 fling 时大位移上的滞后会糊成一片。平滑值存在 `TrapezoidView.scaleSmooth` 上
（卡片重建即新对象，不用清理，也不会像 SparseArray 那样在换位后下标错位）。

> **`ty` 必须用平滑后的 s 算**：支点补偿量和实际画出来的大小必须同源，否则卡片缩小的过程中
> 会自己往下滑一小截。另外 `p ≥ line`（还没过线）那一侧也要补 `−cross·(1−s)` ——
> 低通拖尾让 s 略小于 1 时，不补就会在"越过 1/9 线"的瞬间轻轻跳一下。

### 7.8 越界拖拽（橡皮筋 + 稀疏 + 俯身）

```
位移：shiftExcess += dy（夹 ±4×120dp）
      shift = 120dp·(1−e^(−|excess|/120dp))·sign     → tabList.translationY
      （只在值真的变化时才写，并立刻 updateTransforms）
稀疏：pad = max(0, shift)；ty += pad·1.2·(1−cos(i/(n−1)·π/2))
俯身：steepExcess += dy（≥0）；steep = 1−e^(−excess/(150dp·density))
      theta = tiltNow + steep·10°；dOverH = 2.6·(1−0.25·steep)；hEnd 恒 = cardH
松手（拖动）：540ms Overshoot(1.15) 回位；steep 540ms SineOut 回 0
惯性甩过头：**不回位**，整摞留在过冲位置（下次进任务视图 / 点卡片退出时统一归零）
```

稀疏位移和 7.7 的缩放补偿是**相加**的：越界下拉时，屏幕最上面那几张（越过 1/9 线的）
既缩小又按 `1−cos` 让位，下面几张只让位 —— 间距从「上小下大」单调递增。

### 7.9 惯性滚动（两处共用一套物理）

| | 任务视图 `TabScrollView` | 网页 `ObservableWebView` |
|---|---|---|
| 接管条件 | 系统 `fling(v)` 回调 | 单指 + `|vy|>1.5|vx|` + `|vy|>1100px/s`（把 UP 换成 CANCEL，页面收到 touchcancel） |
| 每帧 | `scrollY += v·dt`，`v *= e^(−decay·dt)`，dt≤0.032 | 同（`scrollBy` + 余量累加） |
| 撞边 | 位置夹边界，多余部分 ×0.55 转成"冲过头"；速度用 `EDGE_DECAY=9` 快速吃完；结束后**不回位**（留在过冲位置） | `canScrollVertically` 到头即停，不回弹 |
| 复位 | 下次「点卡片 / 进任务视图」时在 `finishDismiss` / `openTabs` 里复位 | 无 |

### 7.10 收藏磁贴上移/下移

`sugScroll.translationY: 40.7dp ⇄ 0`，260ms `SineOut`；入场不硬设起点、退场不归零。

### 7.11 书签页 / 其它

书签页 **330ms 上滑进入 / 330ms 下滑退出**（`BOOKMARK_MS`），**都用 `apple` 曲线**。
原来 300 / 260 且曲线不同（进 `SineOut`、出 `accel`）—— 出场又短又"越走越快"，所以显得急；
现在一进一出同长同曲线；
卡片删除 230ms 左飞；重排补位 260/240ms；越界俯身回弹 540ms。

### 7.12 进度条

`finishProgress()`：`progress=100` + VISIBLE → 200ms → alpha 淡出 180ms → GONE；`onPageStarted` 取消并复位。

### 7.13 删除标签后的补位动画（`rebuildAnimated`）

```
removeTabAt(i)
 ├ before = snapshotPositions()          每个 tab.id → (屏幕 y, 缩放)
 ├ ... 删 tab、调整 currentIndex ...
 └ rebuildAnimated(before)
      ├ refreshTabs()                    整摞重建（卡片是新的 View）
      └ afterLayout（onPreDraw）：
           ├ 先把 scrollY 夹回 [0, maxY]  ← **必须在算落点之前**
           ├ updateTransforms()          算出新位置/新缩放（translationY 含缩放补偿）
           ├ 逐卡：**用 cardVisualTop() 和旧位置比**，差 <1px 且缩放差 <0.01 就跳过
           │       否则 start = 旧视觉y − (新布局y + pivotY·(1−旧scale))，再补间到终值
           └ 一个 ValueAnimator(0→1, 260ms SineOut) 统一推进所有卡的 translationY + scale
```

> **"已经就位就不动"这个判断必须用视觉上沿**（`cardVisualTop()`，含 `pivotY·(1−s)`）。
> 用 `cardTopY + translationY` 的话，越过 1/9 线缩小过的卡片这一项有 100~277px 的差，
> 每次重建都会被判成"位置不对"而被塞进补间队列 —— 表现就是**那些缩小的卡片每次都抽搐一下**
> （关标签、左滑删除、换位之后都出现，卡片多的场景才明显）。
两个坑（都是这一轮修掉的，别退回老写法）：

1. **`scrollTo` 必须排在算落点之前**。老写法是先设 animator、最后 `scrollTo(0, desiredScroll())`；
   标签多时 `scrollY>0`，这一归零会让所有卡片当场整体往下跳一段 —— 看着就是「补位动画丢了、
   卡片直接闪现在下方」，越过 1/9 线的卡片（正好在上沿附近）最明显。
2. **不要逐张 `view.animate()`**：`ViewPropertyAnimator` 和 `updateTransforms()` 每帧都写
   `translationY`，谁后写谁赢；带缩放补偿的卡片被覆盖就会"跳"回布局位置。改成**一个** animator
   统一推进所有卡片（位移 + 缩放），动画期间没人跟它抢；结束再把值精确对齐一次。

### 7.13b 拖动换位的性能（三个必须守住的点）

| 做法 | 为什么 |
|---|---|
| `elevation` 只写**名次**（0,1,2…，步长 0.01），不写像素值 | 名次没变就不写。手指在动但没人交叉时**一帧都不碰** elevation；写像素值的话每帧每张卡都要改，父容器每帧都要重排子视图顺序 |
| 让位重排**每帧最多算一次**（`postOnAnimation` + `restackPending` 合并） | 一帧里可能收到好几个 MOVE，每个都算一遍等于白做几倍 |
| `hShift` 亚像素（<0.5）不 `invalidate` | 每重画一次 = 56 条 `drawBitmap`，手指慢速移动时这类改动占大多数 |

实测（`dumpsys gfxinfo`，5 标签、上/下各拖一次）：优化前 64 帧里 5 帧超时（7.81%）、p90 24ms、
"Slow issue draw commands" 5 次；优化后 **1 帧（1.59%）、p90 19ms、draw 1 次、Slow UI thread 0 次**。

### 7.14 任务视图背景模糊（两档，配合进场）

| 档 | 源 | 尺寸 / 半径 | 在哪跑 | 何时上屏 |
|---|---|---|---|---|
| 快速版 `backdropQuick()` | 本次 PixelCopy 的整屏帧 | 1/8（135×270）/ r8 → 等效屏幕 136px | **UI 线程同步**，1~2ms | `startTabsEnter()` 里，`overlayTabs` 显示**之前** |
| 正式版 `prepareBlur(frame)` | 同一帧 | 1/4（270×540）/ r16 → 等效 132px | `blurWorker`（BACKGROUND 优先级） | 算完在 UI 线程替换 |

两档等效模糊半径几乎一样（136 vs 132 屏幕 px），所以正式版替换上去看不出跳变；
关键是**任何一帧的背景都是模糊的** —— 以前浮层显示时正式版还没算好，
背景只是 `#B3000000` 半透明蒙版盖着清晰页面，进到动画中途才"啪"地糊上去。

`prepareBlur()` 的缓存 key = `url|lastScrollTop|屏宽|屏高|topBar.height`，
命中且 `tabBgBlur.drawable != null` 就直接复用；`blurPendingKey` 保证同一份状态只跑一次
（以前重起一轮会把在飞的结果按 `blurSeq` 丢掉，冷启动时等于白算一遍）。
`warmUpTabView()` 里的 `warmBlur()` 只拿空白图把 `fastBlur` 的 JIT / 缓冲跑热，不碰任何 View。

---

### 7.15 长按拖动换位（「木板交换」）

拖动换位**不是**「过线跳一格」的离散动画，而是由拖动进度连续驱动的，看起来像两块木板互换位置。

```
s = dy / cardStep                    拖动进度（单位＝格，连续）
dragTo = 带滞回的阈值判定（见下）     只用来决定最终顺序
每张其余卡片 k：
   p = clamp(|s| − 格数差, 0, 1)      它自己那一格过渡的进度 0..1
   相位（带滞回，阈值见常量表）：
     0 原地 → p ≥ 0.30 转 1
     1 让开 → p ≥ 0.85 转 2；p < 0.10 回 0
     2 补位 → p < 0.60 回 1
   切相时启动一次**自己的补间**（200 / 240ms SineOut）：
     1 让开：screenShift = 0，hShift = dipShift   沿平面沉到手里那张下面，停住等
     2 补位：screenShift = dir·step，hShift = 0   滑进腾出来的那一格，停住
```

**手里那张卡也带"重量"**：它不再和手指刚性绑定，而是每帧朝手指位置**指数趋近**

```kotlin
k = 1 − e^(−dt / DRAG_FOLLOW_TAU)        // TAU = 0.06s，对帧率不敏感
translationY += (dragTargetY − translationY) × k
```

追赶速度正比于"手指和卡片之间的距离"：刚起手时距离小 → 走得慢；手指拖远了 → 追得越来越快（**先慢后快**）；
手指停下 → 差值一点点收掉，稳稳落住。落后量上限 `dp(120)`，免得飘太远。
`dragTargetY` 由 MOVE 事件记录（`dragBaseY + dy`），追赶由 `followTicker`（`postOnAnimation`）驱动；
左滑删除（axis 1）不走跟随，整张仍跟手指 1:1。

**曲线**（模拟有重力的物体）：

| 动作 | 曲线 | 时长 | 感觉 |
|---|---|---|---|
| 让开（下沉） | `Accelerate(1.6)` | 300ms | **先慢后快**：开头像还顶着、被挤得不情愿，越往下越快沉到手里那张下面 —— 慢起快落才有重量感（用 Decelerate 会显得轻飘飘） |
| 补位（上滑进格子） | `PathInterpolator(0.32, 0, 0.16, 1)` | 320ms | 慢起 → 缓缓加速 → 长收尾，**不过冲**（过冲会"磕"一下，显得积极、犀利）；和让开那条"慢起快落"接得上 |
| 松手归位（没触发换位） | `Decelerate(1.8)` | 260ms | 快速被"按"回原位，不弹 |

**松手归位必须防一件事**：`settle()` 会把手里那张卡补间回原位，但同一时间"让开/补位"的补间
每帧都在调 `updateTransforms()` —— 它会把这个卡的位置直接写成终值，回归动画一帧就被抹掉，
看起来就是"一松手啪地闪现回原位"。所以新增 `settlingView`，`updateTransforms()` 的跳过条件变成
`c === dragView || c === hero || c === settlingView`。

**让开波与层级**：

| 方向 | 目标位置 | 让开深度 |
|---|---|---|
| 往下拖、被让的卡**往上**补（`dir=-1`） | 上面一格 = 最终画在**后面** | 同一个公式：`H + 0.45·step` |
| 往上拖、被让的卡**往下**补（`dir=+1`） | 下面一格 = 最终画在**前面** | 同一个公式：`H + 0.45·step` |

**换位判定：阈值 + 滞回（第 0 格也参与）**

```
prog = |s| ; cur = |dragTo − dragFrom|
prog ≥ cur + DRAG_SWAP_ON(0.62)                     → 往前换一格
prog < cur − (1 − DRAG_SWAP_OFF(0.42))              → 退回一格
其余                                                → 不动
```

- 以前用 `round(s)`（0.5 格就换），长按后轻轻一拖就触发，太敏感；现在要拖过 **0.62 格**才换，
  退回要掉到 **0.42 格**以下 —— 两个阈值不同＝滞回，临界线上来回抖不会反复触发。
- **提交换位时 `viewOrder` 必须重置成恒等映射**。`commitReorder` 里 `tabs` 已经被重排成新的视觉顺序了，
  `viewOrder` 若还留着"旧下标"的排列，`refreshTabs` 会把同一个排列再套一遍 → 顺序彻底乱
  （实测：4 张卡 2↔3 交换，期望 1,3,2,4，旧代码得到的是被打乱的 4,1,3,2 那种"看着像整体转了一格"的结果）。
  ```kotlin
  tabs.clear(); tabs.addAll(newTabs)
  viewOrder = MutableList(tabs.size) { it }      // ← 必须
  currentIndex = cur?.let { tabs.indexOf(it) } ?: 0
  ```
- **换位范围是全整摞（0 … n−1），第 0 格也能参与**。整摞的顺序**就是 `viewOrder`**：
  `refreshTabs()` 现在优先沿用 `viewOrder`（换位提交后它就是"用户拖出来的顺序"），
  只有在它失效（增删标签后数量/内容对不上）时才回退到「当前页在最上面」的旋转
  （`(currentIndex + k) % n`）。
  - 以前是**无条件旋转**，于是换位一旦涉及第 0 格，提交后整摞就整体旋转一格 ——
    松手瞬间所有卡片都动一下、手里的那张还飞到最后面去；
  - 曾经用「把 `dragTo` 钳在 `[1, n−1]`」绕开它，代价是**最顶上那张永远不能换位**，
    而且拖了之后会在松手一瞬间闪回原位（预览和提交不一致）。
  - 现在改成"顺序跟随 `viewOrder`"，这两条都消失了。
- 配套：当前页那张卡**不再固定在 child 0**，所以在用到它的地方都要按 `childOfTab(currentIndex)` 查：
  `playEnter()` 的 `front`、`startTabsEnter()` 里的 `heroAsPage(bmp, front)`、
  以及 `desiredScroll()`（进任务视图时滚到"当前页那张卡"，否则换位后起飞帧会从屏幕外飞进来）。

**两个方向必须用同一个深度公式**（早期给 `dir=-1` 用的是固定 1.4 格 ≈ 626px，而 `H` 往往有 730px，
**不够深**）：那样翻转发生时两个矩形还重叠着，翻转就把"让开中的卡"从后面翻到了前面 ——
它于是从手里那张的**下沿**擦着往上顶回来，看着像"蹭着怼回来"。加深到 `H + 0.45·step` 之后，
翻转那一刻它已经整个沉到手里那张下面（两矩形**不重叠**），翻转看不见；
之后它从**后面**升回自己的格子 —— 和往上拖时"从下面绕上来"是同一套观感。

为什么往下让必须"深 + 晚翻"：它最终要画在**前面**（下面一格），所以过程中必然有一次前后翻转。
翻转只有在**两个矩形完全不重叠**时才看不出来 —— 也就是它的**上沿**要越过手里那张的**下沿**。
所以往下让的深度按「手里那张高度 + 0.45 格」算（），到让开波最高点（）
两个矩形刚好错开，那一刻翻到前面，看见的只是它从下面重新顶上来。

> 反过来（`dir=-1`）不用这么讲究：它最终画在**后面**，很早翻过去也会被手里那张挡住，
> 所以只要让开波期间压住就行 —— 这就是为什么"和前面那张换位"一直没出问题。


**为什么不再"逐帧按 p 插值"**：那样两张卡的运动被死死绑在一起 —— 手指一停对面也停、手指快对面就快，
看着像被手指推着走。现在改成三态自走：手里的卡拖到"挨得近"才触发让开，之后它**按自己的速度**沉到底部等着；
等手里的卡进到它原来的格子附近，它再**按自己的速度**滑进空位。两张卡各走各的，手感更像真的木板。

**为什么是 `hShift` 而不是 `translationY`**：卡片是**竖着斜放的平面**，人从斜上方俯视。
"卡片往下挪"在真实空间里是**顺着它自己的平面往远处走**，所以屏幕上是"往下 + 变小"（近大远小）。
`hShift` 把 `hStart/hEnd` 一起加，`Trap.draw` 的投影公式会自己算出这个效果：

```
屏幕纵向   y(h) = h·cosθ·d / (d + h·sinθ)          ← 不是线性的，越远每一步越小
宽度系数        = d / (d + h·sinθ)                  ← 越远越窄
（d = D_OVER_H × hEnd 固定不变）
```

- **`hRef` 必须仍传原来的 `hEnd`**：相机距离只跟卡片本身尺寸有关。若让 d 跟着 hShift 变，
  整张卡的透视强度会被一起改掉 —— 那就成了"变形"，不是"挪动"。
- `hShiftForDy(dy) = dy·d / (cosθ·d − dy·sinθ)` 是 `projY` 的反解，用来把"屏幕上要挪多少"换算成 3D 位移。

**两种位移分工（重要）**：

| 场 | 用途 | 会不会改变卡片大小 |
|---|---|---|
| `TrapezoidView.screenShift`（`canvas.translate`） | 换位时"往上一格 / 往下一格"的整格位移 | **不变** —— 静止时每张卡都按 hShift=0 渲染，尺寸本来就一样；平移才是自洽的 |
| `TrapezoidView.hShift`（hStart/hEnd 同时加） | 只给"让开"那一下的 3D 下沉 | 会 —— 往下近大远小变小；往上会变大 |

早期把整格位移也放进 `hShift`，结果往上让一格时卡片被投影**放大 12% 高、6% 宽**，
松手 `hShift` 归零就"啪"地缩回原尺寸 —— 也就是「被迫交换的卡片在上移到极限时偏大、松手打回原形」。
现在整格位移走 `screenShift`（大小不变、和静止态一致），`hShift` 只剩下沉那段，
`pe → 1` 时它自然回到 0，所以到达新格子那一刻尺寸就已经是最终尺寸了。
- **不再渐隐渐显**：全靠位置 + 前后关系表达"让开/插回"，卡片始终是实心的。
- **0.08 死区**：手里的卡片还没挤到它之前它不动，到位后才顺着平面滑下去 —— 就是"被挤下去"的手感。
- **让开时的前后关系**：早期靠"额外往后压 2.5 格层级"，现在换成下面「前后遮盖」里那套精确规则 —— 只动手里的卡片。
- 因为相邻卡片的 `p` 正好差 1，**任何时刻只有一张卡处在过渡中**，画面不会乱。
- 松手：换了位置走 `commitReorder()`（重建后按各卡当前视觉位置补间）；没换位置走 `settle()`
  （`hShift` 用 220ms `SineOut` 沿平面滑回 0）。

**前后遮盖：只动手里那张，其他卡片一律保持原来的层级**

```
静止/非拖动：elevation = (子视图下标 + 1) × 0.01      // 就是"下标大的压着下标小的"
拖动中：
  passed = clamp(dragFrom + round(s), 0, n-1)
  手里那张：往下拖 → (passed + 1) × 0.01 + 0.005   // 已经压过 passed 那几张
            往上拖 →  passed      × 0.01 + 0.005   // 已经让过 passed 那几张
  其他卡片：z 一动不动
```

**为什么不能"无脑提到最上层 / 压到最下层"**（用户明确要求）：
换位只该改变"这两张之间"的先后。若把让开的那张压到最底层，它会**同时**跑到无关卡片后面 ——
例如交换第 2、3 张时，第 1 张本来不该盖住第 3 张，却会盖住。
所以除手里那张（它要在整摞里"穿过"被压过的那几张）之外，其余卡片的 z 一律不动，
它们彼此的遮盖关系完全不变。`TrapezoidView` 没有 outline，elevation 只参与绘制顺序排序、
**不会画出阴影**，所以随手指连续改也安全。

**尺寸（1/9 线缩放）也必须跟着拖动位置走**：
`updateTransforms()` 里算"这张卡在哪"用的是

```kotlin
val p = cardTopY(c) + shiftScreenDy(c)      // shiftScreenDy = projY(hShift)
```

拖动的位移是 `hShift`（不经过 translationY），不把它算进去的话，被让的卡**升到 1/9 线以上也不会缩小**，
一直按原尺寸画；松手后才被补算 —— 表现就是"上移动画里卡片偏大，动画一结束突然缩一圈"。
现在 `applyDragRestack()` 每帧调一次 `updateTransforms()`（缩放、支点、补偿一起跟上），
`settle()` 里把 `hShift` 滑回 0 的那个 animator 也是每帧调一次。

**翻转时机**：`round(s)` 每变一次，就是"手里那张的顶边越过下一张顶边"的时刻；
那一刻那张卡正好在让开波最高点（已缩下去、被下面几张挡着），所以翻转看不见。
这也是**让开深度必须够**的原因：深度不够时翻转发生在两个矩形还没错开的时刻，就会看到"半途蹦到上层"。

**往下让要沉多深？（几何推导）** 翻转发生在 `round(s)` 变化那一刻，也就是手里那张走完**半格**：

```
让开那张的上沿 = 它的起点 + sm·step + dip        （sm = smoothstep(pe) ≈ 0.435 @ 翻转点）
手里那张的下沿 = 它的起点 − 0.5·step + H
要 上沿 ≥ 下沿  ⇒  dip ≥ H + (0.5 − sm)·step ≈ H + 0.065·step
```

关键结论：**这个下限与「按的是第几张」无关** —— 两张卡是一起动，起点差里的 `f` 会消掉。
它只取决于两件东西：`H`（手里那张的**视觉**高度，会被 1.02 缩放和缩小态影响）和
`step`（标签数决定）。所以实现里就是 `dip = H + DRAG_DIP_DOWN_EXTRA·step`。

（`dir=-1` 用同一个公式时下限是 `H − 0.065·step`，同样被 0.45 格覆盖。）

`DRAG_DIP_DOWN_EXTRA = 0.45` 是在下限之上**额外**加的余量。为什么必须有：只按几何下限算，
翻转那一刻两矩形几乎正好相切（实测只剩 10~30px），掉一帧就会翻早。
加上 0.45 格后实测余量 ≈ **+90 ~ +180px**（探针量的是 `gap = 让开卡上沿 − 手里那张下沿`，
在 5 标签、往上拖两格的场景下取的）。

**拖拽用的临时状态每次都要复位**：`dragS`（本帧拖动进度）在 `beginDrag` / `endDrag` 里必须清零 ——
它决定"手里那张压过了几格"。留着上一次的值，下一次长按第一帧就会把卡片插到很靠前的位置，
表现就是"长按一下，卡片突然跳到最上层"。

**坑**：`settle()` 里不能只调一次 `updateTransforms()` —— 它会把其它卡片的 `translationY`/`alpha`
瞬间写成终值，正在"让开"的那张会从屏幕下方啪地闪回。正确做法：先把别人的当前值存下来，
`updateTransforms()` 之后放回去，再各自补间归位（见坑 27）。

### 7.16 卡片遮光「上亮下暗」（`shadowDepth` / `shadowFrac`）

光是**从上面打下来的**：每张卡只在"被下一张卡压住之前"露出那一条，露出的部分上沿正常亮度、
越往下越暗 —— 就像前面那张卡在挡光。三个属性分工：

| 属性 | 含义 | 取值 |
|---|---|---|
| `shadowDepth` | 从"完全不暗"到"最暗"的**屏幕 px** 距离 | 等于 `cardStep`：正好在这一条的下沿（= 下一张卡的上沿）压到最暗。**最底下那张设 0 = 不画**（它上面没有卡） |
| `shadowAlpha` | 最暗处的黑度 | `0.45` |
| `shadowFrac` | **强度系数** 0~1 | 静止恒 1；只在"长方形 ⇄ 倒梯形"那段动画里从 0 长到 1 / 从 1 退到 0 |

**怎么画的**（`drawShade`）：不逐条带，直接用**和主位图同一个投影**算出这一段的四条边
（上沿 `yT`、下沿 `yB`、上宽 `wT`、下宽 `wB`），拿一条竖直渐变填进这个四边形 ——
透视里梯形的两条侧边仍是直线，四边形就够了。

渐变是**三段近似平方曲线**：`透明 → a·0.10 → a`，位置 `0 / 0.55 / 1`，`TileMode.CLAMP`。
纯线性会让卡片中段就灰掉，看着不像"被挡住的光"；CLAMP 让"最暗"之外保持最暗，
所以深度小于可见高度时（动画中途）下半截是均匀的暗，不会露出一条亮边。

> **顺序**：遮光画在标题条（`overlay`）**之后**。普通卡片没有 overlay，阴影一直是盖整张的；
> 英雄视图如果把阴影画在标题条前面，它那条地址栏会在暗面下"亮着"，和动画结束时真正的卡片对不上。

**11-10 19:30 改：遮光不再"渐隐渐显"，改成按遮挡关系实时算**（`refreshShadows()`）

| 量 | 现在的来源 |
|---|---|
| `shadowDepth` | **挡光那张卡（下一张）的视觉上沿 − 自己这张的视觉上沿**；两张**没有重叠**时恒为 0 |
| `shadowFrac` | 挡光那张的 alpha × `clamp(重叠量 / cardStep, 0, 1)` —— 压得越深挡得越多 |

> 只看"重叠"这一条很关键：进场时两张卡还隔着一整个卡高、根本没有谁压着谁，
> 那时候不该有任何暗带；以前按"直线距离"算深度，远处那张也会给出一段暗，正是
> 用户说的"原有阴影没删掉、现在会冲突"。

于是进场/退场时旁边那张一动，阴影跟着它走（深度变），而不是靠 `p²` 的全局渐隐去"假装"；
最后一排（没有卡压着）深度 0。`drawShade` 的渐变长度也不再夹到卡片下沿 ——
挡光那张被甩远时整张卡只是"很长的淡渐变"，不会在渐变中途出现硬边。

`refreshShadows()` 的调用点：`updateTransforms()` 末尾（滚动/静止）、以及进场/退场的
`startShadowTick()` 每帧 ticker（那两段动画不经过 `updateTransforms`）。
建卡时**不再**预置 `shadowDepth = cardStep`（以前那行会和这里的几何遮光打架，已删）。

<details><summary>历史：最初的做法（已被上面取代，留档）</summary>

**为什么当初要有 `shadowFrac`**：

进场那 400ms 里，卡片是从"一整页长方形"慢慢立成倒梯形的。**起步那一帧前面根本没有卡片压上来**
（其余卡片此时 `alpha = 0` 正在飞入），这时候画阴影就是错的 —— 整页上凭空有一层灰。
所以阴影必须跟着形变**从无到有**；退场（倒梯形 → 长方形）反过来，卡片被"抽走"了，
那层被挡住的暗也要一路退干净，不能留在整页上。

```
进场：hero + standUpCards 的 shadowFrac = p²      （0 → 1）
退场：hero + flatCards   的 shadowFrac = p²      （1 → 0，p 从 1 递减）
```

用 `p²` 而不是 `p`：`apple` 曲线前段涨得快，线性的话动画刚开始 1/4 处阴影就已经 45% 了，
和"前面还没有卡"的状态打架；平方曲线让它在后半段才压下来，正好赶上后面那张卡落位。
退场用同一个式子，因为 `p` 本身就是 1→0，两端天然对称。

**每一条路径都要接上，漏一条就是"啪地跳出阴影"**：

| 位置 | 处理 |
|---|---|
| `refreshTabs()` | 新建的卡 `shadowDepth = if (k < n−1) cardStep else 0`（`shadowFrac` 默认 1） |
| `heroAsPage()` | 英雄视图演的就是第 k 张卡，`shadowDepth` 同样按 `k < tabs.size−1` 给 —— **否则进场动画收尾那一刻阴影会突然冒出来** |
| `playEnter()` | `applyHero(..., p²)` |
| `standUpCards()` | 每张卡起点 `shadowFrac = 0`，动画里 `p²`，`onAnimationEnd` 归 1 |
| `startExit()` | 起点 `applyHero(..., 1f, 1f)`：**第一帧必须和那张卡片逐像素一致**，包括它身上那层遮光 |
| 退场 hero 动画 | `applyHero(..., p²)` |
| 退场 `flatCards`（其余卡片放平） | `shadowFrac = q²`，和 `theta` 同一条曲线 |
| `endHero()` / `finishDismiss()` | `hero.shadowFrac = 1f` 复位，别让下次复用英雄视图时继承退场留下的 0 |

</details>

---

## 8. 投影几何（`Trap`）



```
d(θ,hEnd) = D_OVER_H × hEnd
宽度系数   = d / (d + h·sinθ)
y 投影     = h·cosθ·d / (d + h·sinθ)
projectedHeight(hEnd) = hEnd·cosθ·d/(d+hEnd·sinθ)
diagCrossRatio(θ) = 1/(1+ dOverH/(dOverH+sinθ)) ≈ 0.541（28°）
```
两条辅助：
```
Trap.projY(h, θ, dOverH, hRef)      = h·cosθ·d/(d + h·sinθ)            // d = dOverH×hRef
Trap.hShiftForDy(dy, θ, dOverH, hRef) = dy·d / (cosθ·d − dy·sinθ)      // projY 的反解
```
后者用来把"屏幕上要挪多少"换算成"沿卡片自身平面挪多少"（拖动让位用它，见 7.15）。

`Trap.draw()` 把位图切 **56 条**横向条带逐条 `drawBitmap`；每条源行 = `(h−hStart)/k`；
`dst.bottom+0.7px` 防条带缝；`hRef` 让「只画一段」（标题条）时仍用整段的 d。

**本机实测值（1080×1951，展开态）**

```
cardW = 1080 − 2×dp(8)=48      = 1032     ← 卡片左右各留 8dp（CARD_SIDE_DP）
heroFullH = webContainer.bottom= 1836     ← 「顶栏 + 整张页面」的高度
cardH = 1836 × 1032/1080       = 1755     ← 卡片内容 = 整页（不裁切），见下
cardHeaderH = 121 × 1032/1080  = 115
cardRenderH(28°)               = 1312
cardStep(≥4 张)                = 457      ← 一屏 4 个上沿（不变）
k_end = 1032/1080              = 0.9556
```

**必须同时成立的等式**

| # | 等式 | 意义 |
|---|---|---|
| 1 | `cardHeaderH = 顶栏高 × cardW / 屏宽` | 动画末帧「页面顶栏」与「卡片标题条」逐像素重合（115 ≈ 121×0.9556） |
| 2 | `hero.k(末) = cardW·s / pageW` | 与卡片同宽 |
| 3 | `hero.hEnd(末) = cardH·s` | 同高 |
| 4 | `tabChrome.height == webContainer.bottom == heroFullH()` | 模糊层只盖「顶栏+网页」 |
| 5 | `cardTopY(首张) ≥ shrinkLineY()` | 静止时当前页不缩放（线下 2dp） |
| 6 | `fieldBox.height(p=1) == topBar.height(p=1) == 24dp` | 网址上下等宽 |
| 7 | `suggestEnterOffset = 页面首排上沿 − 浮层首排上沿 = 40.7dp` | 磁贴两端贴合 |
| 8 | `cardW/H/HeaderH` 只依赖屏幕与顶栏 | 预热缓存有效 |
| 9 | **英雄视图底边 ≤ `webContainer.bottom`** | 不会盖住工具栏造成"底栏空白一下" |
| 10 | `hero.top == heroBaseY`（第一帧就成立） | 起飞帧不闪（见 10.9） |
| 11 | **`cardH = heroFullH × cardW/屏宽`** | 卡片装的是**整张页面**（起飞帧同一份内容，只是小一号）：投影源行数 = `cardH/k` = `heroFullH`，一行不少 |

**卡片要装下整页，不能只装上半截（10-25 改）**

以前 `cardH = 屏高 × 0.62`（1209）：卡片位图里只有「标题条 + 页面**上半截**」，
`renderCard()` 的缩略图是「按宽度铺满、顶部对齐」，超出的部分被 ImageView 裁掉 ——
于是倒梯形卡片看起来把页面截图的下半截切掉了。

现在 `cardH = heroFullH × cardW/屏宽`（1755），也就是**起飞帧那张图按 0.9556 缩一号**：

- 缩略图区高度 = `cardH − cardHeaderH − 1` ≈ 1639，而页面截图按宽度缩放后正好 1639 → **一行不裁**；
- 投影时源行数 = `cardH / k` = `heroFullH`，整张图都映射进梯形里（透视压短，不是裁掉）；
- 起飞帧（整页）和卡片是同一份内容的不同尺寸，英雄视图收尾时不会"只剩半张"。

代价：`cardRenderH` 从 904 涨到 1312（屏高的 67%），卡片更高、叠得更深；
`cardStep` 仍是 457（一屏 4 个上沿的约束不变），所以每张卡露出的那一截比例更小。

---

## 9. 你的需求 → 落点对照（累计）

| # | 要求 | 实现 | 状态 |
|---|---|---|---|
| 1–8 | 模糊层半截 / 取消竖排 / 取消手感 / 网址居中 / 磁贴对齐 / 磁贴动画 / 字重 / 被裁断 | `clipToPadding`、锁宽、`centerPad`、40.7dp、Light 字重… | ✅ |
| 9 | 点＋看不到磁贴 | `flyNewTabWhenReady` 等 pageDone | ✅ |
| 10 | 系统状态栏 + 动画不含状态栏 | 删假状态栏，`contentTopY=0` | ✅ |
| 11 | 默认必应 | `searchUrl()` | ✅ |
| 12–13 | 首进闪屏 / 缩略图同步 | 预热 + blurKey + `updateThumbFromFrame` | ✅ |
| 14–15 | 地址栏加宽加大 / 收缩更激进 | 385×34dp、18sp、44→24dp | ✅ |
| 16 | 底栏交叉淡化 | 模糊层不盖底栏 + `fadeTabBottomBar` | ✅ |
| 17 | 收缩灵敏/抖 | 单侧累加 + 20/8dp 滞回 | ✅ |
| 18 | 私密加号居中 | 独立 FrameLayout | ✅ |
| 19 | 编辑态左边距 | padStart 14dp | ✅ |
| 20–21 | 点卡片抽一下 / 动画跑下半屏 | `heroBaseY` 反解、常量落点、`listLaidOut` | ✅ |
| 22 | ＋飞入底栏不稳定 | 底边钉 `heroFullH` + 同一套淡出 | ✅ |
| 23 | 点卡片延迟 1 秒 / 飞入掉帧 | `composeFrame`、`capture` 延后、预热延后 | ✅ |
| 24 | 连点导致任务视图消失 | `heroAnimId` 代号 + `flySeq` | ✅ |
| 25 | 缩到 1/9 线 | `shrinkLineY()/9` | ✅ |
| 26 | 两端橡皮筋 + 弹簧 | `stackShift` + overshoot | ✅ |
| 27 | 标题条「像打印机吐纸」 | `overlay` + `overlayFrac` + `hRef` | ✅ |
| 28 | 弹簧慢一点 / 软一点 | 540ms + Overshoot 1.15 | ✅ |
| 29 | 间距更宽、一屏 4 个 | step 457 | ✅ |
| 30 | 默认俯角更立体 | 28° | ✅ |
| 31 | 越界也执行缩放（旋转感） | `cardTopY` 含位移 + 位移变化即 `updateTransforms` | ✅ |
| 32 | 顶边下拉变稀疏（上小下大） | `1−cos` 累计 | ✅（有覆盖 bug，见 12.1） |
| 33 | 去掉"高度截短"、保留并加强俯角 | `hEnd=cardH` + 真实 θ/d | ✅ |
| 34 | 3D 要像真实明信片 | θ+10°、d×0.75（比例一致） | ✅ |
| 35 | 滚动更慢更稳 + 撞边不加惯性 | 自定义 fling | ✅ |
| 36 | 网页也用这套 | `ObservableWebView` 接管 | ✅ |
| 37 | 取消按钮左右间距一致 | padEnd 4dp（两侧 8dp） | ✅ |
| 38 | 甩到底「去」保留、「回」去掉 | 冲过头后不回位 | ✅ |
| 39 | 网页手感再轻 | 0.85 / 1.8 | ✅ |
| 40 | 点卡片偶尔闪一帧截图 | `hero.layout()` 立即摆 frame | ✅ |
| 41 | 应用图标 | 位图 + 自适应图标 | ✅ |
| 42 | 滚动网页时栏不要跟着卡 | 固定 viewport（§3.1）+ 手指驱动收缩（§7.2） | ✅ |
| 43 | 标签多时点完成，其他卡的动画来不及 | 卡片让路按张数定时，≤300ms；`budget+80ms` 硬撤（§7.5） | ✅ |
| 44 | 缩小态卡片与英雄动画对不齐 | `cardState` 补 `pivotY·(1−s)`（§7.5） | ✅ |
| 45 | 退场时当前卡不许「啪」地跳到最上层 | 英雄视图保持 `HERO_Z`，靠卡片让开逐段露出（§7.5） | ✅ |
| 46 | 换位要被"挤下去"、像木板互换 | 三态自走 + 让开深度 `H+0.45·step` + 上下落位（§7.15） | ✅ |
| 47 | 换位时的 3D 近大远小 | `hShift`（沿平面下沉）＋ `screenShift`（整格平移，不改大小）（§7.15） | ✅ |
| 48 | 换位只影响这两张，别动别人的层级 | 层级只动手里那张（下标 + 穿越），其余不动（§7.15） | ✅ |
| 49 | 最顶上那张也要能换位 | 顺序跟随 `viewOrder`，`dragTo` 放开到 0…n−1（§7.15） | ✅ |
| 50 | 换位别一拖就触发 | `DRAG_SWAP_ON=0.62` / `OFF=0.42` 滞回（§7.15） | ✅ |
| 51 | 左滑删除不要渐隐 | 去掉 alpha 淡出（§7.6） | ✅ |
| 52 | 没换成时松手要自己归位、不要闪现 | `settlingView` + `Decelerate(1.8)`/260ms（§7.15） | ✅ |
| 53 | 被拖的卡也要有重量（先慢后快） | 指数趋近跟随，`DRAG_FOLLOW_TAU=0.06`（§7.15） | ✅ |
| 54 | 点地址栏先给收藏磁贴，别给搜索推荐 | `showSuggestions()` → `renderFavTiles()`；输入字符才切推荐（§6.4） | ✅ |
| 55 | 磁贴渐显 + 缩放同时完成（现 2s、0.7→1.0） | `refreshFavGrid(animate)`：alpha/scale 同曲线同时长（§6.4） | ✅ |
| 56 | 在起始页点地址栏时磁贴只上移、不要再渐显放大 | `isOnStartPage()` → `animate = false`（§6.4） | ✅ |
| 57 | 点地址栏左移的动画要苹果那条非线性曲线 | `apple = PathInterpolator(0.25,0.1,0.25,1)`，三段共用（§7.3） | ✅ |
| 58 | 出场 / 磁贴上移下移 / 栏收缩舒张 / 进出任务视图 都用苹果曲线 | 全部 `apple`（§7.1、7.3、7.4、7.5、7.10） | ✅ |
| 59 | 编辑态：刷新按钮不左移、渐隐被矩形盖住，原位换成小圆盘叉 | `applyFieldEdit()`：反补 `+Wc·f` + 渐隐；`btnClear` 17dp 圆盘叉渐显（§7.3） | ✅ |
| 60 | 删空网址时磁贴不要重播出现动画 | `renderFavTiles(force)`：已在显示就直接返回（§6.4） | ✅ |
| 61 | 书签页进出 / 新标签飞入也用苹果曲线；书签页进出要慢一点 | 全换 `apple`；书签页进出统一 `BOOKMARK_MS = 330ms`（§7.6、7.11） | ✅ |
| 78 | 缩到 0.6 之后继续上滑，那张卡要**往下走**（像转盘/齿轮转过最高点）；往回滑先升回"刚好 0.6"再变大 | `crest`（`SHRINK_CREST`，指数渐近）+ `roll`（`SHRINK_ROLL`，半余弦，`over>span2` 才生效）。纯位置函数 → 正反天然对称（§7.7） | ✅ |
| 77 | 顶到头之后卡片"死了"，手指再推一动不动，而且顶边还往回沉 | **软钉**：`creep = softPin·(1−e^(−over/softPin))`，`softPin = span2×0.14`；over=0 处斜率正好 1，和上一段"上沿跟手"C1 接上；渐近线有上限不会顶进状态栏（§7.7） | ✅（已被 78 的 `crest` 取代） |
| 76 | 滚出 1/9 线的缩放"太生硬"，要那种井井有序、流畅优雅的后推 | ① 两段线性 → **半余弦**（`easeHalfCos`，两端斜率 0，拼缝 C1 连续，锚点不动）；② `scaleX` 加 35ms 指数低通（`SHRINK_SMOOTH_TAU_MS`，存 `scaleSmooth`，带"别人写过就跟随""跨步太大直接给"两条豁免）；③ `ty` 用平滑后的 s 算，未过线一侧也补 `−cross·(1−s)`（§7.7） | ✅ |
| 74 | 卡片要有"被前面那张挡住的光"：每张被遮住的卡**上沿正常亮度、越往下越暗** | `TrapezoidView.shadowDepth/shadowAlpha` + `drawShade()` 用同一投影画四边形 + 三段近似平方渐变（§7.16） | ✅ |
| 75 | 进任务视图（长方形→倒梯形）阴影**从无到有**；退出（倒梯形→长方形）**从有到无** | `shadowFrac`：进场 `p²` 0→1、退场 `p²` 1→0，hero 与其余卡片同一条；遮光改画在标题条之后（§7.16） | ✅ |
| 72 | 进场时当前页那张卡的上沿**精确**压在 1/9 线上（哪怕上一轮滑到最底、它已缩小过） | `alignCurrentCardToRest()`：粗滚 + 实测补滚（§7.4） | ✅ |
| 71 | 倒梯形卡片装下**整张页面截图**（不裁掉下半截） | `cardH = heroFullH × cardW/屏宽`（§7.4「卡片要装下整页」） | ✅ |
| 70 | 当前页那张卡的上沿固定落在「上 1/9 分界线」下方 2dp（不高于该线，否则会缩小） | `refreshTabs()` 的 `topPad = restTop`（§7.4） | ✅ |
| 73 | 进/退任务视图时**所有卡片旋转方向、角度完全一致**（当前页上方那几张也不许反向） | 进场一律从下方升上来 + 缩放统一 `1→静止s`；退场所有卡片一起 `theta:tiltNow→0`（§7.4） | ✅ |
| 69 | 进任务视图时其余卡片**绕上沿起立 + 向当前卡片靠拢**，与当前页同一段动画 | `standUpCards()`：`theta 0→tiltNow`、`ty (k−skip)·(cardH−cardStep)→0`、`alpha 0→1`，400ms `apple`（§7.4） | ✅ |
| 68 | 任务视图：深黑背景 + 深色底栏，**都保留毛玻璃**；状态栏图标随底色变白 | `#B3000000` / `tabBarScrim` / `setStatusBarIconsWhite()`（§7.4 配色表） | ✅ |
| 67 | 任务视图卡片上沿几乎顶到屏幕两边 | `CARD_SIDE_DP = 8f`（原 18f，§7.4 配色表） | ✅ |
| 66 | 任务视图卡片缩略图**保留当前滚动位置**（不回到页顶） | 缩略图直接裁自起飞帧（`updateThumbFromFrame`，§7.4 流程） | ✅ 已有 |
| 65 | 离开编辑态时磁贴页**渐隐**关闭（返回、回车都一样） | `hideSuggestions()`：`alpha 1→0`、`SUGGEST_EXIT_MS=240ms`、`apple`（§7.3「退出的时序」） | ✅ |
| 64 | 编辑态按**一次**返回就全收（键盘+磁贴页+地址栏回常态） | `UrlEditText.onKeyPreIme` 抢在 IME 之前接 BACK（§7.3「退出的时序」） | ✅ |
| 63 | 按下先加深、点击才左移、左移完才出键盘、长按只弹复制 | 见 §7.3「按下的时序」 | ✅ |
| 62 | 起始页地址栏显示「刷新」而不是「✕」 | `syncReloadIcon()`：起始页恒为 `ic_reload`，点它重新渲染起始页（§6.1） | ✅ |

---

## 10. 关键坑（改代码前必读）

1. **`clipChildren` 与 `clipToPadding` 是两套裁剪**：英雄视图靠负 topMargin 顶到列表 padding 之上，两个都必须关。
2. **0 尺寸不能画**：`TrapezoidView.onDraw` 开头 `if (width<=0||height<=0) return`，否则 `cx=0` → 左上角闪图。
3. **`wrap_content` 的「取消」会被挤成竖排**：必须量完锁 `lp.width`。
4. **`includeFontPadding` 让文字偏下**：地址栏/取消/卡片标题都要关。
5. **WebView 软件绘制不可信**：`draw()` 会返回全透明/全白/偏移；起飞帧用 PixelCopy，卡片帧用 `composeFrame`。
6. **`hero.top` 会晚一帧**：滚动过之后要等 layout，所以 `applyHero` 用 `heroBaseY` 反解。
7. **列表刚重建时子视图 0×0**：落点用常量算 + `listLaidOut()` 闸门。
8. **`updateTransforms()` 会把 alpha 恢复成 1**：进场那一帧要在它之后再清一次。
9. **hero 第一帧必须用「刚设置的 margin」**：`heroAsPage` 给完 `layoutParams` 后立刻 `hero.layout(...)`；
   否则列表滚动过时第一帧用旧 `top`，会闪一帧偏下截图（10-23 18:21 修）。
10. **起飞前必须把栏复位成展开态**：`cardHeaderH` 与取景都按 44dp 算。
11. **模糊图不能用 centerCrop**：`tabChrome` 被裁到 1830 后 centerCrop 会整体上移 ~60px。
12. **`VelocityTracker` 给的是手指速度**，`scrollBy` 要的是滚动位移 → **必须取反**（系统 `ScrollView.fling(-v)` 同理）。
13. **越界累积量必须复位**：`shiftExcess` 跨手势残留 → 下一次一碰边缘就一帧弹到最大（卡片集体瞬移）。
14. **加探针只能新增行**：两次事故都是「replace 掉原有语句」/「按区间删」把真代码删了（`fadeTabBottomBar(150)`、`shiftExcess = 0f`）。
15. **连点要防重入**：英雄动画同一时刻只能有一个（`heroAnimId`），否则旧动画的 `finishDismiss()` 会关掉新动画的浮层。
16. **`tabScroll` 的 `clipChildren` 必须为 true**：卡片栈比视口长时（标签多了），卡片会画到 1830 以下、
    盖住浏览页底栏；任务视图底栏一淡出，那条位置就露出"下面那张卡片"，看着像底栏真空。
    `clipToPadding` 仍为 false（英雄视图要用负 margin 顶到列表 padding 之上）。
17. **`clipChildren` 对 `elevation > 0` 的子视图无效**：卡片为了压住相邻卡片设了 `elevation`（Z>0），
    ViewGroup 会把带 Z 的子视图单独当一层画，`clipChildren=true` 裁不到它。
    所以只改 XML 属性不够，必须在 `bindViews()` 里给 `tabScroll.clipBounds = Rect(0,0,w,h)`（布局变化时重设）。
    `clipBounds` 是裁这一层自己的绘制，与 Z 无关，最稳。
    复核方法：把 `tabScroll` 画到一张比它高 130px 的 bitmap 上，取 `height+20` / `height+100` 处的像素，
    应为 `0x0`（全透明）而不是白色卡片。
18. **`updateTransforms()` 里 `ty` 只能 `+=`，不能 `=`**：`ty` 初值是「越界变稀疏」的那段位移，
    缩放补偿一用 `=` 就把它整体覆盖 —— 越过 1/9 线的几张（正好是屏幕最上面、最该拉开的几张）
    在顶边下拉时纹丝不动，间距不单调。改这个函数时先想清楚 `ty` 里已经有什么。
19. **背景模糊必须在浮层 `VISIBLE` 之前就绪**：模糊图是给 `tabChrome` 用的，它下面只有
    `#B3000000` 半透明蒙版 —— 图没到位时透出来的是**清晰页面**。正式版在后台线程上，
    冷启动第一遍要一百多毫秒，所以 `startTabsEnter()` 里先用 `ensureBackdrop()` 同步铺一张
    「快速版」再显示浮层（见 7.14）。任何"等后台算完"的写法都会让第一次进场的前半截没模糊。
20. **背景模糊的源优先用起飞帧**：`rootView.draw()` 走软件绘制，WebView 会返回全白/全透明/偏移；
    而 `heroComposite` 是 PixelCopy 出来的真实屏幕，既准又快（省掉那一下 UI 线程重活）。
21. **重建列表后先定 `scrollY`，再算落点**：`rebuildAnimated()` 里 `scrollTo` 排在 animator 之后
    会让整摞卡片在动画一开始整体跳一段（标签多、`scrollY>0` 时必现，看起来就是"补位动画丢了"）。
22. **同一个属性不要两套东西同时写**：卡片位置由 `updateTransforms()` 统一算（含缩放补偿），
    它就绝不能再被 `view.animate().translationY()` 并行写 —— 谁后写谁赢，结果就是位置乱跳。
    要动画就整批用一个 animator 推进（见 7.13）。
23. **不要再读 `webContainer.top / bottom / height` 当「网页可见区」**：它是固定 viewport
    （布局 y 恒 66..1951、高度恒 1885），可见区要靠 `webTopVisual()` / `webBottomVisual()` /
    `webVisibleH()` 算（见 3.1 的口径表）。改造时踩过的具体后果：
    ① `heroFullH()` 用容器底 → 英雄视图比真实页面高 176px，底边压进工具栏；
    ② 缩略图裁切用容器高 → `Bitmap.createBitmap` 越界，整条 `updateThumbFromFrame` 静默失效（卡片永远旧图）。
24. **退场时 inflight 的惯性会把卡片位移偷走**：`TabScrollView.blocked` 以前只挡触摸
    （`dispatchTouchEvent`），已经在跑的惯性照样 `scrollBy` —— 英雄动画下面整摞卡片还在动，
    退场算好的绝对落点全部失准，会有一张卡停在页面上。`stepFling()` 现在开头就查 `blocked`。
25. **凡是「按屏幕位置算距离」的地方，都必须用 `cardVisualTop()` / `cardVisualH()`**
    （它们也把 3D 位移 `projY(hShift)` 算进去了）：
    `cardTopY + translationY` 只是"未缩放外框"的顶，卡片一旦 `scaleY = s ≠ 1`，
    真正画出来的上沿还要加 `pivotY·(1−s)`（见 7.5；s=0.6 时 **277px**）。
    踩过的两个具体后果：① 起飞/落点对不齐（"动画和卡片错一小截"）；
    ② 退场"飞出屏幕"少飞 277px，动画跑完了卡片却停在屏幕里（残卡）。
26. **卡片的"前后"只由「下标 + 手里那张的穿越」决定**：静止时是"下标大的压着下标小的"。
    拖动换位时**只改手里那张的 z**（按它压过/让过几格插进下标序列），其余卡片一律不动 ——
    这样换位只影响这两张之间的先后，不会牵连第 1、4 张。
    绝不要按"视觉位置"给所有卡片排序：① 缩小的卡片视觉上沿被支点补偿往下推最多 277px，
    会被顶到邻居前面；② 让开的卡片会被一起重排，进而改变它和无关卡片的前后关系。
27. **`updateTransforms()` 会顺手把「别人」写成终值**：拖拽松手时 `settle()` 里如果直接
    `updateTransforms()`，正在让开的那张卡片会瞬间闪回原位（它的 `translationY`/`alpha` 被覆盖）。
    先把别人的当前值存下来、`updateTransforms()` 之后再放回、再各自补间（见 7.15）。
28. **`captureComposite()` 要连画两层**，且 `View.draw()` 不带 `translation`：网页层是靠
    `translationY` 跟栏走的，漏加会整体偏高 20dp；只画 `getChildAt(0)` 会只剩网页、没有顶栏。

29. **拖拽的临时量（`dragS` / `restackPending`）必须在 `beginDrag`、`endDrag` 里清零**：
    `dragS` 决定"手里那张压过了几格"，留着上一次的值，下次一长按就会把卡片插到最前面
    （"长按后卡片跳到最上层"就是这么来的）。
---

## 11. 性能设计（现状）

| 手段 | 说明 |
|---|---|
| **固定 viewport**（3.1） | 栏收起/展开不再改 WebView 的视口，页面零重排；网页层只用 `translationY` 跟手（纯 GPU 变换） |
| 起飞帧 PixelCopy | 读 GPU 合成后的真实像素，不在点击帧软件绘制 WebView |
| `composeFrame()` | 顶栏 draw + 已有 thumb 1:1 贴图，毫秒级 |
| 模糊异步 | 主线程只画 1/4 小图；blur+裁剪在**后台线程（BACKGROUND 优先级）**做，`blurSeq` 丢过期结果 |
| blur 2 遍 + 复用缓冲 | CPU 减半，每次少分配 ~0.6MB |
| `isBlankBitmap` | 按行 `getPixels`（46 次 JNI）替代逐点 `getPixel`（1200 次） |
| `updateTransforms` 守卫 | 值不变就不写 pivot/scale/translation/alpha |
| `setBars` 守卫 | 高度/字号不变就不触发布局（现在只影响栏层 3 个 View） |
| `applyHero` 守卫 | 参数不变就不 invalidate |
| 缓存 | `cardCache`（7 张、带几何指纹）、`blurKey`、`heroComposite` 复用 |

实测（Mix 2S，4 标签、进出各 3 轮）：p50 22ms / p90 25ms / p95 26ms（优化前后基本持平，收益主要在"点击到动画"的延迟与内存抖动）。

---

## 12. 已知问题 / 死代码 / 待办

### 12.1 10-24 这一轮清掉的四个问题（已修，留档）

1. **稀疏位移被覆盖**：`updateTransforms()` 里 `if (p < line)` 分支用 `ty = …` 整体赋值，
   抹掉了前面算好的稀疏位移 → 顶边下拉时越过 1/9 线的几张间距不增大。已改成 `ty +=`。
2. **`fromFling` 死分支**：「惯性甩过头不回位」之后，`releaseStackShift(true)` / `releaseSteep(true)`
   的 320ms 快回位分支永远走不到。已把 `fromFling` 从这两个函数里删掉，只留「拖动松手 → 540ms 软弹簧」，
   并把 `onOverscrollEnd` 的注释、`TabScrollView` 里 `EDGE_DECAY` 等几处过期注释一并改对。
3. **死代码**：`TrapezoidView.widthBend`（恒 0 的横向透视开关）、`bitmap2`/`mix`（双层交叉淡化，早已不用）、
   `CardTileView`（整类没人用）、`tabFade.alpha`（恒 1 的赋值）—— 全删。`Trap.draw()` 因此少一个参数。
4. **`diagCrossY()` 的相机距离**：改为接受 `dOverH`，`updateTransforms()` 传这一帧的实际值
   （`2.6 × (1 − steep×0.25)`）。顶边下拉时「俯身」和「缩到 0.8 以下」会同时发生，支点不再偏。

### 12.2 之前遗留

- `loading` / `progress` 全局，不区分标签页。
- 缩略图与卡片位图全分辨率（8MB/标签 + 33MB 缓存），多标签有 OOM 风险。
- `blurKey` 不含 `privateMode` —— 现在切模式时直接 `blurKey = null` 强制重算，等于绕过了这个问题。
- ~~栏收起/展开逐帧改 WebView 的 viewport~~ → **10-24 已改造为固定 viewport**（见 3.1）。
  遗留的两点已知副作用：① 短页面底部 64dp 靠 `injectBottomInset()` 注入的 padding 才够得着
  （注入失败就仍是看不到）；② `position:fixed;bottom:0` 的元素在栏展开时被底栏盖住。
- 键盘弹出时 `rootView.paddingBottom` 变化会改 WebView 的 viewport（一次重排），
  这是刻意保留的「键盘让位」行为，不是漏改。
- WebView 惯性接管基于「吞掉 UP」，JS 自绘滚动的页面会收到 touchcancel 且失去惯性。
- 部分命中区仍硬编码 dp（`38dp` 关闭叉、边缘手势 36dp），换屏宽需复核。

### 12.3 10-24 收尾：全量复查结论（几何 / 动画 / 性能 / 内存）

**几何与控件定位**（按 §3.1 的口径表逐项核对）

| 检查项 | 结论 |
|---|---|
| `topBar` / `bottomWrap` / `webContainer` 三处高度与 `translationY` | 自洽：可见上沿恒等于顶栏下沿；`tabChrome` 高 = 1830 实测一致 |
| 英雄视图取景（`heroFullH` / `heroTopY` / `heroAsPage`） | 全部走 `webBottomVisual()`，与固定 viewport 改造后一致 |
| 缩略图裁切（`updateThumbFromFrame`）、起飞帧拼装（`composeFrame`） | 已改用 `webTopVisual()` / `webVisibleH()`，不再越界 |
| 卡片"视觉上沿"口径（`cardVisualTop` / `cardSlotTop` / `cardState` / `rebuildAnimated` / `snapshotPositions`） | 全部含 `pivotY·(1−s)`；只有"排层级"那处**故意**不含（见 §7.15） |
| 命中区 | 关闭叉 38dp、边缘手势 36dp、死区 12/14dp —— 硬编码但都是手势容差，换屏宽不影响正确性 |
| 任务视图 ↔ 当前页 | `playEnter` / `startTabsEnter` / `desiredScroll` 都改成 `childOfTab(currentIndex)`，不再假设 child 0 |

**动画一致性**

- 三种"卡片让位"入口（点叉关闭 / 左滑删除 / 拖动换位）最后都汇到 `rebuildAnimated()`，只有一套补间实现。
- `updateTransforms()` 的三处"跳过"（`dragView` / `hero` / `settlingView`）各自对应一个正在被别的 animator 驱动的卡片，
  少任何一个都会出现"某张卡被写回原位"的跳变。
- 所有 `elevation` 写入只有两个来源：`applyStackOrder()`（拖拽中）和 `resetStackOrder()`（归位后），
  进场/退场由 `heroAsPage()` 显式分配 —— 三套不重叠。

**性能**

- 拖拽逐帧开销：一次 `applyDragRestack`（n 次取整/排序，无分配）+ 至多一张卡重绘 + 至多两次 `elevation` 写入。
- `gfxinfo` 实测（同一套注入手势）：拖拽 0~4 帧超时、p90 18~32ms；注入手势本身抖动大，按区间看。
- 阻塞式重活只剩两类：重建列表时 `renderFlat` 的位图（有 `cardCache`）、以及页面加载后的 `capture()` 软件绘制。

**内存**

- 13 标签实测：Native Heap ≈267MB（位图都在 native）、Dalvik alloc ≈5MB —— 不是 Java GC 问题。
- `cardCache` 上限 7、缩略图全分辨率，仍是已知的取舍（见 12.2）。

**本次复查发现、但**没有**改的问题（等确认）

1. ~~换位之后删标签会让整摞重新旋转~~ → **已修（10-24 21:25）**：`removeTabAt()` 现在同步维护 `viewOrder`
   （删掉那个 tab 的下标 + 后面的整体前移一位），所以"拖好的顺序"在删除标签后保持不变，卡片只做补位滑动。
2. **`newTab()` / `selectTab()` 仍然走"当前页在最上面"的旋转 —— 这是刻意的，不是漏改。**
   两条行为并存，各管一段：
   - **拖动换位**：顺序跟着手指走，`viewOrder` 记住它；
   - **删除标签**：顺序不动，只做补位（上面第 1 条）；
   - **新建标签 / 切换页面**：整摞重新按"当前页在最上面"排一次，摆位过程由 `rebuildAnimated()` 补间
     （卡片从原位滑到新位）—— 这正是原本那套"旋转归位"的动画，也是被明确保留的观感。
   `newTab()` 必须保持旋转：`＋` 的飞入动画（`playNewTabFly`）是按"新页在最前面"设计的。
3. `pageH()` / `pageTop()` 已无人调用（死代码，留着当文档）。
4. `DRAG_DIP_DOWN_EXTRA=0.45` 的余量是按 5~7 标签实测的；标签数很少（step 变大到 780）时余量会薄一些，
   但几何下限随 step 一起变大，仍在安全侧。

---

## 13. 构建 / 交付

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
> 多台设备同时连着时必须带 `-s <serial>`。
> 调试用机是小米 MIX 2S（lineage_polaris，Android 10 / API 29），最低验证到 API 26。

---

## 14. 改代码前的检查清单

1. 动「顶栏高度 / 卡片宽度 / 屏宽」→ 复核等式 1、2、3、4。
2. 动「地址栏高度」→ 复核等式 6 与建议层 44dp。
3. 动「磁贴尺寸 / 行距 / 顶栏间距」→ 复核等式 7 并同步 `startPageHtml()` 的 CSS。
4. 动「列表 padding / margin」→ 复核 `heroAsPage()` 的常量累加。
4b. 动「顶栏/底栏高度、`webContainer` 的 `marginTop`、`BAR_*` 常量」→ 四者必须自洽：
   `marginTop == BAR_H_COLLAPSED_DP`、`BAR_SLIDE_DP == 44 − 24`、`BOTTOM_INSET_DP == 44 + 20`；
   改完按 3.1 的「实测不变量」表复核一遍（topBar 边界、webContainer 可见边界、WebView 布局边界、
   tabChrome 高度）。**任何"网页可见区"都不要直接读 `webContainer.top/bottom/height`。**
5. 新增越出父边界的布局 → 父容器同时关 `clipChildren` 与 `clipToPadding`；
   反过来，**要求真的裁住**（如 `tabScroll`）时，若子视图带 `elevation`，还要设 `clipBounds`（见坑 17）。
6. 新增「按屏幕坐标定位」→ 一律用 `rootView` 坐标（状态栏下方），不要再减 20dp。
7. 任何"动画第一帧"→ 检查：子视图是否已布局、alpha 是否被 `updateTransforms` 恢复、`hero.top` 是否可能晚一帧。
8. 加临时日志 → **只新增整行**，清理时按行过滤；不要 replace 已有语句。
9. 改完跑：启动 → 滚动收缩/展开 → 进任务视图（拖两端/甩两端）→ 点其他卡片 → 点「＋」→ 长按拖拽 → 关闭标签 → 书签页 → 私密模式；`adb logcat -b crash` 应为空。

---

## 15. 修订记录

| 时间 | 内容 |
|---|---|
| 10-22 第 14 轮 | 「取消」竖排：锁 `lp.width` |
| 10-22 第 15 轮 | 地址栏居中基准、取消手感、磁贴对齐、字重、**`clipToPadding` 根因**（地址栏被裁断） |
| 10-23 11:12 | 文档首版（14 章）+ 3 条审查修复：id 索引、空白位图判定、底栏毛玻璃被盖 |
| 10-23 11:24 | 「＋」飞入底边压进工具栏 → 底边钉 `heroFullH`；补回误删的 `fadeTabBottomBar(150)` |
| 10-23 11:29 | 连点产生两个英雄动画 → `heroAnimId`；点击路径改 `composeFrame`；`capture`/预热延后；`flySeq` |
| 10-23 11:43 | 收缩线 1/8 → 1/9；修正 `dp(18)=49` 的 1px 误差（cardW 982 / header 110 / k 0.9093） |
| 10-23 15:52 | 应用图标（位图 + 自适应 + 圆形）；6 项性能优化（异步模糊等） |
| 10-23 16:17 | 两端橡皮筋 + 弹簧；`TabScrollView` 拆出 `onOverscrollEnd`；标题条 `overlay` 吐出 |
| 10-23 16:29 | 橡皮筋瞬移根因：`shiftExcess` 未复位（探针事故） |
| 10-23 16:43 | 越界也执行缩放（`cardTopY` 含位移 + 位移变化即 `updateTransforms`） |
| 10-23 16:59 | 顶边下拉稀疏（上小下大）；去掉高度截短；俯角加强 |
| 10-23 17:12 | 间距 4 张/屏、俯角 28°、弹簧 540/1.15、稀疏系数 ×1.2 |
| 10-23 17:19 | 弯腰改真实 3D：θ+10°、d×0.75（比例一致，不再果冻） |
| 10-23 17:29 | 自定义惯性滚动（任务视图）；撞边"冲过头" |
| 10-23 17:43 | 网页也用同一套惯性（CANCEL 接管，方向取反坑）；取消按钮 padEnd 4dp |
| 10-23 18:04 | 甩到底：EDGE_DECAY 快速吃完 + 320ms 平滑回位；网页再调轻 |
| 10-23 18:09 | 甩过头后**完全不回位**（配 `applyHero` 补偿 + `finishDismiss` 复位） |
| 10-23 18:21 | 点卡片偶发闪一帧偏下截图 → `hero.layout()` 立即摆 frame |
| 10-24 13:03 | 全量复核并重写本文档；新增第 11 章（性能）、第 12 章（问题清单）、等式 10 |
| 10-24 13:24 | 修「标签多了以后，点＋飞入时底部有一段时间没有底栏」：`tabScroll` 的 `clipChildren` 改回 true（卡片画到工具栏上盖住了浏览页底栏）；顺带冻结「任务视图动画期间的顶栏/底栏收缩」，并让飞入/退场的取景高度每帧现取 |
| 10-24 13:38 | 上一条只修了一半：卡片带 `elevation`（Z>0），`clipChildren` 裁不住 → 任务视图底栏淡出时半透明会透出下面的卡片截图。改为在 `bindViews()` 里强制 `tabScroll.clipBounds`（探针验证 `height+20`/`height+100` 像素为 `0x0`） |
| 10-24 23:54 | 找到空白地址栏长按无反应的**真凶**：`buildAndShowMenu()` 开头的 `|| urlField.text.isEmpty()` 守卫把空文本场景整个挡掉；改为只判 `items.isEmpty()` |
| 10-24 23:52 | 粘贴改为**左对齐**显示（只调 scrollX 治不好：折叠态是居中布局，长网址只露右半截）；长按改用**自带 420ms 计时器**（框架 long-click 在 EditText 聚焦时被系统选择逻辑吃掉），空白地址栏按住即弹「粘贴」 |
| 10-24 23:46 | 粘贴后**下一帧**再压一次 `setSelection(0)/scrollX=0`（EditText 在 layout 时才滚到光标，同步设一次会被覆盖）；空白地址栏的「粘贴」菜单改到 `post` 里弹（手势途中弹会被 UP 收掉） |
| 10-24 23:40 | 修「粘贴后只见右半截」（粘贴后 `setSelection(0)`）；空白地址栏长按**当场**弹「粘贴」（`fieldMenuEarly`，松手不重复弹） |
| 10-24 23:35 | 长按菜单：松手先**全选**网址，菜单去掉「全选」（只剩 复制 / 粘贴 / 删除）；点「删除」→ `startFieldEdit(blank=true)` 直接进空白左对齐编辑态；加深/渐浅 130→**220ms** 更柔和 |
| 11-10 23:40 | **应用图标改矢量**：原来 5 个密度 × 3 张 PNG（ic_launcher / round / foreground）共 **409KB**。先用 PIL 对 432px 的旧 PNG 采样量出几何（圆心 215.5、半径 127.5、刻度带 0.70R~0.95R、60 根、指针半宽 15.5、针尖 r=118、渐变自上 #3FCBFF 而下 #0A6BFF），据此生成 `drawable/ic_launcher_foreground.xml`（**3.4KB**，圆盘用 `aapt:attr` 内联渐变）。`minSdk = 26` ⇒ `mipmap-anydpi-v26` 的 adaptive-icon 永远优先，旧的密度 PNG 是死重，**全部删除**。APK 里 `res/` 现在合计 82KB，图标只占 4.5KB |
| 11-10 23:30 | **网页 sticky 顶栏抽搐**：栏收缩/舒张时网页层被**亚像素**平移（`dp(20f)*(1−p)` 是小数），而且和顶栏高度各自取整、最多差 1px。`position: sticky` 的顶栏通常被提升成独立合成层、按**整数像素**吸附，外层亚像素移动它自己整数跳 → 抽搐。新增 `barBottomY(p)` / `webTyPx(p)`：顶栏高度和网页层位移都取整，且**网页层位移 = 顶栏底边 − 24dp** 严格相等。副作用也是好的：WebView 这个巨大的层不再每帧重采样，滚动更省 |
| 11-10 23:20 | **任务视图流畅度**（卡片一多就卡）。定位到两个真瓶颈：① **内存/GC** —— 每张卡的位图是 `cardW×cardH` = 1036×1755 ≈ **7.3MB**，20 个标签就是 140MB+，每次开任务视图都要重新分配 + 频繁 GC；② **每帧重录** —— `refreshShadows()` 每帧给每张可见卡写 `shadowDepth`，值每帧变半个像素 → `invalidate()` → 整张卡（网格 + 渐变 + 路径）重录。改法：**懒渲染**（`refreshTabs` 只为视口 ±1~2 屏内的卡生成位图，远处 `bitmap = null`；`updateTransforms` 里滚到附近再补，且**每帧最多补一张**）+ **`trimCardCache` 保护在用位图**并建完再裁一次 + **遮光深度量化到 1px** + `refreshShadows` 跳过远处/无位图的卡。**没有上 NDK/Rust**：瓶颈是位图内存和 View 重录，不是 CPU 计算；Skia/GPU 已在做缩放与网格绘制，换成 native 只会多一层 JNI |
| 11-10 23:05 | 新标签页飞入仍是空白：上一版的预缓存只"临时借一张活的起始页 thumb"，没有就在飞入时才现找 —— 找不到就退化成空白页。改成**常驻缓存** `startPageThumb`（只存**页面区**缩略图，半分辨率约 1MB；顶栏在拼帧时才画，几何永远最新），并在三处刷新：`showStartPage` 的 420ms 拍图后、1100ms 再补一拍（首次渲染可能还没画完）、以及 `updateThumbFromFrame()`（进任务视图那一帧最准）。`composeFrame()` 拆出按 thumb 拼帧的重载。**顺手修掉自己埋的坑**：原来用 `old.width == th.width/2` 判"要不要重做缓存"，导致第一次拍到白页后永远不再更新 —— 改成用"源 thumb 是否换过"判断 |
| 11-10 22:45 | 新标签页飞入**页面是空的**：上一版为了让动画立刻起步用了 `captureComposite(includePage=false)`（只有顶栏 + 底色），而 `pageDone` 在新标签页根本不置位 → 换真图那步要等到 700ms 超时，动画（520ms）早就放完了。新增 **起始页预缓存**：`composeFrameFromAnyStartPage()` 直接借用**任意一张已拍好的起始页缩略图**拼帧（所有新标签页画面完全一样），并留一份**半分辨率副本** `startPageFrame`（2MB；尺寸变化即作废）。飞入第一帧就有收藏磁贴；`upgradeNewTabFrame()` 改成"有 thumb 就拼帧"（几毫秒），不再重画 WebView |
| 11-10 22:25 | 三处：① **卡片上的细横线** —— 逐条带 `drawBitmap` 时相邻两条各自独立采样，接缝必差半像素；条数从 56 拉到 200 后缝更密（滑动时跟着动）。`Trap.draw` 改成**一次 `drawBitmapMesh`**（1×N 网格，顶点按投影排），接缝消失、绘制调用从 ~200 次降到 1 次。② **进场动画中立刻点＋→缩小卡片突然消失** —— `standUpCards()` 的动画没被取消，被打断后卡片停在"半立起来"的中间态；新增 `standUpAnim` 字段、`endHero()` 里取消，点＋时 `endHero()` 之后补一次 `updateTransforms()` 直接落到静止态。③ **新标签页延迟高** —— 探针实测 `waited=512ms`（`pageDone` 在新标签页根本不置位，每次都等满 500ms 超时）+ `captureComposite` 53ms。改成**立刻起飞**：`captureComposite(includePage=false)`（不画 WebView，只有顶栏 + 底色）当第一帧，动画立即开始；`upgradeNewTabFrame()` 轮询到 `pageDone` 再把 `hero.bitmap` 换成真图（换图时卡片正在长大，看起来像"页面填上了"）。不再走 `flyNewTabWhenReady` 的 500ms 等待 |
| 11-10 22:15 | 横屏卡片太宽：`ensureCardGeometry()` 的 `cardW` 原本按 `hostW`（横屏 2028px）算，横屏卡片变成一条超宽扁带。改成按**屏幕短边** `basisW = min(hostW, hostH)` 算 —— 竖屏时短边就是屏宽（行为不变：cardW=1036、左右余量 8dp），横屏时仍是 1036。`cardH` 的换算仍除以 `hostW`（真实页宽），因为卡片装的是**横屏那一页**，宽高比要跟页面走。卡片在列表里改成**水平居中**（左右余量 = `(hostW − cardW)/2`；竖屏时正好等于 8dp，等于没变） |
| 11-10 22:10 | **任务视图里旋转会乱**：卡片几何（`cardW/cardH/cardRenderH/cardStep`）和每张卡的位图都是按旧屏宽算好缓存的。新增 `rebuildTaskViewAfterRotation()`：① `onConfigurationChanged` 回调时 `rootView` 还是**旧尺寸**，先等 `rootView.width == displayMetrics.widthPixels`（最多重试 4 帧）；② **清 `cardW/cardH/cardHeaderH/cardRenderH`** 再 `refreshTabs()` —— `ensureCardGeometry()` 是"算过就不算"的早期返回，不清零它就原样返回竖屏那套（探针实测过：清之前横屏拿到 `cardW=1036`＝竖屏值）；③ 旧尺寸的 `heroComposite` 回收置空，否则下次进场按错宽高比铺开；④ 重建后 `alignCurrentCardToRest()` + 恢复卡片 alpha。探针验证：横屏 `cardW=1984`、转回竖屏 `cardW=1036` ✓ |
| 11-10 21:45 | ① **横屏点地址栏解锁**：`barLocked` 原本把横屏锁死，结果横屏里地址栏完全点不开、也输不了网址（`uiautomator dump` 实测横屏 topBar=66px、无 bottomWrap）。现在 `startFieldEdit()` 开头遇到 `barLocked` 就解除（状态栏仍保持隐藏），点一下就展开、底栏回来。② **收起态首次点击"随机迟滞"**：ACTION_UP 里新增一支 `!fieldExpanded && compact>0.25 → 立刻 startFieldEdit()`，不再走 `postDelayed(PRESS_MS)` 等按下加深跑完（那会先变深、过一会儿才展开，延迟随按住时长变化）。实测：竖屏正常 / 横屏初始 topBar=66 无底栏 / 横屏点地址栏 → topBar=121 + bottomWrap=959..1080 / 转回竖屏 topBar=121 + bottomWrap ✓ |
| 11-10 21:30 | 再修三处：① **展开时闪光标** —— ACTION_DOWN 里 `isCursorVisible = false`（只在 `!fieldExpanded` 时关，编辑中途点进文本要挪光标不能关），真进编辑时 `startFieldEdit` 里再打开；② **惯性滚动中点地址条"展开一下又缩回"** —— 拦截分支先 `web.stopFlingNow()`（`ObservableWebView` 新暴露）+ `barLockUntil = now+600`，否则滚动回调继续喂 `feedBar()` 把栏收回去；③ **旋转后底栏消失/地址栏不居中/无法展开** —— `applyOrientationChrome()` 之前用 `if (land == landscapeFull) return` + `animateBars`，旋转回来时留下半截状态：改成**每次都直接写终值**（`setBars(if (land) 1f else 0f)`，先 `barLocked = land` 再写），并在下一帧 `centerField()` + `syncReloadIcon()`（编辑态下不动左对齐） |
| 11-10 21:20 | 修 21:05 引入的连锁 bug（"收起态点地址栏后卡住、退不出来、滚页也不收栏"）：拦截分支里只 `animateBars(0f)` 就返回，留下三处痕迹 —— ① `ACTION_DOWN` 已经 `animateFieldPress(1)` 把地址栏加深，没人退回去；② 手指点 EditText 顺带给了焦点，而 `feedBar()` 原来的守卫是 `if (urlField.hasFocus()) return`，于是滚页永远不收栏；③ `releaseFieldFocus()` 会把地址栏设成不可聚焦，导致**下一次点击聚焦不上**（编辑态永远进不去）。现在：拦截分支里 `animateFieldPress(0)` + `dismissFieldMenu()` + `releaseFieldFocus()` 后**立刻把可聚焦还回去**；`feedBar()` 的守卫从 `hasFocus()` 改成 `fieldExpanded`（只在真编辑时挡） |
| 11-10 21:05 | 按 iOS 7 原版补两条（参考《The ultimate Safari guide》）：① **栏收起状态下点网址条 = 先把栏展开**（`startFieldEdit()` 开头判 `compact > 0.25` 就 `animateBars(0f)` 返回；`blank/keepText` 那两条入口不拦，否则长按粘贴会被挡）；② **横屏 = 完全全屏**：新增 `onConfigurationChanged` → `applyOrientationChrome()`，横屏 `barLocked=true` + `setBars(1f)` + 隐藏系统状态栏，竖屏恢复；`setBars()` 在 `barLocked` 时拒绝 `p<1`，滚页不会把栏拉回来。说明：iOS 的"点状态栏"在 Android 上点不到（系统状态栏不吃 app 的触摸），所以落到"点收缩后的网址条"这个等价动作上 |
| 11-10 20:40 | 回退 20:35 的三项改动：`TILT_BASE` 54→**42**、`MAX_VISIBLE_CARDS` 4.6→**4**、卡片标题左对齐→**居中**（用户在视频里看到的"左对齐"其实是标题太长顶到了 × 右边，文字本身居中）。实测数据保留在 §5.1 与 20:35 那条里，供以后再试 |
| 11-10 20:35 | **对着真机视频（iOS 7_ 10 Tips for Safari.mp4，t≈126s）逐帧实测校准**：① 卡距 = 139px / 屏高 700px = **19.9%（一屏 5 张）**，我们原来是 23.6%（4 张）→ `MAX_VISIBLE_CARDS` 4 → **4.6**；② 卡片左右斜边斜率 dw/dy = 0.295（每降 139px 收窄 41px），模型里 = tanθ/(H/W·D)，D=2.6、宽高比 1:1.775 反解得 **θ ≈ 54°**，我们原来是 42°（斜率 0.195）—— 真机卡片宽高比 **1.25**（扁片），我们是 0.95（近正方形），这是"不像"的主因 → `TILT_BASE` 42 → **54**；③ 卡片标题在真机上是**左对齐**紧跟左上角 × 后面（不是居中）→ `drawCardTitle` 改左对齐。另确认真机底栏就是 **Private / ＋ / Done** 三段（我们早已一致）。见 §7.4 |
| 11-10 20:10 | ① 遮光改成"**只有两张卡真的重叠才挡光**"（`overlap = 自己高度 − 上沿差`，重叠 ≤0.5px 直接 0），强度随压入深度按 `overlap/cardStep` 渐强；删掉建卡时预置的 `shadowDepth=cardStep`（与几何遮光打架）。② 任务视图**其它**动画也 ×2：`DRAG_YIELD_MS 300→600`、`DRAG_FILL_MS 320→640`、`DRAG_SETTLE_MS 260→520`、删标签补位 260→520、让开位移回位 220→440。③ **卡片锯齿**：`Trap.draw` 的条带数改成按梯形斜边总跨度自适应（`spanX` px → 条数，夹 56~200）—— 原来固定 56 条，静止时每级台阶 3.8px，斜边是肉眼可见的阶梯；行边界改 `Math.round` 且相邻共用（原来 `toInt()+1` 每条多画一行，内容被纵向拉伸、接缝对不齐） |
| 11-10 19:30 | 三件事：① **退场不再渐隐渐显**（卡片 alpha 恒 1，实心飞走）；② **进场/退场时长 ×2**（`ENTER_MS=800`、`EXIT_MS=840` + `EXIT_DELAY_MS=140`、`EXIT_CARDS_BUDGET_MS=600`、看门狗 1800）；③ **遮光改成按遮挡关系实时算**（`refreshShadows()`：深度 = 下一张卡的视觉上沿差、强度 = 它的 alpha），去掉了 `p²` 的假渐显。顺手修**滚动时卡片抖动**：`updateTransforms()` 里"回读 `cardScale` 比对"的守卫在真透视下会自激（俯角一变尺寸就变 → 每帧被判成"别人写过" → 反复复位），改成由 `applyCardScale()` 落盘 `scaleSmooth`、并先写 theta/dOverH 再算尺寸 |
| 11-10 17:50 | **上真透视**：缩小不再等比缩放，改成 `k=1, hStart=h, hEnd=h+cardH` 沿卡片平面推远，相机参考高度固定 `hRefBase=cardH`（不固定的话相机会追着卡片后退、推多远都不缩）；`recedeFor()` 一元二次反解由 s 求 h（实测 0.9/0.8/0.7/0.6 → u=0.234/0.511/0.846/1.261，高度比精确命中）。代价：0.6 时上沿宽度 0.755，比等比缩放宽 26%（物理上正确）。`Trap.draw` 新增 `y0` 参数把"推远造成的绝对纵向偏移"抵掉，绘制从视图上沿开始 —— 否则触摸区域整个偏掉。进/出统一：`applyHero` 第 8 个数 = `hRefBase`（整页时 heroFullH、卡片时 cardH）；退场不再 `applyCardScale(v,1f)` 硬复位（那会让小卡"啪"地弹回原大），改由 `flatCards` 放平时一起长回（= 进场的镜像）。新增 `cardScale()` 统一"当前大小"读法（真透视下 k 恒 1）。见 `iOS7Safari-卡片变换架构.md` §3/§7 |
| 11-10 17:35 | **卡片变换统一到单一通道**：删掉 `View.scaleX/scaleY` 与 `pivot`，缩小改成 `k`、`hEnd` 同比缩放（`applyCardScale()`），数学上与 `scaleX` 逐像素等价、视觉不变；`cardVisualTop = cardTopY + translationY`、`cardVisualH = projY` 一次读表，`cardState()` 直读卡片自己的参数。改到的调用点：`updateTransforms` / `standUpCards` / `startExit` / `rebuildAnimated` / `beginDrag`(1.02) / `settle` / `applySteep`（顺手修掉它把 `hEnd` 写死成 cardH、会把缩小卡瞬间放大的 bug）。新增独立架构文档 `iOS7Safari-卡片变换架构.md` |
| 11-10 17:10 | 转盘化：缩到 0.6 之后不再是"钉住不动"，而是**滚下去**。`crest`（`SHRINK_CREST=0.22`，指数渐近，over=0 处斜率 1 → 与"上沿跟手"C1 接上）负责爬到最高点；`roll`（`SHRINK_ROLL=0.45`，`rollMax = cardStep×0.45`，半余弦，只在 `over > span2` 后生效）负责往下走。**全程只是位置的函数**，反向滑回时 `roll` 自动回 0、回到"刚好 0.6"才重新变大 —— 天然可逆、无需状态机。见 §7.7 |
| 11-10 17:00 | 上软钉：第二段的顶边不再死钉在 `edge`，改 `creep = softPin·(1−e^(−over/softPin))`、`softPin = span2 × SHRINK_SOFT_PIN(0.14)`。over=0 处斜率正好 1 → 和第一段"上沿跟手"**C1 接上**；顺带修掉"顶到头后顶边反而往回沉 152px"（硬钉下 `cross·(1−s)` 把视觉上沿往下推）。只加在第二段，第一段不能加（会带偏 1/9 线对齐）。见 §7.7 |
| 11-10 16:55 | 卡片缩放的"生硬"：两段线性 → **半余弦**（`easeHalfCos`，两端斜率 0，1/9 线与状态栏下沿两个拼缝都变成 C1 连续，锚点 1.0/0.8/0.6 一个没动）；`scaleX` 加 **35ms 指数低通**（`scaleSmooth` 存在 View 上，dt 用 `nanoTime` 实测、夹 4~40ms；两条豁免：别人写过 `scaleX` 就跟随、单帧跨步 >0.05 直接给到目标）；`ty` 改用平滑后的 s，未过线一侧补 `−cross·(1−s)` 防"过线瞬间跳一下"。见 §7.7 |
| 11-03 20:30 | 卡片遮光接上形变：新增 `TrapezoidView.shadowFrac`（强度系数 0~1），进场 `shadowFrac:0→1`、退场 `1→0`，都用 `p²` —— 长方形那一端"前面还没有卡压上来"，不能带阴影；遮光也改画在标题条之后（`onDraw` 顺序：主图 → overlay → shade），并给 `endHero/finishDismiss` 加复位。**顺手删掉两处残留探针**（`SHADCHK` 每次 `refreshTabs` 打一行、`ROTCHK` 在起立动画里每帧拼 `StringBuilder`）。见 §7.16 |
| 10-25 17:35 | "当前页上方那几张旋转方向不对"：① 进场 `translationY` 由 `(k−skip)·gapFlat` 改成 **`abs(k−skip)·gapFlat`**（一律从下方升上来，不再有卡片从上往下掉）；② 进场缩放统一从 **1.0** 补到静止 s（原来上方那几张一直停在 0.6/0.68）；③ 退场时**所有卡片**跟着英雄视图一起 `theta:tiltNow→0`（420ms / 70ms delay / apple），不再只有被点的那张"站起来" |
| 10-25 16:48 | 其余卡片补上「靠拢」：平铺状态相邻两张相距**一整张卡（`cardH`）**，进场时 `translationY` 从 `(k−skip)·(cardH−cardStep)` 收到 0（与 `theta 0→tiltNow` 同步、400ms `apple`、无错峰）。**顺手修掉一个真 bug**：`hero` 也是 `TrapezoidView` 且已 addView 到列表末尾，原循环把它也当成"其余卡片"改了 `theta/translationY`，动画会花 —— 现在 `c === hero` 直接跳过 |
| 10-25 16:35 | 卡片装下整页：`cardH` 从「屏高×0.62」改成 **`heroFullH × cardW/屏宽`**（1209→1755，`cardRenderH` 904→1312），缩略图不再被裁掉下半截，投影源行数正好等于整页；新增 `alignCurrentCardToRest()`（粗滚 `desiredScroll` + 实测视觉上沿补滚 ≤2 趟），修掉"滑到整摞最下面后重进任务视图、当前页那张已缩小、上沿不在 1/9 线上" |
| 10-25 16:10 | 任务视图进场改为「整摞一起立起来」：删除 `revealCards()`（从屏底+24 飞入、470ms、按张数错峰），新增 `standUpCards()`（其余卡片只动 `theta 0→tiltNow` + `alpha 0→1`，400ms `apple`，与 hero 同步）；`topPad` 不再和"居中"取 max，当前页那张卡的上沿固定落在上 1/9 分界线下方 2dp（标签少时也贴线，不再居中） |
| 10-25 15:40 | 任务视图改深色：背景蒙版 `#80D9D9DE`→`#B3000000`、底栏 `#BFFFFFFF`→`#B8000000`（私密 `#CC000000`），**毛玻璃都保留**；底栏文字/＋改白；新增 `setStatusBarIconsWhite()`（进出浮层切换，否则深底上时间/电量看不见）；卡片左右留白 18dp→**8dp**（`CARD_SIDE_DP`），倒梯形上沿贴近屏幕两边，`cardW 982→1032`、`cardHeaderH 110→115`、`k_end 0.9093→0.9556` |
| 10-25 15:05 | 退出编辑态一次到位：新增 `UrlEditText.onKeyPreIme` 抢在输入法之前接返回键（原来 IME 先收键盘、`onBackPressed` 不触发 → 要按两次）；磁贴页关闭由"下滑"改为**整体渐隐** 240ms（`SUGGEST_EXIT_MS`）；抽出幂等的 `collapseFieldVisuals()`，返回/取消/失焦共用；`exitFieldEdit()` 无内容可收时把返回键交回系统 |
| 10-24 23:30 | 地址栏按下时序：加深(130ms) → 点击才左移(280ms, 同长渐浅回原色) → 左移完才出键盘 + 磁贴背景渐显；长按不展开、松手弹复制（新增 scrim / `startFieldEdit` / `fadeInSuggestBg` / `showKeyboard`，并关掉 `showSoftInputOnFocus`） |
| 10-24 22:55 | 书签页进出统一 330ms（原 300 进 / 260 出，出场偏快）+ 都用 `apple`；抽出常量 `BOOKMARK_MS` |
| 10-24 22:50 | ① 书签页进场/出场 + 「＋」新标签飞入全部换成 `apple` 曲线（出场原来是 `accel`）；② 起始页地址栏改为常驻「刷新」：新增 `syncReloadIcon()`（只有真的在加载网页才显示 ✕），点刷新在起始页上 = `showStartPage()` 重新渲染 |
| 10-24 22:36 | 圆盘叉再收敛：圆盘 `alpha=150`（实心偏抢眼）；点它清空后改为 `alignFieldLeft()` 保持**左对齐**（原来误用 `centerField()` 会拉回居中） |
| 10-24 22:32 | ① 删空网址时磁贴不再重建/重播"渐显+放大"（`renderFavTiles(force)`：已在显示就 return；进编辑态那次仍 `force = true`）；② 圆盘叉配色改为取自主题（盘 = `sub` 占位文字色、叉 = `fieldBg` 圆角矩形填充色）；③ 修 `btnClear` 初始化顺序（挪到 `setupClearButton()`，在 `bindViews()` 末尾、`applyTheme()` 之前，否则启动崩溃） |
| 10-24 22:25 | 编辑态补 iOS 7 那个细节：刷新按钮**不跟着圆角矩形左移**（反补 `+Wc·f`）而是原地渐隐、被矩形右沿扫过盖住；原位渐显一个 17dp 的「深灰圆盘 + 白 ✕」（`btnClear`，复用 `ic_stop`）；`setBars` 的刷新按钮 alpha 与编辑态叠加 |
| 10-24 22:10 | 苹果曲线 `cubic-bezier(0.25,0.1,0.25,1)` 推广到：地址栏编辑态**出场**、起始页磁贴**上移/下移**（`sugScroll`）、**工具栏收缩/舒张**（原 AccelerateDecelerate）、**任务视图进场**（原 `SineOut`）、**退场**（原 `ease`）—— 全项目 9 处统一 |
| 10-24 22:05 | 地址栏进入编辑态的整段左移（网址 / 圆角矩形 / 取消按钮 / 蓝色丝带）曲线换成**苹果默认曲线** `cubic-bezier(0.25,0.1,0.25,1)`（新增 `apple`），三段同曲线一起收尾（出场随后也一并换成 apple，见 22:10） |
| 10-24 22:00 | 磁贴出现时长再收到 **1400ms**（用户实测 2s 偏久） |
| 10-24 21:55 | 磁贴出现动画更温柔：时长 260ms → **2000ms**、起始缩放 0.8 → **0.7**，曲线从 `Decelerate(1.6)` 换成标准 `ease`（2 秒量级用 Decelerate 会"前半秒走完、后面卡着"） |
| 10-24 21:50 | 起始页上点地址栏不再播磁贴"渐显+放大"（`isOnStartPage()` 时只有 `sugScroll` 的上移/下移），避免同一批磁贴叠两套出现动画 |
| 10-24 21:45 | 点地址栏先给「个人收藏」磁贴页（不再先出搜索推荐），输入字符才切推荐、删空退回磁贴；磁贴出现动画：渐显 + 0.8→1.0 同时进行、`Decelerate(1.6)`/260ms |
| 10-24 21:25 | 修 12.3 的第 1 条：`removeTabAt()` 同步维护 `viewOrder`（删下标 + 后续前移），删标签后不再整摞旋转；`newTab`/`selectTab` **保留**"当前页在最上面"的旋转（原本那套动画，刻意保留） |
| 10-24 21:20 | 全量复查（几何/动画/性能/内存）并补文档：需求对照表 42~53、新增 **12.3 复查结论**（含两个未修的问题：换位后 newTab/removeTabAt/selectTab 会让整摞重新旋转）。代码未改（等确认） |
| 10-24 21:12 | 手里那张卡改成**指数趋近**跟随手指（`DRAG_FOLLOW_TAU=0.06`，`k = 1−e^(−dt/τ)`，落后上限 120dp）：刚起手慢、拖远了追得快（先慢后快），手指停下自己落住 —— 有了"一点点重力"的手感 |
| 10-24 21:08 | 补位（上滑进格）改温柔：`Overshoot(0.75)`/260ms → `PathInterpolator(0.32,0,0.16,1)`/320ms —— 慢起、缓缓加速、长收尾，去掉过冲（过冲的"磕"一下显得积极犀利） |
| 10-24 21:05 | 让开（下沉）的曲线换成**先慢后快**：`Accelerate(1.6)`、时长 200→300ms —— 原来 Decelerate 显得轻飘飘，现在开头"顶着"、越沉越快，有重量 |
| 10-24 21:00 | ① 中途松手不再"啪"地回原位：新增 `settlingView`，`updateTransforms()` 跳过它 —— 否则每帧的 `updateTransforms()` 会把归位补间覆盖掉；归位用 `Decelerate(1.8)`/260ms。② 让开/补位的曲线：让开 `Decelerate(1.5)`（先快后慢）、补位 `Overshoot(0.75)`（轻微过冲回落），模拟有重力的物体 |
| 10-24 20:40 | ① 长按左滑删除**不再淡出**（滑的时候整张跟着手指，滑过阈值直接飞出去）；② 让位动画从"逐帧按拖动进度插值"改成**三态自走**（原地 → 让开沉底 → 补位），相位由阈值+滞回触发，每个相位用**自己的 200/240ms 补间** —— 两张卡各动各的，不再被手指速度绑死 |
| 10-24 20:30 | 修「卡片多时，关标签/左滑删除/换位之后，1/9 线以上的缩小卡片都抽搐一下」：`rebuildAnimated` 里"已就位就跳过"的判断用了 `cardTopY + translationY`，漏掉缩小支点补偿 `pivotY·(1−s)`（最多 277px）→ 缩小卡每次重建都被当成"位置不对"塞进补间队列。改用 `cardVisualTop()` 比较与换算 |
| 10-24 20:20 | 修「4 张卡 2↔3 交换后变成整体转一格」：`commitReorder` 里 `tabs` 重排之后 `viewOrder` 必须重置成恒等映射，否则 `refreshTabs` 会把"旧下标的排列"再套一遍（探针 ORDERCHK 实测：期望 4,2,1,3，旧代码得到 3,1,4,2 再被旋转） |
| 10-24 20:05 | 最顶上那张卡不能换位/换位后闪回：根因是整摞顺序**无条件按 `(currentIndex+k)%n` 旋转**，换位涉及第 0 格就整体转一格。改成 `refreshTabs()` 优先沿用 `viewOrder`（失效才回退旋转），同时把 `playEnter`/`startTabsEnter`/`desiredScroll` 里"当前页 = child 0"的假设改成 `childOfTab(currentIndex)`。换位范围恢复为 0…n−1 |
| 10-24 19:55 | ① 换位判定改成**阈值 + 滞回**（`DRAG_SWAP_ON=0.62` / `DRAG_SWAP_OFF=0.42`，原来 `round(s)` 0.5 格就换，太敏感）；② 修「换位松手瞬间其他卡片也动/手里的卡飞到最后面」：整摞是按 `(currentIndex+k)%n` 旋转排的，换到/离开第 0 格会让提交后整体旋转一格 → 把 `dragTo` 钳在 `[1, n-1]`（第 0 格留给当前页） |
| 10-24 19:45 | 拆开两种位移：整格换位走新的 `TrapezoidView.screenShift`（`canvas.translate`，**不改变大小**），`hShift` 只负责让开的 3D 下沉。修「被迫交换的卡片上移到极限时偏大、松手打回原形」（hShift<0 会被投影放大 12% 高/6% 宽）。`settle` 里 hShift 与 screenShift 一起滑回 0 |
| 10-24 19:35 | 被让的卡上移时尺寸偏大、松手才缩一圈：`updateTransforms()` 的水平位置改成 `cardTopY + shiftScreenDy(hShift)`，并在 `applyDragRestack`/`settle` 的 hShift 动画里**每帧**调用它 —— 升到 1/9 线以上就跟滑动时一样变小。顺手删掉不再需要的 `dragBaseTy` |
| 10-24 19:25 | ① 让开深度两个方向统一成 `H + 0.45·step`（`dir=-1` 原来是固定 1.4 格 ≈626px，而 H≈730px 不够深 → 翻转时两矩形还重叠，卡片"蹭着下沿怼回来"）；② 松手换位改走 `rebuildAnimated()`：**缩放也一起补间**，修「松手那一下 1/9 线以上的缩小卡片抖闪」 |
| 10-24 19:05 | 核实「下移多深才允许翻层级」：几何下限 = `H + (0.5−sm)·step ≈ H + 0.065·step`，**与「按第几张」无关**（两张卡一起动，f 消掉），只跟 H 和 cardStep 有关；实测按旧公式余量只有 10~30px，掉帧就会翻早。改成 `H + 0.45·step`，探针实测余量 +90~180px。常量 `DRAG_DIP_DOWN_MARGIN` → `DRAG_DIP_DOWN_EXTRA` |
| 10-24 18:05 | 修「滚动后长按卡片突然跳到最上层」：`dragS`/`restackPending` 在 `beginDrag`/`endDrag` 里清零 —— 残留的 `dragS` 会让 `applyStackOrder()` 以为手里那张已经压过好几格，直接插到很前面 |
| 10-24 18:00 | 层级规则改成「**只动手里那张**」：`elevation=(下标+1)×0.01` 保持静止层叠顺序，手里那张按压过/让过几格插进去，其余卡片一律不动 —— 换位不再影响它们与无关卡片（第 1、4 张）的遮盖关系。删掉 `dragPenalty` / `DRAG_DIP_BEHIND` / `cardSlotTop` / `zBuf`+`iBuf` |
| 10-24 17:50 | 「上拖换位时下移的卡半途蹦到上层」的真正解法：让开的**深度按手里那张的高度算**（`DRAG_DIP_DOWN_MARGIN=1.08`），`pe<0.5` 期间压到最后面，到让开波最高点（两矩形刚错开）才翻到前面 —— 翻转发生在看不见的时刻。`dir=-1` 不受影响（它翻早了也被挡住） |
| 10-24 17:40 | 「往上拖换位时下移的卡半途蹦到上层」：让开波（`sin(πpe)` 下沉）改成**只给 `dir=-1`（往上让）**；往下让只做 smoothstep 平移，层级在「两张卡顶边重合」那一瞬翻转（那一瞬看不见）。同一套手势实测 **0 janky / 64 帧、p90 18ms** |
| 10-24 17:30 | ① 修「往上拖换位时层次错乱」：让开的"往后压一层"只对 `dir<0`（往上让）生效，往下让不能再压（它的目标位置本来就在前面）；② 拖拽性能三项：elevation 只写名次（名次不变不写）、让位重排每帧合并一次、`hShift` 亚像素不重画。实测 jank 帧 7.81%→1.59%、p90 24→19ms |
| 10-24 17:20 | 让开判定放宽（死区 0.15→0.08）；让开深度 1.15→1.4 格；**让开时额外往后压 `2.5×cardStep` 层级**（否则它压在下一张前面、居中标题一直露着）—— 下沉超过一格即被完全盖住 |
| 10-24 17:15 | 拖动让位改成**沿卡片自身平面的 3D 位移**（`TrapezoidView.hShift` + `Trap.projY/hShiftForDy`，`hRef` 固定为 `hEnd` 保持相机距离）—— 往下挪会自动变小（近大远小）；取消渐隐渐显；加 `DRAG_PUSH_DEAD_ZONE=0.15` 死区做"被挤下去"的手感；`cardSlotTop/cardVisualTop` 都补 `projY(hShift)` |
| 10-24 17:00 | 拖动时的层级排序从 `cardVisualTop()` 改成 `cardSlotTop()`（不含缩放支点补偿）——修「上九分之一缩小状态的卡片遮盖反了」 |
| 10-24 16:55 | 拖动换位的**前后遮盖**改成按位置动态算（`applyStackOrder()`：`elevation=(视觉上沿+4000)×0.01`，松手 240ms 后 `resetStackOrder()` 还原）——修「插回原位置时层次是反的」。`TrapezoidView` 无 outline，elevation 不画阴影，可随手指连续变 |
| 10-24 16:50 | 长按换位改成「木板交换」：拖动进度 `s` 连续驱动，被靠近的卡片先向下让开一格多 + 淡到 0.08，再插回腾出的格子（`smoothstep` + `sin(πp)` 让开波，任何时刻只有一张在过渡）；`beginDrag` 记 `dragBaseTy` 基准、`settle()` 不再把别人的状态瞬间写死。见 7.15 |
| 10-24 15:33 | 「残卡」根因补全：退场"飞出屏幕"的距离用的是布局上沿，缩小卡片（s=0.6）少飞 `pivotY·(1−s)=277px` → 停在屏幕里。改用 `cardVisualTop()/cardVisualH()`（新增这两个辅助函数，`cardState`/`snapshotPositions`/`onScreen` 一起改口）。**不**复刻滚动那套 0.6→0.8→1.0 |
| 10-24 15:26 | 卡片让路预算 400→300ms（低帧率设备上"最后几帧"会拍到没飞完的卡），并加硬兜底：`budget+80ms` 到点把剩余卡片 `alpha=0`，不依赖最后一帧是否渲染。探针实测 12 标签滚到底：`movers=4 step=28 fly=216`（只有 4 张真的飞） |
| 10-24 15:17 | 「标签多时其他卡片来不及飞完」：卡片飞出改成**按张数定时**（`step=clamp(120/n,8,35)`、`flyMs=clamp(400−step(n−1),180,330)`、delay 夹在 `400−flyMs` 内，全部 ≤400ms，英雄视图 490ms 收尾留 90ms 余量）；`cardState()` 补上缩放支点项 `pivotY·(1−s)`（点完成的卡片处于缩小状态时，英雄视图起点比卡片高一截）；`fadeOutTabs()` 也挂 900ms 看门狗，改用 `exitSeq` 认人 |
| 10-24 14:57 | 撤掉 14:50 那条「退场时英雄视图提到最上层」：层叠秩序被破坏（旁边卡片还没分开，当前卡片就跳到最上层）。恢复 `HERO_Z` + 330/300ms + delay 35；保留「视口外卡片直接 alpha=0」「stepFling 查 blocked」「900ms 看门狗」「向下飞不淡出」 |
| 10-24 14:50 | 「标签多、滑到底部极限后点完成，其他卡片来不及飞完、压在页面上」：退场期间英雄视图提到最上层（`CARD_FRONT_Z+1`）；视口外的卡片直接 `alpha=0` 不再横穿屏幕；向下飞的也带淡出；`TabScrollView.stepFling()` 补 `blocked` 判断（惯性以前能在退场动画下面继续滚，把落点带偏）；`startExit` 挂 900ms 看门狗强制收尾 |
| 10-24 14:26 | **固定 viewport 改造**：`webContainer` 从 `weight=1` 改为「`match_parent` + `marginTop=24dp`」独立成层，栏改成叠在上面的 `topBar/spacer/bottomWrap`；`setBars()` 只加一个 `webContainer.translationY = 20(1−p)dp`，WebView 的 viewport 永久不变（实测 WebView 布局 2084→2028 只是位移、高度恒 1885）。几何口径收拢成 `webTopVisual/webBottomVisual/webVisibleH`，`chromeH/heroFullH/缩略图裁切/模糊层高` 全部改口；配套 `injectBottomInset()` 给页面补 64dp 底边距、`topBar/bottomWrap` 设 `clickable`（spacer 绝不能 clickable）。文档新增 3.1 |
| 10-24 14:14 | 栏的收缩改由**手指**驱动（`trackBarByFinger`/`feedBar`，滚动回调降级为惯性兜底）；删标签补位动画改为「先定 scrollY → 一个 animator 统一推 translationY+scale」（修「缩小状态的卡片补位时闪现在下方」）；私密模式切换**不再销毁页面**（只换主题） |
| 10-24 13:52 | 第一次进任务视图「背景模糊晚一步」：模糊源改用起飞帧 + `blurPendingKey` 去重 + 预热只热机器（`warmBlur`）；并在浮层显示前**同步**铺一张 1/8 快速版（`ensureBackdrop`），正式版算完替换。另清掉 12.1 的四个问题（`ty +=`、删 `fromFling` 死分支、删死代码、`diagCrossY(dOverH)`） |
