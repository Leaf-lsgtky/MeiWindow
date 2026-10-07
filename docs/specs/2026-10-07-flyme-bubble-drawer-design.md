# Flyme 小窗交互复刻 — 设计文档 (Spec)

- 日期：2026-10-07
- 状态：已与用户逐段确认
- 工程位置：`E:\workspace\flyme\bubbledrawer\`
- 参考原件：`E:\workspace\flyme\out\SystemUITools_src\`（`com.flyme.systemuitools` = 魅族"系统界面工具"，jadx 反编译）

## 1. 目标与非目标

### 1.1 目标
复刻 Flyme 12 小窗的**交互层**，作为可独立安装的 APK，移植到任意 Android 13+ 真机：

1. **边角滑动手势**：从屏幕底部左/右角落斜向上滑出，展开应用气泡条（app 逐个弧形错峰展开）。
2. **逐个展开动效**：错峰时长、PathInterpolator 曲线、回弹、缩放超冲，参数照搬反编译源码。
3. **拖拽瞄准 + 松手启动**：手指在扇形内移动选中气泡，松手启动该应用。
4. **「更多」入口**：气泡条尾部"更多"tile → 全屏更多页（4 列网格 + 尾部"添加应用"tile）。
5. **固定常用管理**：应用列表按**拼音首字母 A–Z 分类并排序**，右侧字母索引条，★区=已固定（上限 6，进入气泡条）。

### 1.2 非目标（本期不做）
- **不实现真正的悬浮小窗**：点选后一律以标准方式**全屏启动**应用。启动动作抽象为 `ILaunchStrategy`，日后替换为 freeform/小窗策略时不改其余代码。
- 不做"功能"页签（LauncherApps pinned shortcuts / 微信支付宝扫一扫类），列为二期。
- 不做迷你小窗、贴边吸附（welt）、窗口chrome 手势、`WindowModeOperateActivity` 那一套。
- 不做系统签名/sharedUserId 版本；不使用 `ISystemGestureListener`（目标系统无 Flyme framework，且无法普通安装）。

### 1.3 成功判据
| # | 判据 | 验证方式 |
|---|---|---|
| A1 | 真机（Android 13+）安装即用，仅需授予悬浮窗权限 | adb install + 手工 |
| A2 | 底部角落斜上滑展开气泡条，与系统返回手势不互相误触 | 真机回归（两侧角落各 10 次） |
| A3 | 逐个弧形错峰展开，观感与 Flyme 原版一致（帧级参数一致） | 录屏对比 + 参数常量表 |
| A4 | 拖拽瞄准有放大反馈，松手全屏启动对应应用 | 真机 |
| A5 | 更多页网格展示 + 点击进入；"添加应用"进固定管理 | 真机 |
| A6 | 固定管理页拼音分组正确（如"微信"归 W、"支付宝"归 Z），索引条可跳转；固定≤6 后气泡条即时更新 | 单元 + 真机 |
| A7 | 固定列表在重启/杀进程后保持；进程由前台服务保活 | adb |

## 2. 技术选型与理由

| 决策 | 选择 | 理由 |
|---|---|---|
| 语言/UI | Kotlin + 传统 View/ValueAnimator（不用 Compose） | 原版 `GestureAppLauncher` 即自定义 ViewGroup + 极坐标 `onLayout` + 多条 ValueAnimator 错峰；View 层可 1:1 映射，触摸路由可控，依赖最少 |
| 手势来源 | 全屏 `TYPE_APPLICATION_OVERLAY` 透明容器，仅角落带内消费事件 | 免 root 唯一可行路径；Flyme 原生用 `IWindowManager.registerSystemGestureListener`（框架私有），普通应用不可用 |
| 保活 | 前台服务 + `android:foregroundServiceType="specialUse"` | Android 14+ FGS 类型强制；overlay 常驻需服务宿主 |
| 持久化 | 应用私有 SharedPreferences（默认后端）；`Settings.Global` 作二期扩展（root/shizuku 通道） | 与 Flyme 原版一致的目标形态是 `Settings.Global`（原版读 `"long_press_app"`），但普通应用无法自授 `WRITE_SECURE_SETTINGS`（pm grant 对 signature|privileged 权限会失败），故本期以内部存储为主，跨组件用进程内回调同步；`PinStore` 接口预留 Global 后端（用户 adb 写入场景，二期） |
| 构建 | AGP 9.4.1 + Kotlin 2.4.20 + Gradle 9.6.1 wrapper（本机 9.4.1 发行包首跑自动下载）；compileSdk 37 / minSdk 33 / targetSdk 35；JDK 25 | 本机 SDK 含 android-33~37 + build-tools 37.0.0；依赖栈对齐 `E:\workspace\ios16\hypermirror`（AGP 9.3.2/Kotlin 2.4.10 同级验证可行），取最新稳定版 |
| 依赖 | androidx core-ktx 1.19.1 / appcompat 1.8.0 / material 1.14.0 / recyclerview 1.4.0 / lifecycle 2.11.0 / coroutines 1.11.0 / annotation 1.10.0；JUnit4.13.2 单测；**不引** MzX (`flyme.support.v7.*`)、libpag、glide | 后三者厂商私有/需替换；图标加载用 PackageManager + LruCache 自实现 |

## 3. 组件结构

```
bubbledrawer/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ app/build.gradle.kts
└─ app/src/main/
   ├─ AndroidManifest.xml
   ├─ kotlin/com/repl/bubbledrawer/
   │  ├─ LauncherService.kt          前台服务，生命周期宿主
   │  ├─ overlay/OverlayHost.kt      悬浮层添加/移除/尺寸与旋转跟随
   │  ├─ overlay/OverlayRootView.kt  根 ViewGroup：角落带命中判定与事件分流
   │  ├─ gesture/CornerGestureDetector.kt   斜上滑状态机 + 让位(CANCEL)策略
   │  ├─ gesture/TouchZone.kt        角落热区几何(内缩/宽/高)计算
   │  ├─ bubble/BubbleDockView.kt    极坐标布局 + 逐个展开/瞄准/收起动画
   │  ├─ bubble/BubbleItem.kt        数据模型 + LauncherAppPersistent 字段
   │  ├─ bubble/MotionSpec.kt        动效常量表（照搬 flyme 数值）
   │  ├─ more/MoreAppsActivity.kt    更多页 4 列网格
   │  ├─ pin/PinManageActivity.kt    固定管理（拼音分组 + 索引条）
   │  ├─ pin/LetterIndexBar.kt       A–Z 侧栏（复刻 fastscrollletter 行为）
   │  ├─ pin/SectionAdapter.kt       多 ViewType 分组适配器(★/推荐/A..Z/头部)
   │  ├─ pinyin/HanziToPinyin.kt     GB 拼音表移植（自 C2282f）
   │  ├─ pinyin/AppSortKey.kt        排序键 + 比较器（自 C7094a/C2874h）
   │  ├─ data/PinStore.kt            后端抽象：PrefsPinStore(默认) / SettingsGlobalPinStore(二期)
   │  ├─ data/AppRepository.kt       launcher 应用枚举/图标缓存/使用频次
   │  ├─ launch/ILaunchStrategy.kt   启动抽象（本期 FullscreenLaunchStrategy）
   │  ├─ root/EdgeModeHelper.kt      贴边模式(root)开关与降级提示
   │  ├─ settings/MainActivity.kt    引导/权限/触发带调参/备选触发位
   │  └─ util/Dimens.kt, Sp.kt
   └─ res/  values(字符串/尺寸/颜色) layout drawable mipmap xml(偏好)
