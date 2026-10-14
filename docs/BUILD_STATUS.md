# 构建状态汇总（实测）

> 数据来源：GitHub Actions **实际运行结果 + 产物 jar 内省**，不是「应该能编译」。
> 最后更新：2026-10-14

## 总览

| 加载器 | 版本数 | 编译通过 | 产物已内省验证 |
|---|---|---|---|
| NeoForge | 8 | **8 / 8** ✅ | ✅ |
| Forge | 3 | **3 / 3** ✅ | ✅（含 refmap） |
| Fabric | 11 | **11 / 11** ✅ | ✅ |
| **合计** | **22** | **22 / 22** ✅ | ✅ |

`Build all versions`（main 分支的 matrix 总 workflow）：**22 / 22 成功**。

## 逐个版本

| # | 分支 | 加载器 | MC | Java | 编译 | 产物 jar |
|---|---|---|---|---|---|---|
| 1 | `forge-1.20.1` | Forge 47.x | 1.20.1 | 17 | ✅ | `unbounded-height-forge-1.20.1-1.0.0.jar` |
| 2 | `forge-1.20.2` | Forge 48.x | 1.20.2 | 17 | ✅ | `unbounded-height-forge-1.20.2-1.0.0.jar` |
| 3 | `forge-1.20.4` | Forge 49.x | 1.20.4 | 17 | ✅ | `unbounded-height-forge-1.20.4-1.0.0.jar` |
| 4 | `neoforge-1.20.6` | NeoForge 20.6.x | 1.20.6 | 21 | ✅ | `unbounded-height-neoforge-1.20.6-1.0.0.jar` |
| 5 | `neoforge-1.21.1` | NeoForge 21.1.x | 1.21.1 | 21 | ✅ | `unbounded-height-neoforge-1.21.1-1.0.0.jar` |
| 6 | `neoforge-1.21.4` | NeoForge 21.4.x | 1.21.4 | 21 | ✅ | `unbounded-height-neoforge-1.21.4-1.0.0.jar` |
| 7 | `neoforge-1.21.8` | NeoForge 21.8.x | 1.21.8 | 21 | ✅ | `unbounded-height-neoforge-1.21.8-1.0.0.jar` |
| 8 | `neoforge-1.21.11` | NeoForge 21.11.x | 1.21.11 | 21 | ✅ | `unbounded-height-neoforge-1.21.11-1.0.0.jar` |
| 9 | `neoforge-26.1` | NeoForge 26.1.x | 26.1 | 25 | ✅ | `unbounded-height-neoforge-26.1-1.0.0.jar` |
| 10 | `neoforge-26.2` | NeoForge 26.2.x | 26.2 | 25 | ✅ | `unbounded-height-neoforge-26.2-1.0.0.jar` |
| 11 | `neoforge-26.3` | NeoForge 26.3.x | 26.3 | 25 | ✅ | `unbounded-height-neoforge-26.3-1.0.0.jar` |
| 12 | `fabric-1.20.1` | Fabric | 1.20.1 | 17 | ✅ | `unbounded-height-fabric-1.20.1-1.0.0.jar` |
| 13 | `fabric-1.20.2` | Fabric | 1.20.2 | 17 | ✅ | `unbounded-height-fabric-1.20.2-1.0.0.jar` |
| 14 | `fabric-1.20.4` | Fabric | 1.20.4 | 17 | ✅ | `unbounded-height-fabric-1.20.4-1.0.0.jar` |
| 15 | `fabric-1.20.6` | Fabric | 1.20.6 | 21 | ✅ | `unbounded-height-fabric-1.20.6-1.0.0.jar` |
| 16 | `fabric-1.21.1` | Fabric | 1.21.1 | 21 | ✅ | `unbounded-height-fabric-1.21.1-1.0.0.jar` |
| 17 | `fabric-1.21.4` | Fabric | 1.21.4 | 21 | ✅ | `unbounded-height-fabric-1.21.4-1.0.0.jar` |
| 18 | `fabric-1.21.8` | Fabric | 1.21.8 | 21 | ✅ | `unbounded-height-fabric-1.21.8-1.0.0.jar` |
| 19 | `fabric-1.21.11` | Fabric | 1.21.11 | 21 | ✅ | `unbounded-height-fabric-1.21.11-1.0.0.jar` |
| 20 | `fabric-26.1` | Fabric | 26.1 | 25 | ✅ | `unbounded-height-fabric-26.1-1.0.0.jar` |
| 21 | `fabric-26.2` | Fabric | 26.2 | 25 | ✅ | `unbounded-height-fabric-26.2-1.0.0.jar` |
| 22 | `fabric-26.3` | Fabric | 26.3 | 25 | ✅ | `unbounded-height-fabric-26.3-1.0.0.jar` |

