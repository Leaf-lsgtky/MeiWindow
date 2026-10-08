# 输入 Hook 架构（给后续 agent 的说明）

本文说明 `bubbledrawer` 是怎么在 Android 17 / HyperOS 上把"底部角落内滑 → 应用面板"接进系统输入
pipeline 的，以及每一处为什么这么做。**改代码前请先读完"与桌面的仲裁"和"热重载"两节**——这两处
各踩过一次真机坑（面板和回桌面同时触发、热重载后手势失效），结论都写在这里了。

## 0. 一句话模型

> 在 **SystemUI 进程**里建一条 `[Gesture Monitor]` 转发监视器当"眼睛"；底部角落的 DOWN 由我们
> **当场 pilfer 拿下**（或由 `system_server` 侧让桌面看不见它）；之后用一个纯 Kotlin 状态机判断
> 这是"面板手势"还是"轻点"，轻点再交还给应用。**系统原生的左右贴边返回、底边上滑回桌面都不改**。

## 1. 进程与作用域

| 进程 | 作用域 | 干什么 | 代码 |
|---|---|---|---|
| `com.android.systemui` | 必须 | 手势监视器、扇子窗口、BACK 提交抑制 | `SystemUiHookInstaller`、`CornerInputMonitor`、`FanHost`、`BackGestureGuard` |
| `system`（system_server） | 可选 | 只读/实验性的"桌面监视器区域"仲裁（**当前禁用**，见 §3.3） | `LauncherMonitorRegion` |
| `com.miui.home` | 无害但**无效** | 只读探针 `LauncherInputObserver`；A17 上根本不会加载 | 同左 |

`com.miui.home` 为什么进不了：Android 17 的小米桌面由小米自己的
`/system_ext/bin/hyos_spawner`（USAP 式进程池）fork，不走 ART zygote，所以 LSPosed 的 Java 注入
永远进不去。这一点 MiuiBackGestureHook 项目的报告里写得很清楚
（`ANDROID_17_MIUI_HOME_NATIVE_LOADER_REPORT.md:25-42`："这正是传统 LSPosed Java 注入未进入该进程的
主要原因"；:387"静态 scope 中加入 `com.miui.home` 也不能自动使传统 Xposed 注入适配 `hyos_spawner`"）。
本模块实测一致：加了作用域、重启桌面进程后，日志里 **0 条** `LAUNCHER_*`。

## 2. 输入通道

### 2.1 主通道：gesture monitor（spy 监视器）

`InputManagerGlobal.monitorGestureInput(name, 0)`（三条反射兜底路径见 `createMonitor()`），拿到
`InputMonitor` + `InputChannel`，用 `InputEventReceiver` 在主线程读事件。它是 **spy**：
事件同时发给应用和所有监视器，**不消耗触摸**；只有我们显式 `pilferPointers()` 才把整笔手势接管过来。

**监视器名字是有功能的，别乱改**（`MONITOR_NAME = "BubbleDrawer-corner-MultiTaskSwitch"`）：
本机 `services.jar` 反编译（`com.android.server.input.GestureMonitorSpyWindow:30-33`）：

```java
this.mWindowHandle.inputConfig = 16388;                        // SPY | TRUSTED_OVERLAY
if (name != null && (name.endsWith("MultiTaskSwitch") || name.endsWith("pip-resize"))) {
    this.mWindowHandle.inputConfig |= DisplayDeviceInfo.FLAG_ALLOWS_CONTENT_MODE_SWITCH;
}
```

`FLAG_ALLOWS_CONTENT_MODE_SWITCH = 1048576`，这一位就是 `dumpsys input` 打印的 **`DO_NOT_PILFER`**。
实测：名字匹配的监视器在别人抢流后**仍留在触摸状态里**；不匹配的（我们旧名字、以及桌面自己的
`swipe-up`）会被**整个踢出触摸状态**。所以后缀必须保留。

### 2.2 兜底：SPY 小窗

监视器建不出来时（权限/ROM 差异），退化为两个 `TYPE_ACCESSIBILITY_OVERLAY` 的 SPY 窗口
（`inputFeatures |= SPY`，`setTrustedOverlay`）。它同样只是观察者，命中后才 pilfer。
日志会打 `SYSTEMUI_CORNER_READY transport=spy-view-fallback`。

## 3. 与桌面的仲裁（**必读**）

### 3.1 谁在抢

底部区域的手势归 `com.miui.home`：`dumpsys input` 里 `[Gesture Monitor] swipe-up`（ownerUid 10149）
在 DOWN 后约 9 ms 就 `pilferPointers()` 拿下整笔，然后才按手指走向判定。它自己的 Rust 串写着
`" Home gesture recognized, delay pilfer"`、`formula=down_y-current_y>record_area_height_px`。

### 3.2 我们怎么办：**在它判定之前拿走整笔**