```

## 4. 手势与交互设计

### 4.1 触发带（方案 A：内缩角落带）
- 屏幕底左/底右各一块热区：`inset=32dp`（距左右边与底边）、`width=96dp`、`height=160dp`，三项均可在设置页调。
- 内缩 32dp 的依据：AOSP 底部返回手势判定带最窄约 24dp，内缩后事件先到达我们的 overlay，不会被 system gesture region 抢先；同时用户仍能通过"贴边正上滑"正常返回。
- 角落外区域 `FLAG_NOT_TOUCHABLE` 穿透（overlay 根视图对未命中事件调用 `requestDisallowInterceptTouchEvent` 等价逻辑：根 view 返回 false 不消费，系统自然下发到底层窗口）。

### 4.2 判定（斜上滑，非正上滑）
```
滑动起点在角落带内；
dy <= -40dp                            // 上滑距离
|dx| >= 20dp                           // 必须有横向分量（角落语义）
|dy| >= |dx| * 0.5                     // 仍以向上为主，排除纯横滑（部分 ROM 侧滑返回）
```
- 满足 → `expand()`，此后整条手势由我们消费直到 UP。
- 不满足且已移动 → 取消，收起（若已展开）。
- 收到 `ACTION_CANCEL` → 视为被系统抢走，静默收起，不弹任何提示（这是与返回手势共存的关键礼貌行为）。

### 4.3 展开 → 瞄准 → 启动
1. **展开**：整扇同步 AnimatorSet（§5.1，非逐气泡延迟）；锚点 = 该角落圆心（左角→圆心在左下，右角对称，扇形朝屏幕内 90°）。
2. **瞄准**：以锚点为原点计算手指极角 θ；`Δ=90/childCount`，`|θ-θ_i|<Δ/2` 且半径∈[R-iconW/2, R+iconW/2+slop] 命中（原版 `GestureAppLauncher.m9730q` :559-590 / 带宽 `C2956r.f10731b/f10732c` :788-789）。命中反馈 = **波纹环**（`SlideGestureItemView.dispatchSetPressed→draw`：STROKE 圆 strokeWidth 0→(rView−rIcon)，130ms `(0.33,0,0.66,1)`，色 `slide_gesture_app_circle_icon_bg_color`）；图标尺寸不变、无文字标签（`slide_gesture_list_item.xml` 仅含 44dp CircleImageView，padding 3.25dp）。
3. **松手**：`m9729p` 语义——瞄准位存在且半径离开气泡带（甩出）→ 取消回调；在带内或 fling 速度>2500 朝屏内 → `ILaunchStrategy.launch(item)`；本期 = `startActivity(getLaunchIntentForPackage, NEW_TASK)` = 全屏打开。随后收起动画（§5.2），结束回调里才真正移除视图。
4. **更多 tile**：列表固定前 6 应用 + 尾部"更多"tile（`m9235C` :447-463）；瞄准/点击"更多" → 收起并启动 `MoreAppsActivity`。
5. **取消**：`f10698k` 语义——未命中任何气泡松手 / 半径 < 带内下限 → 取消回调 + 收起。

### 4.4 贴边模式（预留，方案 B）
设置页开关"贴边触发（需 root）"：
- 探测 `su`；可用则执行 `settings put secure system_gesture_insets_enabled 0`（并在关闭开关时还原为 1）。
- 成功后 `inset → 0dp`，触发带完全贴边并独占角落（含正上滑），与 Flyme 判定一致。
- 不可用/无 su → 保持内缩模式并给出"需 root 或改用三键导航"的说明文案。
- 设置页同时提供"侧边中部"备选触发位（方案 C，一个 下拉选择：底左/底右/双侧/侧边中部），实现成本极低且救场。

## 5. 动效规格（订正版：逐行重读反编译源码）

来源（订正时重新精读）：`GestureAppLauncher.java:412-504`（两条 AnimatorSet 定义）、`:110-232`（全部 update 监听器）、`:507-514`（角度分配）、`:770-796`（onLayout 极坐标）、`:759-764`（fling 甩出判定）、`SlideGestureItemView.java:22-232`（瞄准波纹环）、`:174/192`（130ms/0.33,0,0.66,1）。

**真实结构（与初版 spec 的"逐气泡 startDelay"不同，以代码为准）：所有 child 同步动画、整体成扇。** 展开/收起是各 7 条 ValueAnimator 的单一 AnimatorSet，每条驱动"遍历全部 child"的 setter——错峰感来自极坐标位置差与 rotation 扫弧，而非独立延迟。

### 5.1 展开 `m9714B()`（:460-504）：`f10710w`，7 通道
| # | 值域 | 时长 | 延迟 | 曲线 | 驱动（监听器） |
|---|---|---|---|---|---|
| 1 | alpha 0→1（整容器 setAlpha） | 130 | — | `(0.33,0,0.67,1)` | C2946h :225 |
| 2 | **rotation -270→5**（全部 child.setRotation） | 130 | — | `(0.24,0.17,0.53,0.82)` | C2947i :236 |
| 3 | **scale 0.8→1.05**（全部 child，瞄准位除外） | 130 | — | `(0.24,0.74,0.53,0.92)` | C2948j :249 |
| 4 | 位移 终位←起点（左角：起点=右下角+18px；右角对称） | 130 | — | `(0.24,0.55,0.53,0.8)` | C2949k :266 |
| 5 | rotation 5→0（收尾归正） | 250 | 130 | `(0.19,-7.6,0.48,1)`（强回弹） | C2950l :299 |
| 6 | scale 1.05→1.0 收尾 | 250 | 130 | `(0.19,0.31,0.48,1)` | C2951m :312 |
| 7 | 位移 x 微调 ±18px→0 | 250 | 130 | `(0.19,1.23,0.67,1)` | C2952n :329 |

### 5.2 收起 `m9713A()`（:417-457）：`f10711x`，7 通道，j=100ms
alpha 1→0 `(0.33,0,0.67,1)`｜rot 0→5 `(0.17,0,0.53,-6.56)`｜scale 1→1.05 `(0.17,0,0.53,0.7)`｜位移→角落起点（同 #4 反向）各 100ms；随后延迟 100ms 再三条：rot 5→**-270** `(0.19,-0.06,0.32,1)`、scale 1.05→0.8 `(0.19,0,0.32,1)`、位移回角落 `(0.19,0.06,0.32,1)`。收起完成 `f10693f=-1` 复位（:592-605）。

### 5.3 布局几何（非猜测项）
- 半径：`R = @dimen/slide_gesture_launcher_item_radius = 277dp`（无导航栏变体 242dp）；构造函数 `f10694g = getDimensionPixelSize(f9517R)`（:935），`setRadius()`（:878）可覆盖；有导航栏时锚点 y=`measuredHeight`，否则减导航高度。
- 角度分配 `m9726m(i,n)`（:507-514）：`margin=0` 时 `θ = 90*(i+1)/(n+1)`；`f10709v≠0` 时 `f=(90-2m)/n; θ=i*f+f/2+m`。
- 摆放 `onLayout`（:777-791）：`x = sin(θ)*R`（左角镜像 `width-sin(θ)*R`），`y = H - cos(θ)*R`，child 居中于该点。角度始终 0°=正上、90°=屏幕内。
- 瞄准 `m9730q`（:559-590）：`Δ=90/childCount`；`|θ_touch - θ_i| < Δ/2` 且半径 ∈ [R-w/2, R+w/2+touchSlop]（:788-789）→ `setPressed(true)`（C2956r 带宽缓存 :386-395）；角度算法 `m9727n = acos((H-y)/dist)`（:517-519，dist<1 防 NaN：`f10705r` :521-528）。
- **瞄准反馈 = 波纹环**（`SlideGestureItemView.draw` :225-232 + `dispatchSetPressed` :202-222）：STROKE 圆，半径 `r_icon + strokeWidth*0.5`，`strokeWidth = (r_view-r_icon) * p`，p 0→1 130ms `(0.33,0,0.66,1)`；描边色 `@color/slide_gesture_app_circle_icon_bg_color`；按压取消则 p→0 反转。**图标本身不放大**（初版 spec 写错，已订正）。
- 甩出加速 `onFling`（:739-767）：`√(vx²+vy²) > 2500` 且角度/速度方向朝屏内 → 松手仍启动（`f10698k=false` 路径 :752-760）；UP 时瞄准者半径带内直接放行启动（:531-556 `m9729p`）。
- 气泡 tile：`slide_gesture_list_item.xml` = `SlideGestureItemView{ padding=@dimen/slide_gesture_item_padding(3.25dp) } > CircleImageView id/slide_icon 44dp`（无文字标签；原版气泡条就不显示应用名）。
- 列表构成 `AppLauncherWindow.m9235C`（:447-463）：**固定取前 6 项 + 尾部"更多"tile**（drawable f9593o）；点击更多 → MoreAppWindow。
- 触发位移阈值 `slide_trigger_scroll_distance = 50dp`（f9527a0，:944）。

## 6. 数据与页面

### 6.1 数据模型
```kotlin
data class LauncherAppPersistent(   // 对齐 Flyme 原版字段(C2762j.java:407-432)
  val packageName: String,
  val userId: Int = 0,
  val shortcutId: String? = null,
  val recentStartTime: Long = 0,
  val usageCount: Int = 0,
)
```
- 固定列表（气泡条内容）= 用户勾选前 6 项 + 由 `PinStore` 顺序定义；顺序保存为 `"pkgA#0;pkgB#0;..."`，读回时按 indexOf 排序（原版 `C2762j.h` 比较器语义）。
- 数据源：`packageManager.getInstalledApplications` + `getLaunchIntentForPackage != null` 过滤；排除自身包名。
- 使用频次：本期用"启动时 +1"本地计数（`usageStats` 需 `PACKAGE_USAGE_STATS` 授权，列为可选项）。

### 6.2 更多页（对齐 `MoreAppWindow.java`）
- 4 列 `RecyclerView` 网格；内容 = 全量可固定应用（固定 6 个之后的部分优先，保持原版"第 6 项以后"语义），尾部追加"添加应用"tile（→ 固定管理页）。
- 右上菜单 → 固定管理页（原版 `onOptionsItemSelected` → `SlideLaunchAppSettings`）。
- 点击项 = 全屏启动 + 收起气泡条；启动后把该应用插入★区末尾（若已满 6 则替换末位），与原版"最近使用上提"一致。

### 6.3 固定管理页（对齐 `SlideLaunchAppSettings.java` 应用页签）
- 分组顺序：**★（已固定，≤6）** → **推荐**（使用频次 Top N，对应原版 `recommend` 组头）→ **A…**Z → **其它**（首字符非字母/数字，原版 `C2874h` 把非字母沉底）。
- 排序键：`HanziToPinyin.convertToPinyin(label)` 取首字母大写；数字/英文原样；比较器 = 频次降序（开关可选）→ 排序键 `compareTo`（原版 `C7094a`）。
- 交互：点项=固定/取消固定（★ 标记 + 触顶时 toast "最多固定 6 个"）；★区支持长按拖拽排序（对齐原版 `long_press_app` 配置排序）；右侧索引条点击/拖动定位分组，★置顶。
- 索引条 `LetterIndexBar`：复刻 `com.meizu.common.fastscrollletter.FastScrollLetter` 的**行为**（A–Z 竖排、当前字母高亮、滚动联动、点击回调），重写实现，不引 MzX。

### 6.4 `HanziToPinyin` 移植要点
源：`common/utils/C2282f.java`（366 项边界汉字表 `f7593b` + 拼音表 `f7594c` + `Collator(Locale.CHINA)` 二分）。移植为纯 Kotlin 对象，保持"逐字取首字母、英文数字原样、非中文字符跳过"的行为；用少量单测锁定（微信→W、支付宝→Z、QQ→Q、美团→M、bilibili→b 原样）。

### 6.5 原版 UI 事实表（反编译直读；实现必须照抄，不得另猜）
| 元素 | 事实 | 出处 |
|---|---|---|
| 气泡 tile | `SlideGestureItemView`(波纹环容器) padding **3.25dp** `slide_gesture_item_padding`；内仅 1 个 `CircleImageView id/slide_icon` **44dp** `launcher_app_item_icon_width`；**无标题文本** | `slide_gesture_list_item.xml` |
| 波纹环色 | `slide_gesture_app_circle_icon_bg_color`（0x7f06053b，values/colors.xml 取值随实现抄录） | `SlideGestureItemView.java:148` + `AbstractC2765m.f9497e` |
| 气泡个数 | 固定 **前 6** + 尾部"更多"tile（drawable 0x7f13? f9593o） | `AppLauncherWindow.m9235C:447-463` |
| 触发位移 | `slide_trigger_scroll_distance = 50dp` | f9527a0 dimens |
| 半径 | `slide_gesture_launcher_item_radius = 277dp`；无导航栏 `242dp` | dimens.xml |
| 更多页 | Activity 全屏，`fitsSystemWindows`；RecyclerView **4 列** GridLayoutManager(this, 4)；`paddingStart 27dp / paddingEnd 13dp` | `MoreAppWindow.java:175` + `slide_gesture_more_app_window.xml` |
| 更多页 tile | 宽 **66dp** `launcher_more_app_item_width` minHeight **91dp**；图标 **48dp** marginTop 9dp centerCrop；标题 marginTop 12dp，`TextAppearance.AppItemTitle.MoreApp`(Flyme.B6, textColor **#ff000000**, b6 尺寸) | `launcher_more_app_item.xml` + styles.xml |
| 管理页 tile | 宽 **70dp** minHeight **84dp** paddingVertical 8dp；图标 **50dp** marginTop 7dp 描边 1dp `#0d000000`(outline_variant)；标题 12sp **#e6000000** marginTop 10dp maxLines1 sans-serif-medium includeFontPadding=false + 右上角 24dp operate_icon(选中角标，默认 invisible) | `launcher_app_item.xml` + `TextAppearance.AppItemTitle` |
| 管理页结构 | AppBar(`colorSurfaceBright`, elevation 0)：head 行「已添加」(14sp TipsTitle `#993c3c43`) + 右「长按拖动图标以排序」(14sp TipsSummary `#664d4d4d`)，paddingH 20dp paddingTop 18dp；已选区 `AppDragLayout` minHeight 78dp marginTop 12dp marginBottom 20dp + 空态「暂无已添加应用」marginTop 40dp 居中；divider marginBottom 16dp；tab 行 minHeight **54dp** indicator 透明 | `slide_launch_app_settings.xml` + `app_settings_item_head/selected/divider.xml` |
| 分区页 | 组头 TextView（14sp AllApp.Category，paddingTop 18dp paddingBottom 8dp paddingH 20dp）+ 4 列网格内嵌；「最近」区带右侧「清除」按钮 | `app_settings_item_all.xml`、`app_settings_item_recent.xml`、`SlideLaunchAppSettings.m9522W f10411g0=4` |
| 字母索引条 | 宽 **28dp** `mc_fastscroll_letter_layout_wdith`；文字 **12px** `mc_fastscroll_letter_text_size`；上下 padding **50dp**、右 padding 4dp(curved 6dp)；选中字母背景圆角 **7dp**、行距 **4dp**；overlay 气泡 59dp 宽、字号 32.5sp(两字18sp/三字16sp)、距右 44dp、文字白 `#ffffffff`；普通字母色 `#cccccc`(default) | dimens/colors `mc_fastscroll_*`、`fastscroller_overlay_*` |
| 文案（zh） | 更多应用/添加应用/更多/推荐/已添加/长按拖动图标以排序/暂无已添加应用/最近/清除/应用/功能 | values-zh-rCN/strings.xml |

