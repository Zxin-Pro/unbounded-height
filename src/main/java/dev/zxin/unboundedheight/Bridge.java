package dev.zxin.unboundedheight;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 平台桥：把「加载器相关」的能力注入到共用代码里。
 *
 * <p>三个加载器各自在初始化时调用 {@link #bind} 完成注入：
 * <ul>
 *   <li>配置读写（Forge/NeoForge 走原生 TOML，Fabric 走 config/*.json）</li>
 *   <li>服务端 → 客户端 的高度表同步发送器</li>
 *   <li>日志</li>
 * </ul>
 * 共用代码只通过本类访问平台能力，因此 {@code common/} 下的源码在 22 个目标上完全一致。
 */
public final class Bridge {

    private static volatile Supplier<dev.zxin.unboundedheight.config.UhConfig> configLoader =
            dev.zxin.unboundedheight.config.UhConfig::defaults;

    private static volatile Consumer<String> logger =
            message -> System.out.println("[Unbounded Height] " + message);

    private static volatile Consumer<String> warner =
            message -> System.err.println("[Unbounded Height] " + message);

    /** (玩家, 高度表文本) → 发送同步包。服务端侧绑定。 */
    private static volatile BiConsumer<Object, String> tableSender = (player, text) -> {
    };

    private Bridge() {
    }

    public static void bind(Supplier<dev.zxin.unboundedheight.config.UhConfig> loader,
                            Consumer<String> log,
                            Consumer<String> warn) {
        configLoader = loader;
        logger = log;
        warner = warn;
    }

    public static void bindConfig(Supplier<dev.zxin.unboundedheight.config.UhConfig> loader) {
        configLoader = loader;
    }

    public static void bindLogger(Consumer<String> log) {
        logger = log;
    }

    public static void bindWarner(Consumer<String> warn) {
        warner = warn;
    }

    /** 绑定「服务端 → 客户端」的高度表发送器。 */
    public static void bindTableSender(BiConsumer<Object, String> sender) {
        tableSender = sender;
    }

    /** 读取平台配置（每次调用都会重新读盘 / 重新组装，供 /height reload 使用）。 */
    public static dev.zxin.unboundedheight.config.UhConfig loadConfig() {
        dev.zxin.unboundedheight.config.UhConfig loaded;
        try {
            loaded = configLoader.get();
        } catch (Throwable t) {
            warn("读取配置失败，回退到默认值: " + t);
            loaded = dev.zxin.unboundedheight.config.UhConfig.defaults();
        }
        if (loaded == null) {
            loaded = dev.zxin.unboundedheight.config.UhConfig.defaults();
        }
        loaded.normalize();
        return loaded;
    }

    public static void sendTable(Object player, String text) {
        try {
            tableSender.accept(player, text);
        } catch (Throwable t) {
            warn("同步高度表失败: " + t);
        }
    }

    public static void info(String message) {
        try {
            logger.accept(message);
        } catch (Throwable ignored) {
            System.out.println("[Unbounded Height] " + message);
        }
    }

    public static void warn(String message) {
        try {
            warner.accept(message);
        } catch (Throwable ignored) {
            System.err.println("[Unbounded Height] " + message);
        }
    }
}
