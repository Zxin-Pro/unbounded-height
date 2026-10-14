# 维度兼容性说明

## 1. 为什么用「ServerLevel 构造钩子」而不是注册表遍历

需求要求「自动检测并捕获通过其他模组动态注册的新维度」。常见做法有三种：

| 做法 | 问题 |
|---|---|
| 遍历 `RegistryAccess` 里的 `Registries.DIMENSION_TYPE` | `registryOrThrow` 在 26.x 改名成 `lookupOrThrow`；且拿不到「维度 ID」（`DimensionType` 没有 ID 字段），只能靠 `getKey()` 反查 |
| 分别监听 Forge `RegisterEvent` / NeoForge `RegisterEvent` / Fabric 维度事件 | 三套 API，三份代码，三个失败面；数据包维度还不在事件里 |
| **`ServerLevel` 构造完成时登记**（本模组） | ✅ 零 API 风险 ✅ 天然覆盖数据包与模组维度 ✅ 天然区分客户端 |

本模组的做法：

```java
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void uh$registerDimension(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        HeightManager.registerLevel(self.dimension(), self.dimensionType());
    }
}
```

`Level.dimension()` / `Level.dimensionType()` 这两个方法名从 **1.20.1 到 26.3 一字未改**
（已用 `mcjar.py` 对官方 jar 逐个版本核实）。而 `Level.dimensionTypeId()` 在 26.x 被**移除**，
`RegistryAccess.registryOrThrow` 在 26.x 改名成 `lookupOrThrow` —— 这两条路都会在 26.x 断掉。

**「有世界就一定有登记」**：无论维度来自原版、数据包、Forge/NeoForge 注册事件还是 Fabric 的维度加载事件，
只要它真的能进，就一定会创建 `ServerLevel`，也就一定会被登记。

## 2. 客户端为什么不会被二次覆盖

专用客户端上没有 `ServerLevel`（只有 `ClientLevel`），因此客户端**永远不会**被登记进身份映射，
`HeightManager.effective()` 对它一律返回 `null`（不改动）。

于是：

| 场景 | 结果 |
|---|---|
| 专用服务器 + 装了本模组的客户端 | 客户端用「服务端同步过来的高度」渲染/碰撞 —— 正确 |
| 专用服务器 + **原版客户端** | 同上，仍正确（高度是原版注册表同步带过去的） |
| 单机（集成服务器） | 服务端那一份 `DimensionType` 被登记并改写；客户端那一份来自网络同步、不被登记 → 不会二次覆盖 |
| 只在客户端装本模组 | 没有服务端注册表 → 不改动 → 用同步值 → 正确 |

这正是「身份比较」而不是「全局改高度」的价值所在。

## 3. 策略矩阵

| 维度来源 | 默认行为 | 可调项 |
|---|---|---|
| `minecraft:overworld` / `the_nether` / `the_end` | 套用 `global.minY/maxY` | `dimensions.<id>` 覆盖；`enabled=false` 关闭 |
| 数据包维度 | 套用 `global`（若 `autoApplyToNewDimensions`） | `dimensions.<id>` / `compat.whitelist` / `blacklist` |
| 模组维度（自带高度） | **保持原生**（`respectNativeHeight=true`） | 打开 `forceOverrideAll` 或给该维度写 `dimensions.<id>` |
| 模组维度（高度等于原版样式） | 套用 `global` | 同上 |
| 黑名单里的维度 | 完全不改 | — |
| 白名单非空时未列出的维度 | 完全不改 | — |

「原版样式高度」的判定（`HeightLimits.isVanillaLikeHeight`）：
`(minY == -64 && height == 384)`（主世界）或 `(minY == 0 && height == 256)`（下界/末地）。

## 4. 「某些模组硬编码了高度假设」怎么处理

需求 2 最后一条要求：扩展后无法正常工作时自动回退 + 日志警告。本模组提供三层保护：

1. **异常兜底**：`HeightManager.effective()` 全程 try/catch，任何异常都 `RESOLVED.put(dt, NONE)` 并回退原生高度
2. **配置兜底**：把出问题的维度加进 `compat.blacklist`，或 `dimensions.<id>.enabled = false`
3. **日志可见**：每个维度的决策都会打一条去重日志（登记 / 应用 / 跳过 / 拒绝 / 性能提醒）

> 注意：「自动检测某个模组内部硬编码高度」在技术上不可能普适地做到（那需要理解任意模组的语义）。
> 本模组的做法是**出问题时可控**：不崩、可关、可查。

## 5. 已知的兼容性注意点

| 对象 | 注意 |
|---|---|
| 依赖「世界高度 = 384」的模组 | 可能画错雾/天空。把该维度设为 `enabled=false` 或缩短 `global` 跨度 |
| 地图/小地图模组 | 通常按维度高度自动缩放，一般无影响 |
| 结构生成模组 | 结构用自己配置的 Y，不受本模组影响 |
| 其他改高度的模组（Endless / BeyondHeights 等） | **不要同时装**。两者都改 `DimensionType`，会互相覆盖 |
| 世界边界/传送逻辑 | `maxBuildHeight` 变大后，掉出世界的判定点也随之外移，属预期 |
| Immersive Portals | 参考其 Issue #1386 的讨论；本模组不做自定义维度渲染，风险较低，但未实测 |

## 6. 存档行为

| 操作 | 结果 |
|---|---|
| 扩张高度后继续玩 | 旧区块方块位置**不变**（区块存档用绝对 section Y） |
| 扩张后写入高处方块 | 正常保存，用绝对 section Y（byte 编码，安全范围 `-2048..2047`） |
| 把扩张过的存档拿到纯原版加载 | 超出原版范围的 section 被跳过，**不崩溃**；那些方块不可见 |
| 把扩张过的存档拿到**更低**的扩张配置 | 同上，超出新范围的方块不可见但数据仍在 |
| 移除本模组 | 超出原版高度的方块不可见/不可操作（**数据还在**，装回即恢复） |
