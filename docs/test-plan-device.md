# 真机验证清单（Android 13+，无 root 优先）

安装：
```
E:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## A0 应用列表完整性（重启后不缩水）
> 复现过的故障：重启两次后扇子"更多"面板只剩「设置/联系人」。原因：模块在**开机时**（用户还没解锁）
> 于 SystemUI 进程里枚举应用，平台此时只给出直启子集（实测 2 个 vs 解锁后 216 个），而这份结果被
> `AppRepository.cache` 缓存到 SystemUI 结束。
- [ ] 重启手机，**先不要解锁**：`adb logcat -s MeiWindow_AppRepo` 出现
      `loadAll finished … userUnlocked=false`（这一步列表小是平台行为，不算故障）
- [ ] 解锁后：同 tag 出现 `APP_LIST_RELOADED reason=android.intent.action.USER_UNLOCKED total=2xx`，
      并且扇子/更多面板立刻是**全量应用**（不是只有设置/联系人）
- [ ] 手动核对：`adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PROBE` →
      `PROBE env uid=… / queryLauncherActivitiesForUser(user=0) -> 2xx`（应与 `pm list packages` 的量级一致）
- [ ] 未开双开时 `getAllUserIds result: [0]`（不应出现 999）；开启双开后才应出现 999 与分身子集
- [ ] 面板打开后 `MORE_PANEL_TREE … items=… letters=…` 的 items/letters **非 0**（真实内容而非空态）

## A1 安装即用
- [ ] 打开"气泡抽屉"，勾选**启用角落手势** → 弹悬浮窗授权 → 允许 → 返回自动开启服务（通知栏常驻）
- [ ] 通知点击回到主页，开关仍为开

## A2 角落手势与返回手势共存
- [ ] **应用内**（浏览器/微信/设置等前台）右角斜向上滑 → 气泡扇展开（此前只在桌面生效）
- [ ] **应用内**左角斜向上滑 → 展开方向朝右；系统返回仍正常
- [ ] 正上滑（返回）不被劫持：不出现"既返回又开扇"
- [ ] 若被系统手势抢走：扇静默收起，无残留（ACTION_CANCEL 路径）
- [ ] 设置页三滑杆（内缩/宽/高）调整后重启服务，起手势区域随之变化
- [ ] 触发位=侧边中部：左右边缘中段横滑出扇

### A2 日志判据（LSPosed 模块日志，tag `BubbleDrawer`）
- [ ] 启动即 `PREFS_APPLIED … transport=gesture-monitor`（写 `spy-view` 说明监视器没建起来，
      日志里 `MON_INPUT_UNAVAILABLE_FALLBACK_SPY_VIEW` 带原因）
- [ ] 一次成功手势：`MON_DOWN side=… x=… y=… mode=pre-own lat=N` → `MON_ACTIVATE …`
- [ ] 应用内**不再**出现 `MON_DOWN` 紧跟 `MON_SYSTEM_CANCEL … owned=true`（旧症状：
      `SPY_*_DOWN` +6~22ms `SPY_*_SYSTEM_CANCEL`；`owned=true` 表示这一笔被系统抢了回去）
- [ ] `dumpsys input` 的 `Gesture Monitors (implemented as spy windows)` 里有
      `[Gesture Monitor] BubbleDrawer-corner`

## A3 展开动效（对照 Flyme 录屏逐帧）
- [ ] 展开：整扇从角落飞出、-270°旋入带过冲（-7.6 控制点回弹），130ms 主体 + 130ms 延迟的 250ms 收尾
- [ ] 收起：先 100ms 侧移+微旋，再 100ms 延迟后 5°→-270° 旋回角落并缩到 0.8
- [ ] "更多"瓦片为原版 icon_gesture_more_app 图形（白圆环+三点）

## A4 瞄准与启动
- [ ] 手指在扇内移动：命中气泡出现白色波纹环（130ms），无图标放大
- [ ] 环带外松手 → 取消收起；气泡半径带内松手 → **全屏启动**该应用
- [ ] 快速朝角落方向甩出（>2500px/s）→ 取消手势（原版 onFling 语义）

## A4b 小窗启动与任务身份（同一应用只应有一个实例 / 一个小窗）
> 设置页"小窗启动"关闭 = 全屏；开启 = 小窗。依据：ROM 自己的小窗（侧边栏、WMShell linkage、
> 标题栏"小窗"）都只带 `NEW_TASK`，从不带 `MULTIPLE_TASK`；"新建小窗"是显式动作且有 2 个上限。
- [ ] 应用**未运行** → 从扇子打开 → 出现小窗（冷启动，正常）
- [ ] 该小窗开着 → 收起后再从扇子选**同一应用** → 还是**那一个**小窗被带到前台，没有第二个
- [ ] 应用在后台（先打开再回桌面）→ 从扇子打开 → 小窗里是**离开时那个界面**，不是重新冷启动的首页
- [ ] 应用在前台全屏 → 从扇子选同一应用 → 只出现一个小窗（不是"全屏一个 + 小窗里又一个实例"）
- [ ] 连续从扇子打开同一应用 3 次后：`adb shell dumpsys activity activities` 里该应用只有一个小窗任务
      （搜 `windowingMode=5` / freeform root task）
- [ ] 小窗之间的多实例仍按系统规则工作（不同应用各一个小窗；同一应用要第二个小窗只能走系统"新建小窗"）

## A5 "更多"面板（overlay 管理页）
- [ ] 气泡条尾部"更多"→ **当前窗口上方**浮出管理页 overlay（不再全屏新页），样式与固定管理页一致
      （56dp 仿 ActionBar：标题 + 管理/完成 + ✕；4 列网格、★区、A–Z、字母条都在）
- [ ] 面板**屏幕居中**；设置页"更多面板"四个滑杆（长/宽 = 占屏幕 %、图标 dp、文字 sp）改完下次开面板生效
- [ ] 点面板外部 → 关闭；轻遮罩 `#33000000` 随面板一起收
- [ ] 管理/完成可用：编辑态点击加/减固定、★区长按拖动排序，退出面板后顺序仍在；
      日志 `PIN_DRAG_START key=… pins=N slots=N centers=N myPos=K`（`slots` 必须等于 `pins`；
      `slots=0` 就是"长按只放大、怎么拖都不动"，不齐时同一行会标 `SLOTS_MISMATCH`）、
      松手 `PIN_DRAG_END key=… index=…`
- [ ] 面板里点应用 → 面板收起，应用以**面板的位置/大小**开小窗（日志 `MORE_LAUNCH … rect=`）
- [ ] `adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_MORE` 可开关面板；
      `adb logcat -s BubbleDrawer` 有 `MORE_PANEL_SHOW [l,t][r,b] screen=… pct=… page=texts=[…]`
- [ ] 全屏管理页仍可从设置页进入，且 ActionBar 的"管理/完成"菜单在

## A6 固定管理
- [ ] 标题"选择快捷启动的应用"，tab"应用"，网格内点图标加固定（绿?角标=remove/add）
- [ ] 超过 6 个 → toast"最多固定 6 个"
- [ ] A–Z 分组：微信在 W、支付宝在 Z、QQ 在 Q、数字开头在 0 桶、符号在 #（最底）
- [ ] 右侧字母条：点字母滚动到组；滚动时当前字母同步
- [ ] ★ 区长按拖动排序；"管理/完成"切换后顺序持久化。
      **冷启动就拖**：`PIN_DRAG_START` 的 `pins` 不能是 0（2026-10-16 的回归正是首次组合时把空列表冻进了拖拽状态）

### 长按拖动排序失效（用户 2026-10-16：「编辑收藏时无法排序，长按只能放大无法移动」）

成因是 **Compose 的 `remember` 把首次组合的那一份列表冻住了**：`PinDragState` 的落点表由
`remember(model) { PinDragState(model) { visibleCells.filterIsInstance<Cell.Pin>() } }` 喂进去，而
`PinManageModel.cells` 的初值是 `emptyList()`、应用列表由 `reload()` **异步**装（`scope.launch`）——
首次组合时它必然是空的，于是每次长按拿到的 `pins` 恒为 0、`initialSlots` 也是空的，
`onDrag` 第一行就 return：外观上只剩 `draggingKey` 打开的那一下 1.12 倍放大，怎么拖都不动。

修法：`rememberUpdatedState(visibleCells)`，lambda 在**按下那一刻**读最新值（这也是它当初写成 lambda
的理由 —— `remember(model)` 只跑一次，直接捕获局部变量就会把它冻结）。`PIN_DRAG_START` 那行日志就是
为这个失败模式留的证据：`slots` 与 `pins` 不齐时它标 `SLOTS_MISMATCH`。

### 拖到第一次换位就被"放下"（用户 2026-10-16 第二轮：「app 顺序一变就自己放下来了，也没有移动让位的动画」）

两件事，都在同一次改动里：

1. **手势原来挂在每一格上**。收藏区是按位置组合的（`Column{ Row{ Box{ PinCell } } }`，没有 `key`），
   换位之后"这一格"在组合里已经被别人占了，`pointerInput(manageMode, key)` 的键（应用标识）跟着变
   → 手势协程被取消 → `onDragCancel` → 刚换完一次位就被放下。现在手势挂在**收藏区**这个从头到尾
   不动的节点上（它只驱动拖拽状态，不认具体哪一格），抓的是哪一格由长按落点判定：`grabAt()` 取
   "最近的、且落在自己那格范围内"的中心点（落点表由每一格 `onGloballyPositioned` 登记，
   `onDragStart` 先按当前收藏清掉多余的键 —— 取消收藏/折叠到 20 个之外的格子不会自己注销）。
2. **没有让位动画**（普通 `Column` 没有 `animateItem`，那只对 lazy 列表有效）。现在每一格
   `LaunchedEffect(key, slot, isDragging)` 盯着自己的槽位：一变就从旧槽位滑到新槽位
   （位移 = 按下那一刻登记的两个槽位中心之差，`SLOT_ANIM_MS` = 180ms；拖拽中的那一格不参与，
   它贴着手指）。

判据：拖一个应用横跨两格 —— 让位的格子各滑一格（看得见"谁给谁让了位"）、拖的那一格始终贴着手指、
**中途不会掉下来**、松手后顺序持久化。

### 让位动画的两个细节（用户 2026-10-16 第三轮：「在终点位置闪一下再重新放让位动画」「手指拖动的图标图层在动画下面」）

1. **在终点闪一下**：把起始位移放在 `LaunchedEffect` 里算，等于新槽位先按"位移 0"画了一帧，之后才
   snap 回旧位置开始动画。现在换位那一刻（`onDrag` 里）就把"旧槽位中心 − 新槽位中心"写进
   `pendingShifts`，格子在**组合期**用 `remember(key, slot, isDragging) { drag.shiftOf(key) }` 取走
   —— 第一帧就画在旧位置。`shiftOf` 是纯读，组合期调用没有副作用。
2. **拖拽图层在动画下面**：格子是按行嵌套的（`Row` 里的 `Box` 里才是 `PinCell`），`zIndex` 只管同一层
   的兄弟，跨行根本管不到。现在拖拽中的那一格由收藏区最上层的**零尺寸绝对定位图层**（`OverlayAt`）
   再画一遍（网格里那一格留透明占位），必然盖住所有格子与让位动画；零尺寸是为了不给收藏区加高度
   （`MeasuredHeader` 量到的高度决定上滑折叠的偏移）。位置用 lambda 传：跟手位移是快照状态，
   在测量里读它，每帧只重放这一个节点，不重组收藏区。
- [ ] 固定后回主界面 → 角落滑动气泡条即时更新

## A7 持久与保活
- [ ] 杀后台后服务自启（START_STICKY）；重启手机后开关仍工作（首次需手动启动一次，二期加 BOOT）
- [ ] 固定列表重启不丢

## A8 小窗联系人条（Flyme「联系人头像」移植）
前置：设置页「实验性功能 → 小窗联系人条」默认开启。

**数据来源与 HyperOS 的关键差异**：头像/标题取自**通知**（Flyme 原版同样如此），但 HyperOS
在应用被打开时会清掉该应用的全部通知（任意方式打开，包括小窗）。因此模块把通知当"发现渠道"：
每条聊天通知都会写入一份**会话记忆**（标题 / 头像 / 点击意图 / 最近时间），通知被清掉后仍然保留
—— 否则小窗一出现列表就空了。记忆按**会话**归档（主键 `包名|会话名`，见 A19）而不是按通知标题：
每应用 20 条、总计 60 条、12 小时未再出现则过期。

- [ ] 先让微信来消息（通知栏有微信通知）→ 再把微信以小窗打开 → 小窗**正下方**浮出「头像 + 名称」条
      （名称 = 通知标题；日志 `CONTACT_BAR_DATA pkg=com.tencent.mm items=N live=0 remembered=N`：
      `live=0` 正是"打开即清通知"的证据，条靠 `remembered` 显示）
- [ ] 通知被清时能在日志看到 `CONTACT_BAR_NOTIF_REMOVED pkg=com.tencent.mm title=…`
- [ ] **圆角与小窗一致**（ROM 的 `getCornerRadius()`；日志里 `CONTACT_BAR_GEOM` 可对照），不是胶囊形
- [ ] **实时拉伸小窗**：拖右下角改尺寸时，条宽逐帧跟随，不出现错位/滞后（日志
      `CONTACT_BAR_GEOM … visual=… bar=… animating=true`，`bar` 的 width 始终 == `visual` 的 width）；
      窗口变宽/变窄只影响条本身（头像不再按宽度增减，见下一条）
- [ ] **左右滑动**（用户 2026-10-16：「联系人条无法左右滑动」）：会话比条宽时整行可以横着拖（Flyme
      的原版就是水平 `RecyclerView`），松手带惯性；会话少时头像**居中**；横滑**不会**把头像划掉
      （日志里不出现 `CONTACT_BAR_FORGET`），竖滑仍然是移除
- [ ] 条上的头像数 = 该应用的会话数（最多 20，Flyme 的上限），不再只画撑得下的 6 个
- [ ] **尺寸阈值**：设置页滑块（默认 60%，= 小窗宽度须达到屏幕宽度的 60%）—— 把小窗拖到比阈值更窄 → 条消失（`CONTACT_BAR_HIDE reason=TOO_SMALL`）；
      拖回来 → 条回来；滑到 0 → 不再按尺寸隐藏
- [ ] **上滑/下拉头像 → 移除该联系人**（`CONTACT_BAR_FORGET pkg=… label=… title=…`）；移除最后一个后整条消失
      （`CONTACT_BAR_HIDE reason=EMPTY_AFTER_REMOVE`）；该会话来了**新消息**后应重新出现。
      斜着划（先竖后横、手势被横滑抢走 → `ACTION_CANCEL`）只复原、**不删人**
- [ ] 小窗**移动**（拖顶部把手）时条跟着走
- [ ] **两个小窗并存**：操作另一个小窗 → 条消失（`CONTACT_BAR_HIDE reason=UNFOCUSED` /
      `CONTACT_BAR_LOST_FOCUS` 或 `CONTACT_BAR_FOCUS_SWITCH`）；**点回微信小窗 → 条回来**
      （`CONTACT_BAR_FOCUS_SWITCH` 或 `CONTACT_BAR_REGAINED_FOCUS`）。
      信号是 `MultiTaskingTaskRepository.updateFreeformTaskToTop`（任何小窗获得焦点都会触发）
- [ ] 小白条上滑 → **迷你小窗**：条立即消失（日志 `CONTACT_BAR_HIDE reason=MINI`）；
      从迷你恢复成普通小窗 → 条自动回来（`CONTACT_BAR_SHOW`）
- [ ] **关闭小窗动画期间不再跟随**：点 ✕ / 小白条上滑关闭的**那一刻**条就消失
      （`CONTACT_BAR_TASK_CLOSING` + `CONTACT_BAR_HIDE reason=CLOSING`），不跟着收起动画走
- [ ] 贴边（普通/迷你贴边，mode 2/3）不显示条
- [ ] 点某个头像 → 会话在小窗内打开。Flyme 的点击链逐字对齐：
      `pi.send(ctx,0,intent,null,null,null,options)` 失败 → `startActivityAsUser(intent, options)`。
      对应日志：`CONTACT_BAR_OPEN_PI`（首选）→ `CONTACT_BAR_OPEN_INTENT`（PI 已被微信随通知一起
      cancel，但 Intent 仍能启动 —— 这就是"时灵时不灵"的修复点）→ `CONTACT_BAR_OPEN_PLAIN` /
      `CONTACT_BAR_OPEN_FALLBACK`（最后兜底，退化为打开应用本体）
- [ ] **键盘弹出时不遮挡输入法**：窗口下方放不下就让到窗口上方，两处都让不开则隐藏
      （`CONTACT_BAR_HIDE reason=IME_NO_ROOM`），键盘收起后自动回来。信号来自
      `MiuiFreeformModeDisplayInfo.setImeVisibility`（日志 `IME_VISIBILITY showing=… height=…`），
      取不到时退化为每 300ms 问一次输入法服务
