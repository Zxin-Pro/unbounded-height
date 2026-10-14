package dev.zxin.unboundedheight.forge;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.UnboundedHeight;
import dev.zxin.unboundedheight.command.HeightCommand;
import dev.zxin.unboundedheight.config.ConfigBootstrap;
import dev.zxin.unboundedheight.network.HeightTablePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge 游戏总线事件（1.20.1 / 1.20.2 / 1.20.4 同一份）。 */
@Mod.EventBusSubscriber(modid = UnboundedHeight.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeEvents {

    private ForgeEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HeightCommand.register(event.getDispatcher());
    }

    /** 玩家进服时把当前高度表推给他（仅用于客户端展示；功能高度走原版注册表同步）。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ForgeNetwork.sendToPlayer(player, HeightTablePacket.ofCurrent());
            Bridge.info("已向 " + player.getName().getString() + " 同步高度表");
        }
    }

    /** 配置目录就绪后初始化（mod 构造阶段 config 目录已存在）。 */
    static void bootstrap() {
        ConfigBootstrap.init(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
    }
}
