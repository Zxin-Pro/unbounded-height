package dev.zxin.unboundedheight.neoforge;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.UnboundedHeight;
import dev.zxin.unboundedheight.command.HeightCommand;
import dev.zxin.unboundedheight.config.ConfigBootstrap;
import dev.zxin.unboundedheight.network.HeightTablePacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * NeoForge 入口（1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 / 26.1 / 26.2 / 26.3）。
 *
 * <p>与 Forge 的差别：构造器可以注入 {@code IEventBus} 与 {@code ModContainer}；
 * 事件监听统一用 {@code NeoForge.EVENT_BUS.addListener(...)}（不需要 @EventBusSubscriber）。
 */
@Mod(UnboundedHeight.MOD_ID)
public final class UnboundedHeightNeoForge {

    public UnboundedHeightNeoForge(IEventBus modBus, ModContainer container) {
        Bridge.bindLogger(message -> org.slf4j.LoggerFactory.getLogger("Unbounded Height").info(message));
        Bridge.bindWarner(message -> org.slf4j.LoggerFactory.getLogger("Unbounded Height").warn(message));

        ConfigBootstrap.init(FMLPaths.CONFIGDIR.get());

        modBus.addListener(NeoNetwork::register);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                HeightCommand.register(event.getDispatcher()));

        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                NeoNetwork.sendToPlayer(player, HeightTablePacket.ofCurrent());
                Bridge.info("已向 " + player.getName().getString() + " 同步高度表");
            }
        });

        Bridge.info("Unbounded Height 已加载 (NeoForge) — 高度扩展在维度创建时生效");
    }
}
