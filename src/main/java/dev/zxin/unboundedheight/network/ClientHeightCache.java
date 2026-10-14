package dev.zxin.unboundedheight.network;

/**
 * 客户端侧的高度表缓存。
 *
 * <p>纯展示用途：客户端真正用于渲染/碰撞的高度来自原版注册表同步的
 * {@code DimensionType}，与这里的文本无关。这里保存的是「配置层面的表」，
 * 便于客户端侧显示 / 排查。
 */
public final class ClientHeightCache {

    private static volatile String table = "";

    private ClientHeightCache() {
    }

    public static void set(String text) {
        table = text == null ? "" : text;
    }

    public static String get() {
        return table;
    }

    public static int dimensionCount() {
        int n = 0;
        for (String line : table.split("\n")) {
            if (!line.isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
