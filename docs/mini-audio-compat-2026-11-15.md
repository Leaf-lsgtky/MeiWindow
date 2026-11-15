# 迷你小窗长按静音：旧版静态兼容修复

## 对照输入与证据

本次使用的旧版文件：

- `E:/workspace/os3/音质音效_16-4.0-4-20260706.apk`
- `E:/workspace/os3/系统界面_16.03.251211.r.apk`
- `C:/Users/liu/Downloads/音质音效(HyperOS)-15-3.2-3-20260902(260902).apk`

两份音质音效 APK 的以下实现一致：

| 位置 | 已确认的行为 |
|---|---|
| `com.miui.misound.playervolume.a.o()` | 反射 `AudioManager.getPlayerVolume(String)` 和 `setPlayerVolume(AudioPlaybackConfiguration, float)` |
| `com.miui.misound.playervolume.a$h$a.onProgressChanged()` | 将滑杆进度除以最大值，按 0–1 浮点倍率调用上述 setter |
| `com.miui.misound.playervolume.f.a(Context)` | 查询播放配置后，继续筛选 `getPlayerState()==2`，且 `usage==1` 或 `volumeControlStream==3`；同 UID 去重 |
| 16 系列 `o.f.onPreferenceChange()`、15 系列 `b0.f.onPreferenceChange()` | 分应用音量开关对应 `Settings.Global.sound_assist_key` |

旧 SystemUI 的 `MiuiFreeformModeTaskInfo` 继承 `MultiTaskingBaseTaskInfo`，后者的 `getTaskInfo()`
返回 `mTaskInfo: RunningTaskInfo`；DEX 中也有 `effectiveUid` 字段引用。因此没有把“旧系统一定缺少
effectiveUid”当作故障结论。

另外核对了此前提供的较新 `framework.jar`、`miui-services.jar`：

- `PlaybackActivityMonitorStubImpl.setPlayerVolume(...)` 在 `mOpenSoundAssist=false` 时直接返回，
  `getPlayerVolume(String)` 同时返回 1.0。setter 是 void，“没有抛异常”不能证明音量发生变化。
- `PlayerProxy.setVolume(float)` 调用目标播放器的 `IPlayer.setVolume`，最终修改
  `PlayerBase.setVolumeMultiplier`。这是播放器倍率，与全局媒体流音量不同。

**证据边界：**本次没有旧 ROM 的 framework/services 实现和运行日志。旧 APK 能证明其调用契约，
不能单独证明旧音频服务的具体权限检查、Sound Assist 闸门或实际发声结果。上述静默返回是在较新
服务实现中确认的；补丁通过运行时写后读回处理这种行为，不假定所有旧 ROM 内部完全相同。

## 修复内容

1. 按原厂条件筛选当前 UID 的正在播放媒体通道。取消 `.any { setPlayerVolume(...) }` 的短路调用，
   逐个处理全部匹配播放器，避免首个配置导致其余播放器被跳过。
2. 写厂商音量后读取实际返回值，只有与目标倍率一致才确认。接口缺失、抛错、返回无效值或静默忽略时，
   尝试目标配置的 `getPlayerProxy().setVolume(...)`；逐通道记录成功与失败，部分失败不报告全部成功。
3. 先注册播放监听，再取初始快照，避免两者之间开始播放的竞态。播放前点静音继续保留待生效请求；
   播放器兜底保持监听，在新播放器或暂停后恢复播放时重新应用，重复快照不反复下发。
4. 保存原倍率，并单独持久化“曾直接修改播放器”的恢复标志。解除静音、关闭/还原迷你小窗时，
   暂停中的播放器也参与恢复。厂商接口报告 1.0 不能清除直接播放器的恢复责任；未能恢复的记录留给
   后续播放回调或新 SystemUI 实例处理。
5. 热重载时停止旧播放回调并尝试恢复，阻止旧类加载器的实例重新下发静音。没有模块静音记录的窗口
   关闭时，不再执行音频策略复位。
6. UID 优先使用任务所在用户的包信息，兼容顶部 Activity 的应用信息和验证过包归属的 effectiveUid。
   不跨用户退到主用户副本；无效目标返回失败，不登记 `uid=-1` 的静音意图。
7. 日志增加 `players/vendor/proxy/failed/pending/success`，AppOps 兜底也读回模式后才确认成功。

这些选择按运行时能力执行，没有根据音质音效 APK 的版本号硬编码分支。

## 验证与限制

单元测试覆盖静默无效 setter、多播放器不短路、单通道失败、无法读回、直接播放器恢复、暂停/媒体
过滤、UID 缺失、分身隔离，以及热重载仍能枚举已经生效的静音请求。构建运行
`:app:testDebugUnitTest :app:assembleRelease`，包含 Release 的 lintVital 检查。

最终结果：357 项测试中 356 项通过、1 项跳过，0 失败；本次新增 11 项回归全部通过。
Release 构建、lintVital、APK 签名校验通过；交付包的 DEX 已确认包含本次兼容类与诊断日志。
测试包为 `work_assets/releases/MeiWindow-1.4.6-mini-audio-compat.apk`（保留版本 1.4.6 / 169），
SHA-256：`f1e04594930ff6ad3b58e0f19aa06c34486ff4b699b59b856cf3c1569cc7b5cd`。

本次只做静态修复和本地构建，没有安装这些系统 APK，也没有在旧 ROM 上做听感或实际音频验证。
音质音效 APK 的“15/16”版本名不等于设备的 Android 版本；模块仍沿用现有 minSdk 36。

播放器兜底没有可读取的倍率 getter，因此其图标显示本模块持有的静音状态；通过其他系统界面改变
播放器倍率后，不能保证图标即时同步。厂商正常路径仍按读回音量同步。

## 旧设备回归

- 分应用音量开关开启/关闭时，分别测试正在播放的迷你窗口静音与取消静音。
- 暂停时点静音，再恢复播放；切换视频/曲目导致播放器重建后仍静音。
- 同一应用同时存在多个媒体播放器时，所有通道生效；另一个应用继续正常发声。
- 暂停中解除静音、关闭迷你窗口、还原普通小窗，再恢复播放，声音恢复。
- 静音期间模块热重载或 SystemUI 重启，确认没有旧回调再次静音，也没有遗留零倍率。
- 分身与工作资料的应用，核对日志 UID 是否属于对应用户。
- 音量键和系统音量条仍正常。日志中的 `proxy>0` 仅表示播放器命令调用成功，不等同于听感证明。
