package dev.zxin.unboundedheight.fabric;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.UnboundedHeight;
import dev.zxin.unboundedheight.network.HeightTablePacket;
import net.minecraft.server.level.ServerPlayer;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric 网络层（服务端侧）。
 *
 * <p>两个版本段：
 * <ul>
 *   <li><b>1.20.5+</b>：原版 {@code CustomPacketPayload} + Fabric 的
 *       {@code PayloadTypeRegistry}。注意 26.x 里 Fabric API 把
 *       {@code playS2C()} 改名成 {@code clientboundPlay()}。</li>
 *   <li><b>1.20.1 ~ 1.20.4</b>：传统 {@code Identifier + PacketByteBuf} 通道。</li>
 * </ul>
 * 载荷内容始终是一个 UTF 字符串。
 */
public final class FabricNetworkServer {

    private FabricNetworkServer() {
    }

    public static void register() {

        // 1.20.1~1.20.4：没有 CustomPayload，用传统通道（客户端侧在 UnboundedHeightFabricClient 注册接收器）

        Bridge.bindTableSender((player, text) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                sendToPlayer(serverPlayer, new HeightTablePacket(text));
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, HeightTablePacket packet) {

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HeightTablePacket.encode(packet, buf);
        ServerPlayNetworking.send(player, CHANNEL_ID, buf);

    }

    public static final ResourceLocation CHANNEL_ID = UnboundedHeight.id("height_table");

}
