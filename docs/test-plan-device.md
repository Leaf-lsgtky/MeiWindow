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
- [ ] 一次成功手势：`MON_DOWN side=… x=… y=…` → `MON_PILFER_OK=true` → `MON_ACTIVATE …`
- [ ] 应用内**不再**出现 `MON_DOWN` 紧跟 `MON_SYSTEM_CANCEL`（旧症状：
      `SPY_*_DOWN` +6~22ms `SPY_*_SYSTEM_CANCEL`）
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
- [ ] 管理/完成可用：编辑态点击加/减固定、★区长按拖动排序，退出面板后顺序仍在
- [ ] 面板里点应用 → 面板收起，应用以**面板的位置/大小**开小窗（日志 `MORE_LAUNCH … rect=`）
- [ ] `adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_MORE` 可开关面板；
      `adb logcat -s BubbleDrawer` 有 `MORE_PANEL_SHOW [l,t][r,b] screen=… pct=… page=texts=[…]`
- [ ] 全屏管理页仍可从设置页进入，且 ActionBar 的"管理/完成"菜单在

## A6 固定管理
- [ ] 标题"选择快捷启动的应用"，tab"应用"，网格内点图标加固定（绿?角标=remove/add）
- [ ] 超过 6 个 → toast"最多固定 6 个"
- [ ] A–Z 分组：微信在 W、支付宝在 Z、QQ 在 Q、数字开头在 0 桶、符号在 #（最底）
- [ ] 右侧字母条：点字母滚动到组；滚动时当前字母同步
- [ ] ★ 区长按拖动排序；"管理/完成"切换后顺序持久化
- [ ] 固定后回主界面 → 角落滑动气泡条即时更新

## A7 持久与保活
- [ ] 杀后台后服务自启（START_STICKY）；重启手机后开关仍工作（首次需手动启动一次，二期加 BOOT）
- [ ] 固定列表重启不丢

## A8 小窗联系人条（Flyme「联系人头像」移植）
前置：设置页「实验性功能 → 小窗联系人条」默认开启。

**数据来源与 HyperOS 的关键差异**：头像/标题取自**通知**（Flyme 原版同样如此），但 HyperOS
在应用被打开时会清掉该应用的全部通知（任意方式打开，包括小窗）。因此模块把通知当"发现渠道"：
每条聊天通知都会写入一份**会话记忆**（标题 / 头像 / 点击意图 / 最近时间），通知被清掉后仍然保留
—— 否则小窗一出现列表就空了。记忆上限：每应用 12 条、总计 60 条、12 小时未再出现则过期。

- [ ] 先让微信来消息（通知栏有微信通知）→ 再把微信以小窗打开 → 小窗**正下方**浮出「头像 + 名称」条
      （名称 = 通知标题；日志 `CONTACT_BAR_DATA pkg=com.tencent.mm items=N live=0 remembered=N`：
      `live=0` 正是"打开即清通知"的证据，条靠 `remembered` 显示）
- [ ] 通知被清时能在日志看到 `CONTACT_BAR_NOTIF_REMOVED pkg=com.tencent.mm title=…`
- [ ] **圆角与小窗一致**（ROM 的 `getCornerRadius()`；日志里 `CONTACT_BAR_GEOM` 可对照），不是胶囊形
- [ ] **实时拉伸小窗**：拖右下角改尺寸时，条宽逐帧跟随，不出现错位/滞后（日志
      `CONTACT_BAR_GEOM … visual=… bar=… animating=true`，`bar` 的 width 始终 == `visual` 的 width）；
      变宽后能多显示几个头像
- [ ] **尺寸阈值**：设置页滑块（默认 60%，= 小窗宽度须达到屏幕宽度的 60%）—— 把小窗拖到比阈值更窄 → 条消失（`CONTACT_BAR_HIDE reason=TOO_SMALL`）；
      拖回来 → 条回来；滑到 0 → 不再按尺寸隐藏
- [ ] **上滑/下拉头像 → 移除该联系人**（`CONTACT_BAR_FORGET pkg=… title=…`）；移除最后一个后整条消失
      （`CONTACT_BAR_HIDE reason=EMPTY_AFTER_REMOVE`）；该会话来了**新消息**后应重新出现
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

