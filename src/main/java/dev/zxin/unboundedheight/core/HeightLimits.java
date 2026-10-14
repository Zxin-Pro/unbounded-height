package dev.zxin.unboundedheight.core;

/**
 * Minecraft 世界高度的<b>结构硬限制</b>。
 *
 * <p>数值来自对官方 client.jar 的字节码实测（1.20.1 与 26.3 一致），
 * 见 {@code docs/VERIFIED_INTERNALS.md}：
 * <pre>
 *   DimensionType.BITS_FOR_Y = 12
 *   DimensionType.Y_SIZE     = (1 &lt;&lt; BITS_FOR_Y) - 32 = 4064
 *   DimensionType.MAX_Y      = (Y_SIZE &gt;&gt; 1) - 1       = 2031
 *   DimensionType.MIN_Y      = MAX_Y - Y_SIZE + 1       = -2032
 *   构造器校验：minY + height &lt;= MAX_Y + 1 = 2032
 *               height % 16 == 0 / minY % 16 == 0 / height &gt;= 16 / logicalHeight &lt;= height
 *   区块存档：ChunkSerializer 用 putByte("Y", sectionY) → sectionY ∈ [-128,127]
 *             （即方块 Y ∈ [-2048, 2047]，比上面的限制更宽松，不构成瓶颈）
 * </pre>
 *
 * <p>所以可配置的合法区间是：{@code -2032 <= minY}、{@code maxY <= 2031}、
 * 且 {@code maxY - minY + 1 <= 4064}。超出即拒绝并在日志报错（需求 6「边界保护」）。
 */
public final class HeightLimits {

    /** 最低可建造方块 Y。 */
    public static final int HARD_MIN_Y = -2032;
    /** 最高可建造方块 Y（= minY + height - 1 的上界）。 */
    public static final int HARD_MAX_Y = 2031;
    /** 总跨度上限（格）。 */
    public static final int HARD_MAX_SPAN = 4064;
    /** 区块 section 边长。 */
    public static final int SECTION = 16;
    /** 原版主世界默认。 */
    public static final int VANILLA_OVERWORLD_MIN_Y = -64;
    public static final int VANILLA_OVERWORLD_HEIGHT = 384;
    /** 原版下界 / 末地默认。 */
    public static final int VANILLA_NETHER_MIN_Y = 0;
    public static final int VANILLA_NETHER_HEIGHT = 256;

    private HeightLimits() {
    }

    /** 向下对齐到 16 的倍数。 */
    public static int floorToSection(int y) {
        return y & ~(SECTION - 1);
    }

    /** 向上对齐到 16 的倍数。 */
    public static int ceilToSection(int y) {
        return (y + SECTION - 1) & ~(SECTION - 1);
    }

    /** 该 minY/height 组合是否是「原版默认高度」之一（用于 respectNativeHeight 判断）。 */
    public static boolean isVanillaLikeHeight(int minY, int height) {
        return (minY == VANILLA_OVERWORLD_MIN_Y && height == VANILLA_OVERWORLD_HEIGHT)
                || (minY == VANILLA_NETHER_MIN_Y && height == VANILLA_NETHER_HEIGHT);
    }

    /** 判定 text 是否是三个原版维度之一。 */
    public static boolean isVanillaDimension(String id) {
        return "minecraft:overworld".equals(id)
                || "minecraft:the_nether".equals(id)
                || "minecraft:the_end".equals(id);
    }
}
