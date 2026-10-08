# 气泡抽屉 BubbleDrawer — Flyme 小窗交互复刻

复刻魅族"系统界面工具"（`com.flyme.systemuitools`）小窗**交互层**的独立 APK：
边角斜上滑 → 气泡扇逐个弧形展开 → 拖拽瞄准 → 松手启动（本期为**全屏启动**，小窗留 `ILaunchStrategy` 接口）
→ 尾部"更多"页 → 固定管理（拼音 A–Z 分组 + ★区≤6 + 字母索引条）。

所有动效/尺寸/文案/表格**逐行取自反编译产物**（`E:\workspace\flyme\out\SystemUITools_src`），
关键处均有 `原文件:行号` 注释。设计与计划见 `docs/`。

## 快速开始

```powershell
.\gradlew.bat :app:assembleDebug          # 产物 app\build\outputs\apk\debug\app-debug.apk
E:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

1. 打开"气泡抽屉" → 授予悬浮窗权限 → 勾选"启用角落手势"
2. 从屏幕**底部左/右角落斜向上滑**（正上滑留给系统返回）
3. 手指在扇内移动瞄准（白波纹环），松手启动；点"更多"进应用网格；管理页固定常用
4. 手感不对先调"触发区"三滑杆；被系统手势抢走属预期（内缩带再调大）

验证清单：`docs/test-plan-device.md`（A1–A7）。

## 与原版的差异（有意为之）

| 原版 | 本复刻 | 原因 |
|---|---|---|
| `ISystemGestureListener` 框架回调 + sharedUserId systemui | SystemUI 进程内 **gesture monitor**（`InputMonitor` + `InputEventReceiver`）观察整屏，越过阈值后 `pilferPointers()` 独占该笔手势（监视器建不起来时退回角落 SPY 小窗） | 目标 ROM 无 Flyme 框架；普通应用不可得 |
| `start_windowmode`/`virtual_mode` Bundle 开小窗 | `startActivity` 全屏（`FullscreenLaunchStrategy`） | 本期不做小窗；换 freeform 只改这一个类 |
| `Settings.Global "long_press_app"` | 应用私有 SharedPreferences（同名 key，PinBackend 接口预留 Global 后端） | `WRITE_SECURE_SETTINGS` 普通应用拿不到 |
| MzX（AloneTabContainer/MzRecyclerView…）+ libpag | androidx/material 等价物；.pag 演示动画不搬 | 厂商私有依赖 |
| `C4411f` 触感/`AicyPicker` 推荐 | 公开 haptic；本地启动计数 Top8 作"推荐"组 | 私有服务不可移植 |
| 贴边独占角落 | 内缩 32dp 默认；"贴边模式"经 root 关系统手势 inset | 与系统返回手势共存 |
| `persistent=true` 常驻（systemui 共享进程） | 前台服务 specialUse + `BootReceiver`（持久化"用户意图"开关，重启自动恢复，用户关掉则不拉起） | 普通应用可达的最接近行为 |

## 为什么以前"只在桌面生效"（已修）

桌面能用、应用内不能用，原因是**输入通道**而不是手势算法：旧实现自建了一个
`type 2024 + setTrustedOverlay() + INPUT_FEATURE_SPY` 的角窗，用 `View.onTouchEvent`
读手势。桌面（MiuiHome 前台）下这条流能活；**应用前台时它在 ACTION_DOWN 之后
6–22 ms 就被输入分发器踢出触摸状态**：

```
SPY_RIGHT_DOWN side=RIGHT x=199.0 y=237.0 wh=299/299 inBox=true eligible=true
SPY_RIGHT_SYSTEM_CANCEL tracking=true claimed=false                 (+9 ms)
```

远早于斜向判定阈值（`内滑 ≥ max(1.75·slop, 14dp)`、`上滑 ≥ max(0.5·slop, 4dp)`），
所以扇子永远来不及认领。

期间试过的两条 hook 方向经真机日志证明都是空转（本分支已删除）：
`EdgeBackGestureHandler` 在本 ROM 上已无手势处理代码；`dumpsys input` 里**根本没有
`[Gesture Monitor] edge-swipe` 窗口**，一次 `InputMonitor.pilferPointers()` 都没被调用
（模块日志零条 `PILFER_ANY`）。本 ROM 的边返回主人是 MiuiHome 可触摸的 `GestureStubView`。

修法是换用系统自己的观察通道：本机 `services.jar` 里
`InputManagerService.monitorGestureInput` 会**无条件**建 `GestureMonitorSpyWindow`，
其 `InputWindowHandle.inputConfig = SPY | DO_NOT_PILFER`（`GestureMonitorSpyWindow.java:35`）。
即"别人抢走指针也不会把我踢出触摸状态"——正是"先观察、后认领"需要的语义
（`dumpsys input` 里 SystemUI 自己的 `[Gesture Monitor] MultiTaskSwitch` 就带
`DO_NOT_PILFER`，而我们旧的裸 SPY 窗口没有）。所以改为：

- `InputManagerGlobal.monitorGestureInput("BubbleDrawer-corner", 0)` 拿监视器，
  `InputEventReceiver` 在主线程收全屏事件，DOWN 用**屏幕坐标**落在角框内才布防；
- 越阈值 → `InputMonitor.pilferPointers()` 独占该笔手势 → 后续事件原样喂扇子
  （与 app 自己的手势语义一致）；
- `@hide` 类用 `:hiddenapi` **compileOnly** 桩模块编译，绝不打进 APK
  （`dexdump` 验证：包内无 `android/view/InputEventReceiver` 定义）；
- 监视器建不起来（无 `MONITOR_INPUT` 权限 / 换 ROM）时自动退回原 SPY 小窗，
  桌面可用性不受影响（日志 `PREFS_APPLIED transport=` 可区分当前通道）。

### 真机日志定位到的第二层原因：底部手势带的"抢流"

换到监视器后仍然只有**快滑**能触发。真机日志（2026-10-08）把全部失败与成功按
"起点离屏幕底边高度"排序后分得很干净：

| 结局 | 起点离底边 |
|---|---|
| 全部 `MON_SYSTEM_CANCEL`（DOWN 后 6–22ms） | ≤ 70px（21dp） |
| 存活并成功认领 | ≥ 77px（24dp）**或**抢在抢流之前完成 pilfer |

### 抢流者是谁：`com.miui.home`（系统桌面）

`dumpsys input` 里那条全屏 spy 监视器 `[Gesture Monitor] swipe-up` 的
`ownerPid=32577 ownerUid=10149` 就是 **com.miui.home**。它已用 Rust 重写
（APK 内**没有 dex**，只有 `libapp_launcher.so` / `libapp.so` 等），但 Rust 的
符号与日志串把逻辑说得很清楚：

- `app_launcher::recents::gesture::gesture_input_monitor` + `GestureInputMonitorImpl`
  —— 它自己有一个手势输入监视器；
- **底部"录音区"就是它认领的主场**：
  `record_area_height_px`、`formula=down_y-current_y>record_area_height_px`、
  `init_record_area_height_cache` → `getRecordAreaHeight`
  （`com.miui.voiceassist.contentprovider.GlobalContentProvider`）、
  `gesture_up: record_area_height_px UNKNOWN, default in_record_area=true`
  —— 实测的 ≤21dp 边界正是这个区域高度；
- 拿手手段两套都在：`input_InputMonitor_pilferPointers`（对应
  ` Home pilfer_pointers`、` Home gesture recognized, delay pilfer`）与
  `input_MiuiInputManager_request_redirect`（`redirect policy disallowed redirection to '…'`、
  `Redirect motion event on view(…)`）——后者就是 MiuiBackGestureHook 笔记里写到的
  "DOWN-time RedirectionHelper.requestRedirect 仲裁"；
- 它自己也承认会吃掉手势：`on_pilfered_at_down: passthrough_eligible = true`、
  `scheduled passthrough after 300ms x=`、`passthrough timeout fired, injecting tap x=`
  ——**别人在 DOWN 抢流之后，它会在 300ms 后补注入一个 tap 做透传**。

所以这不是"某个 bug 进程"，而是 MIUI 全屏手势的正常仲裁：底部区域归桌面。
跨进程、且发生在 system_server/桌面的原生代码里，SystemUI 侧 hook 不到
（模块日志零 `TOKEN_PILFER`、旧 `InputMonitor.pilferPointers` hook 也从未命中，
正是因为调用方在 com.miui.home 里）。

**运行时确认（一次即可）**：

模块现在把 **`com.miui.home` 也放进了 LSPosed 作用域**（`META-INF/xposed/scope.list`），
并在**桌面进程内**装了一个**只读探针**（`xposed/LauncherInputObserver.kt`）：它在桌面进程里
hook 框架输入 API（`InputMonitor.pilferPointers` / `InputManagerGlobal.pilferPointers(IBinder)` /
`cancelCurrentTouch` / `InputManager.injectInputEvent` / `monitorGestureInput` /
MIUI 的 `updateInputMonitor*`），**只记日志、随后 `proceed()`，绝不改行为**（在桌面进程里
写错会造成桌面崩溃循环，比抽屉不优雅严重得多）。

这一步是关键分叉点：桌面用 JNI 调**框架 Java 方法**时，代码跑在**桌面进程内**，
于是能用纯 Java hook 拦（可做优雅方案）；如果这些日志**一条都不出现**，说明桌面走的是
**原生 AIDL/binder** 直连，那就必须像 MiuiHome 手势 hook 项目那样做原生内联 hook
（该项目的 `miui-home-hyos-native/` 里有完整体系：`miui_home_native_hook.cpp`(242KB)、
`input_monitor_pilfer_hook.S`、Dart/运行时解析器、`native_init.list` 入口与
`safe_lsposed_native_deploy.py` 部署脚本——是一个成熟但体量很大的系统）。

日志判据：

| 日志 | 含义 |
|---|---|
| `LAUNCHER_MONITOR_PILFER` / `LAUNCHER_TOKEN_PILFER` | 桌面用 Java pilfer 抢流 → **可纯 Java 拦** |
| `LAUNCHER_CANCEL_CURRENT_TOUCH` | 桌面直接取消整笔触摸 |
| `LAUNCHER_INJECT_INPUT_EVENT action=… x=… y=…` | 桌面自己的 tap 透传补发（判定"双击"问题） |
| `LAUNCHER_MONITOR_CREATE name=swipe-up` | 确认那条 spy 监视器就是它建的 |
| 一条都没有 | 走原生路径 → 需要原生内联 hook 体系 |

也可以直接用 logcat / ftrace 从系统侧确认：

```powershell
adb logcat -v time | Select-String -Pattern 'GestureInputMonitor|pilfer|redirecting|passthrough|GestureStub'
# 或抓 binder 事务（input 服务）：63=pilferPointers 57=cancelCurrentTouch
adb shell su -c "echo 1 > /sys/kernel/tracing/events/binder/binder_transaction/enable; echo 1 > /sys/kernel/tracing/tracing_on"
#   …做一次右下角斜滑…
adb shell su -c "echo 0 > /sys/kernel/tracing/tracing_on; cat /sys/kernel/tracing/trace"
```

即**系统为"底边上滑 = 回桌面"预留的那条带子里，有 SystemUI 之外的进程在 DOWN 后
~8ms 把触摸接管走**；它只有在"别人还持有该手势"时才能得手——我们一旦先 pilfer，
整段日志里再没出现过随后的 CANCEL，这正是快滑能过的原因。所以现在：

- **带内（底边上方 28dp）**：DOWN 当场 `pilferPointers()` 拿下，不再和它抢；
- **带外**：维持"先观察、过阈值才认领"的透传语义，角落轻点/应用内拖动不受影响；

#### 现在默认走"夺回"而不是"预占"（无需猜测区域）

真机日志显示：能被抢走的笔都在底边带内，而**带外**的笔照常认领成功；每次失败的形态都是
"我们正在观察的那笔被 CANCEL"。于是改成**被动夺回**：平时完全不预占（DOWN 归应用，
轻点/拖动不受影响），**只有当我们在追踪的那笔手势被外力 CANCEL 掉时**（且距 DOWN ≤120ms，
即抢流特征）才 `pilferPointers()` 把笔夺回来，并用**原来的 DOWN 起点**继续跑同一个状态机——
开扇、上滑回桌面、轻点补发的逻辑一字未改，但不再需要"28dp 带子"这个猜测值，也不再
在 DOWN 时刻吃掉任何东西。

- 夺回成功后若 200ms 内没有后续事件，判定本机"夺不回" → 自动退回旧的"带内预占"模式
  （日志 `MON_SHADOW_FAILED_FALLBACK_TO_OWNED_BAND`），**不会退化成打不开**；
- 确认可用则记 `MON_SHADOW_CONFIRMED`，此后一直走优雅路径；
- 每笔只尝试一次夺回，避免和桌面来回抢（`MON_SHADOW_REPILFER ok=…`）。
- 带内被我们拿下、最后不是扇子而是**向上滑**时，按桌面自己的公式
  （`down_y - current_y > record_area_height` ≈ 本带高度）**在手指还没抬起时就提交**
  `KEYCODE_HOME`（不再等松手），日志 `MON_UPWARD_REPLAY_HOME`；
  （若你想改成补发 BACK 而不是 HOME，改一处常量即可。）
- 带内被拿下、最后判定为**轻点**（位移 ≤ 1.5×slop、时长 ≤ 250ms）时补注入一个 tap
  还给应用：**先等 700ms**（桌面自己的透传是 `scheduled passthrough after 300ms`，留足抖动余量），
  并在自己的监视器流里检查**从 DOWN 起**是否出现过任何外部注入事件（注入事件 `deviceId = -1`），
  **只有它没补才由我们补**（`MON_TAP_PASSTHROUGH_DELEGATED` / `MON_TAP_PASSTHROUGH_INJECTED`）。
  这样即使桌面也补发了 tap，也只会触发一次——即"点击可能触发两次"的修法。


## 结构

- `bubble/GestureAppLauncher.kt` — 948 行原版的逐通道移植（双 AnimatorSet、极坐标、扇区瞄准、fling/scroll 取消语义）
- `bubble/MotionSpec.kt` — 全部时长/曲线常量（含 -7.6 回弹控制点），出处行号注释
- `bubble/SlideGestureItemView.kt` — 瞄准波纹环（130ms、#40ffffff、描边公式照抄）
- `gesture/` — 角落状态机（纯 JVM 可测）；`overlay/OverlayHost.kt` — 双窗装配
- `xposed/CornerInputMonitor.kt` — SystemUI 进程内的角落捕获（gesture monitor 主通道 + SPY 小窗兜底）、
  `xposed/FanHost.kt` — 扇子双窗与收起路径、`xposed/BackGestureGuard.kt` — 认领后屏蔽原生 BACK 提交
- `hiddenapi/` — `@hide` 平台类的 **compileOnly** 桩（`InputMonitor`/`InputEventReceiver`/`InputChannel`/
  `InputManagerGlobal`），只参与编译，不进 APK
- `pinyin/` — 407 项边界表（`work_tables/gen_tables.ps1` 自动转录再生成）+ 排序键/比较器
- `more/`、`pin/` — 更多页（MoreAppWindow 移植）、固定管理（SlideLaunchAppSettings 应用页签切片 + FastScrollLetter 行为复刻）
- `root/EdgeModeHelper.kt` — 贴边模式 su 命令；`settings/MainActivity.kt` — 权限引导/调参/预览

测试为整体轮跑：`.\gradlew.bat :app:testDebugUnitTest`（拼音/分组/状态机冒烟，非逐任务 TDD——按约定精简）。

## 已知限制（v0.1）

- **角落带内的轻点现在透传**：gesture monitor 只观察，认领前的 DOWN 仍属于下层应用
  （旧 SPY 小窗通道才会吃掉它，属兜底通道的固有限制：软键盘首键恰在带内时调小"触发区"滑杆、
  改"仅左/右角"，或打字前从通知暂停服务）。
- 气泡条悬浮在游戏/通话等全屏界面之上仍可见（二期：前台包名黑名单）。
- **边缘指示条未复刻**：原版 `window_slide_indicator.xml`（20×64dp pill）在 SystemUITools 代码中零引用（id 常量
  `2131558896` 全树 grep 无命中）——它由 SystemUI（手势区主人）绘制，非本 APK 行为；复刻不拥有系统手势区，
  画常驻 pill 反而偏离原版。素材归档在 `work_assets/`（注意：那是 APK 内**已编译**的 .9.png，直接放回 res/
  会被 AAPT2 二次编译报错，见 `work_assets/README.md`）。
- "推荐"组为本地启动计数 Top8（原版 SmartRecommend 服务不可移植）。
