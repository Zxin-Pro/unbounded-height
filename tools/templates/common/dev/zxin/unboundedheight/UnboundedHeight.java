package dev.zxin.unboundedheight;

//#if MC>=12111
// MC 1.21.11 / 26.x：Mojang 把 ResourceLocation 统一改名为 Identifier
import net.minecraft.resources.Identifier;
//#else
import net.minecraft.resources.ResourceLocation;
//#endif

/**
 * 无界高度 · Unbounded Height
 *
 * <p>移除 Minecraft 原版三个维度（主世界 / 下界 / 末地）的建筑高度限制，
 * 并为其他模组动态注册的新维度自动套用同样的高度扩展范围。
 * 纯高度限制修改 + 维度兼容 + 配置驱动：<b>不添加任何新物品、方块、实体</b>。
 *
 * <p>核心机制见 {@code dev.zxin.unboundedheight.mixin.DimensionTypeMixin}：
 * 注入 {@code DimensionType} 的 {@code minY()} / {@code height()} 记录访问器，
 * 让世界高度在<b>唯一真源</b>处被改写，从而自动带动
 * {@code LevelHeightAccessor} 的全部 default 方法、区块 sections 数组容量、
 * Heightmap 位宽、区块序列化，以及（通过原版注册表同步的）
 * 客户端渲染与碰撞。
 */
public final class UnboundedHeight {

    public static final String MOD_ID = "unbounded_height";
    public static final String MOD_NAME = "Unbounded Height";
    public static final String VERSION = "1.0.0";

    private UnboundedHeight() {
    }

//#if MC>=12111
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
//#elif MC>=12100
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
//#else
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
//#endif
}
