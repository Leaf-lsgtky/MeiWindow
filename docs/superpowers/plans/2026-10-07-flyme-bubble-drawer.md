# Flyme 气泡抽屉复刻 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpower-subagent-driven-development (recommended) or superpower-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建独立 APK `com.repl.bubbledrawer`，复刻 Flyme 边角滑出气泡条（逐个弧形错峰展开）、更多页、固定管理（拼音 A–Z 分组+索引条），点选暂时全屏启动。

**Architecture:** Kotlin + 纯 View 层。前台服务持有全屏透明 overlay，根 ViewGroup 仅在底部角落带内拦截触摸并驱动状态机；气泡布局为极坐标（角度×半径），动画参数照搬反编译 `GestureAppLauncher.java`。数据层 PinStore（SharedPreferences 后端，预留 Global 接口）；启动经 `ILaunchStrategy` 抽象，本期唯一实现=全屏。

**Tech Stack:** Gradle 9.6.1 wrapper + AGP 9.4.1 + Kotlin 2.4.20 + JDK 25（`C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot`）；compileSdk 37 / minSdk 33 / targetSdk 35；core-ktx 1.19.1、appcompat 1.8.0、material 1.14.0、recyclerview 1.4.0、lifecycle-service 2.11.0、annotation 1.10.0、coroutines 1.11.0；JUnit 4.13.2 单测。

**Spec:** `docs/specs/2026-10-07-flyme-bubble-drawer-design.md`（本仓库内）。
**参考源码（只读）：** `E:\workspace\flyme\out\SystemUITools_src\`；工程模板参照 `E:\workspace\ios16\hypermirror\`（wrapper jar 直接复制其 `gradle/wrapper/gradle-wrapper.jar`）。

**约定:**
- 工作目录 `E:\workspace\flyme\bubbledrawer`，所有相对路径以此为根。
- 构建命令统一 `.\gradlew.bat`（PowerShell，首跑自动下载 wrapper 到缓存）。
- 单测命令 `.\gradlew.bat :app:testDebugUnitTest --tests "<pattern>"`。
- git 提交在每个 Task 的最后一步；消息用 conventional 风格。

---

### Task 0: 工程骨架与构建打通

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`(root), `gradle.properties`, `local.properties`, `.gitignore`
- Create: `gradle/wrapper/gradle-wrapper.properties`（改 distributionUrl 为 9.6.1）+ `gradle/wrapper/gradle-wrapper.jar`（复制 hypermirror）+ `gradlew.bat`（复制 hypermirror）
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/{strings.xml,colors.xml,themes.xml}`、`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` + `res/drawable/ic_launcher_foreground.xml`（纯色 vector 占位即可）
- Create: `app/src/main/java/com/repl/bubbledrawer/MainActivity.kt`（临时空壳，Task 9 替换）

- [ ] **Step 1:** 写 `settings.gradle.kts`：

```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "bubbledrawer"
include(":app")
```

- [ ] **Step 2:** 写 root `build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
```

- [ ] **Step 3:** 写 `gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`local.properties`: `sdk.dir=E\:/Android/Sdk`

- [ ] **Step 4:** wrapper：从 `E:\workspace\ios16\hypermirror\gradle\wrapper\` 复制 `gradle-wrapper.jar`；写 `gradle-wrapper.properties` 同 hypermirror 但 `distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip`；复制 `gradlew.bat` 与 `gradlew`。
- [ ] **Step 5:** `app/build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.repl.bubbledrawer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.repl.bubbledrawer"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
```

注：AGP 9.x 的 `kotlin{}` DSL 若在 AGP9.4.1+kotlin2.4.20 下不可用，回退 `android.kotlinOptions` 已删除的事实——改用顶层 `kotlin { compilerOptions { jvmTarget = ... } }`（KGP 提供）。构建报错时以报错信息为准调整，保持行为不变。

- [ ] **Step 6:** Manifest（仅骨架，Task 6 起逐项补齐）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <queries><intent><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent></queries>
    <application android:allowBackup="false" android:icon="@mipmap/ic_launcher" android:label="@string/app_name" android:theme="@style/Theme.BubbleDrawer">
        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask">
            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
        </activity>
    </application>
</manifest>
```

