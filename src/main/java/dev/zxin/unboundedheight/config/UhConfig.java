package dev.zxin.unboundedheight.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置模型（纯 POJO，Gson 直接序列化）。
 *
 * <p><b>为什么统一用 JSON 而不是 ForgeConfigSpec / ModConfigSpec / Fabric Config：</b>
 * 需求要求 {@code dimensions.<维度ID>.minY/maxY/enabled} 这种<b>动态键</b>，
 * 而 ForgeConfigSpec / NeoForge ModConfigSpec 的键必须在 build 阶段静态声明，
 * 无法表达「任意维度 ID」；同时 22 个目标里 NeoForge 的
 * {@code ModConfig.Type.COMMON} 在 26.3 被改名为 {@code SYNCED}，
 * 三套配置 API 会让版本分支与失败面成倍增加。
 * 因此本模组在<b>所有加载器</b>上统一使用 {@code config/unbounded-height.json}，
 * 键路径与需求文档 1:1 对应，且能被 {@code /height set} 在运行时安全改写。
 */
public final class UhConfig {

    /** 全局默认高度范围。 */
    public Global global = new Global();
    /** 单维度覆盖：key 是维度 ID（如 {@code minecraft:the_nether}）。 */
    public Map<String, DimOverride> dimensions = new LinkedHashMap<>();
    /** 兼容性策略。 */
    public Compat compat = new Compat();
    /** 指令设置。 */
    public Command command = new Command();
    /** 性能相关。 */
    public Performance performance = new Performance();
    /** 是否把 logicalHeight 一并改成扩展后的高度（默认 false，保持原版行为）。 */
    public boolean overrideLogicalHeight = false;
    /**
     * 维度覆盖的「手写快捷方式」，与 {@link #dimensions} 合并（dimensions 优先）。
     * 每条形如 {@code "minecraft:the_nether|-2032|2031|true"}（id|minY|maxY|enabled）。
     */
    public List<String> dimensionOverrides = new ArrayList<>();

    public static UhConfig defaults() {
        UhConfig c = new UhConfig();
        c.global.minY = -2032;
        c.global.maxY = 2031;
        return c;
    }

    // ------------------------------------------------------------------ 子结构

    public static final class Global {
        public int minY = -2032;
        public int maxY = 2031;
    }

    public static final class DimOverride {
        /** 该维度扩展后的最低 Y。 */
        public int minY = -2032;
        /** 该维度扩展后的最高 Y。 */
        public int maxY = 2031;
        /** 是否对该维度启用高度扩展。 */
        public boolean enabled = true;

        public DimOverride() {
        }

        public DimOverride(int minY, int maxY, boolean enabled) {
            this.minY = minY;
            this.maxY = maxY;
            this.enabled = enabled;
        }
    }

    public static final class Compat {
        /** 是否自动把高度扩展应用到新发现的（模组）维度。 */
        public boolean autoApplyToNewDimensions = true;
        /** 是否尊重模组维度自带的原生高度（默认 true，不强行覆盖）。 */
        public boolean respectNativeHeight = true;
        /** 是否强制覆盖所有维度（含模组维度）的高度，优先级最高。 */
        public boolean forceOverrideAll = false;
        /** 黑名单：列出的维度不应用任何高度修改。 */
        public List<String> blacklist = new ArrayList<>();
        /** 白名单：仅列出的维度应用高度修改（非空时与黑名单互斥，白名单优先）。 */
        public List<String> whitelist = new ArrayList<>();
    }

    public static final class Command {
        /** /height 需要的权限等级，默认 2（管理员）。 */
        public int permissionLevel = 2;
    }

    public static final class Performance {
        /** 总跨度超过该值即打印性能警告。 */
        public int warnAboveSpan = 1024;
        /** 总跨度硬上限（不允许配置超过结构硬限制）。 */
        public int maxSpan = 4064;
    }

    // ------------------------------------------------------------------ 工具

    /** 把 {@link #dimensionOverrides} 的快捷写法合并进 {@link #dimensions}（dimensions 优先）。 */
    public void mergeShortcuts() {
        if (dimensionOverrides == null) {
            return;
        }
        for (String line : dimensionOverrides) {
            if (line == null) {
                continue;
            }
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] parts = s.split("\\|");
            if (parts.length < 3) {
                continue;
            }
            String id = parts[0].trim();
            if (id.isEmpty() || dimensions.containsKey(id)) {
                continue;
            }
            try {
                int minY = Integer.parseInt(parts[1].trim());
                int maxY = Integer.parseInt(parts[2].trim());
                boolean enabled = parts.length < 4 || Boolean.parseBoolean(parts[3].trim());
                dimensions.put(id, new DimOverride(minY, maxY, enabled));
            } catch (NumberFormatException ignored) {
                // 忽略坏行
            }
        }
    }

    /** 规整化：补齐 null、合并快捷写法。 */
    public void normalize() {
        if (global == null) {
            global = new Global();
        }
        if (dimensions == null) {
            dimensions = new LinkedHashMap<>();
        }
        if (compat == null) {
            compat = new Compat();
        }
        if (compat.blacklist == null) {
            compat.blacklist = new ArrayList<>();
        }
        if (compat.whitelist == null) {
            compat.whitelist = new ArrayList<>();
        }
        if (command == null) {
            command = new Command();
        }
        if (performance == null) {
            performance = new Performance();
        }
        if (performance.maxSpan <= 0) {
            performance.maxSpan = 4064;
        }
        if (command.permissionLevel < 0) {
            command.permissionLevel = 0;
        }
        if (command.permissionLevel > 4) {
            command.permissionLevel = 4;
        }
        mergeShortcuts();
    }
}
