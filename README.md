# 无界高度 · Unbounded Height

> 移除 Minecraft 原版三个维度（主世界 / 下界 / 末地）的建筑高度限制，并**自动**为其他模组动态注册的新维度套用同样的高度范围。
> 纯高度限制修改 —— **不添加任何新物品、方块、实体**。

[![Build](https://github.com/Zxin-Pro/unbounded-height/actions/workflows/build-all.yml/badge.svg)](https://github.com/Zxin-Pro/unbounded-height/actions/workflows/build-all.yml)

---

## 一句话原理

1.18 之后「世界有多高」只有一个真源：`DimensionType` 的 `minY` / `height` 两个字段（以及它们的记录访问器）。
本模组只在这两个访问器上做一次 `@Inject(at = HEAD, cancellable = true)`，把返回值换成配置值。

这一处注入会自动带动**整条链路**（每一条都经官方 `client.jar` 字节码实测，见 [docs/VERIFIED_INTERNALS.md](docs/VERIFIED_INTERNALS.md)）：

| 环节 | 为什么自动跟随 |
|---|---|
| `LevelHeightAccessor` | 只把 `getHeight()` / `getMinBuildHeight()` 声明为抽象，其余（`getSectionsCount` / `getMinSection` / `getMaxSection` / `isOutsideBuildHeight` …）全是建立在这两个之上的 default 方法 |
| `Level` / `ChunkAccess` | 这两个方法只是转发到 `DimensionType` |
| 区块 `LevelChunkSection[]` 容量 | `getSectionsCount()` 决定，自动按新高度分配 |
| `Heightmap` 位宽 | `bits = Mth.ceillog2(chunk.getHeight() + 1)`，**动态推导**（384→9 位，4064→12 位） |
| 区块存档 | 用**绝对** section Y 写入（`putByte("Y", sectionY)`），旧方块位置不变，无需迁移 |
| **客户端渲染 / 碰撞** | 原版注册表同步用 `RecordCodecBuilder` 的 `forGetter(DimensionType::minY)` 编码**活对象**，方法引用仍指向被注入的方法 → 服务端算出的高度原样发给客户端 |

**因此客户端不需要任何自定义网络包就能正确工作**（连原版客户端 + 本模组服务端也正确）。
本模组自带的一个小同步包只用于把「配置层面的高度表」发给客户端做展示。

---

## 功能

- **移除原版三维度高度限制** —— 默认 `Y = -2032 .. 2031`（总高 4064），上下限可独立配置，支持负数下限
- **维度兼容层** —— 自动捕获数据包 / 其他模组动态注册的维度并套用高度扩展；尊重模组维度自带高度（可关）；黑 / 白名单；`forceOverrideAll` 强制覆盖；任何异常都自动回退到该维度原生高度并打日志
- **`/height` 指令** —— `get` / `set` / `setall` / `reset` / `resetall` / `compat` / `reload`，权限等级可配置（默认 2）
- **配置驱动** —— `config/unbounded-height.json`，键路径与需求一一对应
- **边界保护** —— 超出 Minecraft 数据结构硬限制直接拒绝并报错；非 16 对齐自动取整到 section 边界；跨度过大给性能警告
- **存档兼容** —— 不修改已有区块数据，只扩展可操作范围；扩展后的存档拿到纯原版加载不会崩

---

## 硬限制（必须知道）

反编译 `DimensionType.<clinit>` 实测：

```
BITS_FOR_Y = 12
Y_SIZE     = (1 << 12) - 32 = 4064     ← 总跨度上限
MAX_Y      = (Y_SIZE >> 1) - 1 = 2031
MIN_Y      = MAX_Y - Y_SIZE + 1 = -2032
构造器校验：minY + height <= 2032、height % 16 == 0、minY % 16 == 0、height >= 16
```

所以：

| 项 | 合法范围 |
|---|---|
| `minY` | `>= -2032` |
| `maxY` | `<= 2031` |
| 总高 | `<= 4064` 且必须是 16 的倍数 |

> ⚠️ **需求里写的 `Y = -2032 .. 2032`（跨度 4065）比原版结构上限多 1 格。**
> 本模组默认取 **`-2032 .. 2031`（跨度正好 4064，顶格）**，语义等价且不会撞上 `IllegalStateException`。
> 配置成 `2032` 会被**拒绝**并在日志里报错（需求 6「边界保护」），该维度保持原生高度。

区块存档还有一层更宽的约束：section Y 被编码成 **byte**（`-128..127`），即方块 Y ∈ `[-2048, 2047]`。
`-2032..2031` 落在这个范围内，不构成瓶颈。

---

## 版本支持表

22 个分支 = 3 个加载器 × 各 MC 版本。每个分支都是**独立完整**的 Gradle 工程（含 wrapper 与 CI）。

| 分支 | MC | 加载器 | 构建 JDK | Gradle | 加载器版本 | 状态 |
|---|---|---|---|---|---|---|
| `forge-1.20.1` | 1.20.1 | Forge | 17 | 8.14.5 | 47.4.27 | ✅ |
| `forge-1.20.2` | 1.20.2 | Forge | 17 | 8.14.5 | 48.1.0 | ✅ |
| `forge-1.20.4` | 1.20.4 | Forge | 17 | 8.14.5 | 49.2.11 | ✅ |
| `neoforge-1.20.6` | 1.20.6 | NeoForge | 21 | 9.2.1 | 20.6.141 | ✅ |
| `neoforge-1.21.1` | 1.21.1 | NeoForge | 21 | 9.2.1 | 21.1.259 | ✅ |
| `neoforge-1.21.4` | 1.21.4 | NeoForge | 21 | 9.2.1 | 21.4.158 | ✅ |
| `neoforge-1.21.8` | 1.21.8 | NeoForge | 21 | 9.2.1 | 21.8.54 | ✅ |
| `neoforge-1.21.11` | 1.21.11 | NeoForge | 21 | 9.2.1 | 21.11.45 | ✅ |
| `neoforge-26.1` | 26.1 | NeoForge | 25 | 9.2.1 | 26.1.0.19-beta | ✅ |
| `neoforge-26.2` | 26.2 | NeoForge | 25 | 9.2.1 | 26.2.0.90 | ✅ |
| `neoforge-26.3` | 26.3 | NeoForge | 25 | 9.2.1 | 26.3.0.69-beta | ✅ |
| `fabric-1.20.1` | 1.20.1 | Fabric | 17 | 9.7.1 | API 0.92.12 | ✅ |
| `fabric-1.20.2` | 1.20.2 | Fabric | 17 | 9.7.1 | API 0.91.6 | ✅ |
| `fabric-1.20.4` | 1.20.4 | Fabric | 17 | 9.7.1 | API 0.97.3 | ✅ |
| `fabric-1.20.6` | 1.20.6 | Fabric | 21 | 9.7.1 | API 0.100.8 | ✅ |
| `fabric-1.21.1` | 1.21.1 | Fabric | 21 | 9.7.1 | API 0.116.17 | ✅ |
| `fabric-1.21.4` | 1.21.4 | Fabric | 21 | 9.7.1 | API 0.119.4 | ✅ |
| `fabric-1.21.8` | 1.21.8 | Fabric | 21 | 9.7.1 | API 0.136.1 | ✅ |
| `fabric-1.21.11` | 1.21.11 | Fabric | 21 | 9.7.1 | API 0.141.6 | ✅ |
| `fabric-26.1` | 26.1 | Fabric | 25 | 9.7.1 | API 0.145.1 | ✅ |
| `fabric-26.2` | 26.2 | Fabric | 25 | 9.7.1 | API 0.161.0 | ✅ |
| `fabric-26.3` | 26.3 | Fabric | 25 | 9.7.1 | API 0.162.0 | ✅ |

> Fabric Loader 统一 `0.19.5`，Loom `1.18-SNAPSHOT`。
> Fabric 的 Gradle 必须跑在 **JDK 25**（Loom 1.18 自身要求），编译目标仍由 toolchain 固定为 17/21/25 —— CI 里因此装了两次 JDK。
> `✅` = GitHub Actions 实测编译通过并产出 jar（详见 [docs/BUILD_STATUS.md](docs/BUILD_STATUS.md)）。本表 22 行全部为实测结果，无失败版本。

---

## 安装

1. 按你的 MC 版本切到对应分支，或直接下载该分支的 Actions 构建产物
2. 把 jar 放进 `mods/`
3. 首次启动会生成 `config/unbounded-height.json`
4. 改完配置用 `/height reload` 或重启生效

---

## 配置

`config/unbounded-height.json`（三个加载器**同一份**，Forge/NeoForge 的 `config/` 与 Fabric 的 `config/` 指向同一目录，存档可互换）：

```json
{
  "global": { "minY": -2032, "maxY": 2031 },
  "dimensions": {
    "minecraft:the_nether": { "minY": -2032, "maxY": 2031, "enabled": true }
  },
  "compat": {
    "autoApplyToNewDimensions": true,
    "respectNativeHeight": true,
    "forceOverrideAll": false,
    "blacklist": ["some_mod:broken_dim"],
    "whitelist": []
  },
  "command": { "permissionLevel": 2 },
  "performance": { "warnAboveSpan": 1024, "maxSpan": 4064 },
  "overrideLogicalHeight": false,
  "dimensionOverrides": ["minecraft:the_end|-2032|2031|true"]
}
```

| 键 | 说明 |
|---|---|
| `global.minY` / `global.maxY` | 全局默认高度范围，默认 `-2032` / `2031` |
| `dimensions.<维度ID>.minY/maxY` | 单维度覆盖，**优先于全局** |
| `dimensions.<维度ID>.enabled` | 是否对该维度启用扩展，默认 `true` |
| `compat.autoApplyToNewDimensions` | 是否自动应用到新发现的维度，默认 `true` |
| `compat.respectNativeHeight` | 是否尊重模组维度的原生高度，默认 `true` |
| `compat.forceOverrideAll` | 强制覆盖所有维度（含模组维度），默认 `false`，优先级最高 |
| `compat.blacklist` | 黑名单，列出的维度**不**应用任何修改 |
| `compat.whitelist` | 白名单，**非空时**只对列出的维度生效（与黑名单互斥，白名单优先） |
| `command.permissionLevel` | `/height` 权限等级，默认 `2` |
| `overrideLogicalHeight` | 是否把 `logicalHeight` 一起改成扩展高度，默认 `false`（保持原版行为） |
| `dimensionOverrides` | 手写快捷方式，格式 `"维度ID|minY|maxY|enabled"`（`dimensions` 优先） |

> **为什么不用 ForgeConfigSpec / ModConfigSpec / Fabric Config？**
> 需求要求 `dimensions.<维度ID>.minY` 这种**动态键**，而 ForgeConfigSpec / NeoForge ModConfigSpec 的键必须在 build 阶段静态声明，表达不了「任意维度 ID」；
> 同时 NeoForge 的 `ModConfig.Type.COMMON` 在 26.3 被改名成 `SYNCED`，三套配置 API 会让版本分支与失败面成倍增加。
> 统一 JSON 后，22 个目标的配置行为完全一致，且能被 `/height set` 在运行时安全改写。

---

## 指令

```
/height get                          查看所有维度的高度范围
/height set <维度ID> <min_y> <max_y>   设置指定维度
/height setall <min_y> <max_y>        设置所有维度（并清空单维度覆盖）
/height reset <维度ID>                恢复指定维度的默认高度
/height resetall                     恢复所有维度
/height compat                       列出已注册维度，标记原版 / 模组，显示当前策略
/height reload                       重新读取配置文件
```

权限等级由 `command.permissionLevel` 决定（默认 2 = 管理员）。
> 1.21.11 / 26.x 把 `CommandSourceStack#hasPermission(int)` 换成了 `PermissionSet` 体系，
> 本模组在那个版本段改用 `Permissions.COMMANDS_MODERATOR/GAMEMASTER/ADMIN/OWNER` 做等价映射。

**配置变更何时生效？**
已加载区块不受影响；新高度范围在**区块重新加载**（重进世界 / 传送）后按新范围读写。
`/height set` 之后建议重新进入世界。

---

## 维度兼容性

| 场景 | 是否支持 | 说明 |
|---|---|---|
| 原版三维度 | ✅ | 默认全部扩展 |
| 数据包维度（`data/<ns>/dimension/`） | ✅ | 只要真的进得去（会创建 `ServerLevel`）就会被登记 |
| Forge / NeoForge 维度注册事件 | ✅ | 同上，**不需要**分别适配注册事件 |
| Fabric 维度加载事件 | ✅ | 同上 |
| 模组自带高度配置的维度 | ✅（尊重） | `respectNativeHeight=true` 时保持其原生高度；要强制覆盖把 `forceOverrideAll` 打开 |
| 硬编码高度假设的模组 | ⚠️ | 若扩展后异常，把该维度加进 `compat.blacklist`，或 `dimensions.<id>.enabled=false` |
| 客户端 | ✅ | 高度走原版注册表同步；专用客户端的 `ClientLevel` 永不被登记，因此不会二次覆盖 |

**登记机制**：`ServerLevel` 构造完成时把 `DimensionType 实例 → 维度 ID` 记进身份映射。
用这个钩子而不是遍历注册表，是因为：
- 零 API 风险 —— `Level.dimension()` / `Level.dimensionType()` 从 1.20.1 到 26.3 名称未变；而 `RegistryAccess.registryOrThrow` 在 26.x 改名成 `lookupOrThrow`，`Level.dimensionTypeId()` 在 26.x 被移除
- 天然覆盖动态维度 —— 有世界就一定有登记
- 天然区分客户端 —— `ServerLevel` 只在逻辑服务端存在

详见 [docs/DIMENSION_COMPATIBILITY.md](docs/DIMENSION_COMPATIBILITY.md)。

---

## 存档兼容

- **不修改已有区块的方块数据**，只扩展可操作范围
- 扩展后写入的方块用**绝对 section Y** 存档 → 拿到纯原版加载时，超出原版范围的 section 会被跳过而**不会崩溃**
- ⚠️ 移除本模组后，超出原版高度的方块将**不可见 / 不可操作**（数据仍在存档里，重新装回本模组即可恢复）

---

## 与参考模组的关系

| 参考 | 借鉴的点 | 本模组的差异 |
|---|---|---|
| [Endless](https://github.com/UpperMoon0/Endless) | 注入 `LevelHeightAccessor` 的思路；稀疏分页应对超大高度 | 本模组做**每维度独立稠密扩张**（≤4064 格，区块段数 ≤254，完全在原版结构内），因此不需要稀疏分页；也正因为每维度独立，才需要实例→维度 ID 的身份映射 |
| [BeyondHeights](https://modrinth.com/mod/beyondheights) | 配置驱动 + 维度高度覆盖 + 存档标记 | 本模组不用数据包覆盖 `dimension_type`，改走运行时注入，配置统一 JSON |
| [WAC](https://github.com/KenRouKoro/WAC) | 配置驱动的高度覆盖思路 | 同上 |
| [infs-farlands](https://github.com/InfGithub/infs-farlands) | 提醒了 `Heightmap` 是潜在坑 | 实测本方案**不需要**补 `Heightmap` —— 它的位宽由 `chunk.getHeight()` 动态推导；那个模组需要补是因为它的窗口 Y 超出了维度自身范围，而本模组是**真的把维度高度改大** |

---

## 从源码构建

```bash
git clone https://github.com/Zxin-Pro/unbounded-height
cd unbounded-height
git checkout fabric-1.21.1      # 或任意分支
./gradlew build
# 产物：build/libs/unbounded-height-<loader>-<mc>-1.0.0.jar
```

`main` 分支的 `tools/` 里有跨版本生成器（唯一真源）：

```bash
python3 tools/gen.py            # 重新生成全部 22 个分支到 tools/out/
python3 tools/gen.py forge-1.20.1
python3 tools/push_all.py       # 用 GitHub API 推送所有分支（直连不通时用）
```

---

## 许可

MIT © Zxin-Pro
