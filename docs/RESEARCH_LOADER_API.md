# 加载器 API 版本差异研究（子代理② 产出 + 主代理复核）

> **来源标注**：本文由 **子代理②**（加载器 API 差异研究）调研并汇总；
> 其报告未及时落盘（超时/未写出文件），由**主代理**依据该子代理回传的关键结论 +
> 在 `xp-jetpack`（同结构姐妹项目，22/22 CI 全绿）里**已实测编译通过**的模板代码复核后整理成文。
> 凡标注「已实测」的条目，均在 22 个分支的 GitHub Actions 真实构建中得到验证。

## 一、Mixin 集成（本项目最关心的一条）

| 加载器 | 接入方式 | 是否要 refmap | 依据 |
|---|---|---|---|
| Forge 1.20.1 / 1.20.2 / 1.20.4 | `org.spongepowered.mixin` 插件 + `mixin { add sourceSets.main, "${mod_id}.refmap.json" }` | **要**（运行期 SRG 名） | 已实测：产物 jar 内含 `unbounded_height.refmap.json`，且三个方法都映射到 `f_156647_()` / `f_156648_()` / `f_63865_()` |
| NeoForge 1.20.6 ~ 26.3 | ModDevGradle **自动**支持 mixin，无需 build.gradle 配置；在 `neoforge.mods.toml` 用 `[[mixins]] config = "..."` 声明 | 不要（运行期官方名） | 已实测：8/8 分支编译通过，产物无 refmap |
| Fabric 1.20.1 ~ 26.3 | Loom 自动运行 Mixin AP 并做**构建期静态重映射**；在 `fabric.mod.json` 的 `mixins` 字段声明 | 不要 | 已实测：11/11 分支编译通过，产物无 refmap |

**最关键的坑（本项目实际踩到并被 CI 抓到）**：只加 `mixin { ... }` 而不加
`annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'`，
**编译能过、jar 也能出，但不会生成 refmap** → Forge 运行期在 SRG 名下找不到 `minY`，
配合 `injectors.defaultRequire = 1` 会直接报错。
**只看 CI 绿灯发现不了，必须下载产物 jar 内省。**

## 二、映射与包名（决定能不能编译）

| 项 | Forge | NeoForge | Fabric |
|---|---|---|---|
| mappings | `channel: 'official'` | official（ModDevGradle 默认） | `loom.officialMojangMappings()` |
| 运行期名 | SRG（1.20.1~1.20.4） | 官方名 | 官方名（26.x 已去混淆） |

→ **三个加载器统一是 mojmap 名**。因此 `net.minecraft.world.level.dimension.DimensionType`
从 1.20.1 到 26.3 完全一致，核心 Mixin 可以做到**零版本分支**。

> 我最初按 Yarn 的包名（`net.minecraft.world.dimension.DimensionType`）写了 Fabric 分支，
> CI 直接报 `package net.minecraft.world.dimension does not exist` —— 已修正并实测通过。

## 三、逐版本 API 边界（已实测条件编译点）

| 条件 | 含义 | 用在哪 |
|---|---|---|
| `MC>=12002` | Forge 网络从 `NetworkRegistry.newSimpleChannel` 换成 `ChannelBuilder`；且 `send` 参数顺序反转 | `ForgeNetwork` |
| `MC>=12005` | 原版引入 `CustomPacketPayload` + `StreamCodec`（三加载器通用的现代包 API） | `HeightTablePayload`（`//@only MC>=12005`） |
| `MC>=12111` | `CommandSourceStack#hasPermission(int)` 被 `PermissionSet` 体系取代；`FMLEnvironment` 也不再用 | `HeightCommand.allowed()` |
| `MC>=26000` | Fabric `PayloadTypeRegistry.playS2C()` → `clientboundPlay()`；`ResourceLocation` → `Identifier` | `FabricNetworkServer` |
| `MC>=26300` | NeoForge `ModConfig.Type.COMMON` → `SYNCED`（26.1/26.2 仍是 `COMMON`） | —（本项目刻意不用原生 config API，见下） |