`<queries>` 必加：Android 11+ 包可见性限制，否则枚举不到 launcher 应用（这是与原版的隐性差异点，原版有 QUERY_ALL_PACKAGES）。
strings/colors/themes：`app_name="气泡抽屉"`；Theme 继承 `Theme.Material3.DayNight.NoActionBar`。

- [ ] **Step 7:** 构建验证：`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`，产出 `app\build\outputs\apk\debug\app-debug.apk`。
- [ ] **Step 8:** `git init; git add -A; git commit -m "chore: gradle + AGP 9.4.1 + Kotlin 2.4.20 skeleton"`。

---

### Task 1: 拼音模块（HanziToPinyin + 排序键）

**Files:**
- Create: `app/src/main/java/com/repl/bubbledrawer/pinyin/HanziToPinyin.kt`
- Create: `app/src/main/java/com/repl/bubbledrawer/pinyin/AppSortKey.kt`
- Test: `app/src/test/java/com/repl/bubbledrawer/pinyin/HanziToPinyinTest.kt`
- Test: `app/src/test/java/com/repl/bubbledrawer/pinyin/AppSortKeyTest.kt`

纯 JVM（仅 `java.text.Collator`），可完全单测。

- [ ] **Step 1 (红):** 写失败测试：

```kotlin
class HanziToPinyinTest {
    @Test fun chineseInitial() {
        assertEquals("W", HanziToPinyin.sortKey("微信"))
        assertEquals("Z", HanziToPinyin.sortKey("支付宝"))
        assertEquals("M", HanziToPinyin.sortKey("美团外卖"))
    }
    @Test fun asciiPassThrough() {
        assertEquals("Q", HanziToPinyin.sortKey("QQ"))
        assertEquals("b", HanziToPinyin.sortKey("bilibili")) // 小写原样，分组时再 upper
    }
    @Test fun mixedAndSymbols() {
        assertEquals("W", HanziToPinyin.sortKey("360卫士".drop(3))) // "卫士"
        assertNull(HanziToPinyin.sortKey("!@#"))                    // 无有效首字母
    }
}
class AppSortKeyTest {
    @Test fun groupsByUpperInitial() {
        assertEquals("W", AppSortKey.groupOf("微信"))
        assertEquals("Q", AppSortKey.groupOf("QQ"))
        assertEquals("#", AppSortKey.groupOf("!@#")) // 非字母数字组沉底桶
    }
    @Test fun comparatorUsageThenKey() {
        val a = BubbleApp("a", "阿里邮箱", usageCount = 5, sortKey = "A")
        val b = BubbleApp("b", "微信", usageCount = 5, sortKey = "W")
        val c = BubbleApp("c", "Z应用", usageCount = 9, sortKey = "Z")
        assertEquals(listOf(c, a, b), listOf(b, a, c).sortedWith(AppSortKey.DEFAULT))
    }
}
```

- [ ] **Step 2 (绿):** 实现 `HanziToPinyin`：从 `E:\workspace\flyme\out\SystemUITools_src\sources\com\flyme\systemuitools\common\utils\C2282f.java` 移植 366 项边界表（字段 `f7593b`，静态数组逐字符转写）与拼音表 `f7594c`；API：`fun sortKey(label: String): String?`（取首个可映射字符的拼音首字母串，英文数字原样保留大小写，跳过空白/符号直到找到有效字符）。用 `Collator.getInstance(Locale.CHINA)` + 边界二分（同原版 `m7034b`）。实现时**必须**以反编译表数据为准，不许凭记忆写表。
- [ ] **Step 3:** `BubbleApp` 数据类（`bubble/BubbleItem.kt`）：

