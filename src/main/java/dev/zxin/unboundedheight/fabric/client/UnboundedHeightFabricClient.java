package dev.zxin.unboundedheight.fabric.client;

import dev.zxin.unboundedheight.network.HeightTablePacket;

import dev.zxin.unboundedheight.network.HeightTablePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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

        ClientPlayNetworking.registerGlobalReceiver(HeightTablePayload.TYPE, (payload, context) ->
                context.client().execute(() -> payload.toPacket().applyOnClient()));

    }
}