## 7. 权限与首次配置
| 权限/设置 | 用途 | 获取方式 |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮层 | 设置页按钮跳 `ACTION_MANAGE_OVERLAY_PERMISSION` |
| `POST_NOTIFICATIONS` | 前台服务通知 | 运行时申请 |
| （无需授权）内部存储 | `PinStore` 默认后端：应用私有 SharedPreferences（JSON），launcher 进程与 overlay 服务经单进程同包共享可直接读写 | 开箱即用 |
| `WRITE_SECURE_SETTINGS`（可选，二期） | `Settings.Global` 后端（对齐原版 `long_press_app` 形态，便于脚本注入/跨用户） | 普通应用不可 pm grant；二期经 root 或 Shizuku 提供，PinStore 已留接口 |
| root（可选） | 贴边模式 | `su` 探测；失败仅提示 |

首次启动流程：主界面 = 权限引导 + 服务开关 + 触发带调参 + 快速测试按钮（立刻弹出气泡条预览）。

## 8. 测试策略
- **单测（JVM）**：拼音排序键与分组（含多音字常见项、英文/数字/符号）、比较器稳定性、`PinStore` 序列化/反序列化、触发带几何与判定阈值纯函数（输入序列→是否触发）、★上限 6 与顺序持久化。
- **UI 验证**：debug 构建在真机手工回归 A2–A6；录屏与 Flyme 原版逐帧比对展开曲线（同机型：魅族 22 可提供参考录屏）。
- **兼容性检查**：`GestureDetector` 与系统返回手势共存（开启/关闭贴边模式各测）、旋转/分屏下 overlay 尺寸重建、后台服务被杀后自动恢复。