```kotlin
data class BubbleApp(
    val packageName: String,
    val label: String,
    val userId: Int = 0,
    val shortcutId: String? = null,
    val recentStartTime: Long = 0L,
    val usageCount: Int = 0,
    val sortKey: String = AppSortKey.of(label),
)
```

`AppSortKey`：`of(label)=HanziToPinyin.sortKey(label) ?: label`；`groupOf(key)`=首字符 `uppercaseChar`，字母→A–Z，数字→"0–9"，其他→"#"；`DEFAULT` 比较器 = usageCount 降序 → sortKey `compareTo`（原版 C7094a）；`GROUP_ORDER` 比较器 = groupOf 升序、`#` 最后、`0–9` 在 A–Z 前（对齐 C2874h 非字母沉底）。
- [ ] **Step 4:** `.\gradlew.bat :app:testDebugUnitTest --tests "com.repl.bubbledrawer.pinyin.*"` 全绿。
- [ ] **Step 5:** Commit `"feat: pinyin sort key ported from flyme HanziToPinyin"`。

---

### Task 2: 触发区几何与手势状态机（纯逻辑）

**Files:**
- Create: `bubble/../gesture/TouchZone.kt`、`gesture/CornerGestureDetector.kt`（包 `com.repl.bubbledrawer.gesture`）
- Test: `app/src/test/java/com/repl/bubbledrawer/gesture/CornerGestureDetectorTest.kt`

- [ ] **Step 1 (红):** 测试定义判定语义（与 spec §4.2 一致，单位 px 由调用方换算，阈值构造注入）：

```kotlin
class CornerGestureDetectorTest {
    private fun det(zones: List<CornerZone>) = CornerGestureDetector(zones, slopPx = 20f, minUpPx = 100f, minSidePx = 50f, minUpRatio = 0.5f)
    private val l = CornerZone(Corner.BOTTOM_LEFT, 0f, 0f, 288f, 576f, 900f) // inset,x0,y0..简化见实现
    @Test fun diagonalUpSwipeFromLeftCornerTriggers() {
        val d = det(listOf(l)); d.onDown(144f, 850f)
        assertEquals(Feed.ACCEPT, d.onMove(144f + 120f, 850f - 260f))
        assertTrue(d.triggered && d.corner == Corner.BOTTOM_LEFT)
    }
    @Test fun straightUpSwipeIsIgnored() {
        val d = det(listOf(l)); d.onDown(144f, 850f); d.onMove(144f + 10f, 850f - 300f)
        assertFalse(d.triggered)
    }
    @Test fun downOutsideZoneNeverTakes() {
        val d = det(listOf(l)); assertEquals(Feed.IGNORE, d.onDown(600f, 400f))
    }
    @Test fun cancelResetsToIdle() { /* onDown→move→triggered→onCancel ⇒ state==IDLE, !triggered */ }
    @Test fun horizontalSwipeIgnored() { /* |dy|<0.5|dx| ⇒ !triggered */ }
}
```

- [ ] **Step 2 (绿):** 实现。`enum Corner{BL,BR,SIDE_L,SIDE_R}`；`data class CornerZone(corner, left, top, right, bottom)`（屏幕坐标，由 Task 3 从 dp 配置算出）；`enum Feed{IGNORE, PENDING, ACCEPT}`；`CornerGestureDetector`：onDown 命中 zone→PENDING 记录起点；onMove：PENDING 中先满足 slop 判定，再判 `dy<=-minUp && |dx|>=minSide && |dy|>=|dx|*minUpRatio`→ACCEPT+triggered(corner)；斜率方向需朝向屏幕内（BL 要求 dx>0，BR 要求 dx<0）；onCancel→reset；SIDE_* 侧边备选位：`dx<=-minSide && |dy|<|dx|*minUpRatio && |dy|<=minUp`（纯横滑）。
- [ ] **Step 3:** 测试全绿（含构造器注入参数变体：inset=0 贴边仍同一状态机）。
- [ ] **Step 4:** Commit `"feat: corner gesture state machine"`。

