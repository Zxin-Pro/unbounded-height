# 无界高度 Unbounded Height · 交接文档

> 版本 1.0.0 · 2026-10-14 · 仓库 https://github.com/Zxin-Pro/unbounded-height

---

## 1. 这是什么

Minecraft 模组：**移除原版三维度（主世界/下界/末地）的建筑高度限制**，并把扩展后的高度范围**自动套用到其他模组注册的新维度**。不加任何新物品/方块/实体，纯 Mixin 改高度 + 配置驱动。

| 项 | 值 |
|---|---|
| mod id | `unbounded_height` |
| 包名 | `dev.zxin.unboundedheight` |
| 版本 | 1.0.0 |
| 默认高度范围 | **Y = -2032 ~ 2031**（总高 4064） |
| 覆盖 | Forge 3 + NeoForge 8 + Fabric 11 = **22 个分支** |
| CI | **22/22 全绿**（含 build-all 矩阵） |

---

## 2. 当前状态（务必先看）

| 项 | 状态 |
|---|---|
| 22 分支编译 | ✅ 全绿 |
| 产物 jar 内省（元数据/refmap/类） | ✅ 逐项验过 |
| `[26.3]` 版本范围 bug | ✅ **已修**（见 §7） |
| **运行时验证** | ❌ **未做**（只有编译期 + 产物体检） |

> ⚠️ **你手上那个 26.3 的 jar 是修复前的版本，装不上。请重新下载。**
> 我这边沙箱连不上 GitHub 的 artifact blob 存储（Azure），从网页下最快：
> `https://github.com/Zxin-Pro/unbounded-height/actions/workflows/build.yml?query=branch%3Afabric-26.3`
> → 最新绿色 run → 页面底部 **Artifacts**

---

## 3. 分支与产物

```
main                          ← 文档 / CI 总 workflow / 生成器
forge-1.20.1  1.20.2  1.20.4
neoforge-1.20.6  1.21.1  1.21.4  1.21.8  1.21.11  26.1  26.2  26.3
fabric-1.20.1  1.20.2  1.20.4  1.20.6  1.21.1  1.21.4  1.21.8  1.21.11  26.1  26.2  26.3
```

产物名：`unbounded-height-<loader>-<mc>-1.0.0.jar`（Forge 37KB / NeoForge 36KB / Fabric 64KB）

**每个分支都自带**：完整 Gradle 工程、Gradle Wrapper、`.github/workflows/build.yml`（push / PR / 手动触发，自动按版本选 JDK 17/21/25）。

---

## 4. 技术核心（全部官方 jar 字节码实测，非推测）

### 4.1 唯一注入点

只在 `DimensionType` 的记录访问器上注入：

```java
@Mixin(DimensionType.class)
public abstract class DimensionTypeMixin {
    @Inject(method = "minY",   at = @At("HEAD"), cancellable = true) ...
    @Inject(method = "height", at = @At("HEAD"), cancellable = true) ...
}
```

**一处生效，全链路跟随**：`LevelHeightAccessor`（只有 `getHeight()/getMinBuildHeight()` 是抽象方法，`getSectionsCount`/`getMinSection`/`isOutsideBuildHeight` 全是 default）→ `Level`/`ChunkAccess`（纯转发）→ 区块 sections 数组 → `ChunkSerializer` → **客户端**（走原版注册表同步，见 4.3）。

### 4.2 硬限制（反编译 `<clinit>` 得出）

```
BITS_FOR_Y = 12
Y_SIZE     = (1 << 12) - 32 = 4064
MAX_Y      = (Y_SIZE >> 1) - 1 = 2031
MIN_Y      = MAX_Y - Y_SIZE + 1 = -2032

构造器校验：minY + height ≤ 2032、height % 16 == 0、minY % 16 == 0、height ≥ 16
```

> ⚠️ 你要的 `-2032 ~ 2032` 跨度是 4065，**比原版结构上限多 1 格**。
> 默认取 `-2032 ~ 2031`（顶格）；配置超限会被**拒绝并报错**，不会静默夹紧。

### 4.3 客户端同步：零自定义包

`RegistrySynchronization` 用 `RecordCodecBuilder` 的 `forGetter(DimensionType::minY)` 编码**活对象** → 走被注入的访问器 → 客户端天然拿到正确高度。

单机下用「**只注入服务端实例**」避免二次覆盖：`ServerLevelMixin` 登记维度，客户端注册表里的实例不在映射表中 → 不覆盖 → 用服务端同步来的值。

### 4.4 意外之喜

