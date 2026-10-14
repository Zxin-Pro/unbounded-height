# 配置说明

配置文件路径：`config/unbounded-height.json`（服务器与客户端各自读取本地文件；**服务端是权威**）。

## 为什么是 JSON 而不是 ForgeConfigSpec / ModConfigSpec / Fabric Config

本模组**刻意**不使用三个加载器的原生配置 API，原因：

1. **动态键不可表达**。需求里的 `dimensions.<维度ID>.minY` 是**运行时才知道有哪些维度**的动态键。`ForgeConfigSpec` / `ModConfigSpec` 要求所有键在构建 spec 时静态声明，无法表达；`Fabric Config` 没有官方 API。
2. **运行时改写**。`/height set` 必须在运行时修改并持久化配置。原生 spec 的值在加载后不可变。
3. **22 个版本的一致性**。三套原生 API 在 22 个目标上会产生大量版本分支（例如 NeoForge 26.3 把 `ModConfig.Type.COMMON` 改成了 `SYNCED`）。统一 JSON 让配置行为在**所有加载器、所有版本上完全一致**。
4. 键路径与需求**一字不差**地对应，反而比 TOML 子表更贴近需求描述。

## 完整示例

```json
{
  "global": { "minY": -2032, "maxY": 2031 },
  "dimensions": {
    "minecraft:the_nether": { "minY": -2032, "maxY": 2031, "enabled": true },
    "twilightforest:twilight_forest": { "enabled": false }
  },
  "compat": {
    "autoApplyToNewDimensions": true,
    "respectNativeHeight": true,
    "forceOverrideAll": false,
    "blacklist": ["some_mod:cursed_dim"],
    "whitelist": [],
    "dimensionOverrides": ["minecraft:the_end|-2032|2031|true"]
  },
  "command": { "permissionLevel": 2 },
  "performance": { "warnAboveSpan": 1024, "maxSpan": 4064 },
  "overrideLogicalHeight": false
}
```

## 字段说明

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `global.minY` | int | `-2032` | 全局默认最低可建造 Y（含） |
| `global.maxY` | int | `2031` | 全局默认最高可建造 Y（含） |
| `dimensions.<ID>.minY` | int | — | 该维度专属下限，优先于全局 |
| `dimensions.<ID>.maxY` | int | — | 该维度专属上限 |
| `dimensions.<ID>.enabled` | bool | `true` | `false` = 完全不修改该维度 |
| `compat.autoApplyToNewDimensions` | bool | `true` | 是否自动应用到新发现的维度 |
| `compat.respectNativeHeight` | bool | `true` | 模组维度自带非原版高度时保持不动 |
| `compat.forceOverrideAll` | bool | `false` | 强制覆盖所有维度（含自带高度的模组维度） |
| `compat.blacklist` | string[] | `[]` | 黑名单，列出的维度绝不修改 |
| `compat.whitelist` | string[] | `[]` | 白名单，**非空时只有列出的维度会被修改**（与黑名单互斥，黑名单优先） |
| `compat.dimensionOverrides` | string[] | `[]` | 便捷写法：`"<维度ID>|<minY>|<maxY>|<enabled>"`，与 `dimensions` 合并，JSON 里的 `dimensions` 优先 |
| `command.permissionLevel` | int | `2` | `/height` 所需权限等级（0-4；26.x 映射到 `Permissions.COMMANDS_*`） |
| `performance.warnAboveSpan` | int | `1024` | 总跨度超过此值记录性能警告 |
| `performance.maxSpan` | int | `4064` | 允许的最大总跨度，超过则**拒绝配置并报错** |
| `overrideLogicalHeight` | bool | `false` | 是否把 `logical_height` 一起改掉（默认保持原版，最安全） |

## 优先级（从高到低）

1. `compat.blacklist` 命中 → 不修改
2. `compat.whitelist` 非空且未命中 → 不修改
3. `dimensions.<ID>.enabled = false` → 不修改
4. `dimensions.<ID>.minY/maxY`（或 `dimensionOverrides`）→ 使用该值
5. `compat.forceOverrideAll = true` → 使用 `global`
6. 原版三维度（`minecraft:overworld/the_nether/the_end`）→ 使用 `global`
7. 模组维度 + `autoApplyToNewDimensions`：
   - 若 `respectNativeHeight` 且该维度原生高度**不是**原版常见值（`(-64,384)` / `(0,256)`）→ 不修改并记录日志
   - 否则 → 使用 `global`
8. 其余 → 不修改

## 边界保护

| 规则 | 行为 |
|---|---|
| `minY < -2032` | **拒绝**，报错并保持原值 |
| `maxY > 2031` | **拒绝**，报错并保持原值 |
| 总跨度 > `performance.maxSpan`(4064) | **拒绝** |
| 总跨度 > `performance.warnAboveSpan` | 接受，记录性能警告 |
| `minY` 非 16 对齐 | 自动向下取整到 section 边界 |
| `maxY+1` 非 16 对齐 | 自动向上取整（保证覆盖你要求的范围） |
| `height < 16` | **拒绝** |

> ⚠️ **为什么上限是 2031 而不是 2032？**
> 原版 `DimensionType` 的硬性校验是 `min_y + height ≤ 2032`，而 `maxY = minY + height - 1`。
> 取 `minY = -2032` 时 `maxY` 最大只能是 **2031**（跨度 4064）。你要的 `-2032..2032` 跨度为 4065，
> 比原版结构上限多 1 格，会被拒绝并给出明确报错。这 1 格需要「稀疏分页」架构（见 Endless）才能突破。

## 修改配置的三种方式

1. 直接编辑 `config/unbounded-height.json`，然后 `/height reload`（**部分改动需要重启服务器**，见下）
2. 游戏内用 `/height set` / `/height setall` / `/height reset`（会自动写回文件）
3. 数据包 / 启动脚本预置文件

> **什么时候必须重启？**
> 高度是在**区块存储分配时**确定的。已经加载过的区块无法原地扩容，因此：
> - **首次**把范围调大 → 必须重启（新区块才会按新高度分配）
> - 在已经生效的范围**内部**微调 → `/height reload` 即可
> - 调**小** → 超出新范围的内容会被丢弃，务必先备份
