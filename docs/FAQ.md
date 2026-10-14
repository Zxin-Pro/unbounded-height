# 常见问题（FAQ）

## 装了模组但高度没变？

1. **必须重启服务器**。高度是在区块存储分配时确定的，已加载的区块不能原地扩容。改完配置先重启。
2. 用 `/height compat` 看该维度当前生效范围，以及它是否被 `blacklist` / `whitelist` / `enabled:false` / `respectNativeHeight` 排除。
3. 检查日志里的 `[Unbounded Height]` 行，被拒绝的配置一定会在日志里报错。
4. 确认客户端和服务端都装了（或都没装）。**服务端装了、客户端没装**时，原版客户端仍能连入（高度通过原版注册表同步下发），但客户端自己会按同步到的值渲染，不会有问题；反之客户端装了服务端没装则完全没有效果。

## 上限为什么是 2031 而不是 2032？

原版 `DimensionType` 的硬性校验是 `min_y + height ≤ 2032`，而最高方块 Y = `min_y + height - 1`。
`min_y = -2032` 时最高只能是 **2031**（总跨度 4064）。你要求的 `-2032..2032` 跨度 4065，**比原版结构上限多 1 格**，
会被边界保护拒绝并报错。要突破这 1 格需要「稀疏分页」架构（参考 Endless），不在本模组范围内。

## 能改到 ±8000000 吗？

不能。本模组是**稠密扩张**：整个高度范围都会参与区块 section 数组分配。
真正的无界（±8,000,000）需要把「逻辑建造范围」和「原版稠密区块核心」分离，超出部分存到稀疏分页里
（Endless 的做法），那是另一个量级的工程。本模组的硬上限是原版结构上限（跨度 4064）。

## 会很吃内存吗？

会按比例增长，但比想象中小：

- 高度扩张主要影响每区块的 `LevelChunkSection[]` 数组长度。原版主世界 24 个 section，扩到 4064 后是 **254 个**（约 10.6 倍）。
- **空 section 不会真的分配完整的调色板数据**，实际开销远低于 10 倍。
- `Heightmap` 的位宽是**动态推导**的（`ceillog2(chunk.getHeight()+1)`），高度扩张后自动从 9 位升到 12 位，客户端两端由同一个同步值推出，天然一致。
- 总跨度超过 `performance.warnAboveSpan`（默认 1024）时日志会给出性能警告，请据此评估。

## 存档兼容性

| 场景 | 结果 |
|---|---|
| 扩张后的存档，**仍用本模组**加载 | ✅ 完全正常。区块按**绝对 section Y** 存储（`putByte("Y", ...)`），旧方块留在原来的 Y 上，**零迁移** |
| 扩张后的存档，**移除本模组**用原版加载 | ⚠️ 不崩溃。超出原版范围（-64..319）的 section 在读取时被跳过，那些方块**不可见/不可操作**（不是删除，只是读不到）。重新装回模组即可恢复 |
| 原版存档，**首次**装本模组 | ✅ 正常。旧区块在原地，新生成的区块按新高度分配 |
| 把高度**调小** | ⚠️ 超出新范围的方块会被丢弃，**务必先备份** |

## 和别的维度模组冲突吗？

见 [DIMENSION_COMPATIBILITY.md](DIMENSION_COMPATIBILITY.md)。简要：

- **只加维度、不改高度的模组**（绝大多数）：完全兼容，新维度会自动应用 `global` 范围。
- **自带非原版高度的维度模组**：默认**尊重其原生高度**不覆盖（`compat.respectNativeHeight: true`）。想强制覆盖就设 `forceOverrideAll: true`，或给该维度单独写 `dimensions.<ID>`。
- **其他高度模组**（Endless / CubicChunks 等）：**不要一起装**，两者会互相打架。
- **硬编码高度假设的模组**：把该维度加进 `compat.blacklist`。

## 和光影（Iris / OptiFine）冲突吗？

本模组只改 `DimensionType` 的访问器返回值，不动渲染管线，正常情况下不冲突。
若光影在启动早期读取高度（少数情况），可能拿到尚未解析的值；此时把该维度写进 `dimensions.<ID>` 显式指定即可。

## `/height` 提示没有权限？

默认需要权限等级 2（管理员）。改 `command.permissionLevel`（0-4）。
在 1.21.11 / 26.x 上，权限等级会映射到新的 `Permissions.COMMANDS_MODERATOR/GAMEMASTER/ADMIN/OWNER` 体系。

## 客户端需要装吗？

**功能上不需要**——高度通过原版注册表同步（`RegistrySynchronization` 用 `RecordCodecBuilder` 的 `forGetter(DimensionType::minY)` 编码活对象）自动下发，客户端渲染与碰撞检测都跟着走。

**装了更好**：客户端会多收到一个 `unbounded_height:height_table` 包，内含完整高度表，便于排查问题。

## 为什么没有 `refmap.json` 在 Fabric/NeoForge 的 jar 里？

因为不需要：

- **Forge 1.20.1/1.20.2/1.20.4** 运行期使用 **SRG 名**，Mixin 里写的是官方名（`method = "minY"`），必须靠 `refmap` 翻译 → **有 refmap**。
- **NeoForge 1.20.6+** 与 **Fabric**（Loom 用 `officialMojangMappings()` 并在构建期静态重映射）运行期就是官方名 → **不需要 refmap**。
