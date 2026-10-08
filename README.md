# 魅窗 (MeiWindow)

<p align="center">
  <b>在 Xiaomi HyperOS 上复刻 Flyme 经典屏幕边角内滑小窗呼出方式的 LSPosed 模块</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-HyperOS%20%7C%20Android%2014+-brightgreen.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Framework-LSPosed%20(API%20102)-blue.svg" alt="LSPosed" />
  <img src="https://img.shields.io/badge/UI-Miuix%20(HyperOS%20Design)-orange.svg" alt="Miuix" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-lightgrey.svg" alt="License" />
</p>

---

## 📖 简介

**魅窗 (MeiWindow)** 是为 Xiaomi HyperOS 打造的系统级增强模块。它将魅族 Flyme 系统中最受好评的**「屏幕底角内滑呼出小窗」**操作逻辑带到了 HyperOS 上。

无需繁琐地打开多任务切换器或侧边栏，只需从屏幕左下角或右下角轻轻向内划出，即可展开标志性的扇形底栏与应用面板，快速以**悬浮小窗**启动常用 App。

---

## ✨ 核心特性

- 🪟 **经典边角内滑手势**
  - 精确的屏幕左右底角 1/4 椭圆区域判定算法，顺滑接管内滑上提动作；
  - 智能手势分流，完全不影响系统原生的屏幕贴边返回手势；
  - 支持设置双侧、仅左角或仅右角触发，可独立调整底边与侧边触发范围。
- 🎯 **扇形呼出面板 (Fan Dock)**
  - 划入手势区即刻展开经典的环形扇面动效，手指划至对应图标抬起即开；
  - 支持自由设置扇形图标数量（5 个 / 6 个）以及展开扇形半径；
  - 支持一键以自由窗口（Freeform）或全屏模式开启应用。
- 📱 **更多应用面板 (More Panel)**
  - 点击扇形面板上的“更多”图标即可无缝展开全量应用面板；
  - 应用居中排布，配备拼音字母索引条与平滑模糊过渡效果；
  - 高度可定制：自由调整面板宽度/高度百分比、应用图标大小、文字大小，以及外部空白区域单击/双击收起。
- ⭐ **收藏应用与拖拽排序**
  - 专为快捷小窗优化的收藏管理页，支持长按拖拽排序与快捷勾选；
  - 修改即时同步生效，无需重启 SystemUI。
- 🎨 **HyperOS / Miuix 原生美学**
  - 深度采用 Miuix (HyperOS Design) Compose 组件库重构；
  - 顶部渐进模糊导航栏，无硬分割切线，完美适配系统动态色彩与深色模式；
  - 状态卡片实时显示模块注入与连接状态，并带有触感回弹效果；
  - 采用 Miuix 封装的原生转场动画与系统边角圆角裁切，无缝融入系统体验。
- 👁️ **触发区域可视化预览**
  - 内置触发区域预览功能，点击可在屏幕左右底角实时绘制物理 1/4 椭圆判定范围，直观感受调整效果。

---

## 🛠️ 安装与激活

1. **环境准备**
   - 运行环境：Xiaomi HyperOS (Android 14 / 15 / 16)；
   - Root 与 Xposed 框架：KernelSU / Magisk / APatch + **LSPosed**。
2. **激活步骤**
   - 安装最新版本的 `MeiWindow` APK；
   - 打开 LSPosed 管理器，在模块列表中启用 **「魅窗」**；
   - 作用域勾选 **「系统界面」** (`com.android.systemui`)；
   - 重启 **SystemUI**（或重启手机）使模块首次生效；
   - 打开「魅窗」应用，顶部状态卡片显示「已连接」即可根据喜好调整手势与面板参数。

---

## ⚙️ 编译构建

本项目使用 Gradle 构建：

```bash
# 运行单元测试
./gradlew test

# 构建 Release APK
./gradlew assembleRelease
```

构建生成的 APK 位于 `app/build/outputs/apk/release/`。

---

## 🤝 鸣谢与参考

- 魅族 **Flyme OS**（经典小窗手势与交互灵感来源）
- [LSPosed](https://github.com/LSPosed/LSPosed)（现代 Xposed 运行时框架支持）
- [miuix](https://github.com/compose-miuix-ui/miuix)（Xiaomi HyperOS 风格 Compose Multiplatform UI 组件库）
- [KernelSU](https://github.com/tiann/KernelSU) / [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) / [ghostlock-app](https://github.com/YuKongA/ghostlock-app)（UI 状态卡片与构建流参考）

---

## 📄 开源许可

本项目基于 [GPL-3.0 License](LICENSE) 开源。