---

### Task 3: MotionSpec + BubbleDockView（核心动效，无系统依赖部分先测）

**Files:**
- Create: `bubble/MotionSpec.kt`、`bubble/BubbleDockView.kt`、`bubble/BubbleAnchors.kt`
- Test: `app/src/test/java/com/repl/bubbledrawer/bubble/BubbleAnchorsTest.kt`（纯几何）

- [ ] **Step 1:** `MotionSpec`（全部常量集中，来源注释行号）：

```kotlin
object MotionSpec {
    // GestureAppLauncher.java:429-499 / AppLauncherWindow.java:483-501
    const val STEP_DELAY = 50L; const val MAX_STAGGER = 150L
    const val RADIUS_DUR = 130L; const val ALPHA_DUR = 130L
    const val ANGLE_DUR = 250L;  const val SCALE_DUR = 250L
    val RADIUS_INT = floatArrayOf(0.19f, -0.06f, 0.32f, 1.0f)   // PathInterpolator 控制点
    val ANGLE_INT = floatArrayOf(0.32f, -1.23f, 0.67f, 1.0f)
    val OVERSHOOT_INT = floatArrayOf(0.19f, 0.31f, 0.48f, 1.0f)
    val ALPHA_INT = floatArrayOf(0.33f, 0f, 0.67f, 1f); val ALPHA_ALT = floatArrayOf(0.24f, 0.55f, 0.53f, 0.8f)
    val POS_INT = floatArrayOf(0.24f, 0.17f, 0.53f, 0.82f)
    const val ANGLE_FROM = -270f; const val ANGLE_TO = 5f
    const val COLLAPSE_DUR = 150L
    const val DRAG_DAMP = 0.05f; const val DRAG_CLAMP = 0.25f
    const val AIM_SCALE = 1.05f; const val AIM_DUR = 200L; val AIM_INT = floatArrayOf(0.25f, 0.1f, 0.25f, 1f)
}
```

- [ ] **Step 2 (红):** `BubbleAnchors` 纯函数单测（布局几何，原版 `GestureAppLauncher.java:781-783` 极坐标）：

```kotlin
class BubbleAnchorsTest {
    @Test fun placesItemsOnArcFromCorner() {
        val p = BubbleAnchors.layout(count = 6, anchor = Anchor(cornerX = 0f, cornerY = 1000f, startDeg = 0f, sweepDeg = 90f), radius = 300f, itemSize = 144f)
        assertEquals(6, p.size); assertTrue(p[0].y < 1000f)             // 向上展开
        val d0 = hypot(p[0].cx - 0f, p[0].cy - 1000f)
        val d5 = hypot(p[5].cx - 0f, p[5].cy - 1000f)
        assertEquals(d0, d5, 1f)                                        // 同半径
        assertTrue(p[5].cx > p[0].cx)                                   // 角度推进朝屏幕内
    }
    @Test fun aimPicksNearestAngleWithinRadiusBand() {
        val p = ... // 同上
        assertEquals(0, BubbleAnchors.aim(p, touchX = 0f + 10f, touchY = 1000f - 260f, minRadius = 60f)) // 接近0°
        assertNull(BubbleAnchors.aim(p, touchX = 0f, touchY = 1000f - 30f, minRadius = 60f))             // 半径不足→取消瞄准
    }
}
```