- [ ] 非 IM 应用（如抖音）小窗不显示条（`CONTACT_BAR_HIDE reason=NOT_IM_APP`）
- [ ] 小窗关闭后条消失（`CONTACT_BAR_HIDE reason=TASK_VANISHED`）
- [ ] 关闭设置开关 → 立即消失（`CONTACT_BAR_PREF_CHANGED enabled=false`）
- [ ] 新消息即时生效：小窗开着时来一条新微信 → 头像条刷新（未读红点出现在头像右上角）
- [ ] **锁屏/解锁不崩**（回归：`NamedListenerSet.remove` 曾因通知监听代理 `equals` 返回 null 抛 NPE）
- [ ] **Flyme 样式小窗：点小窗外关闭不崩**（回归：`SurfaceControlInputReceiver.onInputEvent` 返回 void/bool 混用，
      代理返回 null 会在 `InputEventReceiver.dispatchInputEvent` 拆箱 NPE）
- [ ] 真机自测小窗命令（比手势快）：`adb shell am start --windowingMode 5 -n <pkg>/<activity>`
      —— 注意这样拉起的是**较小**的 freeform 窗口（本机约屏幕宽 33%），默认 60% 阈值下会被
      `TOO_SMALL` 隐藏，属预期；要看条可用手势开正常小窗或把阈值调低
- [ ] **触摸**（2026-10-18 换成 `SurfaceControl` 之后唯一换了投递方的东西，见 A32）：点头像打开会话、
      横滑滚动、上滑移除都照旧；`adb shell dumpsys input | findstr "bubble contact bar"` 能查到输入窗口
- [ ] 条的取证（A32）：`dumpsys SurfaceFlinger --list` 里有 `ContactBarSurface-<taskId>`；
      它不再是窗口，`dumpsys window` 里**查不到** `bubble contact bar` 了
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr CONTACT_BAR`
      （启动时应有 `CONTACT_BAR_HOOKS_INSTALLED pipeline=true` 与 `CONTACT_BAR_NOTIF_LISTENER_ATTACHED`）

## A9 Flyme 样式轻量小窗（黑色遮罩 / 窗外点击关闭）
前置：设置页「实验性功能 → Flyme 样式轻量小窗」开启；`黑色遮罩` 与 `窗外点击关闭` 默认都开。
遮罩是**一块**全屏 `SurfaceControl`（挂在 RootTaskDisplayArea 下、按 z 序压在目标小窗之下），
所以控制器必须始终知道"现在哪一个小窗拥有它"——状态由 `windows`（taskId → 窗口对象）维护。
- [ ] 开一个小窗 → 窗外变暗 35%，点窗外 → 小窗关闭（`OUTSIDE_SURFACE_TAP_DISMISS taskId=…`）
- [ ] **两个小窗并存**：第二个小窗出现后**仍然有遮罩**，点窗外关掉的是**当前这个小窗**，
      剩下的那个小窗**遮罩回到它身上**（日志 `DIM_TARGET … reason=TO_FRONT|APPEARED_RAW`，
      切换时为 `DIM_FOCUS_SWITCH`，点外部关闭后是 `DIM_TARGET … reason=AFTER_OUTSIDE_CLOSE`）
- [ ] 点另一个小窗（或拖它的把手）→ 遮罩换到它下面（`DIM_FOCUS_SWITCH`）
- [ ] 小白条上滑 → 迷你小窗：遮罩立刻消失（`DIM_SUSPENDED reason=GESTURE_MINI`），
      迷你恢复成普通小窗后遮罩回来（`TASK_RESTORED_TO_FREEFORM … RETAKE TAKEOVER`）
- [ ] 关闭单个小窗的动画期间遮罩不再跟出来（`DIM_SUSPENDED reason=CLOSING`）
- [ ] 上滑悬停 → 交还系统原生小窗：不再有遮罩（`DIM_TAKEOVER_CANCELLED reason=SWIPE_UP_HOLD`），
      该小窗重新出现/恢复普通小窗后失效
- [ ] 关掉 `黑色遮罩` 而保留 `窗外点击关闭`：看不见遮罩但点窗外仍能关（alpha=0 的输入层）
- [ ] 两个开关都关 → 遮罩与点击都不生效（`DIM_HIDDEN reason=DISABLED`）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr /R "DIM_ SHOW_DIM BACKGROUND_SURFACE OUTSIDE_"`
      —— 正常情况下每 700ms 会重新压一次遮罩（自愈），日志只在状态变化时出现
      （`SHOW_DIM_SURFACE` / `DIM_HIDDEN` / `DIM_TARGET`），不会刷屏

## A10 扇形外观与时长（层数 / 图标数量 / 半径 / 外圈半径 / 振动 / 自动收起 / 图标大小 / 形状 / 分身角标）
入口：设置页「扇形面板外观」（层数 / 图标数量 / 半径 / 外圈半径 / 滑出振动 / 不操作自动收起 / 图标大小 / 图标形状 / 分身角标）。
- [ ] **层数（默认单层）**：单层 = 现在的样子（一圈，半径就是设置值，切一次层数再切回来观感完全一致）；
      切到**双层**后：内圈 = 前「图标数量」个应用 + 「更多」，外圈 = 接着的应用（内层 5 个 → 外层 7 个），
      **「更多」只在内圈**、外层没有
- [ ] **半径（双层时它 = 内圈半径）**：拖「展开扇形半径」，**两圈都要跟着动**。
      2026-10-16 的 bug 正是这一条：旧几何把设置当成"下限"再和一圈自己的弦长要求取大，180→284dp 整段没反应
      （用户原话「开双层根本控制不了半径」）。现在内圈半径 == 设置值，外圈（自动时）= 内圈 + 一格，
      **整条滑条上都跟着缩放**（中间那版给自动外圈留了"弦长下限"，内圈在 180–213dp 之间拖时外圈不动 ——
      用户第二轮：「即使不启用单独条件，外圈半径也要跟随内圈缩放，不是完全不动」）
- [ ] **外圈半径单独控制**（默认关）：打开后「外圈半径」滑条生效，拖它只动外圈、内圈不动；
      **单层时开关与滑条都置灰**（还在原位，不藏起来）
- [ ] **滑条不会低于内圈**：把「外圈半径」往左拖过内圈半径 → 滑条停在**内圈半径**那一格
      （用户 2026-10-16：「滑到了比内圈半径更小的会自动弹回内圈半径」）；
      把「展开扇形半径」调到比外圈还大 → 外圈跟着抬到内圈半径上（读侧也会夹，不会内外颠倒）
- [ ] 双层下内圈/外圈**各选各的**：手指停在内圈那枚上悬停的是内圈图标，移到外圈同理；两圈之间不留"死区"
- [ ] 双层时**重按翻页**（`FAN_PRESSURE_PAGE_TURN`）后每一页同样是"前几个在内圈、其余在外圈"，
      且图标半径与上一页一致（不忽远忽近）
- [ ] 双层 + 最后一页不满：最后一页的图标仍落在**与其它页相同的两条弧**上（不会整页缩到内圈）；
      若能取到更多推荐，最后一页会被**补齐**（收藏+推荐的总数是每页容量的整数倍，除非机器上应用不够）
- [ ] 关掉「推荐应用」总开关 → 扇形里**一条推荐都没有**（页数 = 收藏切出来的那几页）；
      重新打开后推荐页回来
- [ ] **滑出时振动**（默认开）：从下角滑出、扇形**开始展开的那一帧**振一下（轻 tick，不是重按那种重 click）；
      关掉后同一动作完全无振动，图标悬停的轻 tick 仍在
- [ ] **不操作自动收起**（默认 10 秒，范围 0–30 秒）：滑出后手不点任何图标 → 到点自动收回
      （日志 `FAN_RETRACT_AUTO_TIMEOUT`）；手指还在滑动时不会中途消失（每个事件重新计时）；
      拖到最左「不自动收起」→ 一直停留，只有点选应用 / 点按面板外部 / 熄屏才收
- [ ] 点按外部、选中应用、熄屏三条既有收起路径不受影响（`FAN_RETRACT_VOID` / 选中即收 / `FAN_RETRACT_SCREEN_OFF`）
- [ ] **双击外部关闭**（默认**关**，2026-10-17 新增）：关 = 点扇形外面一下即收；开 = 要连点两下
      （第一下只记时间，日志 `FAN_VOID_TAP_FIRST`；第二下 `FAN_VOID_TAP_CLOSE mode=double`）。详见 A27
- [ ] **图标大小**（默认 44 dp，范围 32–64 dp）：拖动后扇形图标与选中光环一起变大变小，
      扇形半径不变、图标间不重叠；扇开着改动即时生效（`FAN_REBIND` 路径 = `rebindFanIfBusy`）
- [ ] **图标形状**：圆形（默认，Flyme 原样）↔ 系统样式圆角矩形；后者图标与选中光环都变成圆角方形
      （圆角半径 = 图标边长 × 0.225，与桌面图标观感一致）
- [ ] 切换形状后点选/悬停/翻页都正常（光环跟手、无残影）
- [ ] **分身角标**（默认开，见 A17）：双开应用的图标**图本身**与主空间那个应用完全一样，
      只在右下角多一个分身角标；关掉后两者像素级一致（扇子、更多面板、管理页三处都变）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr FAN_`（`FAN_RETRACT_*`、`FAN_PRESSURE_PAGE_TURN`）；
      分身图标来源：`adb logcat -s MeiWindow_AppRepo | findstr CLONE_ICON`

## A10b 更多面板的推荐数量与分区折叠
入口：设置页「更多面板 → 推荐应用」与面板里的推荐区 / 收藏区。
- [ ] **推荐显示数量**手输（4–50，默认 12）：点那一行弹输入框并**自动聚焦**（数字键盘）；
      填 7 → 行尾与面板里都是 7 个；清空输入框 → 「确认」置灰 + 红字，点不动；
      填 99 → 输不进第三位（上限 50 时只收两位）；取消/确认**左右排列、各占半宽**
- [ ] 数量 > 20 时：面板推荐区只铺 20 个，第 20 个下面是**「展开更多 ▾」**（只有文字 + 小三角、居中、比正文浅），
      点一下把剩下的全铺开，**没有「收起」**（收起要重新打开面板）
- [ ] 收藏应用 > 20 个时同理（收藏区自己那一条「展开更多」），展开后收藏可以继续长按拖拽排序，
      拖到第 20 个以外也正常（拖拽落点按"看得见的格子"换算）
- [ ] 搜索时不折叠（搜到多少显示多少）；A–Z 全量列表与功能页的「已选功能」都不折叠

## A11 小窗内横屏（窗口必须落在屏幕内）
Flyme 样式下小窗的大小 = 一个缩放系数 × ROM 自己的**未缩放**任务 bounds（`MiuiMultiWindowUtils.getPossibleBounds`）。
这个 bounds 与方向有关：竖屏窗口宽 ≈ 短边 × ratio，横屏窗口宽 ≈ 短边 × ratio × 屏幕长宽比
（本机 1220×2656 → 约 2.18 倍）。所以同一个「小窗大小」设置在横屏时会撑出屏幕；而 ROM 的旋转路径
（`MiuiFreeformModeAnimation.startFreeformOrientationChangeShellTransition`）只会把窗口**平移**回屏内，**不会**缩小它。
现在生效系数取 `min(设置值, ROM 自己 reviewFreeFormBounds 后的值)`——后者恰好是"装得下"的上限，
竖屏时两者相等（设置值原样生效），横屏时自动收到屏幕宽度。
- [ ] 小窗里点应用的横屏按钮（视频全屏 / 游戏）→ 窗口变成**横屏条**、居中、不超出屏幕
      （宽 ≈ 屏幕宽 − 两侧 6dp，高 = 宽 ÷ 屏幕长宽比）
- [ ] 再转回竖屏 → 回到原来的竖屏大小与居中位置，不需要重开小窗
- [ ] 横屏时黑色遮罩仍铺满全屏、点窗外仍能关闭（A9 的判据不受影响）
- [ ] 横屏时联系人条贴着窗口下边、宽度跟着窗口走，不超出屏幕
- [ ] 直接以小窗打开一个横屏应用（游戏）→ **同样落在屏幕内并且居中**（上下也居中）
      —— ROM 自己只会把横屏小窗横向居中、并且把它**顶到屏幕最上方**
      （`getTopMargin` 在竖屏显示下直接返回 0），所以居中一律由模块按"装得下"的系数算
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr FREEFORM_SCALE`
      —— `target` = 设置值，`rom` = ROM 按屏幕算出的上限，`used` = 实际生效值；
      `clamped=true` 表示这次被上限压小了（横屏正常就是 true，竖屏应为 false）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr CENTERED_BOUNDS`
      —— `scale` 是模块用来摆放的系数，`src=` 说明它是从哪来的（`rom` = ROM 自己给的，`assumed` = ROM
      review 过的设置值，`setting` = 裸设置值），`visual=(宽x高)` 是窗口在屏上的大小；
      横屏应满足 `visual 宽 ≤ 屏幕宽`、且 `rect.left/top` 都是正数（居中而非贴边）
- [ ] **摆放用的系数必须等于 ROM 真正使用的系数** —— 这是「设置了居中却看起来没居中」的头号原因：
      窗口是按**未缩放** bounds 定位、再乘系数绘制的，系数错一点，窗口本身就偏半格。
      日志里 `applied ≠ assumed` 说明这台 ROM **没有**按模块的设置值给小窗定尺寸
      （`MiuiFreeformLaunchConfig.getFreeformArg` / `getFreeFormScale` 这条链没接上），
      这时必须以 `applied` 为准（`src=rom`）；两个数值相等时说明设置值本来就是它用的值。
      `applied` 的来源：迷你→普通恢复取 `getRestoredBounds` 的返回值（`singleTap` 原样把它交给
      `setAnimParam`），启动/切换路径取模块自己 `getFreeFormScale` 钩子给 ROM 的那个答案
- [ ] 出现 `CENTER_SCREEN_MISMATCH` 说明 `Resources.getSystem().displayMetrics` 与 ROM 的
      `getDisplaySize`（= `windowConfiguration.getMaxBounds`）不是同一个尺寸 —— 模块已统一改用 ROM 的值
      （两条居中路径都受影响，所以只差这一点也会整体偏）

## A12 禁止小窗偏移（第二个小窗仍开在同一个位置）
HyperOS 的「错开」在 `MiuiMultiWindowUtils.avoidIfNeeded:1807`（手机走这一支）：新小窗与已有小窗
重叠时，把它挪到 `已有窗口.left + FREEFORM_RECT_OFFSET_X_ZIZHAN` / `.top + FREEFORM_RECT_OFFSET_Y_ZIZHAN`
（:319-320 = **78dp / 44dp**，即"往右下偏一点"）。所有避让都汇聚在 void 且原地改参的
`avoidAsPossible(Rect, Rect, Rect)` 上（启动 `getFreeformRect:1461` / `getCustomFreeformRect:1541`、
全屏→小窗 `MulWinSwitchInteractUtil:104`、迷你→普通恢复 `MiuiFreeformModeMiniStateHandler:276`
与 `MiuiFreeformModePinHandler:953`），所以关掉它一处即可。
入口：实验性功能 → 窗口尺寸与位置 → **禁止小窗偏移**（**默认关**）。
**关键**：ROM 的避让在 `getCustomFreeformRect` *内部*，而模块的「小窗居中」在那个方法*返回之后*重新
摆放窗口 —— 所以居中一开，ROM 那次避让就被覆盖，关着开关也看不到偏移（用户实测到的现象）。
因此关掉开关时，模块在居中之后**自己再调用一次 ROM 的 `avoidAsPossible`**，把避让补回来
（方向与屏幕内夹取沿用 ROM 的逻辑），日志 `CENTERED_BOUNDS_AVOIDED … delta=(dx,dy)`。
- [ ] **保持关闭**（默认）+ 居中默认开：开一个小窗 → 再开第二个 → 第二个相对第一个**往右下错开**
      （本机约 右 103px、下 143px；`CENTERED_BOUNDS_AVOIDED delta=(…)`，`delta` 为 0 时说明两个窗口
      没有重叠，此时本来就无需避让）
- [ ] **打开开关**：同样操作 → 两个小窗**位置完全重合**（日志 `FREEFORM_OFFSET_SKIPPED`）
- [ ] 开关是热切换：改完直接再开小窗即生效，不需要重启系统界面（设置值就在 `cfg.xml` 里）
- [ ] **关掉 Flyme 样式小窗总开关**后重复前两项：打开=重合、关闭=往右下错开
      （本开关**不依赖**总开关：它压的是 HyperOS 自己的避让，原生小窗模式同样生效）
- [ ] 迷你小窗恢复成普通小窗时同样按开关处理（关=错开，开=重合）
- [ ] **迷你小窗点击还原**（`TASK_RESTORED_TO_FREEFORM`）落在屏幕中央：日志有
      `CENTERED_BOUNDS rect=…`；**只有一个窗口时不应出现 `CENTERED_BOUNDS_AVOIDED`** ——
      避让的"另一个小窗"只认 shell 仓库里**还活着**、且不是正在摆放的那一个
      （`MultiTaskingTaskRepository.getFreeformTasksInZOrder` → `getMiuiFreeformTaskInfo`，
      日志尾部带 `src=shell|tracked`）；跳过的情形记
      `CENTERED_BOUNDS_AVOID_SKIPPED reason=SELF_OR_SAME_PACKAGE|NO_OTHER_WINDOW`。
      早期版本拿遮罩跟踪的那个窗口去避让，而它可能是**已经关掉**的窗口（位置恰好和新窗口重叠，
      因为新小窗就开在默认位置上），于是新窗口被推离居中位置 78dp/44dp —— 这就是
      「OS3 上小窗居中不生效」（`activeTaskInfo` 只在下一次 focus 事件才更新，`onTaskVanished`
      过去并不清它）。
- [ ] **迷你→普通恢复还有第二道门**：`MiuiFreeformModeMiniStateHandler.singleTap` 的顺序是死的 ——
      先 `getRestoredBounds(taskInfo, rect)`（模块在这一步居中），紧接着
      `avoidOtherFreeformTaskIfNeed(taskInfo, rect, scale)` 原地改**同一个 `Rect` 对象**。
      这是唯一一个「模块写完之后 ROM 还会改写」的摆放点（启动/切换路径的避让都在 ROM 方法*内部*，
      也就是模块居中*之前*）。模块在上一步已经自己做过这个决定（`avoided=true`，或「禁止小窗偏移」
      明确要求不偏移），所以那一次避让被跳过，日志
      `RESTORE_AVOID_SKIPPED rect=… — the module's own placement stands`；模块没替 ROM 做决定的场合
      （开关关着、且模块自己也没算出偏移）仍然由 ROM 说话。
      判定：有第二个普通小窗时，还原后的窗口要么与它错开（开关关）、要么与它**重叠但居中**（开关开），
      都不应该"既重合又偏半格"
