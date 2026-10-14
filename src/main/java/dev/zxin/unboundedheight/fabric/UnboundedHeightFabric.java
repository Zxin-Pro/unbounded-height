package dev.zxin.unboundedheight.fabric;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.UnboundedHeight;
import dev.zxin.unboundedheight.command.HeightCommand;
import dev.zxin.unboundedheight.config.ConfigBootstrap;
import dev.zxin.unboundedheight.network.HeightTablePacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/**
 * Fabric 入口（1.20.1 → 26.3 同一份）。
 *
 * <p>Fabric 没有内置的配置 API，本模组在三个加载器上统一使用
 * {@code config/unbounded-height.json}（见 {@code UhConfig} 注释），
 * 所以这里直接用 {@code FabricLoader#getConfigDir()}。
 */
public final class UnboundedHeightFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Bridge.bindLogger(message -> LoggerFactory.getLogger("Unbounded Height").info(message));
        Bridge.bindWarner(message -> LoggerFactory.getLogger("Unbounded Height").warn(message));

        ConfigBootstrap.init(FabricLoader.getInstance().getConfigDir());

        FabricNetworkServer.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                HeightCommand.register(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                FabricNetworkServer.sendToPlayer(handler.getPlayer(), HeightTablePacket.ofCurrent()));

        Bridge.info("Unbounded Height 已加载 (Fabric) — 高度扩展在维度创建时生效");
    }
}
