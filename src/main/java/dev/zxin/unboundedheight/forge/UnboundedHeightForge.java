package dev.zxin.unboundedheight.forge;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.UnboundedHeight;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.LoggerFactory;

/** Forge 入口（1.20.1 / 1.20.2 / 1.20.4）。 */
@Mod(UnboundedHeight.MOD_ID)
public final class UnboundedHeightForge {

    public UnboundedHeightForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        Bridge.bindLogger(message -> LoggerFactory.getLogger("Unbounded Height").info(message));
        Bridge.bindWarner(message -> LoggerFactory.getLogger("Unbounded Height").warn(message));

        // 配置（config/unbounded-height.json）在 mod 构造阶段就绪
        ForgeEvents.bootstrap();

        // 自定义通道（高度表同步；功能高度本身走原版注册表同步，见 docs）
        ForgeNetwork.register();

        Bridge.info("Unbounded Height 已加载 (Forge) — 高度扩展在维度创建时生效");
    }
}
