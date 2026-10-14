package dev.zxin.unboundedheight.network;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.core.HeightManager;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 高度表同步包（服务端 → 客户端）。
 *
 * <p><b>先说明一件事：功能上它并不是必需的。</b>
 * 客户端的区块存储、渲染与碰撞高度<b>已经</b>由原版注册表同步
 * （{@code RegistrySynchronization} 用 {@code DimensionType} 的 codec 编码活对象）带过去了，
 * 见 {@code docs/VERIFIED_INTERNALS.md}。本包的作用是让客户端也能拿到
 * 「配置层面的高度表」（哪些维度被改写、原生值是多少），
 * 用于客户端侧的提示 / 调试显示。
 *
 * <p>载荷被刻意设计成<b>一个 UTF 字符串</b>：这样在 22 个目标上
 * 编解码逻辑完全一致（{@code writeUtf/readUtf} 从 1.20.1 到 26.3 未变），
 * 只需要在「包的外壳」上做版本分支。
 */
public final class HeightTablePacket {

    public final String table;

    public HeightTablePacket(String table) {
        this.table = table == null ? "" : table;
    }

    public static HeightTablePacket ofCurrent() {
        return new HeightTablePacket(HeightManager.snapshotText());
    }

    /** 编码（1.20.1~26.3 通用：只有 writeUtf/readUtf 两个调用）。 */
    public static void encode(HeightTablePacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.table);
    }

    public static HeightTablePacket decode(FriendlyByteBuf buf) {
        return new HeightTablePacket(buf.readUtf());
    }

    /** 客户端收到后写进本地缓存。 */
    public void applyOnClient() {
        ClientHeightCache.set(table);
        logSummary();
    }

    public void logSummary() {
        int count = 0;
        for (String line : table.split("\n")) {
            if (!line.isEmpty()) {
                count++;
            }
        }
        Bridge.info("已从服务端同步高度表（" + count + " 个维度）");
    }
}
