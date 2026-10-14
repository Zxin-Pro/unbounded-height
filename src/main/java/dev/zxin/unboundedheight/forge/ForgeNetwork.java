package dev.zxin.unboundedheight.forge;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.UnboundedHeight;
import dev.zxin.unboundedheight.network.HeightTablePacket;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 网络层。
 *
 * <p>1.20.1 用旧的 {@code NetworkRegistry.newSimpleChannel} + {@code registerMessage}；
 * 1.20.2 起 Forge 改成 {@code ChannelBuilder} + {@code messageBuilder}。
 * 注意两个版本段 {@code send} 的<b>参数顺序相反</b>（1.20.1 是 {@code send(target, packet)}）。
 *
 * <p>载荷本身只有一个 UTF 字符串，所以编解码逻辑在 22 个目标上完全一致。
 */
public final class ForgeNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            UnboundedHeight.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ForgeNetwork() {
    }

    public static void register() {

        CHANNEL.registerMessage(0, HeightTablePacket.class,
                HeightTablePacket::encode,
                HeightTablePacket::decode,
                (packet, context) -> {
                    context.get().enqueueWork(packet::applyOnClient);
                    context.get().setPacketHandled(true);
                });

        Bridge.bindTableSender((player, text) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                sendToPlayer(serverPlayer, new HeightTablePacket(text));
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, HeightTablePacket packet) {

        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);

    }
}
