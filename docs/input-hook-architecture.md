# 输入 Hook 架构（给后续 agent 的说明）

本文说明 `bubbledrawer` 是怎么在 Android 17 / HyperOS 上把"底部角落内滑 → 应用面板"接进系统输入
pipeline 的，以及每一处为什么这么做。**改代码前请先读完"与桌面的仲裁"和"热重载"两节**——这两处
各踩过一次真机坑（面板和回桌面同时触发、热重载后手势失效），结论都写在这里了。

## 0. 一句话模型

> 在 **SystemUI 进程**里建一条 `[Gesture Monitor]` 观察触摸；`system_server` 裁掉桌面 `swipe-up`
> 输入表面的角落列。只有当这次 DOWN 的坐标、方向、启动次数和有效期都与裁剪确认吻合时才纯观察，
> 达到面板阈值后再 pilfer；缺少确认则在 DOWN 预抢。中间底边仍可回桌面，独立侧边返回窗口不变。

## 1. 进程与作用域

| 进程 | 作用域 | 干什么 | 代码 |
|---|---|---|---|
| `com.android.systemui` | 必须 | 手势监视器、扇子窗口、BACK 提交抑制 | `SystemUiHookInstaller`、`CornerInputMonitor`、`FanHost`、`BackGestureGuard` |
| `system`（system_server） | 裁剪仲裁必需 | 裁剪桌面 `swipe-up` 的 SurfaceControl，发布有几何和有效期的确认 | `LauncherMonitorRegion` |
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
`InputMonitor` + `InputChannel`，用 `InputEventReceiver` 在专用输入线程读事件。它是 **spy**：
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

### 3.2 主路径：在输入分发前裁剪；确认缺失时才预抢

`LauncherMonitorRegion` 保留 `replaceTouchableRegionWithCrop = true`，把 `swipe-up` 表面的 crop
设为 `[left, 0, right, height]`。角落列之外的中间通道继续接收 HOME；角落 DOWN 不进入该监视器。

2026-11-15 复查发现，旧实现虽然有框架 Hook，仍有这些竞态入口：

1. `GestureMonitorSpyWindow` 构造器在自己的事务里 `setCrop(null)`、`show()`、`apply()`，先发布全屏
   监视器。旧 Hook 等 `monitorGestureInput` 返回后才 post 裁剪，期间 DOWN 已可能进入输入队列。
   现在只在目标构造器的线程内，替换该表面的初始 crop；构造器及其调用方做 deopt，避免内联绕过。
   创建方法返回前还同步重施裁剪，覆盖存活的监视器。
2. 原 `bubbledrawer_arbiter_crop_active=1` 会持久化，不能证明当前启动、当前方向和坐标已被保护。
   新 `bubbledrawer_arbiter_crop_state` 记录启动次数、确认 uptime、宽高、rotation、左右边界。
   SystemUI 检查实际 DOWN 点是否被排除；过期、重启、转屏或尚未覆盖新触发宽度均走预抢兜底。
3. 原裁剪单侧最多 `width / 3`，触发区却可设置 160dp。例如宽 1080px、density=3、范围 160dp，
   触发区宽 480px 而裁剪只覆盖 360px，剩下 120px 仍交给桌面。现在按完整触发宽度裁剪，边界留
   1px，并保留非空的中间通道；极端尺寸无法覆盖的点不会被确认成 `cropped`。

确认只在成功提交裁剪后发布。独立工作线程亮屏每 2 秒重施并续期（有效期 6 秒），熄屏停止续期，
低频检查亮屏恢复；输入线程不执行 Settings IPC。监视器移除、配置变化、转屏会重新检查；dispose
取消旧任务并恢复默认 crop，避免热重载后旧回调再把区域改回去。

当前连接设备修前也有正常的 `mode=cropped → MON_ACTIVATE`，说明不是每笔都失败。日志中另有
起点在配置扇区之外的 `MON_DOWN_REJECTED`，这类正常交给系统的上滑要与裁剪竞态区分。

#### 3.2.1 兜底预抢的时序

