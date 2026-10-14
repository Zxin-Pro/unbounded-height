# 已验证的 MC 内部机制（字节码级证据）

> 工具：`tools/mcjar.py`（自写 class 文件解析器，无需 JVM）
> 数据源：`piston-meta.mojang.com` 官方 client.jar + `client_mappings.txt`（mojmap）
> 分析对象：**MC 1.20.1**（`client.jar` obf，映射见 `client_mappings.txt`）

## 1. DimensionType（obf `dfk`）—— 唯一的高度真源 ✅

```
record DimensionType(OptionalLong fixedTime, boolean hasSkyLight, boolean hasCeiling,
                     boolean ultraWarm, boolean natural, double coordinateScale,
                     boolean bedWorks, boolean respawnAnchorWorks,
                     int minY, int height, int logicalHeight,
                     TagKey<Block> infiniburn, ResourceLocation effectsLocation,
                     float ambientLight, MonsterSettings monsterSettings)
```

**mojmap 成员名（实测映射表）**
| 用途 | 字段 | 方法 | 1.20.1 混淆名 |
|---|---|---|---|
| 最低可建造 Y | `minY` | `minY()` | 字段 `s` / 方法 `n` |
| 总高度（格数） | `height` | `height()` | 字段 `t` / 方法 `o` |
| 逻辑高度 | `logicalHeight` | `logicalHeight()` | 字段 `u` / 方法 `p` |

字段名与方法名**字符串完全相同**（`minY` / `height` / `logicalHeight`）。

**构造器校验逻辑（反编译 `<init>`，逐条实测）**
```java
if (height < 16)                              throw new IllegalStateException("height has to be at least 16");
if (minY + height > MAX_Y + 1)                throw new IllegalStateException("min_y + height cannot be higher than: " + (MAX_Y+1));
if (logicalHeight > height)                   throw new IllegalStateException("logical_height cannot be higher than height");
if (height % 16 != 0)                         throw new IllegalStateException("height has to be multiple of 16");
if (minY % 16 != 0)                           throw new IllegalStateException("min_y has to be a multiple of 16");
```
**注意：构造器里没有 `minY >= -2032` 的显式检查！** 下界来自下面的静态常量。

**静态常量（反编译 `<clinit>`，全部由 `BITS_FOR_Y` 推导）**
```java
BITS_FOR_Y   = 12                       // ← 来自 SharedConstants
Y_SIZE       = (1 << BITS_FOR_Y) - 32   // = 4096 - 32 = 4064
MAX_Y        = (Y_SIZE >> 1) - 1        // = 2031
MIN_Y        = MAX_Y - Y_SIZE + 1       // = -2032
WAY_ABOVE_MAX_Y = MAX_Y << 4            // = 32496
WAY_BELOW_MIN_Y = MIN_Y << 4            // = -32512
```
→ **硬限制（本模组必须遵守的边界）**
- `minY >= -2032`
- `minY + height <= 2032`（即最高方块 Y = **2031**）
- `height % 16 == 0`、`minY % 16 == 0`、`height >= 16`、`logicalHeight <= height`
- **总跨度 ≤ 4064**（这就是"2032"这个名字的由来）

> ⚠️ 用户要求的 `Y=-2032..2032`（跨度 4065）**超出原版结构硬限制 1 格**。
> 本模组默认取 `minY=-2032, maxY=2031`（跨度 4064），并在文档中说明；超限配置会被拒绝并报错。

## 2. LevelHeightAccessor（obf `cmo`）—— 方法名与抽象性 ✅

```
int getHeight();            // 抽象（code=0）
int getMinBuildHeight();    // 抽象（code=0）
default int getMaxBuildHeight() { return getMinBuildHeight() + getHeight(); }
default int getSectionsCount()  { return getMaxSection() - getMinSection(); }
default int getMinSection()
default int getMaxSection()
default boolean isOutsideBuildHeight(BlockPos)
default boolean isOutsideBuildHeight(int)
static LevelHeightAccessor create(int minY, int height)
default int getSectionIndexFromSectionY(int)
default int getSectionYFromSectionIndex(int)
```
- 1.20.1 的名字是 **`getMinBuildHeight()`**（不是 `getMinY()`）、**`isOutsideBuildHeight()`**（不是 `isInsideBuildHeight()`）。
- `getSectionsCount()` 等全部 default 方法都建立在 `getMinBuildHeight()+getHeight()` 之上 → **改这两个源头即可全链路生效**。

## 3. ChunkAccess（obf `ddx`）—— 委托给 LevelHeightAccessor ✅