### 26.3 权限 API（子代理未查清，主代理用字节码补上）

```java
// 26.3: net.minecraft.server.permissions.Permissions 提供等级常量
Permissions.COMMANDS_MODERATOR   // ~等级 1
Permissions.COMMANDS_GAMEMASTER  // ~等级 2（/height 默认）
Permissions.COMMANDS_ADMIN       // ~等级 3
Permissions.COMMANDS_OWNER       // ~等级 4

// 判定：source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
```

## 四、NeoForge 网络包 API（全版本一致，无需条件编译）

`RegisterPayloadHandlersEvent` → `PayloadRegistrar`：

```java
registrar.playToServer(InputPayload.TYPE, InputPayload.STREAM_CODEC, (payload, ctx) -> ...);
registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, (payload, ctx) -> ...);
```

**已实测**：1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 / 26.1 / 26.2 / 26.3 的
`PayloadRegistrar.java` 中三参数 `playToClient` / `playToServer` **签名完全相同**，
可一套代码覆盖全部 8 个 NeoForge 目标。（1.21.8+ 额外增加了无 handler 的重载和 4 参数
`playBidirectional`，用不到。）

## 五、Fabric 网络包 API

```java
//#if MC>=12005
//#if MC>=26000
PayloadTypeRegistry.serverboundPlay().register(...);
PayloadTypeRegistry.clientboundPlay().register(...);
//#else
PayloadTypeRegistry.playC2S().register(...);
PayloadTypeRegistry.playS2C().register(...);
//#endif
ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, ctx) -> ctx.server().execute(...));
//#else   // 1.20.1 / 1.20.2 / 1.20.4
ServerPlayNetworking.registerGlobalReceiver(CHANNEL_ID, (server, player, handler, buf, sender) -> ...);
ServerPlayNetworking.send(player, CHANNEL_ID, buf);
//#endif
```

## 六、本项目**刻意不用**原生配置 API

| 加载器 | 原生 API | 本项目为何不用 |
|---|---|---|
| Forge | `ForgeConfigSpec` + `ModConfig.Type.COMMON` | 需求里的 `dimensions.<维度ID>.minY` 是**运行时才知道的动态键**，spec 要求静态声明 |
| NeoForge | `ModConfigSpec` + `ModConfig.Type` | 同上；且 26.3 把 `COMMON` 改名 `SYNCED`，多一份版本风险 |
| Fabric | （无官方 API） | — |

改为**全加载器统一的 JSON 配置**（`config/unbounded-height.json`），
键路径与需求一字不差，且 `/height set` 可在运行时改写并持久化。详见 [CONFIG.md](CONFIG.md)。

## 七、构建环境要点（已实测）

- **Loom 1.18 要求 Gradle 本身运行在 JVM 25**，哪怕编译目标是 17/21。
- CI 里 `setup-java` 装两次会互相覆盖 `JAVA_HOME` → **必须先装编译目标 JDK、后装 Gradle JVM**。
- 26.x 的 Fabric 不再需要 mappings（官方 jar 已去混淆），`mappings` 行应整行删除，
  依赖前缀也从 `modImplementation` 改为 `implementation`。

## 八、未验证项（如实标注）

| 项 | 状态 |
|---|---|
| 26.1 / 26.2 的 `ModConfig.Type` 到底是 `COMMON` 还是 `SYNCED` | **未验证**。姐妹项目 `xp-jetpack` 用 `//#if MC>=26300` 分支并在 26.1/26.2/26.3 上 22/22 全绿，反证 26.1/26.2 仍是 `COMMON`。本项目不用原生 config API，此点不影响 |
| NeoForge 1.20.6 之前 `[[mixins]]` 是否被支持 | 不适用（本项目 NeoForge 从 1.20.6 起）。已核实 FML 1.20.6 的 `ModFileParser.getMixinConfigs` 读取 `[[mixins]]` |
| 1.21.11 的 `Permissions.*` 常量名是否与 26.3 完全一致 | **已实测编译通过**（`fabric-1.21.11` / `neoforge-1.21.11` 分支 CI 全绿） |