`CornerInputMonitor.beginMonitorStroke()` 里，DOWN 落在"冲突带"（底边上方 28dp）时当场
`pilferPointers()`：桌面那边有协作逻辑——`on_pilfered_at_down: passthrough_eligible = true`、
`"skip DOWN pilfer"`，**别人在 DOWN 抢走了，它就不抢了**。这是唯一确定的做法。

代价与补偿（都已有实现，别退回去）：

- **轻点**会被我们吃掉 → 桌面自己会在 ~300 ms 后补注入一个 tap（`scheduled passthrough after
  300ms x=`、`passthrough timeout fired, injecting tap x=`）。我们**等 700 ms 并检查自己的监视器流里
  从 DOWN 起有没有出现过外部注入事件**（`deviceId < 0`），它补了我们就记
  `MON_TAP_PASSTHROUGH_DELEGATED` 并放弃，只有它没补才注入（`_INJECTED`）。这样点击**只会触发一次**。
- **上滑回桌面**：角落扇区内已改为面板手势（见 §4），系统 HOME 从底边其它位置照常可用。

### 3.3 试过但被真机否掉的方案（**别重复踩**）

- **事后夺回**（先观察、被 CANCEL 再 pilfer）：不行。桌面可能已经提交了 home 动画，面板和回桌面会
  **同时**发生（用户实测）。
- **按"向内为主"提前认领**（4dp 径向）：注入触摸能过、真手指过不了（真手指起手就有向上的速度分量）。
- **在 `system_server` 里改桌面监视器的 `touchableRegion`**（`LauncherMonitorRegion`，思路是"让桌面
  在角落根本收不到 DOWN"）：**已验证会打断桌面的底边上滑**——即使区域只是"全屏挖掉两个小角"，
  打上之后从屏幕正中间上滑也不再回桌面（`dumpsys input` 里该监视器的 inputConfig 仍打印
  `SPY`，说明 MIUI 的 dispatcher 并不按 AOSP 那样接受显式 `touchableRegion`，很可能只认 surface crop）。
  因此 `LauncherMonitorRegion.ENABLED = false`，代码保留为"此路不通"的记录。
  另外 LSPosed 的 remote preferences 在 hooked 进程里是**只读**的（`edit()` 抛
  `UnsupportedOperationException: Read only implementation`），system_server 也无法用它回报状态。

### 3.4 参考项目（MiuiBackGestureHook）的方法为什么没照搬

它的协同协议是"**launcher 侧发 accepted-DOWN token，SystemUI 只在 token 精确匹配后才 pilfer**"
（`AGENTS.md:269-273`），配套在 launcher 里把自家手势处理器**在 accepted-input 边界**掐掉
（`AGENTS.md:256-262`）。但：

1. 它的仲裁点是 `GestureStubViewWindow::handle_back_gesture`，**只覆盖左右贴边返回**——它自己的报告
   写明"bottom 不经过它"（`ANDROID_17_MIUI_HOME_NATIVE_LOADER_REPORT.md:1442-1446`），**回桌面那条
   手势根本不在它的协议里**；
2. A17 的 launcher 侧那一半必须是**原生 payload**（`libmiui_home_hyos_lsp.so` +
   `META-INF/xposed/native_init.list` + 注入 `hyos_spawner`），需要**定制版 LSPosed**
   （`miui-home-hyos-native/README.md:14-15`），本项目用的是公共 LSPosed，做不到；
3. 它自己的风险清单：全局 dlopen/dlsym inline hook 会把桌面搞崩（报告:462-464）、不能给 launcher
   加 receiver（会 `signal 11`，:615-641）、不能覆盖正在映射的 `.so`（:1047-1051）、会 ANR
   （:1174-1181）等。

结论：**在 Java 层能做的协同我们已经做到**（DO_NOT_PILFER + DOWN 抢流）；再往前只能整包移植原生
payload + 定制 LSPosed，收益（角落少一次 pilfer）远小于风险。

## 4. 角落手势语义

- **触发区**是一个**1/4 椭圆**（`gesture/CornerZone.kt` 的 `CornerSector`，纯 Kotlin、有单测）：
  「沿底边范围」与「沿侧边范围」两个轴**独立配置**（设置页两个滑杆，默认都等于旧的半径值）；
  两轴相等就是参考实现的 1/4 圆。**屏幕正中间永远不在区内**，所以 HOME 不受影响。
- **方向无关**：DOWN 落在区内后，任何方向都算面板手势，**包括正上方**（用户明确要求"完整扇形"）。
  因此 `CornerGestureEngine` 的认领条件是**径向距离**（`hypot(inward, upward) ≥ max(1.75·slop, 14dp)`），
  不再有"向上为主就交给系统"的规则，也**删掉了 HOME 回放**（`replayUpwardGesture` 已移除）。
  只有"退回边缘"（`inward < -reverseTolerance`）会取消。