```
field  l  : LevelHeightAccessor levelHeightAccessor
method C_ : int getMinBuildHeight()   code=52  ← 转发到 levelHeightAccessor
method D_ : int getHeight()           code=52  ← 转发到 levelHeightAccessor
ctor (ChunkPos, UpgradeData, LevelHeightAccessor, Registry, long, LevelChunkSection[], BlendingData)
```
→ 区块的 sections 数组容量由传入的 `LevelHeightAccessor`（世界创建时就是 `ServerLevel`）决定，**自动跟随 DimensionType**。

## 4. ChunkSerializer（obf `csr` 包 `net.minecraft.world.level.chunk.storage`）—— 存档兼容 ✅

写入路径实测字节码：
```java
// 循环 sectionY = getMinSection() .. getMaxSection()-1
if (!section.isEmpty()) {
    tag.putByte("Y", (byte) sectionY);   // ← ldc "Y" + i2b，绝对 section Y，byte 编码
    list.add(tag);
}
```
→ 结论：
1. section 用**绝对 section Y** 定位（不是数组下标）→ **扩张高度后旧区块的方块仍在正确的世界 Y 上**，不需要迁移。
2. section Y 被编码成 **byte** → 必须 `-128 <= sectionY <= 127`，即 `-2048 <= 方块Y <= 2047`。我们的 -2032..2031 落在范围内 ✅。
3. 读取时按 `chunk.getSectionIndexFromSectionY(绝对Y)` 放回数组，越界 section 会被跳过（不会崩溃）→ **扩张后的存档拿到纯原版加载时，超出范围的方块被忽略而不崩溃** ✅（符合需求 5）。

## 5. Heightmap（obf `dhk`）—— 位宽是动态的，**不需要额外 patch** 🎉

反编译 `<init>` 实测：
```java
17: aload_1
18: invokevirtual  ChunkAccess.getHeight()   // obf ddx.D_()I
21: iconst_1
22: iadd
23: invokestatic   Mth.ceillog2(int)         // obf apa.e(I)I
26: istore_3                                  // int bits = Mth.ceillog2(height + 1)
28: new            SimpleBitStorage
32: iload_3
33: sipush 256                                // new SimpleBitStorage(bits, 256)
39: putfield       Heightmap.data
```
- 位宽 = `Mth.ceillog2(chunk.getHeight() + 1)`，**由高度动态推导**：
  - 原版主世界 height=384 → `ceillog2(385)=9`（与"9 bit"传闻一致）
  - 我们的 height=4064 → `ceillog2(4065)=12` ✅ 自动扩容
- 客户端 `setRawData` 会校验 raw 长度；两端位宽都由**同一个同步后的 DimensionType.height** 推出 → 天然一致 ✅
- ⚠️ 之前担心的"固定 9 位溢出"**不成立**。参考模组 infs-farlands 需要 patch 是因为它的**窗口 Y 超出了维度自身范围**（`chunk.getHeight()` 与它实际写入的 Y 不一致），我们的方案是**真的把维度高度改大**，所以不会触发。

## 6. 结论：本模组的最小充分注入点

```
@Mixin(targets = "net.minecraft.world.level.dimension.DimensionType")
  @Inject(method = "minY",           at = HEAD, cancellable) → cir.setReturnValue(配置值)
  @Inject(method = "height",         at = HEAD, cancellable) → cir.setReturnValue(配置值)
  @Inject(method = "logicalHeight",  at = HEAD, cancellable) → 可选
```
一条注入点即可带动：
- `Level.getMinBuildHeight()/getHeight()`（转发 DimensionType）
- `LevelHeightAccessor` 全部 default 方法（`getSectionsCount`/`getMinSection`/`getMaxSection`/`isOutsideBuildHeight`）
- `ChunkAccess`/`ProtoChunk`/`LevelChunk` 的 sections 数组容量与高度查询
- `Heightmap` 位宽（自动）
- `ChunkSerializer` 读写（绝对 Y，自动）
- **客户端**：注册表同步 `RegistrySynchronization` 用 `RecordCodecBuilder` 的 `forGetter(DimensionType::minY)` 编码 → 走被注入的访问器 → 客户端拿到扩张后的值，自动按新高度分配区块存储、渲染与碰撞 ✅

### 客户端同步为什么不需要自定义包
`RegistrySynchronization.packRegistries()` 对每个动态注册表用 **元素 codec 编码活对象**（`DimensionType.DIRECT_CODEC`，由 `RecordCodecBuilder` 构建，`forGetter` 持的是访问器方法引用）。Mixin 注入修改的是方法体本身，方法引用仍指向它 → **服务端算出的值原样发给客户端**。因此：
- 专用服务器 + 原版客户端也能正确渲染/碰撞（客户端从同步注册表拿到 height=4064）
- 单机下 `ClientLevel` 的注册表是**另一份对象**，本模组必须**只对服务端注册表里的实例生效**（见架构约定：`registry == SERVER_REGISTRY` 身份比较），否则会二次覆盖造成不一致。
