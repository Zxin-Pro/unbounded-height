package dev.zxin.unboundedheight.core;

import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.config.ConfigStore;
import dev.zxin.unboundedheight.config.UhConfig;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高度管理器 —— 本模组的大脑。
 *
 * <p>职责：
 * <ol>
 *   <li>持有当前配置（{@code config/unbounded-height.json}）。</li>
 *   <li>维护 {@code DimensionType 实例 → 维度 ID} 的身份映射（由 {@code LevelMixin} 在每个世界构造时登记）。</li>
 *   <li>为每个 DimensionType 实例解析出「生效高度」并缓存。</li>
 *   <li>边界保护：section 对齐、硬限制拒绝、性能警告。</li>
 *   <li>维度兼容策略：黑/白名单、forceOverrideAll、respectNativeHeight、autoApplyToNewDimensions。</li>
 * </ol>
 *
 * <p><b>为什么用「身份映射」而不是全局高度：</b>本模组是<b>每维度独立稠密扩张</b>，
 * 与 Endless 的「全局高度 + 稀疏分页」完全不同，因此必须知道每个 DimensionType 属于哪个维度。
 * 映射在 {@code Level} 构造时登记，正好覆盖「其他模组动态注册的新维度」——有世界就一定有登记。
 *
 * <p><b>线程安全：</b>访问器会在渲染线程、服务端主线程、区块 IO 线程上被调用，
 * 因此所有可变状态都用同步容器 / volatile。
 */
public final class HeightManager {

    /** 解析结果：{@code [minY, height, logicalHeight]}。 */
    private static final int[] NONE = new int[0];

    /** DimensionType 实例 → 维度 ID。 */
    private static final Map<Object, String> ID_BY_INSTANCE =
            Collections.synchronizedMap(new IdentityHashMap<Object, String>());

    /** DimensionType 实例 → 原生值 {minY, height, logicalHeight}（用于 /height compat 与策略判断）。 */
    private static final Map<Object, int[]> NATIVE =
            Collections.synchronizedMap(new IdentityHashMap<Object, int[]>());

    /** DimensionType 实例 → 生效值；{@link #NONE} 表示「不改动」。 */
    private static final Map<Object, int[]> RESOLVED =
            Collections.synchronizedMap(new IdentityHashMap<Object, int[]>());

    /** 一次性日志去重。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private static volatile UhConfig CONFIG = UhConfig.defaults();
    private static volatile Path configFile;
    private static volatile boolean loaded;

    /** 维度 ID 的反射取值方法（1.20.1 是 location()，26.x 改名为 identifier()）。 */
    private static volatile Method idMethod;

    private HeightManager() {
    }

    // ================================================================== 生命周期

    public static void init(Path file) {
        configFile = file;
        CONFIG = ConfigStore.load(file);
        loaded = true;
        Bridge.info("配置已加载: global=" + CONFIG.global.minY + ".." + CONFIG.global.maxY
                + " 维度覆盖=" + CONFIG.dimensions.size()
                + " autoApply=" + CONFIG.compat.autoApplyToNewDimensions
                + " respectNative=" + CONFIG.compat.respectNativeHeight
                + " forceAll=" + CONFIG.compat.forceOverrideAll);
    }

    public static UhConfig config() {
        return CONFIG;
    }

    public static Path configFile() {
        return configFile;
    }

    /** 重新读盘并清空解析缓存（配置变更后调用）。 */
    public static void reload() {
        if (configFile != null) {
            CONFIG = ConfigStore.load(configFile);
        } else {
            CONFIG.normalize();
        }
        invalidate();
        Bridge.info("配置已重载，高度解析缓存已清空");
    }

    /** 清空「生效值」缓存，使下次访问重新解析（原生值与维度映射保留）。 */
    public static void invalidate() {
        RESOLVED.clear();
    }

    // ================================================================== 维度登记

