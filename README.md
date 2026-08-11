# Mindustry Effect Editor（Android）

基于 Mindustry v159.7 + Arc `208a754044` 的 Android 可视化 Effect 编辑器。

## 功能

- 横屏：左侧代码区，右侧预览区
- 竖屏：上侧预览区，下侧代码区
- 代码编辑器支持 Java 语法高亮与自动补全
- 一键编译并实时播放 Effect 动画
- JavaParser 深度语义分析（点击“分析”触发）
- 导出代码为可独立使用的 Java 文件

## 项目结构

```
EffectEditor/
├── app/build.gradle          # 模块构建脚本，含 v159.7 依赖与运行时库打包任务
├── app/src/main/java/com/r112007/effecteditor/
│   ├── MainActivity.java                 # 主界面，继承 Arc AndroidApplication
│   ├── EffectEditorApplication.java      # 初始化 Tree-sitter
│   ├── CrashHandler.java                 # 崩溃日志捕获
│   ├── ui/
│   │   ├── CodeEditorView.java           # 代码编辑区（Tree-sitter + 自动补全）
│   │   ├── EffectPreviewView.java        # Arc GL 预览区
│   │   └── AutoCompletePopup.java        # 补全弹出框
│   ├── compiler/
│   │   └── EffectCompiler.java           # ECJ + R8 D8 运行时任编译
│   └── analysis/
│       └── EffectAnalyzer.java           # JavaParser 语义检查
└── app/src/main/java/javax/lang/model/SourceVersion.java
    # ECJ 在 Android 上运行时缺少的 JDK 类 stub
```

## 运行方式

1. 确保已安装 Android SDK（API 36）并设置 `ANDROID_HOME`。
2. 确保 `android.aapt2FromMavenOverride=/root/build-tools/aapt2` 指向有效的 aapt2 可执行文件。
3. 在项目根目录执行：

```bash
./gradlew assembleDebug
```

4. 安装 APK：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 编译说明

- 运行时编译流程：用户输入 → ECJ 编译为 `.class` → R8 D8 生成 `.dex` → `DexClassLoader` 加载 → 反射获取 `Effect` 实例。
- `app/build.gradle` 中的 `packRuntimeLibs` 与 `copyAndroidJar` 任务会在构建时将 Mindustry/Arc 运行时类与 `android.jar` 复制到 `assets/`，供 ECJ 作为 classpath 使用。
- Tree-sitter 负责实时代码高亮（面子）；JavaParser 负责点击“分析”时的深度语义检查（里子）。

## ECJ / Android 兼容性说明

- ECJ 3.38+ 会引用 `java.lang.Runtime.Version`（JDK 9+ 类，Android 运行时缺失），因此本项目固定使用 **ECJ 3.33.0**。
- 用户代码片段仅使用 Java 8 特性（lambda），所以运行时编译参数为 `-source 1.8 -target 1.8`，进一步避免 ECJ 调用 Android 不存在的 JDK 9+ API。
- ECJ 还会引用 `javax.lang.model.SourceVersion`，Android 同样缺失，因此提供了最小 stub 实现。

## 示例代码

```java
new Effect(40f, 80f, e -> {
    color(Pal.lancerLaser.cpy().mul(1f, 1f, 1f, e.fout()));
    stroke(e.fin() * 2f);
    Lines.circle(e.x, e.y, e.fin() * 24f);
    Fill.circle(e.x, e.y, e.fout() * 6f);
});
```

## 注意事项

- 本项目依赖 JitPack 与 Maven Central，首次同步请保持网络畅通。
- Tree-sitter 的 native 库在某些模拟器上可能加载失败，编辑器会自动回退到正则高亮。
- 崩溃日志会保存到 `/Android/data/<package>/files/crashes/`（或应用内部目录），可通过 `adb pull` 获取。