- [ ] 迷你小窗自己的排布（同侧迷你自动排队）、贴边/侧边栏避让不受影响
- [ ] 全屏 → 小窗切换（小白条上滑悬停等）与直接打开的表现一致

## A13 小白条上滑 → 迷你小窗（HyperOS 3 与 4 走的不是同一套代码）
> 先看一条**通用**前提：`adb install -r` 会触发 LSPosed 热重载（`autoHotReload`）。热重载会把上一代的
> **所有** hook 摘掉，而模块过去只在那条路径里重建「角落手势监视器」，**没有重装方法 hook** —— 于是
> 每次装完新包，Flyme 样式小窗的整套 hook（黑色遮罩 / 小窗居中 / 小白条手势 / 联系人条 / IME）都会
> 静默失效，直到系统界面重启。已修（`SystemUiHookInstaller.reinstall` 现在会重跑
> `FlymeFreeformController.installHooks`），判断依据是安装后立刻能在日志里看到
> `HOOK_INSTALLED_BoundsCentering rect=… mini=…`：**没有这行就说明当前进程里 hook 是死的**。

这个手势在 ROM 里两边不一样，模块因此有两条路径：

- **HyperOS 4**：`MiuiFreeformModeMoveHandler.onBottomCaptionHandleMotionEvents` 的 ACTION_UP 里有
  **迷你分支**，条件是 `|velocity| < 1000 && getDestinationScaleX() <= getMiniBottomUpLimit() + miniScale
  && !isLastTargetIsFullscreen() && deltaY < 0`。模块的做法是**操纵输入**：把 `getVelocity()` 归零、
  把 `getMiniBottomUpLimit()` 抬到 999（`hookVelocityMonitor` / `hookMiniBottomUpLimit`），让 ROM 自己
  走那条分支；状态转换（mode=1、迷你系数、shell transaction）全是 ROM 干的，模块只把 release 动画的
  类型从 2 改成 11（官方迷你动画）并把目标摆到迷你位置。
- **HyperOS 3**：ACTION_UP **只有**三条出口 —— 关闭（`deltaY < -80 && velocity < -1000` 或系数 < 0.35）、
  全屏（`deltaY > 80` 或超过阈值）、**回弹**，**没有迷你分支**（整个 dex 里也搜不到
  `triggerMiniThreshold` / `getMiniBottomUpLimit`）。所以上面那套"操纵输入"在 OS3 上等于没做任何事：
  用户看到的就是"上滑一下，窗口弹回原位"。

OS3 上模块改为调用 ROM 自己的转换入口 `MiuiFreeformModeController.fromFreeformToMini(int)`
（ROM 的「控制中心」进迷你小窗走的就是它：mode=1 + 迷你系数 + 侧边落位 + WCT + 类型 11 动画 + 避让）。
它有两道门槛，所以实现是**重试**而不是调用一次：

1. 窗口在做连续动画时它直接 return（`isRunningContinuousAnimation()` = 动画类型 1/2/3 还在表里，
   而底部小白条拖拽登记的正是 2）。这个标记由 `proceed()` 启动的 release 动画结束时清掉，何时清掉
   从外面看不到 —— 于是每 60ms 试一次，直到 `onTaskModeChanged` 报出 mode=1 为止。
   （`executeOnShell` 是异步派发，所以"试一次"= 派发一次调用，成没成由那个回调判定。）
2. 万一 release 动画永远不上报完成（标记一直挂着），第 10 次（≈600ms）时模块**只清这一个标记**
   （`removeRunningAnimationType(2)`），让 ROM 的转换接手 —— 这一步在日志里是 `MINI_FALLBACK_UNBLOCK`。

- [ ] OS3：小窗上滑小白条 → **变成侧边迷你小窗**（窗口收缩并落到左上/右上角）
- [ ] OS3 日志判据：`ACTION_UP_SWIPE_MINI taskId=… dy=…` → `MINI_SWIPE_FALLBACK taskId=…` →
      `MINI_SWIPE_CONVERTED_BY_ROM taskId=… old=0 new=1` → `MINI_FALLBACK_CONVERTED taskId=… afterMs=…`
      （`afterMs` = 等 release 动画花了多久；若 `afterMs` 很小，说明窗口是直接滑过去的，
      若接近 300~600ms，说明它先弹回原位再贴边 —— 想消掉这段回弹再做一版）
- [ ] OS4 回归：同一手势必须是 `ACTION_UP_SWIPE_MINI` → `MINI_SWIPE_CONVERTED_BY_ROM` →
      `MINI_SWIPE_NATIVE`，**不应**出现 `MINI_SWIPE_FALLBACK` / `MINI_FALLBACK_*`
- [ ] 启动时一眼看出 ROM 走哪条：`HOOK_INSTALLED_MiuiFreeformModeTaskInfo.getMiniBottomUpLimit count=1`
      = OS4（有原生迷你分支）；`HOOK_SKIPPED_MiuiFreeformModeTaskInfo.getMiniBottomUpLimit` = OS3
      （没有，由模块驱动）。两样都不该出现的是 `MINI_CONVERSION_NOT_ON_THIS_ROM`／
      `MINI_FALLBACK_UNAVAILABLE`（这台 ROM 连转换入口都没有 —— 请把日志发我）