    /**
     * 由 {@code LevelMixin} 在每个世界（{@code Level} 子类）构造完成后调用。
     *
     * @param dimensionKey  {@code Level#dimension()} 返回的 ResourceKey
     * @param dimensionType {@code Level#dimensionType()} 返回的 DimensionType 实例
     */
    public static void registerLevel(Object dimensionKey, Object dimensionType) {
        if (dimensionType == null) {
            return;
        }
        try {
            String id = idOf(dimensionKey);
            if (id == null) {
                return;
            }
            String prev = ID_BY_INSTANCE.put(dimensionType, id);
            if (prev == null || !prev.equals(id)) {
                // 新维度出现 → 旧缓存可能基于「未登记」状态，清掉重算
                RESOLVED.clear();
                logOnce("dim:" + id, "登记维度 " + id
                        + (HeightLimits.isVanillaDimension(id) ? "（原版）" : "（模组/数据包）"));
            }
        } catch (Throwable t) {
            Bridge.warn("登记维度失败（已忽略，不影响游戏）: " + t);
        }
    }

    /** 把 ResourceKey 转成 "namespace:path"。1.20.1 用 location()，26.x 用 identifier()。 */
    private static String idOf(Object resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        try {
            Method m = idMethod;
            if (m == null) {
                m = findIdMethod(resourceKey.getClass());
                idMethod = m;
            }
            if (m != null) {
                Object loc = m.invoke(resourceKey);
                if (loc != null) {
                    return loc.toString();
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的字符串兜底
        }
        // 兜底：ResourceKey[registry / namespace:path]
        String s = String.valueOf(resourceKey);
        int i = s.indexOf(" / ");
        if (i >= 0) {
            int j = s.indexOf(']', i);
            if (j > i) {
                return s.substring(i + 3, j);
            }
        }
        return null;
    }

    private static Method findIdMethod(Class<?> cls) {
        for (String name : new String[]{"location", "identifier"}) {
            try {
                Method m = cls.getMethod(name);
                if (m.getReturnType() != String.class) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    // ================================================================== 核心解析

    /**
     * 由 {@code DimensionTypeMixin} 在每个访问器调用时询问「该不该改写」。
     *
     * @return {@code null} 表示不改动；否则返回 {@code [minY, height, logicalHeight]}。
     */
    /**
     * 快路径：只查缓存。{@code null} = 尚未解析（需要走慢路径）；
     * 长度 0 的数组 = 已解析为「不改动」；否则是 {@code [minY, height, logicalHeight]}。
     */
    public static int[] cached(Object dimensionType) {
        return RESOLVED.get(dimensionType);
    }

    public static int[] effective(Object dimensionType, int nativeMinY, int nativeHeight, int nativeLogical) {
        try {
            int[] cached = RESOLVED.get(dimensionType);
            if (cached != null) {
                return cached == NONE ? null : cached;
            }
            NATIVE.put(dimensionType, new int[]{nativeMinY, nativeHeight, nativeLogical});

            String id = ID_BY_INSTANCE.get(dimensionType);
            if (id == null) {
                // 还没登记（例如注册表加载阶段）。缓存「不改动」以免每次访问都走慢路径；
                // registerLevel() 登记新维度时会清空缓存，因此登记后一定会重新解析。
                RESOLVED.put(dimensionType, NONE);
                return null;
            }
            int[] eff = resolve(id, nativeMinY, nativeHeight, nativeLogical);
            RESOLVED.put(dimensionType, eff == null ? NONE : eff);
            return eff;
        } catch (Throwable t) {
            // 需求 2 最后一条：任何异常都回退到原生高度，绝不因为本模组而崩游戏
            Bridge.warn("解析高度时异常，已回退到该维度的原生高度: " + t);
            RESOLVED.put(dimensionType, NONE);
            return null;
        }
    }

    private static int[] resolve(String id, int nMin, int nHeight, int nLogical) {
        UhConfig cfg = CONFIG;

        if (contains(cfg.compat.blacklist, id)) {
            logOnce("bl:" + id, "维度 " + id + " 命中 compat.blacklist，保持原生高度");
            return null;
        }
        if (!cfg.compat.whitelist.isEmpty() && !contains(cfg.compat.whitelist, id)) {
            return null;
        }

        int wantMin;
        int wantMax;
        UhConfig.DimOverride ov = cfg.dimensions.get(id);
        if (ov != null) {
            if (!ov.enabled) {
                logOnce("off:" + id, "维度 " + id + " 在配置中被设为 enabled=false，保持原生高度");
                return null;
            }
            wantMin = ov.minY;
            wantMax = ov.maxY;
        } else if (cfg.compat.forceOverrideAll) {
            wantMin = cfg.global.minY;
            wantMax = cfg.global.maxY;
        } else if (HeightLimits.isVanillaDimension(id)) {
            wantMin = cfg.global.minY;
            wantMax = cfg.global.maxY;
        } else if (cfg.compat.autoApplyToNewDimensions) {
            if (cfg.compat.respectNativeHeight && !HeightLimits.isVanillaLikeHeight(nMin, nHeight)) {
                logOnce("respect:" + id, "维度 " + id + " 自带原生高度 " + nMin + ".." + (nMin + nHeight - 1)
                        + "，按 compat.respectNativeHeight=true 保持不动（要强制覆盖请开 compat.forceOverrideAll）");
                return null;
            }
            wantMin = cfg.global.minY;
            wantMax = cfg.global.maxY;
        } else {
            return null;
        }

        int[] norm = normalize(id, wantMin, wantMax, cfg);
        if (norm == null) {
            return null;
        }
        int effMin = norm[0];
        int effHeight = norm[1];
        if (effMin == nMin && effHeight == nHeight) {
            return null; // 与原版一致，不必改写
        }
        int effLogical = cfg.overrideLogicalHeight ? effHeight : nLogical;
        logOnce("apply:" + id, "应用高度扩展: " + id + " " + nMin + ".." + (nMin + nHeight - 1)
                + " → " + effMin + ".." + (effMin + effHeight - 1) + "（总高 " + effHeight + "）");
        return new int[]{effMin, effHeight, effLogical};
    }

    /**
     * 边界保护：section 对齐 + 硬限制校验。
     *
     * @return {@code [minY, height]}；越界返回 {@code null}（拒绝配置并报错）。
     */
    private static int[] normalize(String id, int minY, int maxY, UhConfig cfg) {
        if (maxY < minY) {
            errorOnce("rev:" + id, "拒绝配置 " + id + "：maxY(" + maxY + ") < minY(" + minY + ")");
            return null;
        }
        int lo = HeightLimits.floorToSection(minY);
        int hi = HeightLimits.ceilToSection(maxY + 1) - 1;
        int span = hi - lo + 1;

        if (lo < HeightLimits.HARD_MIN_Y || hi > HeightLimits.HARD_MAX_Y || span > HeightLimits.HARD_MAX_SPAN) {
            errorOnce("hard:" + id, "拒绝配置 " + id + "：" + minY + ".." + maxY
                    + " 超出 Minecraft 世界高度的结构硬限制（minY >= " + HeightLimits.HARD_MIN_Y
                    + "，maxY <= " + HeightLimits.HARD_MAX_Y + "，总高 <= " + HeightLimits.HARD_MAX_SPAN
                    + "）。该维度保持原生高度。");
            return null;
        }
        int maxSpan = Math.min(cfg.performance.maxSpan, HeightLimits.HARD_MAX_SPAN);
        if (span > maxSpan) {
            errorOnce("cfgspan:" + id, "拒绝配置 " + id + "：总高 " + span
                    + " 超过 performance.maxSpan=" + maxSpan + "，该维度保持原生高度。");
            return null;
        }
        if (lo != minY || hi != maxY) {
            logOnce("align:" + id, "配置 " + minY + ".." + maxY + " 已对齐到 section 边界 → "
                    + lo + ".." + hi + "（总高 " + span + "）");
        }
        if (span > cfg.performance.warnAboveSpan) {
            warnOnce("perf:" + id, "性能提醒：维度 " + id + " 总高 " + span
                    + " 格（section 数 " + (span / HeightLimits.SECTION)
                    + "）。每个区块的 sections 数组会按此分配，"
                    + "大范围会显著增加内存占用（可参考 Endless 的稀疏分页方案）。");
        }
        return new int[]{lo, span};
    }

    // ================================================================== 查询 / 修改（供指令使用）

    /** 一行快照：[维度ID, 原生minY, 原生maxY, 生效minY, 生效maxY, 是否原版, 是否被改写]。 */
    public static List<String[]> snapshot() {
        List<String[]> rows = new ArrayList<>();
        Map<Object, String> copy;
        synchronized (ID_BY_INSTANCE) {
            copy = new LinkedHashMap<>(ID_BY_INSTANCE);
        }
        for (Map.Entry<Object, String> e : copy.entrySet()) {
            Object dt = e.getKey();
            String id = e.getValue();
            int[] nat = NATIVE.get(dt);
            int[] eff = RESOLVED.get(dt);
            int nMin = nat != null ? nat[0] : 0;
            int nHeight = nat != null ? nat[1] : 0;
            int eMin;
            int eHeight;
            if (eff == null) {
                // 尚未解析过（世界还没真正用到该维度）：按「未改动」显示，不产生副作用。
                eMin = nMin;
                eHeight = nHeight;
            } else if (eff == NONE) {
                eMin = nMin;
                eHeight = nHeight;
            } else {
                eMin = eff[0];
                eHeight = eff[1];
            }
            boolean changed = (eMin != nMin || eHeight != nHeight);
            rows.add(new String[]{
                    id,
                    String.valueOf(nMin), String.valueOf(nMin + nHeight - 1),
                    String.valueOf(eMin), String.valueOf(eMin + eHeight - 1),
                    String.valueOf(HeightLimits.isVanillaDimension(id)),
                    String.valueOf(changed)});
        }
        rows.sort((a, b) -> a[0].compareTo(b[0]));
        return rows;
    }

    /** 设置单维度覆盖（写入配置并立即生效）。 */
    public static String setDimension(String id, int minY, int maxY) {
        int[] norm = normalize(id, minY, maxY, CONFIG);
        if (norm == null) {
            return "拒绝：范围 " + minY + ".." + maxY + " 不合法（见服务器日志）";
        }
        CONFIG.dimensions.put(id, new UhConfig.DimOverride(norm[0], norm[0] + norm[1] - 1, true));
        save();
        invalidate();
        return "已设置 " + id + " → " + norm[0] + ".." + (norm[0] + norm[1] - 1)
                + "（总高 " + norm[1] + "）。已加载的区块会在重新加载后按新范围读写。";
    }

    /** 移除单维度覆盖（回落到全局策略）。 */
    public static String resetDimension(String id) {
        boolean removed = CONFIG.dimensions.remove(id) != null;
        save();
        invalidate();
        return removed ? "已移除 " + id + " 的覆盖，回落到全局策略" : id + " 没有单独的覆盖配置";
    }

    /** 设置所有维度（写全局，并清空单维度覆盖）。 */
    public static String setAll(int minY, int maxY) {
        int[] norm = normalize("global", minY, maxY, CONFIG);
        if (norm == null) {
            return "拒绝：范围 " + minY + ".." + maxY + " 不合法（见服务器日志）";
        }
        CONFIG.global.minY = norm[0];
        CONFIG.global.maxY = norm[0] + norm[1] - 1;
        int cleared = CONFIG.dimensions.size();
        CONFIG.dimensions.clear();
        save();
        invalidate();
        return "已设置所有维度 → " + CONFIG.global.minY + ".." + CONFIG.global.maxY
                + "（总高 " + norm[1] + "，清除了 " + cleared + " 条单维度覆盖）";
    }

    /** 恢复默认（全局 -2032..2031，清空所有覆盖）。 */
    public static String resetAll() {
        UhConfig def = UhConfig.defaults();
        CONFIG.global.minY = def.global.minY;
        CONFIG.global.maxY = def.global.maxY;
        int cleared = CONFIG.dimensions.size();
        CONFIG.dimensions.clear();
        save();
        invalidate();
        return "已恢复默认高度 " + def.global.minY + ".." + def.global.maxY
                + "（清除了 " + cleared + " 条覆盖）";
    }

    private static void save() {
        if (configFile != null) {
            ConfigStore.save(configFile, CONFIG);
        }
    }

    /** 供客户端同步包使用：把快照压成一行行文本。 */
    public static String snapshotText() {
        StringBuilder sb = new StringBuilder();
        for (String[] row : snapshot()) {
            sb.append(row[0]).append('|').append(row[3]).append('|').append(row[4])
                    .append('|').append(row[5]).append('|').append(row[6]).append('\n');
        }
        return sb.toString();
    }

    // ================================================================== 日志

    private static boolean contains(List<String> list, String id) {
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (s != null && s.trim().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) {
            Bridge.info(message);
        }
    }

    private static void warnOnce(String key, String message) {
        if (LOGGED.add(key)) {
            Bridge.warn(message);
        }
    }

    private static void errorOnce(String key, String message) {
        if (LOGGED.add(key)) {
            Bridge.warn("[错误] " + message);
        }
    }
}
