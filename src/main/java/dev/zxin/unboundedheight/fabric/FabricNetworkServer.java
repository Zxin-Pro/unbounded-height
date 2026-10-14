package dev.zxin.unboundedheight.fabric;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.UnboundedHeight;
import dev.zxin.unboundedheight.network.HeightTablePacket;
import net.minecraft.server.level.ServerPlayer;

import dev.zxin.unboundedheight.network.HeightTablePayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

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

        // Fabric API 26.x（networking-api-v1 6.x）：playC2S/playS2C → serverboundPlay/clientboundPlay
        PayloadTypeRegistry.clientboundPlay().register(HeightTablePayload.TYPE, HeightTablePayload.STREAM_CODEC);

        Bridge.bindTableSender((player, text) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                sendToPlayer(serverPlayer, new HeightTablePacket(text));
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, HeightTablePacket packet) {

        ServerPlayNetworking.send(player, HeightTablePayload.of(packet));

    }

}
