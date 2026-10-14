# 接口约定文档（Architecture Contract）

> 本文是**唯一权威**的接口约定。所有版本分支的代码必须与本文一致。
> 出现冲突时以本文为准；需要变更时先改本文再改代码。

## 1. 项目身份

| 项 | 值 |
|---|---|
| mod id | `unbounded_height` |
| 显示名 | Unbounded Height / 无界高度 |
| 版本 | `1.0.0` |
| 根包 | `dev.zxin.unboundedheight` |
| group | `dev.zxin` |
| 作者 | `Zxin-Pro` |
| 许可 | MIT |
| 配置文件名 | `config/unbounded-height.json` |
| Mixin 配置文件名 | `unbounded_height.mixins.json` |
| Mixin 包 | `dev.zxin.unboundedheight.mixin` |
| 同步通道 id | `unbounded_height:height_table` |
| 指令根 | `/height` |

## 2. 分支与目录布局

一个分支 = 一个「加载器 + MC 版本」的**完整独立** Gradle 工程。

```
main/                          唯一真源 + 文档
├── README.md  LICENSE  .gitignore  push_to_github.sh
├── .github/workflows/build-all.yml      # matrix 总编译
├── docs/                                # 本目录
└── tools/
    ├── gen.py                  生成器：templates/ → out/<branch>/
    ├── push_all.py             用 GitHub Git Data API 推送所有分支
    ├── mcjar.py                官方 jar 字节码分析器（无 JVM）
    ├── templates/              ← 唯一真源
    │   ├── common/             三加载器共用（`//#if` 条件化）
    │   ├── forge/  neoforge/  fabric/    加载器专属
    │   ├── resources/          mods.toml / fabric.mod.json / mixins.json / lang / icon
    │   ├── build/<loader>/     build.gradle + gradle.properties
    │   ├── build/<forge-neo|fabric>/settings.gradle
    │   ├── build/gradle-wrapper.properties
    │   └── workflows/build.yml 单分支 CI
    └── wrapper/                gradle-wrapper.jar / gradlew