- **第二根手指**：直接取消（`ACTION_POINTER_DOWN`）。

## 5. 热重载（**不要再重启手机/杀 SystemUI**）

`module.prop` 里有 `autoHotReload=true`，`ModuleMain` 实现了：

- `onHotReloading`：把旧世界的活对象拆掉——`SystemUiHookInstaller.dispose()` →
  `CornerInputMonitor.dispose()`（监视器 + input channel、兜底 SPY 窗、扇子窗口、主线程回调），
  并把下一代理不了的东西通过 `param.setSavedInstanceState(arrayOf(processName, context, classLoader))`
  交出去；返回 `true`。
- `onHotReloaded`：先 `handle.unhook()` 掉所有 `bubbledrawer.*` 旧 hook，再拿交接来的 Context
  **直接重建运行时**——**不能再去 hook `Application.onCreate`**（本进程里它已经跑过了，永远不会再触发）。
  拿不到 Context 时兜底用 `ActivityThread.getSystemContext()`（建监视器足够）。

日志判据（一次 `adb install -r` 后应当出现，无需任何重启）：

```
HOT_RELOAD_ACCEPTED process=com.android.systemui
MON_DISPOSED_FOR_HOT_RELOAD
SYSTEMUI_CORNER_DISPOSED_FOR_HOT_RELOAD
HOT_RELOADED process=com.android.systemui unhooked=N
SYSTEMUI_CORNER_REINSTALLED_AFTER_HOT_RELOAD
MON_INPUT_READY name=BubbleDrawer-corner-MultiTaskSwitch …
```

`system_server` 那一侧要等它下一次重启才会换成新代码（它没有热重载入口），但那里的 arbiter 是禁用
状态，所以无关紧要。

## 6. 调试手册

日志 tag 全在 LSPosed 模块日志里（`/data/adb/lspd/log/modules_*.log`），关键字：

| 前缀 | 含义 |
|---|---|
| `MON_DOWN` / `MON_DOWN_REJECTED` | DOWN 是否落在触发区（含 `box=`、`band=`、`mode=pre-own\|observe`、`arbiter=`） |
| `MON_ACTIVATE` | 已认领并展开扇子 |
| `MON_PILFER_OK` / `MON_PILFER_FAILED` | pilfer 结果 |
| `MON_SWALLOWED` | 被我们吃掉但既不是扇子也不是轻点（真拖动） |
| `MON_TAP_PASSTHROUGH_DELEGATED` / `_INJECTED` | 轻点交还：桌面补了 / 我们补了 |
| `MON_SYSTEM_CANCEL` / `MON_SHADOW_*` | 被外力取消 / 事后夺回（少见） |
| `ARBITER_*` | system_server 侧（当前禁用） |
| `HOT_RELOAD*` / `MON_DISPOSED_FOR_HOT_RELOAD` | 热重载生命周期 |

常用命令（真机）：

```powershell
# 触发区/监视器是否健在
adb shell dumpsys input | Select-String -Pattern 'BubbleDrawer|swipe-up, id=|MultiTaskSwitch, id='
# 谁持有当前触摸（按住不放时看 TouchStatesByDisplay）
adb shell "dumpsys input | sed -n '/TouchStatesByDisplay/,/CursorStatesByDisplay/p'"
# 真实触摸注入（绕过 input tap 的 deviceId=-1 语义）：/data/local/tmp/sg2.sh x0 y0 x1 y1 步数 步长ms
adb shell su -c "sh /data/local/tmp/sg2.sh 1150 2620 1020 2440 12 60"
```

注入脚本的坐标是**物理像素**（脚本内部 ×100 转 raw）。注意：注入事件的时序不像真手指，
"注入能过/不能过"不能当成结论——**必须用真手指复验**（本项目就在这里翻过车）。

## 7. 设置项（RemotePrefs，实时生效）

| key | 含义 |
|---|---|
| `enabled` / `corner_left_enabled` / `corner_right_enabled` | 总开关、左右角 |
| `corner_trigger_range_dp` | 默认半径（两个轴都未单独设置时使用） |
| `corner_trigger_bottom_dp` / `corner_trigger_edge_dp` | 1/4 椭圆的两个轴 |
| `freeform` | 小窗启动 |

改动通过 `SettingsStore` 写本地镜像 + 远端组，`CornerInputMonitor` 注册了
`OnSharedPreferenceChangeListener`，**改完立即生效**（`PREFS_APPLIED` 日志）。

## 8. 已知限制

- 底部 28dp 冲突带内的**真拖动**（既不是扇子也不是轻点）会被吃掉，日志 `MON_SWALLOWED`。
- 角落扇区内不再触发系统 HOME（这是刻意的：该区域归面板）。
- `LauncherInputObserver`（桌面进程探针）在 A17 上不会加载，保留仅为记录。
