# 更多面板和角落手势修复取证（2026-11-15）

## 字母索引与收藏布局

`LetterIndexBar` 原先把手指选择和列表当前分组共用同一个 `currentLetter`。列表滚动导致 Compose
重新写入高亮后，下一次微小 MOVE 就会把同一字母当成新选择，再次定位和震动。现在触摸选择独立保存，
仅切换字母时反馈，并给分界加入少量迟滞；绘制与命中都使用完整字母列表，短面板的抽样显示不再让命中
下标重复乘 stride。

`GenericLetterBarOverlay` 的回调原来只在 AndroidView factory 中安装，可能一直捕获首次组合的空应用
列表或旧收藏高度。现在每次 update 更新回调。列表用 `animateScrollToItem` 平滑定位，与收藏区折叠
同时运行；新目标取消旧任务，手动滚动、搜索和切换 Tab 也会取消旧定位。

标题定位使用 offset=0：项目实际解析到的 Compose Foundation 1.12.1 在 LazyGrid 的测量和放置中
会加回 contentPadding 对应的 visualOffset，因此不再额外传入负的两栏高度。

`MeasuredHeader` 原先把无限高测量出的收藏内容高度直接作为受限父节点中的布局高度。内容超过面板
时，Compose 会对超出约束的布局做居中补偿，TabRow 仍按完整高度定位，两个坐标系不一致。现在外层
尺寸遵守父约束，完整子内容明确放在 (0,0)，折叠距离仍使用完整测量高度。

## 为什么 Hook 框架后仍可能回桌面

分析输入：用户提供的 SystemUI APK、framework.jar、miui-framework.jar、miui-services.jar；并从
连接设备只读提取 services.jar，核对 InputManagerService 与 GestureMonitorSpyWindow 的实际实现。

1. **初次发布有空档。** `GestureMonitorSpyWindow` 构造器在同一事务中调用
   `setInputWindowInfo → setCrop(surface, null) → show → apply`。旧代码在
   `monitorGestureInput` 返回后才 post 裁剪，监视器已经以全屏区域接收过 DOWN 的可能性真实存在。
   即使接收器尚未建立，输入通道中也可能已有排队事件。现在目标构造器线程内替换该表面的初始 crop，
   并 deopt 构造器和创建调用方；创建方法返回前还会同步重施裁剪。
2. **布尔确认不能描述当前状态。** 持久化的 `bubbledrawer_arbiter_crop_active=1` 没有记录启动次数、
   旋转、尺寸、实际裁剪边界或有效期。现在发布结构化确认，SystemUI 验证当前 DOWN 坐标是否确实在
   被排除的区域；不匹配时恢复原来的 DOWN 预抢兜底，不在输入线程执行 Settings 查询。
3. **宽触发区有漏裁。** 原裁剪最多覆盖单侧屏宽的 1/3，触发区却可到 160dp。1080px / density=3
   的例子中，触发范围 480px，裁剪仅 360px，相差 120px。现在裁剪完整宽度并处理整数边界；极端
   缩放仍保留中间非空 HOME 通道，未实际覆盖的点不会获准跳过预抢。

另外补齐监视器移除后的刷新、dispose 回调清理和转屏后的预抢带几何读取。裁剪刷新在独立工作线程
执行；亮屏每 2 秒续期，确认有效 6 秒，熄屏停止续期并降低检查频率。没有改变 ROM 的
replaceTouchableRegionWithCrop 机制，也没有使用曾经导致中间 HOME 失效的显式 touchableRegion 替换。

这些是从实现中确认的缺口，不能据此把所有用户的 HOME 反馈归为同一个原因。修前设备上已有成功的
`mode=cropped` 手势；日志中还存在起点超出已配置扇区的正常 `MON_DOWN_REJECTED`。

## 已执行验证

- `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 成功，Release 的 lintVital 成功。
  346 项测试，345 项通过、1 项跳过，0 失败；新增的 4 项字母选择与 5 项裁剪确认回归全部通过。
- Debug 包安装后，两进程在 15:37:21 完成热重载，安装了初次裁剪 Hook，新的输入线程正常建立。
  未重启设备、桌面或 SystemUI。
- 15:37:47 通过临时 root 诊断进程建立一个同名 `swipe-up` 监视器：日志出现
  `ARBITER_INITIAL_CROP crop=[115,0][1105,2656]`；dumpsys input 中新监视器与存活桌面监视器的
  实际 touchableRegion 均为 `[115,0][1105,2656]`。诊断监视器立即 dispose，移除回调正确刷新。
- 设备上的非注入触摸日志：15:37:49 左角起点 (68,2587)，15:38:41 右角起点 (1150,2579)，均为
  `mode=cropped → MON_ACTIVATE → claimed=true, owned=true, robbedAt=0, foreignInjections=0`。
  这些记录说明所观察到的两笔链路正常，不能替代长时压力测试或单独证明前台任务没有变化。
- 最终 Release APK 已覆盖安装，签名与原设备安装包一致。包名和版本仍为
  `com.repl.bubbledrawer` / 1.4.6 / 169；APK SHA-256：
  `3da690f11f2dca189f5b4fdd1cf772ff034a6259150a9dde47118e6b6549bec1`。
- Release 在 15:45:32 完成两进程热重载；15:46:52 重复临时监视器测试，首次 crop 和实际输入区域
  均与上述结果一致。释放后仅剩原桌面 `swipe-up` 监视器，没有遗留诊断监视器。

## 仍需设备回归的边界

见 test-plan-device.md 的 A38。未改变设备现有的 3 条收藏做批量造数，24 条以上收藏、各面板尺寸和
完整管理页仍需目视回归；同字母抖动的震动次数目前由选择状态单测覆盖。转屏首笔、160dp 宽触发区、
键盘和长时连续触摸仍需真手指验证。

本次查看过的截图均先转换为 JPEG 并检查小于 900,000 字节；原始截图和本地诊断日志仅存放在忽略的
work_logs 目录，没有加入源码变更。