<branch>/                      生成物（每个分支一份完整工程）
├── build.gradle  gradle.properties  settings.gradle  gradlew  gradlew.bat
├── gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
├── .github/workflows/build.yml
├── LICENSE  .gitignore
└── src/main/
    ├── java/dev/zxin/unboundedheight/…
    └── resources/{META-INF/… | fabric.mod.json, unbounded_height.mixins.json,
                   pack.mcmeta, icon.png, assets/unboundedheight/lang/*.json}
```

**分支命名**：`<loader>-<mc>`，加载器为 `forge` / `neoforge` / `fabric`；MC 用官方版本号（`1.20.1`、`21.11`、`26.3`）。

## 3. 类清单与职责（不得随意改名）

| 类 | 包 | 职责 | 加载器相关？ |
|---|---|---|---|
| `UnboundedHeight` | 根 | `MOD_ID` / `id(String)` 工具 | 否（`//#if` 处理 Identifier 改名） |
| `Bridge` | 根 | 平台桥：配置加载器、日志、警告、高度表发送器 | 否 |
| `core.HeightLimits` | core | 结构硬限制常量 + section 对齐工具 | 否 |
| `core.HeightManager` | core | ★大脑：配置、维度身份映射、生效值缓存、策略解析、边界保护 | 否 |
| `config.UhConfig` | config | 配置模型（Gson POJO） | 否 |
| `config.ConfigStore` | config | 配置读写 | 否 |
| `config.ConfigBootstrap` | config | 把加载器的 config 目录接进 HeightManager | 否 |
| `mixin.DimensionTypeMixin` | mixin | ★核心注入：改写 `minY()/height()/logicalHeight()` | 否 |
| `mixin.ServerLevelMixin` | mixin | 维度登记：`ServerLevel.<init>` RETURN | 否 |
| `command.HeightCommand` | command | `/height` 指令树 | 否（`//#if MC>=12111` 权限） |
| `network.HeightTablePacket` | network | 载荷逻辑 + `encode/decode` | 否 |
| `network.HeightTablePayload` | network | `CustomPacketPayload` 外壳（`//@only MC>=12005`） | 否 |
| `network.ClientHeightCache` | network | 客户端展示缓存 | 否 |
| `<loader>.UnboundedHeightForge` / `…NeoForge` / `…Fabric` | 加载器包 | 入口 | 是 |
| `<loader>.<Loader>Network` | 加载器包 | 网络注册 | 是 |
| `forge.ForgeEvents` | forge | 指令注册 + 进服同步 | 是 |
| `fabric.client.UnboundedHeightFabricClient` | fabric | 客户端接收器 | 是 |

## 4. 核心机制约定

### 4.1 高度改写的唯一注入点

```java
@Mixin(DimensionType.class)
public abstract class DimensionTypeMixin {
    @Inject(method = "minY",           at = @At("HEAD"), cancellable = true) …
    @Inject(method = "height",         at = @At("HEAD"), cancellable = true) …
    @Inject(method = "logicalHeight",  at = @At("HEAD"), cancellable = true) …
}
```

**约定**：
- 三个加载器**统一使用官方映射**（Forge `channel 'official'`、NeoForge ModDevGradle 默认、Fabric `loom.officialMojangMappings()`），
  因此 `net.minecraft.world.level.dimension.DimensionType` 与访问器名 `minY` / `height` / `logicalHeight`
  在 1.20.1 → 26.3 **完全一致**，核心 Mixin **零版本分支**。
- 禁止在任何地方新增对 `DimensionType` 字段的直接写入（record 字段是 final，且会绕过缓存）。
- 原生值读取必须走 `uh$readingNative` ThreadLocal 重入开关，不得使用反射读私有字段。

### 4.2 维度登记

```java
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void uh$registerDimension(CallbackInfo ci) {
        HeightManager.registerLevel(self.dimension(), self.dimensionType());
    }
}
```

**约定**：维度 ID 只通过 `Level.dimension()` → `ResourceKey` 反射取 `location()`（1.20.1）/ `identifier()`（26.x）获得。
**禁止**使用 `RegistryAccess.registryOrThrow` / `lookupOrThrow`（26.x 改名）或 `Level.dimensionTypeId()`（26.x 移除）。

### 4.3 缓存契约

`HeightManager` 维护三张 `IdentityHashMap`（以 `DimensionType` **实例**为键）：

| 表 | 值 | 说明 |
|---|---|---|
| `ID_BY_INSTANCE` | 维度 ID 字符串 | 由 `ServerLevelMixin` 写入 |
| `NATIVE` | `[minY, height, logicalHeight]` | 首次询问时记录的原生值 |
| `RESOLVED` | `[minY, height, logicalHeight]` 或 `NONE`(长度0) | 生效值；`NONE` = 不改动 |

**约定**：
- `cached(Object)` 是快路径（只查表）；`effective(...)` 是慢路径（首次解析）。
- `registerLevel()` 登记**新**维度 ID 时必须 `RESOLVED.clear()`（否则未登记时期的「不改动」会被永久缓存）。
- 任何异常都必须 `RESOLVED.put(dt, NONE)` 并返回 `null`（回退原生高度），**绝不允许因为本模组崩游戏**。

### 4.4 边界保护契约

```
minY >= -2032、maxY <= 2031、总高 <= 4064、总高 % 16 == 0、minY % 16 == 0
```
- 非 16 对齐 → `floorToSection(minY)` / `ceilToSection(maxY+1)-1` 自动取整
- 越界 → 拒绝该维度配置 + 日志报错 + 保持原生高度
- 总高 > `performance.warnAboveSpan` → 性能警告

### 4.5 策略优先级（`HeightManager.resolve`）

```
1. compat.blacklist 命中            → 不改动
2. compat.whitelist 非空且未命中    → 不改动
3. dimensions.<id> 存在且 enabled=false → 不改动
4. dimensions.<id> 存在            → 用其 minY/maxY
5. compat.forceOverrideAll          → 用 global
6. 原版三维度                        → 用 global
7. compat.autoApplyToNewDimensions  → 用 global（若 respectNativeHeight 且该维度高度非「原版样式」则跳过）
8. 否则                              → 不改动
```

### 4.6 网络契约

- 载荷 = **一个 UTF 字符串**（`HeightManager.snapshotText()`），编码固定为 `writeUtf` / `readUtf`
- `MC>=12005` 用 `HeightTablePayload`（`CustomPayloadPayload` 记录）；`<12005` 用各加载器传统通道
- **高度本身不走本包**（走原版注册表同步），本包只用于客户端展示

### 4.7 平台桥契约

共用代码**禁止** import 任何加载器专属类。平台能力只通过 `Bridge` 注入：

```java
Bridge.bind(configLoader, logger, warner);   // 入口调用
Bridge.bindConfig(...) / bindLogger(...) / bindWarner(...) / bindTableSender(...)
Bridge.loadConfig() / info(...) / warn(...) / sendTable(...)
```

## 5. 条件编译指令表（唯一允许的 `//#if` 条件）

| 条件 | 含义 | 使用处 |
|---|---|---|
| `MC>=12002` | Forge `ChannelBuilder` 替代 `NetworkRegistry` | `forge/ForgeNetwork` |
| `MC>=12005` | 原版 `CustomPacketPayload` 存在 | `network/HeightTablePayload`（`//@only`） |
| `MC>=12111` | `ResourceLocation`→`Identifier`；`PermissionSet` 替代 `hasPermission(int)` | `UnboundedHeight`、`command/HeightCommand` |
| `MC>=26000` | Fabric `PayloadTypeRegistry.playS2C`→`clientboundPlay` | `fabric/FabricNetworkServer` |
| `LOADER_FABRIC` / `LOADER_FORGE` / `LOADER_NEOFORGE` | 加载器判定 | 目前核心代码**不再需要**（映射已统一） |

> `MC` 编码：`1.20.1→12001`、`1.21.11→12111`、`26.1→26100`、`26.2→26200`、`26.3→26300`
> （`major==1` 时 `10000+minor*100+patch`，否则 `major*1000+minor*100+patch`）

## 6. 构建契约

| 加载器 | 插件 | Mixin 接线 | 运行期映射 |
|---|---|---|---|
| Forge | `net.minecraftforge.gradle [6.0,6.2)` + `org.spongepowered.mixin 0.7.+` | `mixin { add sourceSets.main, "${mod_id}.refmap.json"; config "${mod_id}.mixins.json" }` + jar 清单 `MixinConfigs` | SRG（靠 refmap） |
| NeoForge | `net.neoforged.moddev {{MDG_VERSION}}` | 无需 build 配置；`neoforge.mods.toml` 的 `[[mixins]] config = "…"` | official |
| Fabric | `net.fabricmc.fabric-loom-remap`（≤1.21.11）/ `fabric-loom`（26.x），`1.18-SNAPSHOT` | `fabric.mod.json` 的 `mixins: [...]` | intermediary（Loom 构建期静态重映射） |

**CI 约定**：Gradle 运行 JVM 与编译目标 JDK 可能不同（Fabric 的 Loom 1.18 要求 JVM 25）。
必须**先装编译目标 JDK、后装 Gradle 运行 JDK**，否则 `JAVA_HOME` 会被后者覆盖。

## 7. 元数据契约

| 文件 | 加载器 | 要点 |
|---|---|---|
| `META-INF/mods.toml` | Forge | `modLoader="javafml"`、`loaderVersion="[47,)"`、依赖用 `mandatory=true` |
| `META-INF/neoforge.mods.toml` | NeoForge | `loaderVersion="[1,)"`、依赖用 `type="required"`、末尾 `[[mixins]] config = "unbounded_height.mixins.json"` |
| `fabric.mod.json` | Fabric | `entrypoints.main`/`client`、`mixins: ["unbounded_height.mixins.json"]`、`depends.fabricloader >=0.15.0` |

## 8. 明确不做的事（防止范围蔓延）

- ❌ 不添加任何物品、方块、实体、合成表、世界生成
- ❌ 不使用数据包覆盖 `dimension_type` JSON（改走运行时注入）
- ❌ 不使用 ForgeConfigSpec / ModConfigSpec / Fabric Config（动态键无法表达 + 26.3 枚举改名）
- ❌ 不修改已有区块的方块数据
- ❌ 不实现稀疏分页（本模组跨度 ≤4064，完全在原版稠密结构内）
