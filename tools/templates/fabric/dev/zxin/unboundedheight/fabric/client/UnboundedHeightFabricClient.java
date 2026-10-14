package dev.zxin.unboundedheight.fabric.client;

import dev.zxin.unboundedheight.network.HeightTablePacket;

//#if MC>=12005
import dev.zxin.unboundedheight.network.HeightTablePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//#else
import dev.zxin.unboundedheight.fabric.FabricNetworkServer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//#endif

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端入口。
 *
 * <p>注意：客户端<b>不需要</b>为了「高度正确」做任何事 ——
 * 高度是原版注册表同步带过来的，客户端的区块存储 / 渲染 / 碰撞会自动跟着走。
 * 这里只注册一个接收器，用来接收「配置层面的高度表」做展示。
 */
public final class UnboundedHeightFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
//#if MC>=12005
        ClientPlayNetworking.registerGlobalReceiver(HeightTablePayload.TYPE, (payload, context) ->
                context.client().execute(() -> payload.toPacket().applyOnClient()));
//#else
        ClientPlayNetworking.registerGlobalReceiver(FabricNetworkServer.CHANNEL_ID,
                (client, handler, buf, sender) -> {
                    HeightTablePacket packet = HeightTablePacket.decode(buf);
                    client.execute(packet::applyOnClient);
                });
//#endif
    }
}