| 项 | 结论 |
|---|---|
| `Heightmap` | **不用 patch**：位宽 = `Mth.ceillog2(chunk.getHeight()+1)`，动态推导（384→9 位，4064→12 位） |
| 存档兼容 | **零迁移**：`ChunkSerializer` 写 `putByte("Y", (byte)绝对SectionY)`，旧方块留在原 Y |
| 26.3 | 官方 jar **已去混淆**（无 mappings 文件）；`ResourceLocation`→`Identifier`；`ResourceKey.location()`→`identifier()`；`Level.dimensionTypeId()` 移除 |
| 访问器名 | mojmap / yarn **同名**（`minY`/`height`/`logicalHeight`）→ 核心 Mixin **零版本分支** |

---

## 5. 工程结构（改代码前必读）

**唯一真源是 `tools/templates/`。永远不要手改分支里的文件。**

```
tools/
├── gen.py            ← 22 分支生成器（预处理器 //#if / //@only）
├── mcjar.py          ← 自写 Java class 解析器 + 字节码反汇编器（沙箱无 JVM 时的杀手锏）
├── push_all.py       ← Git Data API 批量推 23 分支（带磁盘 blob 缓存）
├── collect_jars.py   ← 拉全部 artifact 并按加载器归档
├── templates/
│   ├── common/dev/zxin/unboundedheight/    ← 三加载器共用
│   │   ├── UnboundedHeight.java  Bridge.java
│   │   ├── core/{HeightManager,HeightLimits}.java
│   │   ├── config/{UhConfig,ConfigStore,ConfigBootstrap}.java
│   │   ├── command/HeightCommand.java
│   │   ├── mixin/{DimensionTypeMixin,ServerLevelMixin}.java
│   │   └── network/{HeightTablePacket,HeightTablePayload,ClientHeightCache}.java
│   ├── forge/  neoforge/  fabric/           ← 各加载器适配
│   ├── build/{forge,neoforge,fabric}/       ← build.gradle + gradle.properties
│   ├── resources/                           ← mods.toml / neoforge.mods.toml / fabric.mod.json / mixins.json / lang
│   └── workflows/build.yml
└── out/              ← 生成物（22 个完整工程）
```

### 改代码的正确流程

```bash
cd tools
vim templates/...            # 改模板
python3 gen.py               # 重新生成 22 个分支 → out/
python3 push_all.py          # 推上 GitHub（只推变化的文件）
```

只想推单个分支：`python3 push_all.py fabric-26.3`

### `//#if` 条件表

| 条件 | 含义 |
|---|---|
| `//#if MC>=12002` | Forge 用 `ChannelBuilder`（1.20.1 用 `NetworkRegistry.newSimpleChannel`） |
| `//#if MC>=12005` | 原版 `CustomPacketPayload` 可用（Fabric/NeoForge 分支边界） |
| `//#if MC>=12111` | `CommandSourceStack#hasPermission(int)` 移除 → 改用 `Permissions.COMMANDS_*`；`Identifier` 改名 |
| `//#if MC>=26000` | Fabric `PayloadTypeRegistry.serverboundPlay/clientboundPlay`（旧名 `playC2S/playS2C`） |
| `//#if LOADER_FORGE` | 仅 Forge：mixin 配置里加 `refmap` 键 |

> ★ **必踩的坑**：非 Java 文件（JSON/TOML）如果走 `write_file` 路径写出，**必须显式调 `preprocess()`**，否则 `//#if` 不会被处理。`gen.py` 里写 mixins.json 那几行就是这个原因。

---

## 6. 配置与指令

### 配置文件

**`config/unbounded-height.json`**（所有加载器统一，游戏首次启动后生成）

```json
{
  "global": { "minY": -2032, "maxY": 2031 },
  "dimensions": {
    "minecraft:overworld": { "minY": -2032, "maxY": 2031, "enabled": true }
  },
  "compat": {
    "autoApplyToNewDimensions": true,
    "respectNativeHeight": true,
    "forceOverrideAll": false,
    "blacklist": [],
    "whitelist": []
  },
  "command": { "permissionLevel": 2 },
  "performance": { "warnAboveSpan": 1024 },
  "overrideLogicalHeight": false
}
```

**为什么不用 ForgeConfigSpec / ModConfigSpec**：`dimensions.<维度ID>.minY` 是**运行时才知道的动态键**，原生 spec 无法表达；且 26.3 把 `ModConfig.Type.COMMON` 改成了 `SYNCED`（26.1/26.2 仍是 `COMMON`），跨版本维护成本高。键路径与需求**一字不差**。

