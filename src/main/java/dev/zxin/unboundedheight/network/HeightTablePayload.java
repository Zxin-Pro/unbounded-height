package dev.zxin.unboundedheight.network;

import dev.zxin.unboundedheight.UnboundedHeight;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 1.20.5+ 的原版自定义载荷外壳（NeoForge 全版本 / Fabric 1.20.5+ 使用）。
 *
 * <p>1.20.1~1.20.4 没有这套 API，那三个版本走各加载器的传统通道
 * （Forge {@code SimpleChannel} / Fabric {@code PacketByteBuf}），
 * 载荷内容仍然是同一个 UTF 字符串。
 */
public record HeightTablePayload(String table) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HeightTablePayload> TYPE =
            new CustomPacketPayload.Type<HeightTablePayload>(UnboundedHeight.id("height_table"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HeightTablePayload> STREAM_CODEC =
            StreamCodec.of(
                    (RegistryFriendlyByteBuf buf, HeightTablePayload payload) -> buf.writeUtf(payload.table()),
                    (RegistryFriendlyByteBuf buf) -> new HeightTablePayload(buf.readUtf()));

    public static HeightTablePayload of(HeightTablePacket packet) {
        return new HeightTablePayload(packet.table);
    }

    public HeightTablePacket toPacket() {
        return new HeightTablePacket(table);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
