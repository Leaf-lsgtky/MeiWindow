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
| `ISystemGestureListener` 框架回调 + sharedUserId systemui | 角落小窗抓事件 + 全屏 NOT_TOUCHABLE 画扇（双窗，对应原版 SlideGestureForwarding+AppLauncherWindow） | 目标 ROM 无 Flyme 框架，普通应用不可得 |
| `start_windowmode`/`virtual_mode` Bundle 开小窗 | `startActivity` 全屏（`FullscreenLaunchStrategy`） | 本期不做小窗；换 freeform 只改这一个类 |
| `Settings.Global "long_press_app"` | 应用私有 SharedPreferences（同名 key，PinBackend 接口预留 Global 后端） | `WRITE_SECURE_SETTINGS` 普通应用拿不到 |
| MzX（AloneTabContainer/MzRecyclerView…）+ libpag | androidx/material 等价物；.pag 演示动画不搬 | 厂商私有依赖 |
| `C4411f` 触感/`AicyPicker` 推荐 | 公开 haptic；本地启动计数 Top8 作"推荐"组 | 私有服务不可移植 |
| 贴边独占角落 | 内缩 32dp 默认；"贴边模式"经 root 关系统手势 inset | 与系统返回手势共存 |
| `persistent=true` 常驻（systemui 共享进程） | 前台服务 specialUse + `BootReceiver`（持久化"用户意图"开关，重启自动恢复，用户关掉则不拉起） | 普通应用可达的最接近行为 |

## 结构

- `bubble/GestureAppLauncher.kt` — 948 行原版的逐通道移植（双 AnimatorSet、极坐标、扇区瞄准、fling/scroll 取消语义）
- `bubble/MotionSpec.kt` — 全部时长/曲线常量（含 -7.6 回弹控制点），出处行号注释
- `bubble/SlideGestureItemView.kt` — 瞄准波纹环（130ms、#40ffffff、描边公式照抄）
- `gesture/` — 角落状态机（纯 JVM 可测）；`overlay/OverlayHost.kt` — 双窗装配
- `pinyin/` — 407 项边界表（`work_tables/gen_tables.ps1` 自动转录再生成）+ 排序键/比较器
- `more/`、`pin/` — 更多页（MoreAppWindow 移植）、固定管理（SlideLaunchAppSettings 应用页签切片 + FastScrollLetter 行为复刻）
- `root/EdgeModeHelper.kt` — 贴边模式 su 命令；`settings/MainActivity.kt` — 权限引导/调参/预览

测试为整体轮跑：`.\gradlew.bat :app:testDebugUnitTest`（拼音/分组/状态机冒烟，非逐任务 TDD——按约定精简）。

## 已知限制（v0.1）

- **角落带内的轻点不透传**：非特权 overlay 无法把已接管的 DOWN"事后归还"下层。斜滑手势不受影响；
  软键盘首键恰落在带内时，调小"宽/高"滑杆、改"仅左/右角"，或打字前从通知暂停服务即可。
  彻底解法为二期：无障碍服务探测输入法窗口时临时禁用角落带（原版是系统级监听器，无此问题）。
- 气泡条悬浮在游戏/通话等全屏界面之上仍可见（二期：前台包名黑名单）。
- **边缘指示条未复刻**：原版 `window_slide_indicator.xml`（20×64dp pill）在 SystemUITools 代码中零引用（id 常量
  `2131558896` 全树 grep 无命中）——它由 SystemUI（手势区主人）绘制，非本 APK 行为；复刻不拥有系统手势区，
  画常驻 pill 反而偏离原版。素材归档在 `work_assets/`（注意：那是 APK 内**已编译**的 .9.png，直接放回 res/
  会被 AAPT2 二次编译报错，见 `work_assets/README.md`）。
- "推荐"组为本地启动计数 Top8（原版 SmartRecommend 服务不可移植）。