- [ ] **Step 3 (绿):** `BubbleAnchors.layout/aim`（`anchor.startDeg/sweepDeg` 由角落决定：BL start=0°朝上→90°朝右；BR 镜像）；`aim` = 触摸点极角落在哪个角度区间且半径≥minRadius，否则 null。Δ角默认 `sweep/(n-1)`（n>1；n 含尾部"更多"tile）。
- [ ] **Step 4:** `BubbleDockView : ViewGroup`（Android 类，不做 JVM 单测，逻辑已由 BubbleAnchors 承载）：
  - `onLayout`：静态终位 = `layout(items)`；动画期用 `animatorProgress[i]`（半径0→1/角度-270→5/缩放1.05→1）实时覆盖，`child.translationX/Y + scale + alpha`。
  - `fun expand(corner: Corner, apps: List<BubbleApp>, onLaunched:(BubbleApp)->Unit)`：造 child（图标圆形 mask 56dp + 角标 + 标签 TextView 隐藏），逐 child `AnimatorSet.playTogether(4 条 ValueAnimator)` 按 MotionSpec（错峰 `min(i*STEP_DELAY, MAX_STAGGER)`）。
  - `fun collapse()`：反向 150ms；结束后移除 overlay child。
  - `fun onTouchPoint(x,y)`：调 `aim` → 命中 child `scale→1.05(200ms,AIM_INT)`、显标签，其余 alpha 0.55。
  - 更多 tile：末位固定项（`"+"` 图标 drawable），瞄准启动 `onMoreRequested()` 回调。
- [ ] **Step 5:** 几何测试全绿。Commit `"feat: motion spec constants + polar bubble layout + dock view"`。

---

### Task 4: 数据层 AppRepository + PinStore

**Files:**
- Create: `data/AppRepository.kt`、`data/PinStore.kt`、`data/LaunchCountStore.kt`
- Test: `app/src/test/java/com/repl/bubbledrawer/data/PinStoreTest.kt`（序列化纯函数部分）

- [ ] **Step 1 (红):** `PinCodec` 序列化/反序列化往返 + 上限 6 + 顺序保持 + 非法行跳过测试（`encode(list): String` = `pkg#user;...`；`decode(str, resolver)`）。
- [ ] **Step 2 (绿):** `PinStore(context)`：后端 `SharedPreferences("pins")`（`interface PinBackend` 预留 `SettingsGlobalBackend` 二期）；`var onPinsChanged: ((List<PinnedRef>)->Unit)?`；AppRepository/管理页/服务三方订阅（同进程直接回调，对齐 spec §7 修订）。
- [ ] **Step 3:** `AppRepository(context)`：`suspend fun loadAll(): List<BubbleApp>`（`getInstalledApplications` + `getLaunchIntentForPackage!=null` 过滤 + 排除自身 + label/sortKey/usageCount(LaunchCountStore) + LruCache 图标 `getActivityIcon` 尺寸 56dp）；`fun icon(pkg): Drawable?`。`PinnedRef(pkg,userId)`。
- [ ] **Step 4:** 单测绿。Commit `"feat: app repository + pin store"`。

---

### Task 5: ILaunchStrategy + 全屏启动

**Files:**
- Create: `launch/ILaunchStrategy.kt`、`launch/FullscreenLaunchStrategy.kt`

- [ ] **Step 1:** 接口与实现：

```kotlin
fun interface ILaunchStrategy { fun launch(ctx: Context, app: BubbleApp): Boolean }
class FullscreenLaunchStrategy : ILaunchStrategy {
    override fun launch(ctx: Context, app: BubbleApp): Boolean = try {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(i); true
    } catch (e: Exception) { false }
}
```

（注释处保留 Flyme 对应点：`AppLauncherWindow.java:792-814` 的 `start_windowmode` Bundle 方案在此替换。）
- [ ] **Step 2:** 编译通过。Commit `"feat: launch strategy abstraction (fullscreen for now)"`。

---

### Task 6: OverlayHost + 前台服务 + 触发带接线

**Files:**
- Create: `overlay/OverlayHost.kt`、`overlay/OverlayRootView.kt`、`LauncherService.kt`、`bubble/BubbleConfig.kt`
- Modify: `AndroidManifest.xml`（注册 service + 通知 icon）