## A10 扇形外观与时长（滑出振动 / 自动收起 / 图标大小 / 形状）
入口：设置页「扇形面板外观」（图标数量 / 半径 / 滑出振动 / 不操作自动收起 / 图标大小 / 图标形状）。
- [ ] **滑出时振动**（默认开）：从下角滑出、扇形**开始展开的那一帧**振一下（轻 tick，不是重按那种重 click）；
      关掉后同一动作完全无振动，图标悬停的轻 tick 仍在
- [ ] **不操作自动收起**（默认 10 秒，范围 0–30 秒）：滑出后手不点任何图标 → 到点自动收回
      （日志 `FAN_RETRACT_AUTO_TIMEOUT`）；手指还在滑动时不会中途消失（每个事件重新计时）；
      拖到最左「不自动收起」→ 一直停留，只有点选应用 / 点按面板外部 / 熄屏才收
- [ ] 点按外部、选中应用、熄屏三条既有收起路径不受影响（`FAN_RETRACT_VOID` / 选中即收 / `FAN_RETRACT_SCREEN_OFF`）
- [ ] **图标大小**（默认 44 dp，范围 32–64 dp）：拖动后扇形图标与选中光环一起变大变小，
      扇形半径不变、图标间不重叠；扇开着改动即时生效（`FAN_REBIND` 路径 = `rebindFanIfBusy`）
- [ ] **图标形状**：圆形（默认，Flyme 原样）↔ 系统样式圆角矩形；后者图标与选中光环都变成圆角方形
      （圆角半径 = 图标边长 × 0.225，与桌面图标观感一致）
- [ ] 切换形状后点选/悬停/翻页都正常（光环跟手、无残影）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr FAN_`（`FAN_RETRACT_*`、`FAN_PRESSURE_PAGE_TURN`）

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
      —— `scale` 是实际生效系数，`visual=(宽x高)` 是窗口在屏上的大小；
      横屏应满足 `visual 宽 ≤ 屏幕宽`、且 `rect.left/top` 都是正数（居中而非贴边）

## A12 禁止小窗偏移（第二个小窗仍开在同一个位置）
HyperOS 的「错开」在 `MiuiMultiWindowUtils.avoidIfNeeded:1807`（手机走这一支）：新小窗与已有小窗
重叠时，把它挪到 `已有窗口.left + FREEFORM_RECT_OFFSET_X_ZIZHAN` / `.top + FREEFORM_RECT_OFFSET_Y_ZIZHAN`
（:319-320 = **78dp / 44dp**，即"往右下偏一点"）。所有避让都汇聚在 void 且原地改参的
`avoidAsPossible(Rect, Rect, Rect)` 上（启动 `getFreeformRect:1461` / `getCustomFreeformRect:1541`、
全屏→小窗 `MulWinSwitchInteractUtil:104`、迷你→普通恢复 `MiuiFreeformModeMiniStateHandler:276`
与 `MiuiFreeformModePinHandler:953`），所以关掉它一处即可。
入口：实验性功能 → 窗口尺寸与位置 → **禁止小窗偏移**（默认开）。
- [ ] 开一个小窗 → 再开第二个（不同应用）→ 两个窗口**位置完全重合**，没有右下偏移
      （日志 `FREEFORM_OFFSET_SKIPPED mobile=… other=…`；`mobile` 应与 `other` 基本一致）
- [ ] 关掉本开关再重复 → 第二个小窗回到 HyperOS 原样：相对第一个右移 78dp、下移 44dp
- [ ] 关掉「小窗居中显示」后重复第一项 → 两个小窗仍落在**同一个默认位置**（这条是关键：
      居中关闭时不再有模块覆盖位置，全靠本开关压住 ROM 的避让）
- [ ] 迷你小窗恢复成普通小窗时不因另一个小窗而偏移（同上判据）
- [ ] 迷你小窗自己的排布（同侧迷你自动排队）、贴边/侧边栏避让不受影响
- [ ] 全屏 → 小窗切换（小白条上滑悬停等）落在与直接打开一致的默认位置

## 贴边模式（可选，root）
- [ ] 设置页"贴边模式"：无 su → toast 需要 root；有 su → inset=0，正上滑角落也能出扇
