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
前置：设置页「实验性功能 → 小窗联系人条」默认开启；被验证应用需有**未清除的通知**（头像取自通知）。

- [ ] 微信/QQ 以小窗打开 → 小窗**正下方**浮出头像条，宽度与小窗一致、跟随下边缘
- [ ] **实时拉伸小窗**：拖右下角改尺寸时，条宽逐帧跟随，不出现错位/滞后（日志
      `CONTACT_BAR_GEOM … visual=… bar=… animating=true`，`bar` 的 width 始终 == `visual` 的 width）
- [ ] 小窗**移动**（拖顶部把手）时条跟着走
- [ ] 小白条上滑 → **迷你小窗**：条立即消失（日志 `CONTACT_BAR_HIDE reason=MINI`）；
      从迷你恢复成普通小窗 → 条自动回来（`CONTACT_BAR_HIDE` 不再刷、`CONTACT_BAR_SHOW`）
- [ ] 贴边（普通/迷你贴边，mode 2/3）不显示条
- [ ] 点某个头像 → 会话在小窗内打开（日志 `CONTACT_BAR_OPEN_OPTIONS … reuseTask=<小窗taskId>`）；
      `CONTACT_BAR_OPEN_PLAIN` 表示退化为普通启动（要记录）
- [ ] 非 IM 应用（如抖音）小窗不显示条（`CONTACT_BAR_HIDE reason=NOT_IM_APP`）
- [ ] 小窗关闭后条消失（`CONTACT_BAR_HIDE reason=TASK_VANISHED`）
- [ ] 关闭设置开关 → 立即消失（`CONTACT_BAR_PREF_CHANGED enabled=false`）
- [ ] 通知刷新即时生效：小窗开着时来一条新微信 → 头像条多/换头像（`CONTACT_BAR_DATA pkg=… items=N`）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr CONTACT_BAR`
      （启动时应有 `CONTACT_BAR_HOOKS_INSTALLED pipeline=true` 与 `CONTACT_BAR_NOTIF_LISTENER_ATTACHED`）

## 贴边模式（可选，root）
- [ ] 设置页"贴边模式"：无 su → toast 需要 root；有 su → inset=0，正上滑角落也能出扇