- [ ] **Step 1:** `BubbleConfig`：dp 常量+可调参数读 `SharedPreferences("cfg")`：`insetDp=32f, widthDp=96f, heightDp=160f, edgeMode=false, triggerPos=DOUBLE_BOTTOM`；`fun zones(w:Int,h:Int,density:Float): List<CornerZone>`（含 edgeMode 时 inset→0）。
- [ ] **Step 2:** `OverlayRootView : FrameLayout`：持有 `CornerGestureDetector`+`BubbleDockView`；`onTouchEvent` DOWN 走 detector.feed；ACCEPT 时把后续事件转给 dock（瞄准）；UP 时若已瞄准→`strategy.launch`+collapse，未瞄准且未展开→透传语义（返回 false）。detector 的 slop/minUp/minSide 用 px（dp*density 换算集中在此）。CANCEL→detector.onCancel+dock.collapse。
- [ ] **Step 3:** `OverlayHost(context)`：`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN`（全屏 MATCH_PARENT；**不加 NOT_TOUCHABLE**，拦截策略在根 view 返回值层做）；`add()/remove()`；`OrientationListener`/`onConfigurationChanged`（服务里 override）重建尺寸。
- [ ] **Step 4:** `LauncherService : LifecycleService`：onStartCommand 建通知（channel `gesture`，低优先，icon 复用 launcher 单色）+ `startForeground`（`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`）；检查 `Settings.canDrawOverlays` 失败→stopSelf；订阅 PinStore→更新 dock 数据；Task 9 的设置开关经 `startService/stop` 控制。
- [ ] **Step 5:** Manifest 加 `service` 声明（`foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property 字符串 "corner gesture overlay"）。
- [ ] **Step 6:** `assembleDebug` 通过。Commit `"feat: overlay host + foreground service wiring"`。

---

### Task 7: 更多页 MoreAppsActivity

**Files:**
- Create: `more/MoreAppsActivity.kt`、`res/layout/activity_more_apps.xml`、`res/layout/item_more_app.xml`、`res/layout/item_add_tile.xml`
- Test: `app/src/test/java/com/repl/bubbledrawer/more/MoreListTest.kt`（排序切片纯函数）

- [ ] **Step 1 (红):** `MoreList.build(all: List<BubbleApp>, pins: List<PinnedRef>): List<MoreCell>`——前 6 固定项之后的应用按 `AppSortKey.DEFAULT` 排入网格，尾部 `MoreCell.Add` tile（对齐 `MoreAppWindow.java:135-148` 的 `subList(6, end)` + add-tile 语义；pins 顺序=配置串顺序优先）。
- [ ] **Step 2 (绿):** Activity：4 列 `GridLayoutManager` RecyclerView（AppGridAdapter 复用 Task 8 的 item 布局）；标题"更多应用"；点项=`FullscreenLaunchStrategy.launch` + finish；Add tile→PinManageActivity；右上菜单(overflow xml)→PinManageActivity（对齐 `m9495s`）。Manifest 注册 activity。
- [ ] **Step 3:** 单测绿 + 构建过。Commit `"feat: more apps grid"`。

---

### Task 8: 固定管理页 + 字母索引条

**Files:**
- Create: `pin/PinManageActivity.kt`、`pin/SectionAdapter.kt`、`pin/LetterIndexBar.kt`、`pin/Sections.kt`
- Create: `res/layout/activity_pin_manage.xml`、`res/layout/item_pin_app.xml`、`res/layout/item_section_header.xml`
- Test: `app/src/test/java/com/repl/bubbledrawer/pin/SectionsTest.kt`

- [ ] **Step 1 (红):** `Sections.build(all, pins, usageOn): List<Row>` 纯函数测试：行类型序列 = ★(pins 顺序≤6) → 推荐(usageCount Top8 未固定) → 0–9 → A–Z（组内 sortKey 升序、usageCount 降序优先按开关）→ #（非字母沉底）；组头插入位置正确；空组跳过。
- [ ] **Step 2 (绿):** `LetterIndexBar : View`（复刻 `FastScrollLetter` 行为：竖排★0–9A–Z、当前字母高亮、ACTION_MOVE 命中字母回调 `onLetter(ch)`、`setLetters(visible)` 仅显现有分组）；`PinManageActivity`：RecyclerView+SectionAdapter（3 种 viewType）+索引条定位滚动（`scrollToPositionWithOffset` via LinearLayoutManager）+点行=固定/取消（满 6 toast "最多固定 6 个"）+★区拖拽排序（`ItemTouchHelper` 限 ★ 组）+搜索框（label 忽略大小写过滤，对齐原版搜索入口）；写回 PinStore。
- [ ] **Step 3:** 单测绿 + 构建过。Commit `"feat: pin manager with pinyin sections + letter index bar"`。

---

### Task 9: 主界面（权限引导 + 服务开关 + 调参 + root 贴边）

**Files:**
- Create: `settings/MainActivity.kt`（替换骨架）、`root/EdgeModeHelper.kt`、`res/layout/activity_main.xml`、`res/xml/backup_rules` 不需要
- Test: `app/src/test/java/com/repl/bubbledrawer/root/EdgeModeProbeTest.kt`（命令构造纯函数）

- [ ] **Step 1 (红):** `EdgeModeHelper.buildCmds(enable: Boolean)` 返回 `listOf("settings","put","secure","system_gesture_insets_enabled", if(enable)"0"else"1")`；`probe(suExists: Boolean)=...`。
- [ ] **Step 2 (绿):** MainActivity：服务开关（Switch→start/stopService，状态由 Binder 探测）、悬浮窗权限卡片（`ACTION_MANAGE_OVERLAY_PERMISSION` Intent）、通知权限请求、触发带三滑杆（inset/width/height→cfg）、触发位下拉（双侧/仅左/仅右/侧边）、贴边模式开关（su 探测，失败显示说明文案）、"立即预览气泡条"调试按钮（直接 expand 免手势）、打开更多页/固定页快捷入口。
- [ ] **Step 3:** Commit `"feat: settings hub + edge mode (root) helper"`。

---

### Task 10: 端到端装配 + 真机验证清单

**Files:**
- Modify: `LauncherService.kt`（PinStore→dock 数据流闭环、启动后 `LaunchCountStore.increment` + `recentStartTime` 上提★区末）
- Modify: `OverlayRootView.kt`（更多 tile 点击→启动 MoreAppsActivity + collapse）
- Create: `docs/test-plan-device.md`（勾选清单：A2–A7 每项步骤）

- [ ] **Step 1:** 装配缺口实现（上提语义：启动即 `moveToTopIfPinned` 不改 6 上限；对齐 `C2795h`(DynamicListener) 记录 recentStart 的角色）。
- [ ] **Step 2:** 全量 `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` 绿。
- [ ] **Step 3:** `E:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`（用户真机已连时执行；否则输出命令让用户跑）。
- [ ] **Step 4:** 引导用户按 `docs/test-plan-device.md` 验证；录屏对比 Flyme 展开曲线；按结果微调 `MotionSpec`。
- [ ] **Step 5:** Commit `"feat: end-to-end wiring + device test plan"`；tag `v0.1-interaction-replica`。

---

## Self-review 记录

- Spec §1.1 五项功能 ↔ Task 2/3(手势+展开)、5(启动)、7(更多)、8(固定+分组索引)；§1.2 非目标未混入任务；A1–A7 判据 ↔ Task 0/6/9/10。
- `dynamicanimation` 依赖已从 spec 技术栈移除（原版迷你窗用 Spring，本期交互链不需要）——已在 Task 0 依赖表反映。
- 类型一致性：`BubbleApp/PinnedRef/Corner/CornerZone/Feed/Anchor` 各任务引用一致；`ILaunchStrategy` 签名在 Task 5 定义、6/7/10 使用一致。
- 已知构建风险：AGP 9.x 下 `kotlin{}` DSL 写法差异已在 Task 0 Step 5 注记回退路径；JDK25+AGP9.4.1 组合与 hypermirror(AGP9.3.2/JDK25) 同级，可行。