无失败版本。

## 产物内省结果（不是只看 CI 绿灯）

对下载下来的 jar 逐项检查：

| 检查项 | Forge 1.20.1/1.20.2/1.20.4 | NeoForge 1.20.6~26.3 | Fabric 1.20.1~26.3 |
|---|---|---|---|
| 元数据文件 | `META-INF/mods.toml` ✅ | `META-INF/neoforge.mods.toml` ✅ | `fabric.mod.json` ✅ |
| `MixinConfigs` 清单项 | ✅ `unbounded_height.mixins.json` | 由 `[[mixins]]` 声明 ✅ | 由 `fabric.mod.json` 的 `mixins` 声明 ✅ |
| mixin 配置在包内 | ✅ | ✅ | ✅ |
| **refmap** | ✅ `unbounded_height.refmap.json` | 不需要（运行期官方名） | 不需要（Loom 静态重映射） |
| 语言文件 | ✅ `en_us` / `zh_cn` | ✅ | ✅ |
| 类数量 | 20 | 20~21 | 20~21 |

**refmap 内容实测**（这是 Forge 1.20.x 能真正生效的关键）：

```json
{"searge": {"dev/zxin/unboundedheight/mixin/DimensionTypeMixin": {
  "minY":          "Lnet/minecraft/world/level/dimension/DimensionType;f_156647_()I",
  "height":        "Lnet/minecraft/world/level/dimension/DimensionType;f_156648_()I",
  "logicalHeight": "Lnet/minecraft/world/level/dimension/DimensionType;f_63865_()I"
}}}
```

三个方法的官方名 → SRG 名映射齐全，`@Inject(method = "minY")` 在 Forge 1.20.x 上才能命中。

## 构建过程中真实踩到并修掉的坑

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 22 个分支全失败，`class, interface, enum, or record expected` | `HeightTablePacket.java` 被一次失败的编辑改坏（类体提前闭合） | 整文件重写 |
| 2 | Forge 全失败，`Could not find method mixin()` | `mixin {}` DSL 不属于 ForgeGradle 6，来自 `org.spongepowered.mixin` 插件 | 加 `id 'org.spongepowered.mixin' version '0.7.+'` |
| 3 | Fabric 全失败，`package net.minecraft.world.dimension does not exist` | 我按 Yarn 包名写，但 `build.gradle` 用的是 `loom.officialMojangMappings()`（= mojmap） | 去掉所有 `//#if LOADER_FABRIC`，三加载器统一 `net.minecraft.world.level.dimension.DimensionType` |
| 4 | Fabric 编译错 `expression type ServerPlayer is a subtype of pattern type ServerPlayer` | `handler.getPlayer()` 已是 `ServerPlayer`，多余的模式匹配 | 直接用 `handler.getPlayer()` |
| 5 | **CI 全绿但 Forge 运行期会失败**：jar 里没有 refmap | 缺 `annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'`，注解处理器没跑 | 显式声明 AP + 在 mixin 配置里声明 `refmap`（仅 Forge，用 `//#if LOADER_FORGE`） |

> 第 5 条是**只看 CI 绿灯发现不了**的：编译通过、jar 也生成了，但 Mixin 在 SRG 名环境下找不到 `minY`，
> 配合 `injectors.defaultRequire = 1` 会在加载时直接报错。靠**下载产物 jar 内省**才抓出来。

## CI 配置要点

- 每个分支的 `.github/workflows/build.yml`：`push` / `pull_request` / `workflow_dispatch`，`gradle/actions/setup-gradle@v4` 开缓存，上传 `build/libs/*.jar`。
- 两段式 JDK（仅 Fabric 需要）：默认 JDK = 编译目标（17/21/25），先装；再把 `JAVA_HOME` 指向 Gradle JVM（25）。
  原因：**Loom 1.18 要求 Gradle 本身跑在 JVM 25 上**，若先设 25 再装 17 会被 `setup-java` 覆盖 → 必须「先装编译目标、后装 Gradle JVM」。
- `build-all.yml`：matrix 同时 checkout 各版本分支编译，`continue-on-error: true` 收集全部结果后再汇总，避免一个失败就中断其余。
