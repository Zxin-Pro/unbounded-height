package dev.zxin.unboundedheight.config;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.core.HeightManager;

import java.nio.file.Path;

/**
 * 配置引导：三个加载器各自把自己的 config 目录传进来。
 *
 * <p>注意：本模组<b>不使用</b> ForgeConfigSpec / NeoForge ModConfigSpec / Fabric 的配置 API，
 * 原因见 {@link UhConfig} 的类注释（需要动态键 + 22 个目标行为一致 + 运行时改写）。
 * Forge/NeoForge 的 {@code FMLPaths.CONFIGDIR}、Fabric 的
 * {@code FabricLoader.getInstance().getConfigDir()} 都会指向同一个 {@code config/} 目录，
 * 因此存档 / 配置在加载器之间可以互换。
 */
public final class ConfigBootstrap {

    private ConfigBootstrap() {
    }

    public static void init(Path configDir) {
        Path file = ConfigStore.resolve(configDir);
        HeightManager.init(file);
        Bridge.bindConfig(() -> ConfigStore.load(file));
    }
}