### 指令 `/height`（权限默认 2）

| 指令 | 作用 |
|---|---|
| `/height get` | 看所有维度当前高度 |
| `/height set <维度ID> <min_y> <max_y>` | 改单个维度 |
| `/height setall <min_y> <max_y>` | 改全局默认 |
| `/height reset <维度ID>` | 恢复该维度默认 |
| `/height resetall` | 全部恢复默认 |
| `/height compat` | 列出全部维度，标记原版 / 模组 / 是否已扩展 |
| `/height reload` | 重载配置文件 |

### 边界保护行为

| 情况 | 行为 |
|---|---|
| `minY < -2032` 或 `minY+height > 2032` | **拒绝配置**，日志报错，该维度保持原生 |
| 非 16 对齐 | 自动向下/向上取整到 section 边界，日志提示 |
| 跨度 > `warnAboveSpan`（默认 1024） | 记性能警告 |
| 解析过程抛异常 | 回退原生高度 + 警告（不崩） |

---

## 7. 已修的 bug（含「CI 全绿 ≠ 能跑」的教训）

### 7.1 Forge 缺 refmap（**只看 CI 绿灯发现不了**）

- **现象**：编译过、jar 也出，但 jar 里没有 `unbounded_height.refmap.json`
- **后果**：Forge 1.20.x 运行期是 SRG 名，`@Inject(method="minY")` 找不到目标，配合 `defaultRequire=1` → **直接崩**
- **根因**：少了 `annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'`
- **发现方式**：**下载产物 jar 内省**（不是看 CI）
- **修复后实测 refmap**：
  ```
  minY          → DimensionType;f_156647_()I
  height        → DimensionType;f_156648_()I
  logicalHeight → DimensionType;f_63865_()I
  ```

### 7.2 `[26.3]` 版本范围（你报的那个错）

- **现象**：Fabric 报 `模组 'Unbounded Height' 需要 'Minecraft' 的 [26.3] 版本，但已经安装了的版本 26.3 不对！`
- **根因**：`gen.py` 的 `mc_range()` 对 26.x 用了**精确匹配** `[26.3]`。MC 26.x 是两段式版本号，Fabric 内部规范成三段（26.3 → 26.3.0），精确匹配判不相等。
- **修复**：删掉 26.x 特例，统一用半开区间：
  ```python
  return '[' + mc + ',' + str(major) + '.' + str(minor + 1) + ')'
  # 26.1 → [26.1,26.2)   26.2 → [26.2,26.3)   26.3 → [26.3,26.4)
  ```
  三个加载器的元数据（fabric.mod.json / neoforge.mods.toml / mods.toml）全部生效。

---

## 8. 已知限制

1. **上限 -2032~2031（跨度 4064）**，不是 2032。突破那 1 格需要**稀疏分页**架构（Endless 路线），稠密扩张做不到。
2. **不做稀疏分页**：高度扩张后主世界每区块 sections 数组 24 → 254，但空 section 不分配调色板数据，实际开销远低于 10×。跨度超阈值会记性能警告。
3. **运行时未验证**：`/height` 的真实行为、真实世界的区块分配、模组维度兼容性都还没跑过。
4. **同 `DimensionType` 被多维度共用**时，身份映射表后写覆盖先写（极罕见）。

---

## 9. 下一步（建议优先级）

1. **跑一次真实验证**（最该做）：起一个 Fabric 1.21.1 服务端，不带模组 `setblock 0 2000 0 stone` 应失败、带模组应成功 → 端到端证明高度限制真的被移除。
2. 用 `/height compat` 在装了其他维度模组（如 Twilight Forest / 天境）的整合包里验证自动套用。
3. 如需突破 4064：稀疏分页架构攻坚（大工程）。
4. 真机跑过后再决定是否做 CurseForge / Modrinth 发布。

---

## 10. 环境备注（沙箱侧）

- **沙箱无 JVM**：PRoot 限制，`Failed to mark memory page as executable`。一切编译验证必须走 GitHub Actions。
- **`git push` 直连 github.com 不通**（SSL EOF），必须走 api.github.com → 用 `push_all.py`。
- **GitHub Actions artifact 下载**可能被 Azure blob 存储挡住（`productionresultssa18.blob.core.windows.net` 连不上）→ 从网页下。
- **26.x 官方 jar 已去混淆**，可直接用 `mcjar.py` 反编译；1.20.1~1.21.11 需配 `client_mappings.txt`。
- 官方 jar / mappings 来源：`https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
