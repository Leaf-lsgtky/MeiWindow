# work_assets — 素材暂存区（不参与编译）

## slide_indicator_*.9.png（xhdpi/xxhdpi/xxxhdpi，左/右）

来源：`E:\workspace\flyme\out\SystemUITools_src\resources\res\drawable-*\slide_indicator_{left,right}_side.9.png`（逐字节复制）。

**为什么不在 res/ 里**：APK 资源表中的 `.9.png` 是 **aapt 编译后产物**（已烤入内容矩形、去掉 1px 引导边框）。
直接放回 `res/drawable-*` 会被 AAPT2 再次当作源 9-patch 处理而报错：
`error: file failed to compile`（实测于本仓库构建 :app:mergeDebugResources）。
需要使用时必须取原版**未编译素材**：反编译输出不含它们，须从 APK 用 `aapt2`/`apktool` 原始抽取，或按 20×64dp pill 重绘。

**为什么复刻不画这个 pill**（考证）：`window_slide_indicator.xml` 布局的 id 常量 `2131558896`
在 `SystemUITools_src` 全部 Java 源码中零引用（AbstractC2769q 静态字段体系核对过）——指示条由
SystemUI（系统手势区主人）绘制，不属于本 APK 行为。独立复刻应用无系统手势区，画常驻 pill 反而偏离原版。
若二期做"自绘提示"（设置项开关），素材形状参考这里三份图。
