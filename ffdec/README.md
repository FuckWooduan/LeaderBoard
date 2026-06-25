# StrikeGod.Engine:ffdec

本模块内置 JPEXS/FFDEC 的 SWF 解析源码，包名统一迁到
`com.strikegod.engine.ffdec`。Gateway 活动报告链路直接依赖
`project(':ffdec')`，不再加载外部 FFDEC jar。

## 依赖原则

- 能由 Maven Central 官方坐标直接覆盖的 FFDEC 依赖，必须优先使用第三方库。
- 没有稳定 Central 坐标、或公开坐标缺少 FFDEC fork API 的小型 helper，才允许随本模块源码编译。
- Engine 活动解析不使用的导出 / 播放链路不带入运行时依赖面。

## Maven Central 依赖

以下坐标已在 2026-05-12 通过 Maven Central / MvnRepository 复核，并统一走
`gradle/libs.versions.toml`，不在模块内私自声明仓库或版本：

- `org.tomlj:tomlj:1.1.1`
- `net.java.dev.jna:jna:5.18.1`
- `net.java.dev.jna:jna-platform:5.18.1`
- `ch.randelshofer:org.monte.media:17.2.1`
- `com.twelvemonkeys.imageio:imageio-tga:3.13.1`
- `com.github.gotson:webp-imageio:0.2.2`
- `org.apache.commons:commons-compress:1.28.0`

`org.tomlj:tomlj:1.1.1` 传递声明 `org.antlr:antlr4-runtime:4.11.1`，但
Gateway 运行时会被 Spring Boot / Hibernate 统一解析到 `4.13.2`。这是 Gradle
依赖冲突解析后的单一版本；运行测试时可能看到 TomlJ 生成 parser 的 ANTLR 版本提示。

## 源码保留边界

本模块只保留活动渲染链路实际需要、且没有合适官方替代的源码。所有源码包名都必须位于
`com.strikegod.engine.ffdec` 下，禁止再出现 `gnu.*`、`javazoom.*`、`SevenZip.*`、
`fontastic.*`、`org.doubletype.*`、`net.*`、`at.*` 这类外部包名源码。
FFDEC 的 `.properties`、字体数据、导出模板等运行时资源也必须跟随迁到
`com/strikegod/engine/ffdec/...`，避免 `ResourceBundle` 或 `getResourceAsStream` 回落到旧
`com/jpexs/...` 路径。

当前保留：

- JPEXS/FFDEC 主体源码，迁入 `com.strikegod.engine.ffdec.jpexs`。
- ActionScript Decimal128 支持，迁入 `com.strikegod.engine.ffdec.macromedia.asc.util`。它参与 ABC
  parser / constant pool，暂无比直接源码保留更低风险的通用库替代。
- `com.strikegod.engine.ffdec.compat.webp` 兼容适配器。上游 `webp4j-core` 不在官方仓库，
  适配器只保留 FFDEC 需要的 facade，实际读写委托给 `com.github.gotson:webp-imageio` 和
  Java ImageIO SPI。
- JPEXS 自带图形 / 路径 / timeline helper，已统一在本项目包名下编译。

## 禁用的 Engine-unused 路径

活动报告只需要 SWF 读取、时间轴渲染、文字 / AS3 静态分析和图片输出。下列上游能力不进入
Engine 运行时依赖面：

- VLC / vlcj 视频播放
- FLA / XFL 转换入口 `XFLConverter`
- XAML / Silverlight 导出入口
- DefineText / DefineEditText 互转编辑器工具
- FFDEC 自带 PDF 导出；Gateway 报告 PDF 使用项目已有 iText `html2pdf`
- AVI / APNG / WOFF / TTF / animated GIF / animated WebP 导出
- GIF sprite 导入
- MP3 / NellyMoser PCM 解码和音频播放
- LZMA 压缩写出；LZMA SWF 读取改由 Apache Commons Compress 解压
- JPacker JavaScript 压缩
- DDS 解码
- 上游 repackaged JNA Win32 helper；Engine 统一使用 `net.java.dev.jna:jna-platform`

## Java 25 适配

上游 FFDEC 仍保留 Java 8 时代的短生命周期 platform thread pool。本模块新增
`com.strikegod.engine.ffdec.runtime.FfdecExecutors`，用 Java 25 virtual thread 替换这些内部池：

- `CancellableWorker` 的后台 worker
- `DecompilerPool` 的 AS2 / AS3 反编译任务
- SWF 顶层 tag 并行解析
- AS2 / AS3 script export
- ABC traits 并行转换

这些内部执行器不依赖 Spring bean，避免 `:ffdec` 反向依赖 `:infra`。活动级批量解析仍由
Gateway 的 `SharedVirtualExecutor` 控制全工程并发；FFDEC 内部 CPU 型并行任务会继续遵守
`Configuration.getParallelThreadCount()` 的并发上限，只是等待和短任务调度改为 virtual thread。

`FfdecExecutors` 也是本模块唯一允许新增线程入口：长期后台清理线程、shutdown hook、debug worker
monitor 都必须通过它创建，以便统一命名、统一平台线程 / 虚拟线程选择，并保留 bounded virtual executor
的 active / submitted / completed 统计。JDK 8 时代依赖 `ThreadDeath` 的异常分支已移除；取消与超时只走
`InterruptedException`、`Future.cancel(true)` 和 `CancellableWorker.cancelThread(...)`。

## Engine 运行时收敛

上游桌面版 FFDEC 的 `FileHashMap` 磁盘缓存已从 Engine 构建中移除。`Cache` 保留原有 API，但始终使用
内存 `HashMap` / `WeakHashMap`，由 `Configuration.maxCachedNum` 和 `Configuration.maxCachedTime` 做数量 /
生命周期清理。这样活动解析不会在临时目录写入自管理序列化缓存，也不再需要 FFDEC 自带的磁盘 cache
生命周期代码。

`ffdec/build.gradle` 会把 XFL / XAML / 文本编辑器转换器、上游 repackaged JNA Win32 helper 等桌面导出 /
平台集成类排除在编译产物外；这些能力若未来真的要恢复，必须先确认它们与 Gateway 的 iText PDF、ImageIO PNG
输出或官方 `jna-platform` 不是重复能力，再把依赖和运行时风险补回文档。