`CornerInputMonitor.beginMonitorStroke()` 里，DOWN 落在"冲突带"时当场 `pilferPointers()`：桌面那边有
协作逻辑——`on_pilfered_at_down: passthrough_eligible = true`、`"skip DOWN pilfer"`，**别人在 DOWN
抢走了，它就不抢了**。这条兜底仍是跨进程抢流，不能与已生效的输入表面裁剪等同。

以下为 2026-10-11 的历史预抢取证：

真机 logcat 里能量出这场竞赛的全部过程（同一笔手势）：

```
21:29:47.974  MIUIInput: [MotionEvent] publisher action=0x0 … channel 'a0bf123 com.miui.home/…Launcher'
21:29:47.974  MIUIInput: [MotionEvent] publisher action=0x0 … channel '[Gesture Monitor] BubbleDrawer-corner-…'
21:29:47.976  InputManagerService: pilferPointers            ← 我们（DOWN 后 ~2ms）
21:29:47.977  MIUIInput: [MotionEvent] publisher action=0x3 … channel 'a0bf123 com.miui.home/…'  ← CANCEL
```

**2ms 就是全部余量**：桌面在同一毫秒就会 `MiuiInputManagerService: app requestRedirect` 接管，
而它的 HOME 是"看最初几个向上采样"决定的（§3.3 第一条），事后夺不回来。所以 DOWN 这条路线上
**任何会阻塞的东西都在赌这一笔**，历史上踩过的三个坑（都已修，别退回去）：

1. **在 DOWN 回调里建窗口**：键盘高度探针（IME inset 探针窗口）原本是第一个角落 DOWN 时现场
   `WindowManager.addView` —— 窗口添加是两次 binder 往返 + relayout + insets 分发，于是**每次
   SystemUI 启动后的第一笔角落手势必然输掉**，表现就是"重启系统界面后第一下会回桌面"。现在探针在
   手势通道启动时（`startMonitorTransport`，仍是主线程、仍与手指无关）就建好，功能关掉时随通道一起
   移除；insets 变化由监听器写进 `imeHeightCache`，DOWN 只读一个字段。
2. **在主线程处理输入**：DOWN 回调原本跑在 SystemUI 的主 looper 上，正好排在启动、扇面/联系人条/
   面板的窗口工作后面。现在收在 `bubbledrawer-corner-input` 专用 `HandlerThread`
   （`THREAD_PRIORITY_URGENT_DISPLAY`），只有"要动窗口"的动作才 `post` 回主线程。
3. **抢之前先做 binder 调用**：`PowerManager.isInteractive` / `KeyguardManager.isKeyguardLocked`、
   `WindowManager` 指标读取都曾在抢占之前。现在几何（`refreshGeometry`，主线程）、电源/锁屏
   （`refreshState`，独立状态线程看门狗 + 亮灭屏/锁屏事件）全是缓存字段，DOWN 路径只剩
   **读字段 + 一次 `pilferPointers()`**。启动时 `MON_WARMUP` 还会把这条路径跑 24 遍（类加载/JIT）。

看门狗是这套东西里唯一"不靠手指触发"的开销，所以它跟着屏幕走：亮屏 250ms（远小于人从锁屏摸到角落的
时间），熄屏 5s（这时根本不会来角落手势，慢速 tick 只是兜底，防止亮屏广播丢了把闸门卡在"不可交互"）。

判定区也从"底边上方 28dp"改成 `max(28dp, 触发区自身的纵向半径)`：触发区的纵向轴是用户设置
（最多 160dp），此前高于 28dp 的那部分按下只是"观察"，同样会被 launcher 抢走 HOME。

`MON_DOWN … lat=N` 就是这个余量的现场读数（触摸到抢占用时 ms，正常 ~2ms）；`MON_SYSTEM_CANCEL …
owned=true` 则是"抢下来又被夺回"的签名。

代价与补偿（都已有实现，别退回去）：

- **轻点**会被我们吃掉 → 桌面自己会在 ~300 ms 后补注入一个 tap（`scheduled passthrough after
  300ms x=`、`passthrough timeout fired, injecting tap x=`）。我们**等 700 ms 并检查自己的监视器流里
  从 DOWN 起有没有出现过外部注入事件**（`deviceId < 0`），它补了我们就记
  `MON_TAP_PASSTHROUGH_DELEGATED` 并放弃，只有它没补才注入（`_INJECTED`）。这样点击**只会触发一次**。
  键盘弹着时不抢 DOWN（否则键盘的按键会被"真实 DOWN + 补发 tap"打成两次），代价是那时角落手势
  仍有被 HOME 抢走的风险。
