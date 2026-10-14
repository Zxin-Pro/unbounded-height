package dev.zxin.unboundedheight.neoforge;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.network.HeightTablePacket;
import dev.zxin.unboundedheight.network.HeightTablePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge 网络层（1.20.6 → 26.3 同一份代码）。
 *
 * <p>实测（neoforged/NeoForge 各分支的 PayloadRegistrar.java）：
 * 三参数的 {@code playToClient(Type, StreamCodec, IPayloadHandler)} 在
 * 1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 / 26.1 / 26.2 / 26.3 上签名完全一致，
 * 因此这里<b>不需要</b>版本分支。
 */
public final class NeoNetwork {

    private NeoNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                HeightTablePayload.TYPE,
                HeightTablePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> payload.toPacket().applyOnClient()));

        Bridge.bindTableSender((player, text) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                sendToPlayer(serverPlayer, new HeightTablePacket(text));
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, HeightTablePacket packet) {
        PacketDistributor.sendToPlayer(player, HeightTablePayload.of(packet));
    }
}
