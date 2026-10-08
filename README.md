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

### 最终方案：不预占 DOWN，而是"抢在桌面判定之前认领"

真机核对（`dumpsys input` + `/sys/kernel/tracing` + 反编译本机 `services.jar`）把机制完全钉死了：

1. **监视器名字决定它能不能被抢。** 反编译本机 `/system/framework/services.jar`
   （classes2 `com.android.server.input.GestureMonitorSpyWindow:30-33`）：

   ```java
   this.mWindowHandle.inputConfig = 16388;               // SPY | TRUSTED_OVERLAY
   if (name != null && (name.endsWith("MultiTaskSwitch") || name.endsWith("pip-resize"))) {
       this.mWindowHandle.inputConfig |= DisplayDeviceInfo.FLAG_ALLOWS_CONTENT_MODE_SWITCH;
   }
   ```

   `FLAG_ALLOWS_CONTENT_MODE_SWITCH = 1048576`，而这一位正是 `dumpsys input` 打印的
   **`DO_NOT_PILFER`**。实测：`MultiTaskSwitch`（名字匹配）带该位，别人抢流时**仍留在触摸状态里**；
   我们旧名字、以及桌面自己的 `swipe-up` 都没有这一位，被抢时**整个从触摸状态里消失**。
   所以监视器名字改成 `BubbleDrawer-corner-MultiTaskSwitch`（监视器记账按 token 不按名字，不会撞车）。

2. **抢流者与时机**（按住不放的角落手势，`dumpsys input` 的 `TouchStatesByDisplay`）：

   ```
   0: name='7bcf4df InputMethod'                        targetFlags=FOREGROUND | SPLIT  ← 应用才是主目标
   1: name='PointerEventDispatcherOverlay0'             … DO_NOT_PILFER
   2: name='[Gesture Monitor] BubbleDrawer-corner-…'    pilferingPointerIds=<none>      ← 我们只是观察者
   3: name='[Gesture Monitor] MultiTaskSwitch'          … DO_NOT_PILFER
   4: name='[Gesture Monitor] swipe-up'                 pilferingPointerIds=0…01        ← 桌面抢走了
   ```

   也就是说**不是"某个神秘进程"，而是 `com.miui.home` 的那条 spy 监视器**在 DOWN 后约 9ms
   把底部区域的触摸 pilfer 走，然后再按手指走向判定；它自己的 Rust 串就是证据：
   ` Home gesture recognized, delay pilfer`、
   `formula=down_y-current_y>record_area_height_px`。因此"等我们抢回来再让它取消"是来不及的
   （它的 home 动画已经提交），必须在它判定之前拿走。

3. 于是最终行为（**DOWN 完全透传，什么都不预占**）：

   | 手势 | 谁处理 | 日志 |
   |---|---|---|
   | 角落**轻点**（向内位移 < 4dp） | 应用自己（我们从不认领） | 只有 `MON_DOWN mode=observe` |
   | 角落**向内斜滑**（向内 ≥ 4dp，且斜率不高于 1.5:1） | **我们**：4dp 就 `pilferPointers()` 认领，之后按阈值开扇 | `MON_EARLY_CLAIM inward=… up=…` → `MON_ACTIVATE` |
   | 底边**直上滑**（向内 ≈ 0） | 桌面（我们永不认领） | 只有 `MON_DOWN`，无认领 |
   | 认领后改向上 | 按桌面公式在手指未抬起时就补 `KEYCODE_HOME` | `MON_UPWARD_REPLAY_HOME` |
   | 认领后判定为轻点 | 先等桌面自己的 300ms 透传（`deviceId = -1`），它没补才由我们补一个 | `MON_TAP_PASSTHROUGH_DELEGATED` / `_INJECTED` |

   早期"带内（底边 28dp）预占 DOWN"的做法已经删除——它会吃掉带内 DOWN、轻点要补注入、
   而且和桌面同时触发。现在只有**向内为主的滑动**会被认领，且一定发生在桌面判定之前：
   实测 `MON_EARLY_CLAIM inward=44 up=44` 之后 `topResumedActivity` 仍停在原应用（**没有**回桌面），
   直上滑则照常回桌面。

   兜底仍在：距 DOWN ≤120ms 的 CANCEL 视为抢流特征，用同一个 DOWN 起点
   `pilferPointers()` 夺回（`MON_SHADOW_REPILFER` / `MON_SHADOW_CONFIRMED`）。
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