- **上滑回桌面**：角落扇区内已改为面板手势（见 §4），系统 HOME 从底边其它位置照常可用。

### 3.3 试过但被真机否掉的方案（**别重复踩**）

- **事后夺回**（先观察、被 CANCEL 再 pilfer）：不行。桌面可能已经提交了 home 动画，面板和回桌面会
  **同时**发生（用户实测）。
- **按"向内为主"提前认领**（4dp 径向）：注入触摸能过、真手指过不了（真手指起手就有向上的速度分量）。
- **在 `system_server` 里改桌面监视器的 `touchableRegion`**（`LauncherMonitorRegion`，思路是"让桌面
  在角落根本收不到 DOWN"）：**已验证会打断桌面的底边上滑**——即使区域只是"全屏挖掉两个小角"，
  打上之后从屏幕正中间上滑也不再回桌面（`dumpsys input` 里该监视器的 inputConfig 仍打印
  `SPY`，说明 MIUI 的 dispatcher 并不按 AOSP 那样接受显式 `touchableRegion`，很可能只认 surface crop）。
  因此不能恢复这种显式 region 方案；当前启用的是上文的 **SurfaceControl crop**，两者不同。
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

当前使用 Java 层 SurfaceControl crop + DO_NOT_PILFER + 预抢兜底，不需要移植 launcher 原生 payload。

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

`system_server` 同样在 `onHotReloaded` 重建 `LauncherMonitorRegion`。两个进程的重载先后并无保证，
所以新裁剪确认就绪前必须走预抢，不能沿用旧的持久化布尔值。

## 6. 调试手册

日志 tag 全在 LSPosed 模块日志里（`/data/adb/lspd/log/modules_*.log`），关键字：

| 前缀 | 含义 |
|---|---|
| `MON_INPUT_READY` | 手势监听建好（`thread=bubbledrawer-corner-input` = 事件不在主线程处理） |
| `MON_WARMUP` | 启动时预热 DOWN 路径（几何缓存就绪的证据） |
| `MON_DOWN` / `MON_DOWN_REJECTED` | DOWN 是否落在触发区（`box=`、`band=`、`mode=cropped\|pre-own\|observe`、`cropAge=` 确认年龄、`lat=` 触摸到处理用时 ms） |
| `MON_ACTIVATE` | 已认领并展开扇子 |
| `MON_PILFER_OK` / `MON_PILFER_FAILED` / `MON_PILFER_RETRY` | pilfer 结果（`_RETRY` = 这一笔在 DOWN 已经抢过，Activate 时又抢了一次；没有 `_RETRY` 说明 DOWN 那次没抢） |
| `MON_SWALLOWED` | 被我们吃掉但既不是扇子也不是轻点（真拖动） |
| `MON_TAP_PASSTHROUGH_DELEGATED` / `_INJECTED` | 轻点交还：桌面补了 / 我们补了 |
| `MON_SYSTEM_CANCEL` | 外力取消；**带 `owned=true` 就是"抢下的笔被系统夺回"**（扇面+回桌面同时发生的签名） |
| `MON_IME_PROBE_MISSING` | 键盘高度探针窗口没建起来（降级：键盘上的角落按下也会被抢下） |
| `ARBITER_INITIAL_CROP_HOOKED` / `ARBITER_CROP` | 初次发布 Hook 已安装 / system_server 裁剪结果 |
| `ARBITER_*_FAILED` / `ARBITER_NO_MONITOR_TARGET` | 裁剪不可确认，需查失败原因与 SystemUI 的预抢兜底 |
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

- 预抢兜底路径内的**真拖动**（既不是扇子也不是轻点）会被吃掉，日志 `MON_SWALLOWED`。
- 角落扇区内不再触发系统 HOME（这是刻意的：该区域归面板）。
- `LauncherInputObserver`（桌面进程探针）在 A17 上不会加载，保留仅为记录。