- [ ] 迷你小窗恢复成普通小窗后，黑色遮罩与联系人条照常回来（A8/A9 的判据）
- [ ] OS3 上「上滑悬停 → 交还原生小窗」与「下滑 → 全屏」不受影响
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr /R "MINI_"`

## A14 顶部「重启系统界面」按钮（需要 root）
入口：主页（魅窗）右上角的 miuix `Refresh` 图标。
用途：模块的 hook 都活在 SystemUI 进程里 —— `adb install -r` 触发的 LSPosed 热重载会丢弃上一代
所有 hook，只重建模块自己的运行时（`SystemUiHookInstaller.reinstall`），进程里那些"只在启动时读一次"
的状态也不会重来。想从干净进程复现问题、或让改动立刻生效，按这个比重启手机快。
实现：`SystemUiRestart` 走 `su -c`，命令依次尝试 `killall` → `pkill -f` → `pidof` + `kill -9`
（toybox 三个都有，本机实测 `su -c "pidof com.android.systemui"` 直接返回 pid）。
- [ ] 有 root：点击 → toast「正在重启系统界面…」→ 状态栏/导航栏消失约一秒后回来；
      `adb logcat -s BubbleDrawer` 出现 `SYSTEMUI_RESTART exit=0`，随后 SystemUI 进程 id 变化
      并且重新打出整串 `HOOK_INSTALLED_*` / `FLYME_FREEFORM_CONTROLLER_STARTED`
- [ ] 无 root（或拒绝授权）：toast「重启失败：需要 root 权限」，SystemUI 不动
      （日志 `SYSTEMUI_RESTART exit=…` 非 0）
- [ ] 重启后设置页、扇子、小窗功能一切照旧（模块在 SystemUI 启动时重新装 hook）

## A15 诊断页（没有 adb 的测试机也能定位）
入口：主页（魅窗）→ **实验性功能**下面一行 **诊断**（`DiagnosticsScreen`）。
它自己读模块日志、给出"哪些成功、哪些有问题"，不需要电脑：`ModuleLogSource` 走 `su -c`
（与 A14 同一个 root 前提，第一次会弹授权框）读两个来源 ——

1. `logcat -d -t 4000 -s BubbleDrawer`（现场，最新，但 ring buffer 会滚）；
2. `/data/adb/lspd/log/modules_*.log` 最新一个的尾部（LSPosed 的模块日志，重启进程也不会丢）。

拼接顺序是"持久日志在前、logcat 在后"，所以分析器里"最后一行"永远等于"最近发生的事"——
`HOT_RELOADED` 那类需要判断先后的结论才不会读反。判定逻辑在 `ModuleLogDiagnosis`（纯函数，单测
`ModuleLogDiagnosisTest` 里用的就是真机日志的原文），每一项给出 正常 / 注意 / 有问题 / 信息
+ 一句话原因 + **原始证据行**。

- [ ] 打开诊断页 → 顶部结论卡（"全部正常" / "N 项有问题"）+ 下面按组列出每一项
- [ ] 有 root：第一次弹授权框；允许后 `adb logcat -s BubbleDrawer` 出现 `DIAGNOSIS su-missing`（若没有 su）
      或直接读到内容；拒绝授权 → 页面显示"root 命令没有返回内容…"，**不转圈**
- [ ] **复现一次问题再回来重新诊断**（右上角刷新，或「操作 → 重新诊断」）：结论会变
- [ ] 判据自检：`CENTERED_BOUNDS rect=…` 那一行的落点必须等于 `(屏幕 − 可见尺寸) / 2`，
      差超过 2px 就报"不在屏幕中央"并给出 `偏 (dx, dy) px` —— 这是"设置了居中却没居中"的
      直接证据，页面不用人去心算
- [ ] `applied ≠ assumed` 会报"这台 ROM 的尺寸不是设置值决定的"（配合上面「尺寸配置 hook」那项）
- [ ] 若安装后发生过热重载且没重装 hook：报"hook 是死的"，并在同一页给出「重启系统界面」入口
- [ ] 「复制诊断结果」→ 文字进剪贴板（含结论、每项原因、关键原文，已裁掉 LSPosed 前缀），
      可直接粘贴发人
- [ ] 界面里改了开关后，结论里"系统界面读到的设置"应能反映出来（不一致会直接报出来：
      设置没同步 = 功能静默失效）
- [ ] 多了 **【边角手势】** 一组：采集通道（手势监听 / 降级 SPY 窗口）、最近一次角落按下
      有没有抢下指针流（`mode=pre-own` + 用时 ms）、抢下后有没有被系统夺回（见 A16）

## A16 角落屏蔽手势（回桌面不再和扇面一起出现）
现象（用户报告）：重启系统界面之后，或者很久没划角落再划时，`全面屏手势（回桌面）`会和扇形面板
**一起**弹出。

成因（设备实测）：角落那一笔必须在 **MiuiHome 的原生识别器之前**把指针流抢过来
（`InputManagerService: pilferPointers` 落在触摸后约 2ms；晚一点 launcher 就
`app requestRedirect` 接管了，而它的 HOME 是"看最初几个向上采样"就决定的，事后收不回来）。
所以 DOWN 这条路线上任何会阻塞的东西都在赌这一笔：

- `WindowManager.addView`（键盘高度探针窗口）曾在第一个角落 DOWN 里现场创建 —— 每次 SystemUI
  启动后**第一笔**角落手势必然输掉这场竞赛，表现就是"重启系统界面后第一下会回桌面"；
- 主线程队列（SystemUI 启动、扇面/联系人条/面板的窗口工作）曾排在 DOWN 回调前面；
- `PowerManager` / `KeyguardManager` 的 binder 调用、`WindowManager` 指标读取都在抢之前。

现在的实现：探针窗口在手势通道启动时建好（`startMonitorTransport`，主线程、与手指无关；功能关掉
时随通道一起移除）、insets 监听维护缓存；几何与电源/锁屏状态都是缓存字段（配置变化 / 亮灭屏 / 锁屏
事件 + 输入线程上看门狗：**亮屏 250ms、熄屏 5s** —— 这是本功能唯一不靠手指触发的开销，熄屏慢下来
既省唤醒又不影响判定）、DOWN 回调跑在 `bubbledrawer-corner-input` 专用线程
（`THREAD_PRIORITY_URGENT_DISPLAY`），启动时 `MON_WARMUP` 预热一遍，抢占的判定区也改成
`max(28dp, 触发区自身的纵向半径)`（此前触发区高于 28dp 的那部分按下只是"观察"，同样会被 launcher
抢走 HOME）。

- [ ] **重启系统界面**（A14 的按钮，或 `adb shell su -c "killall com.android.systemui"`）→
      等界面回来 → **第一笔**右下角内滑：只出扇面，**不回桌面**；左上/左下角同理
- [ ] 很久不用（或熄屏一段时间后）再划角落：同样只出扇面
- [ ] 角落轻点（不滑动）仍然是被点的那个应用收到点击（键盘弹着的时候更要试：
      左下角按键只输入一次）
- [ ] 角落**外**的底部上滑（屏幕中间、离角 100dp 以上）依旧正常回桌面 —— 屏蔽只发生在触发区内

### A16 日志判据
- [ ] 每一笔 `MON_DOWN … band=true ime=0px mode=pre-own lat=N`：`lat` 是个位到十几毫秒
      （真机实测正常 2ms；`lat` 超过 8ms 诊断页会开始提示"偏慢"）
- [ ] 紧接着 `MON_PILFER_RETRY ok=true lat=…`（这一笔在 Activate 时又抢了一次 ——
      DOWN 那次是抢到了的；**整笔没有这条**说明 DOWN 根本没抢）
- [ ] 这一笔之后**没有** `MON_SYSTEM_CANCEL … owned=true`（有就是"被 launcher 抢回去了"，
      扇面和桌面会一起出现）
- [ ] `MON_INPUT_READY … thread=bubbledrawer-corner-input`（不再是主线程）
- [ ] `MON_WARMUP rounds=24 ms=… cur=… max=…` 在启动时就出现（几何缓存已就绪）
- [ ] 重启后若探针窗口没建起来会有一条 `MON_IME_PROBE_MISSING`（那时键盘上的角落按下会被抢下，
      属于已知降级）

## A17 分身应用（双开）的图标与角标
做法：分身应用的**图本身永远取主空间那张** —— `AppRepository.mainSpaceIcon(pkg)`：先问
`PackageManager.getApplicationIcon(pkg)`（列表里 user 0 那条画的就是它，所以分身和旁边那条像素一致），
拿不到再退 `LauncherApps.getActivityList(pkg, user 0)` 的 `getIcon(dpi)`（HyperOS 上它可能给主题/
自适应变体，所以只当兜底）。分身用户只决定要不要在这张图上**叠角标**
（`RemotePrefs.KEY_CLONE_BADGE`，设置页「扇形面板外观 → 分身角标」，默认开）：

- XSpace（user 999）：先交给 MIUI 自己的 `XSpaceUserHandle.getXSpaceIcon(context, base)` ——
  它的入参就是上面这张主空间图（MIUI 的双开角标是叠上去的），拿不到才用模块自己画的橙色双环；
- 其它用户配置（工作资料等）：`PackageManager.getUserBadgedIcon(base, handle)`（系统角标同样是叠图）；
- 关掉开关：直接返回主空间那张，和主应用像素级一致。

1.1.8 及以前不是这样：分身图标先走 `LauncherActivityInfo.getBadgedIcon(dpi)`，那是**分身用户自己那份
资源** —— MIUI 预烤的双开图标（另一套美术/尺寸），于是同一个应用在扇子里看着像两个应用。

- [ ] 开双开后：主应用与分身应用在扇子里**图一样**，只有右下角多一个角标
- [ ] 设置页关掉「分身角标」→ 两者完全一致（扇子、更多面板、管理页三处都要看）
- [ ] 开关来回切两次都生效（缓存键里带开关，切换后重新解码，不必重启界面）
- [ ] 主空间那个应用的图标不受影响（`CLONE_ICON` 只对 user≠0 打）
- [ ] 日志：`adb logcat -s MeiWindow_AppRepo | findstr CLONE_ICON` → `badge=true src=main-space+badge`；
      SystemUI 侧另有 `PREFS_APPLIED … icon[cloneBadge=true]`

## A18 横屏小窗落点跟触发侧（左角划出来就开在左边）
现象（用户报告 2026-10-11）：**横屏**下不管从左角还是右角划出扇形，点开的小窗都在**左边**。

成因：小窗位置一直是 ROM 自己定的 —— 启动走 `getActivityOptions(ctx, pkg, noCheck)`
（内部 `getCustomFreeformRect(POS_AUTO, POS_AUTO)`，`POS_AUTO` = "你决定"）。横屏时 ROM 给的落点是
固定的那一侧（设备实测：两次启动留下的 task 配置 `mBounds.left` 完全相同，都是 418）；竖屏看不出来
是因为 ROM 自己把小窗横向居中，而竖屏小窗可见宽度占屏宽七成，左右本来就没多少区别。

做法：把"用户从哪个角落划出来"作为落点交给 ROM 自己的那个入口 ——
`getActivityOptions(ctx, pkg, true, left, top)`（5 参数重载，正是**安全中心侧边栏小窗**
`FreeformUtil` 用的同一个方法：`AbstractC6721e0.m21966e`）。落点由 `triggerSideLeft()` 算，
与 `getFreeformWindowPositionX` 同一套判断：触发点在屏幕左半边 → 贴左边（留 `FREEFORM_SIDE_MARGIN_DP`
= 6dp，与 ROM 的「小窗可用区域」同宽），右半边 → 右边对齐。用的是**可见**宽度
（`getVisualBounds(rect, getOriFreeformScale(ctx, pkg))`）：窗口位置是*未缩放*矩形的原点，画出来只有
那个宽度的 `scale` 倍，用未缩放宽度去贴边会差一条缝。

- 尺寸仍然**全部**来自 ROM（`getCustomFreeformRect`，模块只挪位置）—— 2026-10-09 的裁定没变：
  扇形和更多面板打开的小窗必须是同一个形状；
- 只在**横屏**接管（`isWideScreen`）：竖屏 ROM 自己居中，那是用户一直在用的位置；
- Flyme 样式小窗 + 小窗居中开着时，居中 hook 会在 ROM 算完之后重摆窗口 —— 现在它**只补垂直方向**
  （`CENTERED_BOUNDS … left=triggered`），水平那一半是用户划出来的意图，不再被抹掉；
- 更多面板里点应用走同一个 `freeformTrigger()`，落点一致。

- [ ] 横屏：左角划出 → 点应用 → 小窗**在左边**；右角划出 → 点应用 → 小窗**在右边**（两个位置相差半屏以上）
- [ ] 同一个应用先从左边开、关掉再从右边开：位置跟着走（不是被记住的旧位置）
- [ ] 竖屏行为**不变**：还是 ROM 自己的居中位置
- [ ] 更多面板里点应用：落点与扇形里点一致（面板本身仍然居中）
- [ ] 日志：`adb logcat -s BubbleDrawer | findstr LAUNCH_OPTS` →
      `screen=2656x1220 trigger=FreeformTrigger(anchorX=2656, display=Rect(0, 0 - 2656, 1220)) bounds=Rect(1916, …`
      （右角；左角是 `anchorX=0`，`bounds` 的 left 是 20 —— ROM 会把它再夹到 150，那是横屏那一侧的
      挖孔/应用区 inset，属于正常）
- [ ] 竖屏日志里 `bounds=Rect(183, 289 - 1403, 2241)`（ROM 的居中落点，模块没插手）
- [ ] 关掉 Flyme 样式小窗总开关后行为一致（落点与总开关无关）

## A19 联系人条：一个会话只占一条，群通知显示群名
现象（用户反馈 + 用户复现）：联系人条里可能出现几个一样的联系人图标。2026-10-12 的复现是
`YuKongA | Chat：Perlinkca` 与 `YuKongA | Chat：Yife Playte` 两条 —— 同一个群里的两个人。

成因：群通知的标题是 `群名：发送者`（设备日志实例：`StarVoyager Ideal：ksm`、`YuKongA | Chat：Perlinkca`），
而名字列只有 **45dp**，两条都截成 `YuKongA | Chat` —— 看着就是"两条重复的联系人"。
（更早还试过按头像指纹认人，那条规则正好相反：它会把同一个人的几个群并成一条，已按用户裁定撤掉。）

做法（用户裁定原话「**群通知应该用群名啊**」）：条上的身份 = **会话名** ——
`MessagingStyle.conversationTitle`（权威）→ `EXTRA_CONVERSATION_TITLE`（@hide，用字面量）→ 标题里
`群名：发送者` 的**前半段**（`conversationName()`）。按会话名去重、按会话名显示（`dedupeByConversation`）。
同一个群里两个人说话 = 同一个会话名 → 一条；同一个人在两个群里说话 = 两个会话名 → 两条。
头像仍然取那位发送者的（`MessagingStyle` 里最后一条不是自己的消息的 `Person.getIcon()`，
Telegram 的聊天通知不带大图标，只认 `getLargeIcon` 的话条上只能画应用图标）。
`extractMessagingStyleFromNotification` 是 @hide，走反射（缓存一次）。

- [ ] 开一个 Telegram/微信/QQ 小窗 → 同一个群的几条消息只占一条，显示**群名**
- [ ] 同一个人在两个群里说话 → **两条**（各显示自己的群名），不再"并成一条"
- [ ] 1:1 会话显示对方的名字（标题里没有分隔符 → 整个标题就是会话名）
- [ ] 日志 `CONTACT_BAR_LIST pkg=… n=N live=M 会话名* | …`（`*` = 还在通知栏里）；
      合并发生时有 `CONTACT_BAR_MERGED pkg=… notifications=3 conversations=2 keep=…`
- [ ] 划掉一条 → 该会话整条消失（`CONTACT_BAR_FORGET pkg=… label=会话名 title=…`），不会立刻回来；
      同一个会话换了标题（`(3条新消息)`）也不算新消息，来了**更晚的 postTime** 才回来

### 第二种形状：QQ 的未读数后缀（用户 2026-10-16）
现象（用户反馈 + 设备 `CONTACT_BAR_LIST` 原样，注意同一个会话占了三行）：
`通信12301潘宇锋 | 电院23级年级群 | 通信12301潘宇锋(2条新消息) | 通信12301潘宇锋(3条新消息) | …` ——
QQ 把未读数挂在标题**末尾**，每来一条消息它就变，于是记忆里、条上各占好几条
（45dp 的名字列全截成「通信123…」，看上去仍是"重复的联系人"）。

做法：会话名的规范化 `normalizeConversationName()` 剪掉末尾的 `(N条新消息)`
（全角/半角括号、`[]`／`【】` 都认；括号里没有「条新消息」的群名照旧不动），
`conversationTitle` / `EXTRA_CONVERSATION_TITLE` / 标题前半段**三个来源都过一遍**；
并且 `remembered`/`forgotten` 的主键从**通知标题**改成**会话名**（`包名|会话名`）——
一个会话一条记忆，划掉就是划掉，不会因为下一条消息换了个标题就复活。

- [ ] QQ 会话的标题带未读数（`…(2条新消息)`）时只占**一条**（`CONTACT_BAR_LIST` 里再看不到 `(N条新消息)`）
- [ ] 划掉它 → 下一条消息（标题变成 `(3条新消息)`）**不会**立刻把它摆回来（要更新的 `postTime`）

白名单里补了 Telegram（`org.telegram.messenger`）：它发的就是标准 MessagingStyle 聊天通知，群消息标题
正是 `群名：发送者` —— 也就是这条反馈的形状，而模块作者日常只用它（不在白名单里，这个功能在本机上
根本无从验证）。

### 顺带修掉的一个真问题：热重载之后联系人条收不到通知
`reinstall()`（`adb install` 触发的热重载路径）只重装了 Flyme 那套 hook，**漏了联系人条的**
（`RecentConversations.installHooks`）—— 它自己的注释里写着"联系人条也会静默失效"，但代码没跟上。
后果：每次安装之后通知都进不来，条上只剩启动那一刻的记忆（日志上就是 `CONTACT_BAR_HIDE
reason=NO_CONTACTS` 一直重复，看起来像"功能没做"）。

而且这一步**必须发生在主线程**：在 LSPosed 的热重载线程上调 `addCollectionListener` 会抛
`InvocationTargetException`（设备实测 tid 25978 失败，历史上每次成功都在主线程）。现在
`installHooks` 把碰 pipeline 的那一步发到主线程。

- [ ] `adb install -r` 之后：日志里有 `CONTACT_BAR_NOTIF_LISTENER_ATTACHED`，且它和
      `CONTACT_BAR_HOOKS_INSTALLED pipeline=true` 的线程号等于该进程的主线程号
- [ ] 随后条上仍能出新消息（不是只有重启系统界面之后才行）

### 联系人条与通知栏/控制中心：把图层真的调低（用户 2026-10-12）

> **2026-10-18 更新**：条已经不是窗口了（改成挂在显示区下的 `SurfaceControl`，见 **A32**）。
> 这一节的窗口类型、`mBaseLayer` 与 `dumpsys window` 取证**全部作废**；
> 「通知中心/控制中心要盖住条」这条用户要求没变 —— 现在由 `setRelativeLayer(sc, 窗口 leash, +1)`
> 实现（条跟随窗口的 Z，而面板在整个图层层级之上）。下面这段留作历史：它解释了**当初为什么**要换类型。

现象：下拉通知栏之后联系人条叠在通知上面；再试「躲开」的方案，通知中心不显示了，**控制中心还是显示** ——
HyperOS 的这两个面板不是同一个窗口，靠"识别面板再躲"永远追不完。

所以直接改窗口类型（`ContactBarController.barWindowType`）。设备实测
（`dumpsys window windows` 的 `mBaseLayer`，控制中心打开时）：

```
281000 NAVIGATION_BAR_PANEL  bubble contact bar      ← 原来的条（2024 + setTrustedOverlay）
251000 NAVIGATION_BAR_PANEL  bubble slide ime probe  ← 同一个类型
241000 SYSTEM_OVERLAY        AntiMistakeTouchView
231000 STATUS_BAR_SUB_PANEL  NotificationModalWindowManager
181000 NOTIFICATION_SHADE    NotificationShade      ← 通知中心
171000 STATUS_BAR            StatusBar
151000 APPLICATION_OVERLAY   ShellDropTarget        ← 同一个进程里的 APPLICATION_OVERLAY
131000 BASE_APPLICATION      QQ（小窗）
111000 INPUT_METHOD          InputMethod
```

`TYPE_APPLICATION_OVERLAY` 落在 **151000**：在通知中心（181000）、控制中心那几个面板（231000/241000）
和导航栏之下，又在小窗（131000）与输入法（111000）之上 —— 该盖住它的都盖得住，该被它盖住的还盖得住。
另外**不再设 `setTrustedOverlay`**（原来正是它把条提到窗帘之上：同类型的探针在 251000，条在 281000）。
扇面/手势探针仍用原来的 2024 + trusted（它们只在手势出现的瞬间存在，没有这个问题）。

- [ ] 开着聊天小窗 → 下拉通知中心：条**在下面**（不是消失，也不压住通知）
- [ ] 右侧下拉出控制中心：条同样在下面
- [ ] 收起任一面板：条照旧贴在小窗下沿（位置、点击、划掉都不受影响）
- [ ] 条仍然盖在普通应用/桌面上（它挂在小窗的 leash 之上、又在面板之下），输入法弹出时照旧让位
- [ ] ~~`dumpsys window windows`：`mBaseLayer=151000`、`ty=APPLICATION_OVERLAY`~~ —— 条不再是窗口，
      这一条已作废，改看 `dumpsys SurfaceFlinger --list` 里的 `ContactBarSurface-<taskId>`（A32）

## A20 小窗落点：横方向居中/触发侧，竖方向正中 + 上下滑杆
用户 2026-10-11 的决定（原话）：「**竖屏时始终居中，横屏依旧按你的在那边打开就在哪边显示的逻辑**」、
「**flyme 小窗下默认就是正中，现在加一个滑杆可以上下调。横屏不要生效**」。于是两个轴各管各的：

- **横向**：`小窗居中` 开着才居中（关掉 = 模块不动它）；扇形触发时**永远**按触发侧（见 A18）；
- **纵向**：`小窗居中` 开着 = 正中（就是今天的行为），关着 = 保持 ROM 给的位置；两者之上再叠加设置项
  「小窗上下位置」的滑杆 —— `-100…+100`，按**上下余量**（屏幕高 − 可见高）的百分比算：0 = 正中、
  -100 = 贴顶、+100 = 贴底，任何取值都落在屏幕内。**只在竖屏生效**（横屏窗口几乎占满高度，挪一下就出屏）。

滑杆默认 0，所以默认设置下这条路径与以前逐像素一致（日志 `CENTERED_BOUNDS … vpos=0%(0px)`）；
「小窗居中」的开关语义也因此收窄成"横向"（文案同步改了）。

- [ ] 设置 → Flyme 样式轻量小窗 → 「小窗上下位置」拖动后，新开的小窗跟着上下移动；回到 0 = 正中
- [ ] 调到 ±100：窗口贴顶/贴底，但**始终完整在屏幕内**
- [ ] 横屏：无论滑杆在哪，小窗上下位置不变（日志仍打 `vpos=…`，但 `(0px)`）
- [ ] 关掉「小窗居中」后滑杆仍然有效（居中只管横向了），横屏仍然跟触发侧
- [ ] 日志判据：`CENTERED_BOUNDS rect=… left=centered|triggered|rom vpos=N%(Mpx) centered=true|false`

## A21 联系人条的开销：空闲不再逐帧
「顺便看一下联系人条有没有性能问题」的复查结果，四件事都改了：

1. **常驻 Choreographer 回调**：只要有一个普通小窗存在就按帧跑（60 次/秒的主线程唤醒），空闲时也照样
   反射读状态、每 700ms 写一行模块日志（主线程文件 I/O）。现在：窗口静止 → **250ms 采样**（4 次/秒）；
   ROM 的每个 move/resize 都经过 `MiuiFreeformModeAnimation.startGestureAnimation`，它的 hook 会
   `kick()` 一次，**拖动第一帧就切回逐帧**。日志 `CONTACT_BAR_LOOP idle (250ms sampling)` /
   `per-frame (window moving)` 就是这次切换（只在切换时打一行）。
2. **通知事件全量重建**：以前任何一条系统通知（音乐进度一秒一条）都会触发"遍历所有通知 + 重建列表 +
   写一行日志"。现在只认白名单聊天应用 —— `onEntryAdded/Updated` 读 `mSbn.packageName`，
   `onEntryRemoved` 只给 key（`userId|pkg|id|tag|uid`），按 key 切出包名（`imPackageOfKey`，有单测）。
3. **头像缓存无上限**：144×144 ARGB ≈ 83KB/条，以前只增不减。现在超过 40 条时只保留还有记忆引用的。
4. **一次设置写入跑二十来遍 `applySettings`**（prefs 是逐 key 回调的）：合并成主线程上一轮
   （`scheduleApplySettings`），同一毫秒里二十行 `PREFS_APPLIED` 不会再出现。

## A22 角落手势「时灵时不灵」：时延拆成 recv/pil，看门狗搬离输入线程
用户 2026-10-12 反馈：**边角屏蔽手势时灵时不灵**，并提供了诊断日志。那一份里唯一的异常是

```
MON_DOWN side=RIGHT x=1307.0 y=3199.0 box=193.0 band=true ime=0px mode=pre-own lat=15 cur=1440x3200 …
```

`lat=15` = 手指按下到抢下指针流用了 15ms，而 MiuiHome 的原生识别器约 **8ms** 就认下 HOME、
设备实测正常一笔是 **~2ms** —— 超出的那 13ms 就是被抢的原因。

**成因（已定位并修掉）**：看门狗 `stateWatchdog` 当时**排在输入线程的 Looper 上**，每 250ms 跑一次，
每次 `refreshState()` 两次 binder 往返（`PowerManager.isInteractive` / `KeyguardManager.isKeyguardLocked`）。
Looper 要等当前消息跑完才会回到 `epoll_wait`，而输入通道的 fd 回调排在消息**后面** —— 手指正好落在这
几毫秒里，DOWN 就要排队。所以它"大多数时候 2ms、偶尔 15ms"：正是「时灵时不灵」。
（同一个类别的坑这个功能踩过两次：输入接收器早就从主线程搬到了自己的线程，看门狗被落下了。）

改动：
- 看门狗搬到**自己的线程** `bubbledrawer-corner-state`（`THREAD_PRIORITY_BACKGROUND`，懒建、随传输一起
  销毁）。输入线程上**只许有输入回调**，这条规矩写在 `startMonitorTransport` 的注释里。
- 时延**拆成两段**并留证：`MON_DOWN … lat=N recv=R pil=P` —— `recv` = dispatcher 投递到模块线程
  （大了说明我们的 Looper 被挡 / 系统忙），`pil` = 我们的处理 + binder `pilferPointers`（大了说明是我们
  自己慢）。`onMonitorEvent` 的第一行语句就是取 `recv` 的基准时刻。
- 新增 `LatencyStats`（纯算术、有单测）：每笔都记，**超过 8ms 立刻打 `MON_LAT_SLOW`**（带 recv/pil 拆分），
  每 20 笔打一行 `MON_LAT n=… p50=… p90=… max=… slow(>8ms)=…` 汇总。
- 诊断报告新增一项「角落按下的时延分布」，并把 `recv`/`pil` 的归因直接写在"最近一次角落按下"里 ——
  下次用户报告不用再猜是投递慢还是处理慢。
- `MON_STATE_THREAD name=… input=…`：一眼确认两个线程是分开的。

- [ ] 日志里 `MON_STATE_THREAD`：`name=bubbledrawer-corner-state`、`input=bubbledrawer-corner-input`（不同线程）
- [ ] 划二十来次左右下角 → `MON_LAT p90=` 应在 2~4ms、`slow(>8ms)=0`
- [ ] 若仍偶发：看那一笔 `MON_LAT_SLOW` 的 `recv`/`pil` —— `recv` 大 = 系统忙/投递慢（模块无法再优化），
      `pil` 大 = 抢指针流那一步慢（binder 到 system_server）
- [ ] 扇面照旧在角落滑出，系统不跟着回桌面（`MON_STROKE_END … claimed=true robbedAt=0`）

## A23 收藏页点一下必须当场生效（拉锯的同族问题）
用户 2026-10-12 的两条报告其实是一族：先前是「收藏自动反复添加/移除」，接着是
「收藏或取消收藏应用有概率**停在原地，只改变加号减号图标**，需要收藏或取消收藏其他应用才会刷新」。

成因（两个，都在 `PinManageModel`）：
1. **图标与位置来自不同的快照**：图标读 `isPinned`（缓存），位置读 `cells`。缓存同步落、`cells` 在
   带推荐的协程里晚一步落，两者就会不一致 —— 图标变了、位置没变。
2. **写入之后又回头和解读一次**：收藏的值与它的时间戳是两个 key，逐 key 镜像时会撕裂（见 A19 的
   `PinsSync` 说明），撕裂的那一瞬间读回来的是旧值，于是刚点的那一下被旧值盖掉，要等下一次操作。

改法：
- `togglePin` 用**刚写下的那份**重建（`rebuild(pins = current)`），不再回读；
- `pinnedKeys` 与 `cells` 只在同一个 `applyCells()` 里落地（同一次快照）；
- 每次重建带序号（`rebuildGeneration`），带推荐那条异步路径**旧结果不许覆盖新结果**。

回归测试：`PinManageModelPinTest` 把「写入成功、但紧接着的读拿到旧值」这个顺序摆出来，断言点下去必须
当场生效（旧代码下这两个用例都会失败，已验证）。

- [ ] 主页 → 收藏应用管理 → 点任意应用的加号：**当场**出现在 ★ 区，图标同时变成减号
- [ ] 再点一次取消：**当场**回到下面列表，图标变回加号
- [ ] 反复点十几次（不要等）：每次都对得上，不需要"再点别的"才刷新
- [ ] 扇形面板里的「更多」页同样操作，行为一致
   顺带：同一个 pass 里 `NOT_IM_APP` + `NO_CONTACTS` 两行隐藏日志，现在只留信息量大的那一条。

- [ ] 开一个**非**聊天应用的小窗（浏览器等）静止放置：不再每帧刷 `CONTACT_BAR_HIDE`
- [ ] 拖动小窗：联系人条仍然贴着窗口底边（`per-frame` 出现，松手后回到 `idle`）
- [ ] 放一首歌（进度通知一秒一条）：不再有通知驱动的重建（`CONTACT_BAR_DATA`/`CONTACT_BAR_LIST` 不刷屏）

## A24 气压重按校准（实按 5 次算专属阈值）
参考实现是 JADX 里那份 `com.oxohang.fanfreeform`（「按压手势 → 气压校准」）：
`FanRuntime.handlePressureCalibrationMotion` 逐次记按压峰值 → `recordPressureCalibrationAttempt`
满 5 次 → `PressureGesturePolicy.calibrate` 取「最小值与中位数的中点」当阈值，有效样本不足 3 个算失败；
超时（`schedulePressureCalibrationTimeout` 的 120s，每记一次就重新计时）也会拿已收集的样本收尾。

本模块的适配：
- **采集在 SystemUI**（气压计和扇形都在那里），**落盘在 App**（hook 进程的远程组是只读镜像）：
  App 写 `fan_pressure_calibration_active=true` 请求开始 → `FanHost` 开会话 → 每次按压/结果/超时经
  `PressureCalibrationBridge` 广播回 App → `SettingsStore` 一次写完本地镜像 + 远程组。
- 按压的切分不能再用 DOWN/UP：本模块的重按发生在**扇形展开期间**，划出扇形本身就按住不放。所以用
  气压曲线切分（`PressurePressTracker`：越过 0.08 hPa 开始、跌回 40% 结束），一次划出里连按 5 次都算。
- **越过分段门限 ≠ 有效样本**：样本另有下限 `MIN_SAMPLE_HPA = 0.2 hPa`。门限必须低于任何真实重按，
  所以它必然低于噪声一个数量级；拿「越过门限」当样本，`(min+median)/2` 会把阈值钉到 0.2 hPa 以下，
  打字/走路就能翻页且**没有任何失败提示**。达不到下限的按压照参考实现剔除（次数照算，页面提示
  「这次太轻」），于是「有效样本不足 3 个」在真机上真的会发生（这是对的：宁可还用手动预设）。
- 校准期间**不翻页**（`FanPressureDetector.calibrationSink` 非空时只报按压），扇形顶部显示「气压校准 n/5」；
  这段时间气压计采样从 ≈15Hz 提到 ≈50Hz（15Hz 的 66ms 帧距会漏掉「点一下就走」的快按）。
- 阈值只有一个来源：`RemotePrefs.pressureThresholdFor`（校准值优先，否则灵敏度预设）。校准失败**不丢**
  上一次的校准值（参考实现会把手势打回默认并失效，这里选择不破坏已有结果）。
- **会话由 prefs 里的 `fan_pressure_calibration_active` 表示**，热重载/重启系统界面后会**自动接着采集**
  （`FanHost` 的 INIT_RESUME + `CornerInputMonitor.applySettings` 的补建），不会卡在「校准中」。
- **回程两条通道**（用户 2026-10-13 报「按满 5 次后页面仍显示取消校准」之后改的）：主通道是
  `ContentResolver.call()` → `PressureCalibrationProvider`（同步、有回执，身份用 `Binder.getCallingUid()`
  判定），广播降级为兜底（它的发送方身份 `sentFromUid/sentFromPackage` 在清单接收者上不保证有值，
  拿不到就整条通道静默全拒 —— 那正是这次症状的来源）。终局事件送出后 2s 检查回执（App 会把
  `active` 写成 false），没落地就重投一次；另外设置页打开时会自愈「已按满 5 次却还在会话中」的卡死。
  诊断页会写明投递结果（`via=provider` / `via=none` / 已重投），日志关键字
  `PRESSURE_CALIBRATION_SENT`、`PRESSURE_CALIBRATION_RESULT_RETRY`。

- [ ] 设置 → 扇形面板外观 → 「开始校准（实按 5 次）」→ 说明文字变成「校准中 0/5…」
- [ ] 从屏幕下角划出扇形：顶部出现「气压校准 0/5 · 在扇形空白处重按」
- [ ] 在扇形空白处重按一次：提示变成 1/5（不用抬手，同一次划出里可以连按 5 次）
- [ ] 按满 5 次：扇形提示消失，回设置页看到「已校准：5 个有效样本，阈值 x.xxx hPa」**且按钮变成
      「重新校准」**（不再是「取消校准」）；诊断页那一项要写着投递通道（`via=provider` 或 `via=broadcast`）
- [ ] 此时「重按灵敏度」下拉变灰并提示已使用校准值；点「清除校准结果」后回到可选的预设
- [ ] 校准后重按翻页仍然灵（阈值应当**低于**最轻的一次真实按压）
- [ ] 故意轻点 5 次：页面出现「这次太轻（只升高 x.xxx hPa，至少要 0.2）」，最后报「有效样本不足 3 个」，
      **不会**给出一个 0.2 hPa 以下的阈值（那种阈值下打字/走路都会翻页）
- [ ] 校准中途 `adb install -r`（LSPosed 热重载）：会话自动接着采集，页面不会一直停在「校准中」
- [ ] 中途取消：点「取消校准」后立刻停止采集（日志 `PRESSURE_CALIBRATION_CANCELLED reason=REQUEST`），
      原有阈值不变
- [ ] 无气压计机型：点开始后提示「本机没有可用的气压计」（日志 `PRESSURE_CALIBRATION_UNSUPPORTED`）
- [ ] 诊断页会出现「气压重按校准」一项，成功 PASS、样本不足 WARN（读 `PRESSURE_CALIBRATION_FINISHED`）

没有真人按压也能验完整条链路（adb，只收 shell/root）：
```bash
adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PRESSURE --es cmd start
adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PRESSURE --es cmd sample --ef delta 0.42
adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PRESSURE --es cmd finish
adb logcat -s BubbleDrawer | findstr PRESSURE_CALIBRATION
```
日志里应当依次出现 `PRESSURE_CALIBRATION_STARTED` → `PRESSURE_CALIBRATION_SAMPLE` →
`PRESSURE_CALIBRATION_FINISHED`，App 侧出现 `PRESSURE_CALIBRATION_EVENT stage=…`（八项 prefs 一次写完）。

## A25 功能页（快捷方式）与「已选功能」
管理页（顶部「更多应用」，或扇形面板右上角的列表按钮）现在有两个页签：**应用** / **功能**。
「功能」是从系统读出来的快捷方式（微信扫一扫、支付宝付款码、应用自己声明的动态/静态快捷方式、
桌面长按固定到桌面的捷径），点一下加入「已选功能」。已选的功能会出现在**两个地方**：

1. **扇形（转盘）本身**（用户 2026-10-14 明确要的就是这里）：与应用图标同排，排在收藏应用之后，
   顺序 = 点选顺序；扇形的 tile 没有文字，所以**图标就是全部信息** —— 优先用快捷方式自己的图标
   （来自最近一次枚举结果），拿不到才退回宿主应用图标（那一轮会顺带在后台补一次扫描，下次划出
   就有真图标）。点它按 `ShortcutRepository` 的两条路启动（LauncherApps 的 ShortcutInfo 或
   Intent），小窗开关跟随设置。
2. 「更多」浮层顶部的「已选功能」区（那里有名字与宿主小图标）。

数据从哪来（决定了"页面空着"该怪谁）：
- **枚举在 SystemUI**：只有它有 `ACCESS_SHORTCUTS`（LauncherApps 的全量动态/固定/静态快捷方式）。
  App 进程没有这个权限，读到的是 SystemUI 上报后落盘的缓存。
- **上报走 ContentProvider**（`com.repl.bubbledrawer.shortcuts`，`ContentResolver.call`，带
  `scan_id`/分片序号/**每片回执**）。分片按**字节**切（每片 ≤200KB），不是按条数 —— 25 个 160px 的
  PNG 图标 Base64 之后就能超过 Binder 的 1MB 总缓冲，按条数切照样爆，而爆掉只会被吞掉。
- 扫描有节流：结果缓存 30s、两次扫描至少隔 3s、内容一字不差就不重复上报（这条是"断环"的关键：
  App 存盘会通知页面，页面会请求重扫，不比对内容就是一个永不停止的全量扫描）。
- 缓存只在**内容真的变了**时通知页面；空清单只有 SystemUI 明确标注「这次扫完了」且**已解锁**时才
  会清缓存（否则开机枚举出来的空表会把好数据抹掉）。
- 「已选功能」与「收藏应用」同一套跨进程机制：hook 进程写不了远程组，所以面板里的编辑经广播/服务
  交给 App 进程落盘，两边按**时间戳**和解（值与戳一次 commit，避免监听者读到"新值 + 旧戳"）。
- 诊断页新增「功能页（快捷方式）」一项：区分「枚举到 0 条」「扫到了但没送到 App」「内容没变跳过上报」。

- [ ] 管理页顶部出现「应用 / 功能」两个胶囊页签，点「功能」能切过去（此时右侧字母条跟着换成功能分组）
- [ ] 功能页按**应用名**分组（小标题样式与 A-Z 一致，按拼音排序），每条显示快捷方式自己的图标与名称
- [ ] 点右上角"管理"→ 每个功能右上角出现 ＋/－ → 点一下加入「已选功能」，**页面上立刻生效**
      （连续点几个也不能出现"点了没反应、再点别的才刷新"）
- [ ] **划出扇形**：选中的功能作为 tile 出现在转盘上（在收藏应用之后），图标是快捷方式自己的
      （拿不到才是宿主应用图标）；点它直接启动 —— 这是本功能的主要落点
- [ ] 功能 tile 与收藏应用一起排页：`fanIconCount`（5 或 6）装满后翻页/重按翻页照旧，「更多」tile 仍在
- [ ] 取消一个功能的已选 → 再划出扇形，它不再占 tile（不需要重启系统界面）
- [ ] 顶部「已选功能」区显示刚选的功能；已选的功能**左下角带所属应用的小图标**
- [ ] 功能页的搜索框能按功能名/应用名过滤，进入搜索时列表回到顶部
- [ ] 打开扇形面板（划出扇形 → 更多）→ 顶部「已选功能」区能看到同一个功能，**点它能直接启动**
      （小窗开着时按设置里的"小窗启动"决定全屏还是小窗）
- [ ] 面板的「功能」页上点"管理"再点选一个功能 → 回到管理页，它也在「已选功能」里（跨进程同步）
- [ ] 管理页里取消一个功能 → 面板重新打开时它也不在已选里（反向同步）
- [ ] 面板里**不存在**加号：功能页右上角只有搜索与管理两个按钮（用户 2026-10-13 要求去掉；
      面板里点"管理"就能点选，不需要第二个入口）
- [ ] 读不到功能时（例如 LauncherApps 未授权）功能页显示「还没有读取到功能」，不是白屏；
      但**已选过的功能仍然显示在顶部**（App 会把它们的名称/宿主/启动 Intent 一起发布过来，
      面板不依赖自己的枚举结果；日志 `FUNCTION_DETAILS_CHANGED` / `FUNCTION_DETAILS_PUBLISHED`）
- [ ] 卸载一个提供快捷方式的应用（或清除它的快捷方式）后重新打开功能页：它的条目不再出现
- [ ] 诊断页「功能页（快捷方式）」写着枚举条数与耗时；若显示"没能交给设置应用"说明投递链断了

没有 adb 也能确认链路（诊断页读的就是这些行）：
`SHORTCUT_SCAN reason=… items=N result=SENT|EMPTY|UNCHANGED|FAILED ms=…`、
`FUNCTION_WRITE_HANDOFF broadcast=… service=…`、`PIN_SYNC_REJECTED_SENDER`（有应用冒充 App 写收藏）。

## 贴边模式（可选，root）
- [ ] 设置页"贴边模式"：无 su → toast 需要 root；有 su → inset=0，正上滑角落也能出扇

## A26 轻量打开（六开关）

前置：`scope.list` 已含 `system`；**第一次加作用域要重启一次手机**（作用域由 LSPosed 在开机时应用）。
之后**改代码只 `adb install -r` 即可**：SystemUI 与 system_server 都会热重载、钩子自动重建
（见 docs/light-open.md §4）。设置页 →「轻量打开」→ 打开总开关。

日志（`adb logcat -s LSPosedFramework:V MiuiMultiWindowUtils:D MiuiFreeformServiceImpl:D`；
模块行都带 `LSPosedFramework` 前缀）：

- [ ] 装载行齐全：`LIGHT_OPEN_GATE_INSTALLED enabled=… jump=… share=… text=…`、
      `LIGHT_OPEN_NOTIFICATION_HOOK_INSTALLED path=injected` / `path=island`、
      `LIGHT_OPEN_NOTIFICATION_ARM_HOOK_INSTALLED params=7`、`LIGHT_OPEN_CONTROL_CENTER_HOOK_INSTALLED`、
      `LIGHT_OPEN_TILE_VIEW_HOOK_INSTALLED`、`LIGHT_OPEN_ARM_PENDING_INTENT_INSTALLED count=2 intent=3`、
      `LIGHT_OPEN_OPTIONS_HOOK_INSTALLED`、`LIGHT_OPEN_SEND_HOOK_INSTALLED overloads=…`、
      `LIGHT_OPEN_CONTEXT_LAUNCH_HOOK_INSTALLED`、`LIGHT_OPEN_ISLAND_ANIM_HOOK_INSTALLED`
      （打 `*_TARGET_UNAVAILABLE` / `*_CLASS_MISSING` = ROM 改了类名/字段名，钩子没装上）
- [ ] 「不用重启」验证：`adb install -r` 后应看到 `HOT_RELOADED process=system` 紧跟
      `LIGHT_OPEN_GATE_INSTALLED …`，此时直接触发跳转就能生效

### 通知（SystemUI 侧）
- [ ] 开「竖屏点通知」：竖屏**通知栏列表**点一条通知 → 应用小窗打开；
      日志 `LIGHT_OPEN_NOTIFICATION_ARM_ENTER pkg=<发通知的应用> … decision=FORCE_FREEFORM`
      与 `LIGHT_OPEN_OPTIONS_APPLIED source=notifPublic`
- [ ] **悬浮通知/灵动岛**：等一条横幅/灵动岛通知出现，点它 → 同样小窗；
      日志 `LIGHT_OPEN_ISLAND_ANIM_APPLIED` 或 `LIGHT_OPEN_SEND_APPLIED n=1`
      （栈里应能看到 `DynamicIslandWindowAnimController.openAppUseDefaultAnim`）
- [ ] 关掉竖屏、开「横屏点通知」：横屏点通知 → 小窗；竖屏点 → 仍全屏
- [ ] 通知归属正确：点第三方应用的通知时，日志里 `pkg=` 是**发通知的那个应用**，
      而不是 `android` / `com.android.systemui`（MIUI 会用自己的 PendingIntent 转手）
- [ ] **误伤检查**：在应用内点一个链接（如 Gemini 里点链接）→ 保持全屏，日志没有 `LIGHT_OPEN_FREEFORM`
- [ ] **下拉小窗不受影响**（2026-10-23 用户报）：开着「竖屏点通知」时，在通知栏把一条通知**下拉** →
      系统自带的下拉小窗正常打开；日志出现 `LIGHT_OPEN_SKIP_MINI_WINDOW` 或
      `LIGHT_OPEN_OPTIONS_SKIP_MINI_WINDOW`，且不应出现 `LIGHT_OPEN_OPTIONS_APPLIED`
      （我们的点击 bundle 不能盖掉 ROM 自己的下拉小窗 options）

### 控制中心（SystemUI 侧）
- [ ] 开「长按磁贴」：控制中心长按 WiFi / 蓝牙（原生磁贴）→ 设置页小窗打开；
      日志 `LIGHT_OPEN_CONTROL_CENTER_ENTER tile=… target=…` + `LIGHT_OPEN_OPTIONS_APPLIED source=post.intent/…`
- [ ] 长按**插件磁贴**（小米自己的磁贴，如小米互传/扫一扫）→ 同样小窗；
      日志 `LIGHT_OPEN_TILE_VIEW_ENTER tile=… target=…`
- [ ] 目标不在 MIUI freeform 名单里时（如「设置」）应看到 `LIGHT_OPEN_FALLBACK_OPTIONS pkg=com.android.settings`
      且仍然小窗（兜底路径生效）
- [ ] 锁屏上长按磁贴 → 走 MIUI 自己的解锁流程（不抢跑、没有异常小窗）
- [ ] 关掉总开关 → 通知/磁贴都回原生行为（日志里不再出现 `LIGHT_OPEN_*`）

### 分享 / 文本操作 / 应用间跳转（system_server 侧）
- [ ] 开「分享至应用」：任意应用分享 → 选目标 → 小窗；日志 `why=share`
- [ ] 开「文本操作」：选中一段文字/链接 → 系统编辑条里点识别出的条目（打开链接/地图/电话等）
      → 小窗；日志 `LIGHT_OPEN_TEXT_PROBE … fromClassifier=true` + `why=text`
      （若这里不生效，先看探针行：`fromClassifier=false` 且 `extras=[]` 说明该路径不带
      TextClassifier 标记，需要再找判据）
- [ ] **文本操作不误伤**：应用内普通点链接（`ACTION_VIEW`、无 TextClassifier 标记）→ 全屏
- [ ] **Gemini 蹦床**：桌面点 Gemini 图标 → Gemini 拉起 Google 应用 → 全程全屏
      （即使黑名单模式/全局小窗也不该动它）
- [ ] 白名单模式 + 名单放微信：从别的应用跳微信 → 小窗（`why=list`）；跳其它应用 → 全屏
      （日志无 `FREEFORM` 行）
- [ ] 黑名单模式 + 名单放微信：跳任意其它应用 → 小窗；跳微信 → 强制全屏
      （`LIGHT_OPEN_FULLSCREEN target=com.tencent.mm`）
- [ ] **桌面护栏**：回到桌面，点任意图标 → 正常全屏打开，日志**没有** `LIGHT_OPEN_FREEFORM`
      （前台包是 `com.miui.home` 时必须放行）；最近任务卡片、负一屏同理
- [ ] **同应用护栏**：在一个应用内部翻页/开子页面 → 不受影响
- [ ] **空前台护栏**：锁屏状态下由后台拉起应用 → 不干预
- [ ] **安全中心拦截框**：出现"某应用想打开某应用，是否允许"时 → 弹窗本身保持全屏，
      点「允许」之后那一次启动才按名单判定
- [ ] **不可小窗的应用**：把某个 `resizeableActivity=false` 的应用放进白名单 → 仍全屏，
      日志有 `MiuiMultiWindowUtils: pkg name: X unsupported freeform mode`（这是预期，不是 bug）
- [ ] 开关即时生效：不重启、不重装，在设置页改开关/名单 → 下一次跳转立刻按新配置判定

## A27 扇形：双击外部关闭（2026-10-17）

入口：设置 → 扇形面板外观 → 「双击外部关闭」（默认**关**）。

- [ ] 开关关（默认）：扇形开着 → 点空白处**一下** → 收起；日志 `FAN_VOID_TAP_CLOSE mode=single`
- [ ] 开关开：点一下 → **不收起**（日志 `FAN_VOID_TAP_FIRST mode=double`）；系统双击时限内再点一下
      → 收起（`FAN_VOID_TAP_CLOSE mode=double`）
- [ ] 两下间隔超过双击时限 → 只记第一下，不会误收
- [ ] 热生效：改完直接划出扇形即可，不用重启系统界面（值走共享组，扇子每次读取）
- [ ] 与「更多面板」那一项无关：面板仍按它自己的「单击/双击收起」设置走

## A28 收藏应用管理里点应用 → 目标以小窗打开（2026-10-17 修复）

**用户报告的 bug**：主页 → 收藏应用管理 → 点应用 → 目标**没有**小窗，反而**魅窗自己**变成了小窗；
当时安全中心还弹了「魅窗想要打开 xx，是否继续」。

**成因**：App 进程直接带 MIUI 的 freeform `ActivityOptions` 是走不通的 ——
`MiuiMultiWindowUtils.getActivityOptions` 先过 `checkAuthority()`，第三方调用者拿到 null，
手里只剩一个裸的 `setLaunchWindowingMode(5)`；这次启动还要再过系统的「应用间跳转」判定与安全中心
拦截框，结果那次 freeform 请求落到了**调用者自己**的任务上（魅窗变小窗），目标反而没进小窗。

**现在的链路**（与「应用间跳转」完全相同）：`GateLaunchStrategy` 只给 Intent 加一个标记
`com.repl.bubbledrawer.extra.FREEFORM_REQUEST`，不带任何 options → system_server 的闸门
（`LightOpenGate`，钩 `MiuiFreeformServiceImpl.startActivityByFreeForm`）看到标记返回 true →
ROM 自己用 `MiuiMultiWindowUtils.getActivityOptions(ctx, 目标包, true)` 落窗。与扇形面板
（SystemUI 身份）走的是同一条道。

- [ ] 「小窗启动」开：主页 → 收藏应用管理 → 点应用 → **目标应用**以小窗打开，
      **魅窗保持原样**（它自己绝不该变成小窗）
- [ ] 日志：App 侧 `LAUNCH_GATE_REQUEST pkg=…`；模块日志（system_server 侧）
      `LIGHT_OPEN_FREEFORM_REQUEST target=… top=…`
- [ ] 安全中心弹「魅窗想要打开 xx」→ 点「允许」之后那一次启动**仍然是小窗**
      （标记跟着 Intent 走）；拦截框本身**绝不**变小窗
- [ ] 目标不支持小窗（`resizeableActivity=false`，例如「设置」）→ 仍然全屏（ROM 的判定，不是 bug）
- [ ] 「小窗启动」关：点应用 = 普通全屏打开，日志里连 `LAUNCH_GATE_REQUEST` 都不出现
- [ ] 功能页里点一个**已选功能**（快捷方式）→ 同样小窗（`launchShortcut(…, viaGate = true)`）
- [ ] 扇形「更多」面板里点应用 → 行为不变（那里是 SystemUI 身份，走 `FreeformLaunchStrategy`）
- [ ] 该启动不会把魅窗自己弄成小窗、也不会留下"半截"任务：`adb shell dumpsys activity activities`
      里只应有目标应用那一个小窗任务

## A29 收藏应用管理 / 扇形「更多」页：收藏那一块要能上下滑（2026-10-17 修复）

**用户报告的 bug**：「收藏的应用那一块不响应上下滑动手势」，另外「把上滑淡出删掉」。

**成因**：顶部收藏区（应用 + 已选功能）是**盖在 Pager 上面**的一层（视觉上是可折叠的表头），
它自己不是滚动容器 —— 手指落在上面时，下面那个 `LazyVerticalGrid` 根本收不到手势。
收藏一多，这一层就把可视区盖满：先盖掉 TabRow，再盖掉 A-Z 列表，于是整页滑不动。
面板里最早暴露（窗口只有屏高的 62%，几行收藏就盖满了）；主页的收藏应用管理页同一个问题，
只是要再多几行才盖满。

**改法**：把落在收藏区上的竖直拖动接回**同一条**滚动链 —— 收藏区那个 `Box` 自己挂一个
`scrollable`（状态 = 当前页那个网格）+ 顶栏的 `scrollBehavior`，于是外层 `nestedScroll` 照常先
折叠收藏区、剩下的再滚网格，fling 也一并接上；长按排序进行中把 `scrollable` 关掉
（`enabled = !drag.isDragging`），拖动排序优先。

**上滑淡出**：`pinsAlpha`（`alpha = 1 + headerOffset/height`）整个删掉，收藏区只平移、全程不透明；
完全折叠那一刻它正好落在顶栏下沿（顶栏开模糊时是透明的），所以那里做**硬切**不再画，
避免"顶栏后面透出残影"。

- [ ] 主页 → 收藏应用管理：收藏**超过一屏**（例如 20 个）→ 在**收藏区上**上下滑 → 整页跟着滚
      （先折叠收藏区，再滚 A-Z 列表）；松手有惯性
- [ ] 扇形「更多」面板（62% 窗口）里同样操作 → 一样能滑（这是最早暴露的地方）
- [ ] 从收藏区往下滑（回滚）→ 列表先回到顶，再继续下滑 → 收藏区平滑展开、重新出现
- [ ] 收藏区**不再淡出**：上滑时它是整体平移出去的，图标不会半透明
- [ ] 折叠到位后顶栏后面**没有**收藏区残影（它已经不在绘制列表里）
- [ ] 滚动条/顶栏：从收藏区滑动时顶栏的模糊与收起照常跟随（不是只在网格区域滑才动）
- [ ] 长按拖动排序仍然正常（拖动时不会变成滚页）：长按某一格 → 放大 → 拖动换位 →
      让位动画照旧（回归 A6 的那三条）

## A30 实验性功能：「回桌面不收迷你」（2026-10-17 新增）

**用户要的**：「目前上滑时会把所有小窗变为迷你小窗……在实验性功能里加一个“回桌面不收迷你”的开关」
（"上滑" = 全面屏手势上滑回桌面）。

**这是 ROM 的行为，不是模块的**：framework 发广播 `com.miui.fullscreen_state_change`
（`state=toHome` / `toRecents`）→ WMShell `MiuiFreeformModeSettingsObserver.FreeFormBroadcastReceiver:90-103`
→ `MiuiFreeformModeController.allFreeformFromFreeformToMini()` →（可能先等 100ms 给旋转让路）
`allFreeformFromFreeformToMiniInternal()`：**遍历 Z 序上每一个小窗**，跳过正在拖拽缩放/连续动画/
转场中/正在退出或进入/elude 动画的，其余逐个 `fromFreeformToMini(taskId)` —— mode 1 + 迷你缩放 +
贴到上方左/右角 + `startGestureAnimation(11, …)`。这就是"所有小窗一起变迷你"的来源。
（模块自己的「小白条上滑 → 迷你小窗」只作用于那一个窗口，走的是同一层的 `fromFreeformToMini(int)`。）

**改法**：模块在系统界面进程钩住那两个**无参**入口（`FlymeFreeformController.hookHomeKeepFreeform`），
开关打开时不 proceed，并记 `FREEFORM_HOME_KEEP_SKIPPED via=…`。只拦"批量"这一层 ——
模块的单窗口路径、「从最近任务以小窗打开」（`startEnterMiniShellTransition(…, "fromRecent")`）都不受影响。
与 Flyme 样式轻量小窗总开关**无关**（和「禁止小窗偏移」同性质：压的是 ROM 自身行为，原生小窗也应生效）。

取证（模块日志或 `adb logcat -s BubbleDrawer`）：
`HOOK_INSTALLED_MiuiFreeformModeController.allFreeformFromFreeformToMini hooks=2`
（两个入口都装上），开关打开后再上滑回桌面应出现 `FREEFORM_HOME_KEEP_SKIPPED via=…`。

- [ ] 开关**关**（默认）：开两个小窗 → 上滑回桌面 → 两个都变迷你小窗（现状，作对照）
- [ ] 开关**开**：同样操作 → 小窗**保持原样**（大小与位置都不动，桌面正常回去），
      日志出现 `FREEFORM_HOME_KEEP_SKIPPED`；不再出现 `allFreeformFromFreeformToMini`
- [ ] 开着开关上滑**悬停**进最近任务 → 同样不再收迷你（`toHome` 与 `toRecents` 共用一个入口，一起拦）
- [ ] 开着开关、且「小白条上滑转为迷你小窗」开着：在某个小窗的小白条上滑 → **该窗口**仍然变迷你小窗
      （单窗口路径没被误伤）
- [ ] 开着开关，从最近任务把某个应用**以小窗打开** → 仍正常进入小窗（`fromRecent` 那条路径不受影响）
- [ ] 关掉开关立刻生效（不用重启系统界面）：值走共享组，钩子里每次读取
- [ ] 体检页「回桌面不收迷你 hook」= PASS；若为 WARN，说明这台 ROM 里没有那两个入口，
      开关不会生效（那台 ROM 本来也不做批量迷你化）
- [ ] 开/关 Flyme 样式轻量小窗总开关，本项行为不变

## A31 实验性功能：「迷你小窗长按菜单」的三个修复（2026-10-18 新增）

**用户要的**：「另一个 ai 参考 flyme 小窗内的功能和……做出了迷你小窗长按菜单功能，但目前有点小问题，
比如移动的时候大迷你小窗状态可能会重置，有时候会丢动画，静音后使用其他方式开启声音不会自动切换
按钮状态等」。

**菜单是什么**：长按迷你小窗（`MiuiFreeformModeTouchState.handleMotionEvents` 里 300ms 计时）→
在小窗表面（`SurfaceControlViewHost`，`FlymeMiniMenuOverlay`）弹出两个按钮：

- 静音 / 取消静音（`MiniAppAudioHelper`：`AudioManager.getPlayerVolume/setPlayerVolume` 为主，
  `OP_PLAY_AUDIO` 兜底）；
- 放大 / 还原（`FlymeFreeformController.toggleMiniWindowScale`）。

开关在设置 → 实验性功能 → 迷你小窗（悬浮窗）（`flyme_mini_menu`，默认开）。

### 问题 1：移动时「大迷你」状态会重置

原实现是模块自己给 leash 设 scale/position、跑一个 `ValueAnimator`。但那条 leash 的主人是 WMShell：
拖动的缩放取自 `MiuiFreeformModeTaskInfo.setDownParams:1167-1184`（`getDownScaleX` 就是
`baseAnimTarget.scaleX`），我们只改 SurfaceControl 不改 `baseAnimTarget`，于是第一次拖动就被 ROM
按迷你 scale 重算回来 —— 看起来就是"放大状态重置了"。

改法：不再自己动 leash，改调 ROM 自己的迷你动画入口
`MiuiFreeformModeAnimation.startGestureAnimation(11, taskInfo, target)`（`applyFreeformToMiniAnimation:2348-2406`）。
它做三件事：Folme 从 `baseAnimTarget` 当前几何动画到 target 几何；下发
`MiuiFreeFormInfoChange.setMiuiFreeformScale(target.scale)` + `setBounds(token, …)`
（`MiuiFreeFormManagerService.setFreeformTaskChangesIfNeed:3221-3222` → `Task.setMiuiFreeformScale`）；
动画结束时 `MultiTaskingFolmeAnimExecutor:123-126` → `MultiTaskingFolmeControl.updateBaseAnimParam:519-525`
把最终 scale **写回 `baseAnimTarget`**。放大状态从此属于 ROM，之后的拖动/松手/避让/翻转都按它算。

目标几何（放大多少、往哪边长）是纯逻辑 `launch/MiniScaleToggle.kt`（有单测 `MiniScaleToggleTest`）：
1.5 倍，「窗口靠哪边就往哪边长」，永远夹在屏幕内。

### 问题 2：有时候会丢动画

同一根因的另一面 —— 两条线程往同一条 leash 提交事务（模块的 `ValueAnimator` 在 main，
ROM 的 Folme 在 `wmshell.*`），谁后提交谁生效，动画就"没播"。现在动画只有 ROM 那一条。
另外修了一个会吞掉菜单的竞态：退出动画（120ms）里又弹出新菜单时，旧的回调会把新菜单的
surface 一起拆掉；`FlymeMiniMenuOverlay.dismiss` 现在先把 host/surface 摘出来再动画，只拆自己那一份。

### 问题 4：应用没在出声时，静音按钮按了不起作用（2026-10-18 第二轮）

静音靠 `setPlayerVolume(config, 0)`，它要一条**活跃的** `AudioPlaybackConfiguration`；应用没在播时
没有这条通道，`OP_PLAY_AUDIO` 那条兜底又不一定有权限（`MANAGE_APP_OPS_MODES` 一般只给 Settings），
于是那一次点击什么都没发生 —— 连图标都不变，看着就是个死按钮。

现在把「静音」拆成**意图**和**事实**（纯逻辑 `launch/MiniMuteState.kt`，7 条单测）：

- 按下静音 → 记下意图，图标立刻显示"已静音"（`MiniMuteState.requestMute`）；
- 应用此刻没出声 → 注册一次 `AudioManager.registerAudioPlaybackCallback`，等它**真的开始播声音**
  时在回调里把该 uid 的播放音量压到 0（`MiniAppAudioHelper.playbackCallback`，日志
  `pending mute applied`），然后意图转成事实、取消监听；
- 意图**生效过**之后事实优先：别处把声音开回来，图标跟着变回"静音"（不会退回旧版的永久已静音）；
- 意图**还没机会生效**时，即使读到的音量不是 0 也继续显示"已静音"（用户的意思没变）。

顺带（同一轮用户要求）：「点静音按钮就把面板收掉」，和「放大」一致 —— `FlymeMiniMenuOverlay`
在点击回调末尾 `dismiss(animate = true)`。

另外把 AppOps 兜底改成**两个签名各自独立尝试**：以前万一 `setMode` 抛 SecurityException，就直接
返回失败，后面的 `setUidMode` 根本没跑过。

### 放大倍率滑块（2026-10-18 新增）

设置 → 实验性功能 → 迷你小窗（悬浮窗）→「放大倍率」，**100%–250%**（2026-10-24 从 110%–200% 放宽），
默认 **150%**（= 原来的 1.5 倍）。存的是百分数（`flyme_mini_enlarge_pct`），模块每次点「放大」现读
（改完不用重启系统界面），倍率仍受 `MINI_SCALE_MAX = 0.95` 与屏幕夹取约束。几何换算见
`MiniScaleToggle.miniEnlargeRatioFromPct`。

**保存放大状态（2026-10-24 新增）**：点过「放大」的应用会按包名记进
`flyme_mini_enlarged_packages`（`updateMiniEnlargedPackages`）；`MiuiMultiWindowUtils.getMiniFreeformScale`
的 hook 在 ROM 每次计算迷你尺寸时把倍率乘进去，所以拖动、上滑回桌面、从最近任务直接开迷你窗等路径
都会一进迷你就是大的。点一次「还原」会把这个包名移出集合；聊天记录/应用数据不受影响。

取证（模块日志 / `adb logcat -s BubbleDrawer`）：

```
TOGGLE_MINI_SCALE taskId=… enlarged=true scale=… left=… top=…
TOGGLE_MINI_SCALE_FALLBACK reason=no_geometry|no_animation taskId=…
MINI_ENLARGE_SAVED pkg=… enlarged=true total=…
MINI_ENLARGE_SCALE pkg=… normal=… pct=… used=…
MINI_MUTE taskId=… pkg=… uid=… from=false to=true applied=true
Mute icon pkg=… muted=true enlarged=false
pending mute applied key=… uid=… volume=0
release pkg=… restore=1.0 volume=true appOps=true
```

`FALLBACK` = 这台 ROM 的接口对不上，已退回 ROM 自带的「迷你 → 普通小窗」
（`RESTORE_MINI_TO_FREEFORM`），点击仍有结果。

- [ ] 长按迷你小窗 ~300ms → 菜单从小窗里长出来（有一次触感），松手不会触发单击
- [ ] 点「放大」→ 窗口在**原位置**变大（默认 1.5 倍，右上角的小窗向右上长、左上角的向左上长），
      不跳回屏幕中央；再点一次（图标是"还原"）回到正常迷你尺寸，仍贴着原来那个角
- [ ] 放大后**拖动**它：拖动过程中不闪、松手仍是放大尺寸（不再是"一拖就变回迷你"）
- [ ] 放大后拖动到屏幕边缘 / 上滑悬停进最近任务再回来 / 转屏再转回：尺寸保持
- [ ] 连点两次「放大」：第二次打断第一次，最终停在正确尺寸（不会两个动画打架）
- [ ] 把「放大倍率」拖到 100% 与 250% 各试一次：100% 等于正常迷你，250% 明显更大；极高倍率仍受
      `MINI_SCALE_MAX = 0.95` 与屏幕夹取兜底；改完立刻点「放大」就生效，不用重启系统界面
- [ ] 点「放大」后把窗口还原成普通小窗，再重新上滑/回桌面收成迷你：**仍然是大的**（不用再点一次放大）；
      点一次「还原」后再收起：回到正常迷你尺寸
- [ ] 放大状态下关掉迷你小窗再从桌面重新打开并收成迷你：尺寸按保存的倍率（日志 `MINI_ENLARGE_SCALE`）；
      换另一个没放大过的应用收迷你：仍是普通迷你（不会串到别的应用）
- [ ] 点「静音」：应用声音消失、图标变"取消静音"，**面板随即收起**
- [ ] 应用**暂停/没在出声**时点「静音」：图标立刻变"取消静音"（不再是没反应的死按钮），
      然后让它开始播（点播放）：**一开始就是静音**（日志 `pending mute applied`）
- [ ] 静音后用**音量键 / 音量面板**把声音开回来 → 再长按呼出菜单，图标应显示"静音"
- [ ] 静音状态下关掉迷你窗口 → 从桌面重新打开同一个应用：声音正常（不是被静音的死状态）
- [ ] 长按另一个迷你小窗：菜单跟到那个窗口；点空白处关闭它，新菜单不会被一起拆掉
- [ ] 关掉「迷你小窗长按菜单」开关：长按无菜单，迷你小窗的拖动/单击与 1.3.3 一致
      （倍率滑块此时是灰的）
- [ ] 体检页原有各项仍 PASS（本项未新增体检判据，参考日志 `HOOK_INSTALLED_hookMiniTouchForMenu_complete`）

## A32 联系人条改成 SurfaceControl（用户 2026-10-18 的重构复查）

用户原话：「另一个 ai 重构了联系人条，现在它作为一个视图插进小窗，你检查一下有没有破坏原功能，顺便改进」。

### 改了什么

以前条是一个 **`WindowManager` 窗口**（`TYPE_APPLICATION_OVERLAY`，A19 里为了把图层调低才选它），
每帧 `updateViewLayout` → 同步 binder 到 `system_server` 的 WMS 重新摆窗口 —— 比 WMShell 自己
（同一个进程里的 Folme 动画）晚 1~2 帧，拖动时条会"甩"在窗口后面。

现在是一块挂在 **`TaskDisplayArea`** 下的 `SurfaceControl`（`RootTaskDisplayAreaOrganizer.attachToDisplayArea`，
和黑色遮罩同一套机制）+ `SurfaceControlViewHost` 托管的视图，位置/Z 序每帧在本进程里改完就生效。

| 项 | 以前 | 现在 |
|---|---|---|
| 摆放 | `updateViewLayout`（binder → WMS） | `Transaction.setPosition`（本地，同一帧） |
| 宽度/高度 | 窗口 layout | `SurfaceControlViewHost.relayout` + `setCrop` |
| Z 序 | WMS 的窗口 layer（`APPLICATION_OVERLAY` = 151000） | `setRelativeLayer(sc, 窗口 leash, +1)` |
| 隐藏 | `removeViewImmediate` | `Transaction.setVisibility(false)`（不绘制也不收触摸） |
| 触摸 | WMS 投递 | WWM 的 input channel —— 和迷你小窗长按菜单（A31）同一条路 |

**Z 序那一条要单独说**：条跟着**窗口的 leash** 排 Z，而不是一个固定的绝对图层。通知中心/控制中心
是整个图层层级之上的窗口，所以条照旧"该被盖住时被盖住"（A19 的用户要求没变），同时永远在小窗之上
（不会被窗口本身压住）。拿不到 leash 时退到 `LAYER_WITHOUT_LEASH`（显示区子图层里的高位），
也不会压住面板。

### 逐个功能对照（原功能有没有被破坏）

| 原功能（A8/A19/A21 的判据） | 现在靠什么 | 会不会退化 |
|---|---|---|
| 宽度 == 小窗宽度、贴窗口下沿、逐帧跟手 | 每帧从 `MiuiFreeformModeTaskInfo` 现读几何 → `setPosition` | 不会，反而少 1~2 帧延迟 |
| 键盘弹出让位 / 让不开就隐藏 | `layoutFor` 完全没动（`isImeShowing`/`imeHeightPx`） | 不会 |
| 迷你/贴边不显示 | 模式闸门没动，隐藏方式换成 `setVisibility(false)` | 不会 |
| 圆角跟小窗一致 | `getCornerRadius()` → `ContactBarView.setCornerRadius` | 不会 |
| 会话记忆 / 去重 / 群名 / 上限 | `RecentConversations` 的并入逻辑没动（只改了标题闸门，见下） | 见下面第 9 条 |
| 左右滑动 / 上滑移除 / 点击打开会话 | `ContactBarView` 一行没改，触摸流由 WWM 投递 | **需实测**（唯一换了投递方的东西） |
| 通知中心/控制中心盖住条 | leash 相对的 Z 序 | 换成新机制，需实测 |
| 空闲 250ms 采样、动起来逐帧 | 帧循环没动 | 不会 |
| 热重载后条还在 | `dispose()` → `destroySurface()` 重建 | 需实测 |

### 复查时修掉的问题（都是"会崩 / 永远不出现"，不是审美）

1. **`added = true` 被无条件置上**：事务失败也标记"已显示"，之后 `update()` 只改位置不再 attach ——
   条会**永久不出现**，而且日志看起来一切正常。现在只有 `apply` 成功才算显示，失败拆掉半成品，
   日志 `CONTACT_BAR_HIDE reason=TRANSACTION_FAILED`。
2. **`update()`/`checkLeash()` 里的 transaction 没有 try**：surface 失效后（SurfaceFlinger 重启、
   显示区重建、旋转、系统界面重启）`setPosition` 会抛 `IllegalArgumentException`，而它跑在主线程的
   Choreographer 回调里 —— 那就是**系统界面崩溃**。现在失效即整块重建
   （`CONTACT_BAR_SURFACE_STALE` / `CONTACT_BAR_SURFACE_LOST`）。
3. **失败后每帧重试**：拖一个小窗就是 60 次/秒反射建 surface + 60 行日志。现在 500ms 退避
   （`ATTACH_RETRY_MS`）。
4. **`relayout(LayoutParams)` 这条路根本不存在**（`getMethod` 永远返回 null，白写），尺寸变化只剩
   `relayout(int,int)`；现在失败会退回 `setView`，再不行整块重建，不会留一个尺寸不对、内容被裁掉的条。
5. **`LayoutParams.token = Binder()`**：WWM 不是 `system_server`，随机 token 让每次改尺寸都像"另一个
   窗口"。已删除。
6. **`error("No display available")` / 构造器探测失败时 `error(...)`**：都跑在主线程，抛出去就是崩。
   现在返回 null + 一行 WARN。
7. **`FreeformTask.leash()` 每次读都 `getField`/`getDeclaredField`**（每次复制一遍字段表）：它现在进了
   每帧路径（排 Z 序），改成访问器按类缓存。
8. **数据层：`observeEntry` 是多余的** —— 每条通知先单独快照一次，紧接着 `onChanged` 里的
   `observe()` 又把全部通知快照一遍，同一件事做两次、`CONTACT_BAR_CONV_ACCEPTED` 每个事件打两遍
   （主线程日志 I/O）。删掉，改成一个会话只打一次的 `CONTACT_BAR_CONV_NEW`。
9. **「标题是应用名时从正文取发送者」这个新逻辑收紧**：只认 `发送者：消息` 的形状，取不到就**丢弃**
   这条通知；正文不能当名字（否则「你的微信已在电脑上登录」会变成一条叫这个名字的会话 —— 那正是
   标题闸门当初要挡的）。纯逻辑抽到 `contactbar/NotificationTitle.kt`，`NotificationTitleTest` 9 条。

### 已经实测过的部分（2026-10-18，只有这些）

改完用 `adb install -r` + `killall com.android.systemui` 装了本机跑过：

- 新包在**新的系统界面进程**里加载正常，没有崩（`CONTACT_BAR_HOOKS_INSTALLED pipeline=false`
  → 随后 `CONTACT_BAR_NOTIF_LISTENER_ATTACHED`，这是热重载那条路径的正常顺序）；
- 数据层新日志生效：`CONTACT_BAR_CONV_NEW pkg=com.tencent.mm label=Z【武汉纺织大学阳光校区】美团拼好饭群 title=…`
  （一个会话一行，不再每个事件两行 `CONV_ACCEPTED`）；
- 视图宿主的构造器自检：`CONTACT_BAR_VIEW_HOST_CTOR ok=true params=Context, Display, WindowlessWindowManager, String`
  —— 这台 ROM 上**只有四参那个**（第四个参数是 String），三参的公开构造器不存在。

### 一次真实的翻车：探测太窄 = 条永远不出现（2026-10-18）

复查时我把构造器探测收窄成"多出来的参数必须是 boolean"，而这台 ROM 一个都匹配不上 ——
用户当场反馈「联系人条不显示了」，日志是：

```
CONTACT_BAR_SURFACE taskId=32155 displayId=0 name=Surface(name=ContactBarSurface-32155#886)
CONTACT_BAR_VIEW_HOST_FAILED taskId=32155          ← 每 500ms 一次（退避重试）
No SurfaceControlViewHost(Context, Display, WindowlessWindowManager[, boolean]) in […]
```

教训：**这种跨版本的反射探测只能放宽，不能收窄**；挑不到时把候选列表写进日志。
现在的规则是「前三个参数对得上就行，其余参数按类型给默认值」，参数最少者优先；
`init` 里加了一次性自检 `CONTACT_BAR_VIEW_HOST_CTOR`，以后 ROM 换了能第一眼看出。
（它只是被主线程的 `runCatching` 兜住，所以那次没有崩，只是条不出现。）

**没测到**：条的 surface / 触摸 / Z 序 —— `am start --windowingMode 5` 在这台 ROM 上开出来的是
**全屏**窗口（`dumpsys window` 里 WeChat 是 `(0,0)(fillxfill)`），WMShell 的 freeform 仓库根本不认它，
所以拿不到真的小窗。下面这些只能用手势开小窗来测。

### 取证

```
CONTACT_BAR_SURFACE taskId=… displayId=0 name=…      ← surface 建起来了（挂在显示区下）
CONTACT_BAR_SHOW rect=… items=… leash=true relative=true
CONTACT_BAR_HIDE reason=MINI|UNFOCUSED|TOO_SMALL|…
CONTACT_BAR_SURFACE_STALE / _LOST                      ← 失效后自动重建（每次一行，不是每帧）
CONTACT_BAR_HIDE reason=SURFACE_UNAVAILABLE|NO_DISPLAY|VIEW_HOST_UNAVAILABLE|RELAYOUT_FAILED|TRANSACTION_FAILED
CONTACT_BAR_CONV_NEW pkg=… label=… title=…             ← 一个会话只出现一次
```

条不再是一个窗口，`dumpsys window windows` 里**不会**再有 `bubble contact bar`（A19 那条判据作废），
改看：

```
adb shell dumpsys SurfaceFlinger --list | findstr ContactBar   # 应该有一层 ContactBarSurface-<taskId>
adb shell dumpsys input | findstr "bubble contact bar"          # 输入窗口在 = 能点、能划
```

- [ ] 开一个聊天小窗：条出现在窗口正下方，宽 == 窗口宽（`CONTACT_BAR_SHOW … leash=true relative=true`）
- [ ] **点一个头像**会话在小窗内打开；**左右滑动**能横拖；**上滑头像**移除一个（`CONTACT_BAR_FORGET`）
      —— 触摸走的是 WWM 的 input channel，这是这次重构唯一换了投递方的东西
- [ ] 拖窗口移动/拉伸：条逐帧贴着（`CONTACT_BAR_GEOM` 里 `bar` 的 width == `visual` 的 width），
      不再"甩"在后面
- [ ] 下拉通知中心 / 拉出控制中心：条在**下面**（A19 的要求）；收起后照旧贴着窗口
- [ ] 上滑进迷你、恢复成普通小窗：条消失/回来（`reason=MINI` → `CONTACT_BAR_SHOW`）
- [ ] 关掉小窗（✕ / 上滑）：条立刻消失（`reason=CLOSING`），跟着的 `TASK_VANISHED` 之后不残留
- [ ] 两个小窗来回点：条跟着焦点换窗口，Z 序也跟过去（`relative=true`）
- [ ] 转屏两次（竖 → 横 → 竖）：条的位置/宽度都对，没有跑到屏幕外
- [ ] `adb shell stop && adb shell start`（或 `killall com.android.systemui`）之后重新开小窗：条能建起来
      （`CONTACT_BAR_SURFACE` 在新进程里出现一次）；surface 失效那次应能看到 `_STALE`/`_LOST` 并自愈
- [ ] 热重载（`adb install -r`）：`CONTACT_BAR_DISPOSED` 之后新进程里条照常出现，没有两层的条
- [ ] 标题是应用名的通知（QQ/微信/TIM）：`CONTACT_BAR_CONV_NEW` 里的 label 是人名才有意义；
      系统话术（登录提醒/版本更新）不应该冒出一个联系人
## A33 五个交互问题（用户 2026-10-18 一次报的一批）

### 1. 更多应用面板：tabrow 到顶后继续上滑，整页跟着往下走

**成因（按布局推出来的，不是猜的）**：收藏区展开会同时把 `HorizontalPager` 往下推
（`pagerY = 收藏区高度 + headerOffset`）。`onPostScroll` 以前只要"收藏区还有折叠量"就展开，不管列表
滚到了哪里；列表已经滚下去时那点残余位移属于上一笔手势的尾巴，展开一下整页就跟着沉一下。

**改法**：`onPostScroll` 只在该页网格**真的在顶部**（`firstVisibleItemIndex == 0 &&
firstVisibleItemScrollOffset == 0`）时展开收藏区，否则原样吞掉并打一行
`PINS_EXPAND_BLOCKED first=… off=… delta=…`（一秒最多一行）。A29 写的顺序本来就是"先回到顶，再展开"。

- [ ] 面板里在收藏区上滑：先把收藏区折叠、TabRow 吸顶，再继续上滑 → **只有列表在滚**，TabRow 不动
- [ ] 列表滚到中间时下拉 → 列表先回顶，到顶后继续下拉 → 收藏区才展开（`PINS_EXPAND_BLOCKED` 不出现在这一路）
- [ ] 主页的收藏应用管理页同样两条

### 2. 横屏：居中小窗改成只在竖屏生效

用户原话：「在横屏时打开其他 app 的小窗，上滑使其转变为迷你小窗，随后点击迷你小窗恢复为小窗时，
位置会出现在横屏的最中间，希望能做到从哪里来到哪里去。即居中小窗修改为仅对竖屏生效」。

`centerRectOnScreen` 现在一进函数就按屏幕方向判断：横屏直接返回、一个像素都不改
（日志 `CENTERED_BOUNDS_SKIPPED_LANDSCAPE rect=… screen=(W x H)`，去重一行），竖屏照旧。

**注意（设备实测证据）**：这台机器的实验性「Flyme 样式轻量小窗」总开关当前是**关**的，日志里每次都是
`CENTER_SKIPPED src=mini.getRestoredBounds enabled=false …` —— 也就是说**模块根本没参与摆放**，
横屏落在正中间是 ROM 自己的行为。这条改的是"模块居中"的规则；要让它生效得先打开那个总开关。
若打开后横屏仍然居中，那就是 ROM 的 `getRestoredBounds` 自己算的，需要另一套钩子（把还原矩形改回
窗口去迷你之前的 `taskBounds`），到时候按设备日志再定。

- [ ] 竖屏：迷你 → 点回普通小窗，仍然居中（`CENTERED_BOUNDS` 照旧出现）
- [ ] 横屏：同样的操作，窗口回到它迷你之前的位置，不乱跳（模块日志里出现 `..._SKIPPED_LANDSCAPE`）

### 3. 角落手势：手势黑名单 + 横屏两次上滑（用户 2026-10-18 新要求）

**手势黑名单**（设置主页 → 全局开关 → 「手势黑名单」）：选中的应用在前台时，角落手势什么都不做。
判定在 `FanHost.show` 的最前面（`suppressionReason`）：`FAN_SUPPRESSED reason=BLACKLIST side=… pkg=…`，
并且 `forward()` 把这一笔后续事件整体丢掉（`fanSuppressed`），否则扇形没画出来、dock 却在收事件。
前台包名来自 `ActivityManager.getRunningTasks(1)`；**取不到时不拦**（宁可多弹一次，也不要永久失效）。
落盘格式是换行分隔的包名（`encodeFanBlacklist`/`decodeFanBlacklist`，纯函数，`FanBlacklistCodecTest`）。

**横屏两次上滑**（同一个位置，「横屏时需滑动两次」）：横屏下第一次上滑不弹扇形，只提示一句
「再次上滑」（`FAN_TWO_SWIPE_FIRST side=…`），`FAN_SECOND_SWIPE_MS = 1600ms` 内同一侧再滑一次才打开
（`FAN_TWO_SWIPE_CONFIRMED side=… gapMs=…`）。竖屏不受影响；瞬时横竖判断用 `isWideScreen(screenW, screenH)`。

- [ ] 黑名单里勾一个应用 → 在那个应用里角落上滑：扇形不出现（`FAN_SUPPRESSED reason=BLACKLIST`），
      也不会有"看不见的点击"（不会启动应用）
- [ ] 取消勾选 → 立刻恢复（设置实时同步，不用重启系统界面）
- [ ] 横屏开「横屏时需滑动两次」→ 第一次上滑只出 toast「再次上滑」；1.6 秒内同一侧再滑 → 扇形打开
- [ ] 两次之间超时（等 2 秒以上）→ 又要从"第一次"开始
- [ ] 关掉这个开关 → 横屏一次就打开（和以前一样）

### 4. 自动收起时间滑块加 0 秒 = 松手即收

用户原话：「在扇形面板外观的不操作自动收起时间里滑块添加一个0秒，即松手直接收起」。
以前 0 表示"**永不**自动收起"（滑块最左显示「不自动收起」），现在改成**松手立刻收**：
`armTimeout()` 在 0 时不再挂计时器，改由 `FanHost.forward` 收到 `ACTION_UP/CANCEL` 时收
（日志 `FAN_RETRACT_ON_RELEASE` / `FAN_RETRACT_ON_RELEASE`），滑块文字改成「松手即收」。

- [ ] 扇形外观 → 不操作自动收起 → 拖到最左：显示「松手即收」
- [ ] 滑出扇形后松手（不点任何格子）→ 立刻收回（`FAN_RETRACT_ON_RELEASE`）
- [ ] 滑出后**按住不放** → 不收回；点中某个应用 → 照常启动并收回
- [ ] 拖到 5 秒：5 秒后自动收回（老行为不变）

### 5. 管理 + 搜索时点搜索结果收藏/取消收藏没反应

用户原话：「在扇形面板打开的更多应用页或主页的收藏应用管理，当点击管理再搜索应用时，搜索到的应用
无法正常点击收藏或取消收藏」。

**成因**：搜索结果的格子**永远是 `Cell.App`**（只有 `+`/`−` 徽标不同），点一下收藏时变的是
`cells`（★ 区，搜索时看不见）和 `pinnedKeys` —— 而 `pinnedKeys` 以前是**普通字段**，不是 Compose
状态：徽标读的是 `model.isPinned(...)`，非状态的写入不会让它重组，于是界面上一点变化都没有。
非搜索时看不出来，因为那时格子在 `Cell.Pin`/`Cell.App` 之间**换类型**，列表本身重建了。

**改法**：`pinnedKeys` 改成 `mutableStateOf`（`PinManageModel`），徽标当场翻。模型那半边另有
`PinManageModelSearchManageTest` 两条钉住（搜索+管理下 `togglePin` 必须当场生效）。

- [ ] 面板「更多」→ 点「管理」→ 搜索任意应用 → 点一下：徽标立刻变（`+` ↔ `−`），再点一下变回去
- [ ] 主页 → 收藏应用管理 → 同样操作（两处共用同一个模型/页面）
- [ ] 搜到**已收藏**的应用：徽标是 `−`，点一下取消收藏（徽标变 `+`）
- [ ] 退出搜索回到列表：★ 区与徽标一致（不会出现"点了但没进 ★ 区"）


## A35 功能页加载进度（2026-10-23）

功能页的快捷方式是异步扫描的；加载期间在网格顶部显示一行：
`正在加载功能…` + `第 x 个 / 共 y 个` + 细进度条。进度来自
`ShortcutRepository.loadShortcuts(context, onProgress)`：扫描前用
`countCandidates()` 估算总数（LauncherApps 快捷方式 + 桌面捷径 + 2 条兜底），
每加入一条调用一次 `ShortcutProgress.step()`；总数小于已加载时自动抬高，进度条不会超过 100%。

- [ ] 冷启动/切到功能页：列表加载期间能看到进度条和「第 x 个 / 共 y 个」
- [ ] 数字只增不减，加载完成后进度条消失、列表完整显示
- [ ] 加载期间不闪「还没有读取到功能」空态
- [ ] 没有功能时：加载完成后再显示空态
- [ ] 面板和主页收藏管理页两个宿主都生效

## A36 小窗风暴熔断（2026-10-23）

**背景（真机取证 work_logs/crash_repro_2026-10-23.log）**：竖屏通知开启小窗后点酷安的
升级通知，目标页落进一个全新的 freeform 任务当任务根，酷安的 `UpgradeManagerActivity`
判「非正常入口」→ 回闪屏重启（`MainActivity` CLEAR_TASK），路由又把它送回来，无限乒乓；
它的路由 Intent 自带 `NEW_TASK|MULTIPLE_TASK`，每轮都开新任务，ROM 的
`addLaunchFreeformActivityOptionIfNeed` 链式继承 freeform —— 31 秒弹了上百个小窗。
同一条通知全屏打开完全正常（对照实验）。

**熔断**（`FreeformStormGuard`，两进程各持一份）：2 秒内同一包满 5 次「小窗授予 /
新小窗任务」判为风暴 → 冷却 15s（冷却结束后 30s 内再风暴按 ×4 放大，上限 120s）：

- system_server：`LightOpenGate.installStormBreaker` 拦
  `MiuiMultiWindowUtils.getActivityOptions`，冷却期返回 null（该方法本来就允许 null）；
- SystemUI：`FlymeFreeformController.rememberWindow` 报新小窗任务，
  `LightOpenSystemUi.freeformBundle` 在冷却期退化为普通打开。

- [ ] 复现原场景（竖屏通知小窗 + 点酷安升级通知）：小窗最多弹出 4~5 个即停止，不再连环弹
- [ ] 日志出现 `LIGHT_OPEN_STORM_TRIGGER` / `LIGHT_OPEN_STORM_SUPPRESS`（system 侧）
      与 `STORM_TRIGGER`（systemui 侧）
- [ ] 冷却期内再点同一条通知：普通打开（不弹小窗）；冷却结束后恢复小窗
- [ ] 风暴期间应用自身的连环启动退化为全屏形态，不再新增小窗
- [ ] 正常手动快速连开几个小窗：不触发熔断；一个应用风暴时其它应用小窗不受影响

## A37 小窗长按菜单：遮罩圆角与音量键（2026-10-23）

**遮罩圆角**：菜单的黑色遮罩原来自己画 `18dp × 1/scale` 的圆角，和 ROM 给小窗的真实圆角对不上，
四角会露出黑边。按用户要求做了「不裁剪、保留直角」的 debug 版实测，确认系统会按窗口 leash 的
圆角裁剪子层 —— 永久改成直角矩形，不再自绘圆角。

**音量键**：用户报「静音后按音量键完全没反应，正常应该有系统的音量条弹出，静音后这个都没了」。
`dumpsys audio` 里测试期间没有任何 `adjustSuggestedStreamVolume` —— 按键在输入层就被吞了，
不是「播放音量 = 0」的问题（用同一个 `setPlayerVolume` 压到 0 的对照测试，音量条照常弹出）。
菜单是 `TYPE_APPLICATION` 的 `SurfaceControlViewHost` 窗口，**默认可聚焦**；菜单关掉后焦点不一定
还给下面的小窗，硬件键就被它接着。现在给菜单窗口加 `FLAG_NOT_FOCUSABLE`（点击/触摸不受影响）。

- [ ] 长按菜单的黑色遮罩：四角与系统小窗圆角完全重合（遮罩画直角、由窗口 leash 裁剪）
- [ ] 长按菜单（点静音/放大）关闭后立刻按音量键：系统音量条正常弹出
- [ ] 菜单的静音/放大按钮、点空白关闭都正常（不可聚焦不影响触摸）
- [ ] 静音时应用暂停着，关掉小窗；之后重新播放：音量自动还原（不会把应用永久钉在 0）

## A38 字母索引、长收藏区与角落裁剪（2026-11-15）

### 更多 / 收藏管理的字母索引

- [ ] 按住 C，手指在同一格内轻微移动：开始一次反馈，之后不连续震动；列表滑到 C 时不反向重选。
- [ ] 在 C/D 的分界小幅抖动：不来回震动；越过少量防抖距离后切换一次。
- [ ] 快速从 C 拖到 M 再到 Z：平滑追随最后的目标，不在松手后依次补播旧目标。
- [ ] ★ 平滑回到收藏；点击当前字母也能显示气泡并重新定位。
- [ ] 面板高度 40%、大字号、横屏：显示被抽样时，完整字母列表仍可逐格选到，位置不跳倍数。
- [ ] 功能页加载中 / 加载结束后索引都对准分组标题（计算包括聚合支付和加载进度行）。
- [ ] 手动拖动列表、切换应用/功能、进入搜索时，上一次索引动画停止。

### 收藏数量超过面板高度

- [ ] 收藏 24 个以上应用，展开「全部」，同时留有收藏功能；检查面板 40% / 70% 和完整管理页。
- [ ] 收藏初始从顶栏下方排列；上滑过程中最后一排始终在应用/功能 TabRow 上方，不穿插重叠。
- [ ] 持续上滑后 TabRow 正常吸顶，接着滚动字母列表；回顶下拉能完整展开收藏。
- [ ] 在已部分折叠时收藏/取消收藏或展开/收起，偏移随新高度收敛，没有悬空或重叠。

### 裁剪确认与手势

`Settings.Global[bubbledrawer_arbiter_crop_state]` 格式：
`1|bootCount|confirmedUptime|width|height|rotation|left|right`。
只有当前 DOWN 确实位于左右被排除区域，且启动、方向、尺寸和 6 秒有效期吻合，才记录 `mode=cropped`。

- [ ] 安装热重载后两进程均重新加载，无 `ARBITER_*_FAILED`，存活 `swipe-up` 的输入区域已裁剪。
- [ ] 新建同名监视器时出现 `ARBITER_INITIAL_CROP`，确认修改在首次 `show/apply` 前进入原事务；
      诊断监视器随测试立即 dispose，不需要重启桌面或 SystemUI。
- [ ] 底边范围设到 160dp，在旧 `width/3` 边界外、真实扇区内起手：面板打开，前台任务不回 HOME。
- [ ] 旋转 90°、180°、270° 后立即起手：旧尺寸或旧方向记录不能成为 `mode=cropped` 的依据。
- [ ] 监视器重建、模块热重载、裁剪失效时，确认缺失走 `pre-own`（键盘例外保留），旧回调不复活。
- [ ] 左右分别关闭 / 总开关关闭：对应区域恢复；中间底边 HOME、悬停最近任务、侧边返回仍正常。
- [ ] 真手指复验左右角、慢拖/快滑、亮屏首笔和键盘场景，记录起点与 `MON_DOWN/ACTIVATE/CANCEL`。
      `MON_DOWN_REJECTED` 起点不在扇区时，系统 HOME 属于预期行为，不能记成裁剪竞态。

注入事件只能验证输入通道和局部时序，不能替代最后一条的真实触摸复验。所有用于查看的截图先压缩至
900,000 字节以下，并在查看前确认文件大小。

## A39 旧版音质音效的迷你窗口静音（2026-11-15）

静态对照包含音质音效 `16-4.0-4-20260706`、`15-3.2-3-20260902` 和 SystemUI `16.03.251211.r`。
接口证据、修复边界和设备回归步骤见 [mini-audio-compat-2026-11-15.md](mini-audio-compat-2026-11-15.md)。

- [ ] Sound Assist 开/关两种状态：正在播放时静音和解除均生效。
- [ ] 暂停时静音、恢复播放、切换曲目或视频重建播放器后仍生效。
- [ ] 多播放通道全部处理；另一个应用与不同用户的副本不被误选。
- [ ] 暂停时解除、关闭窗口、还原普通小窗后恢复播放；热重载后不再被旧回调重新静音。
- [ ] 检查 `players/vendor/proxy/failed/pending/success` 日志，并用实际声音验证。