## 9. 已知风险
1. **手势共存**：个别 ROM（MIUI/ColorOS）底部手势区更宽，32dp 内缩仍可能被抢 → 缓解：内缩可调至 48dp + 侧边中部备选位 + 贴边(root)模式。
2. **overlay 覆盖层级**：气泡条在部分系统全屏界面（如游戏、通话）之上体验差 → 二期加"前台包名黑名单"。
3. **`Settings.Global` 二期再上**：本期 PinStore 用应用私有存储（单进程共享，开箱即用）；若二期需要 Global 形态（脚本注入/跨组件Observer），走 root/Shizuku 通道，`PinStore` 接口已预留，不阻塞任何功能。
4. **图标主题差异**：目标 ROM 无 Flyme 图标形状，圆角/尺寸按目标系统自适应（本期统一圆形 mask）。
5. **多音字**：以 Flyme 表行为为准，不做词库修正；用户可用配置串手调顺序（原版同样能力）。

## 10. 里程碑
1. **M1 骨架**：工程 + 前台服务 + overlay 容器 + 主界面权限引导；能手动触发预览气泡条。
2. **M2 手势**：角落带 + 状态机 + 让位策略；免 root 可用。
3. **M3 动效**：`MotionSpec` 全量落地，展开/瞄准/拖拽/收起，全屏启动。
4. **M4 更多+固定**：更多页、拼音分组、索引条、PinStore 双向同步。
5. **M5 打磨**：贴边(root)开关、备选触发位、图标缓存、旋转适配、真机回归。
6. **二期候选**：功能页签、usageStats、黑名单、真正的 freeform 启动策略（接 AOSP `WindowOrganizer`）。
