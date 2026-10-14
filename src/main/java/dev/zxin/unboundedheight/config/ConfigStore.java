package dev.zxin.unboundedheight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置文件的读写（Gson）。三个加载器共用。
 *
 * <p>文件位置由加载器给出（Forge/NeoForge: {@code config/}；Fabric: {@code config/}），
 * 本类只负责序列化与磁盘 IO。
 */
public final class ConfigStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String FILE_NAME = "unbounded-height.json";

    private ConfigStore() {
    }

    public static Path resolve(Path configDir) {
        return configDir.resolve(FILE_NAME);
    }

    /** 读盘；文件不存在或损坏时写出默认配置并返回默认值。 */
    public static UhConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                UhConfig parsed = GSON.fromJson(json, UhConfig.class);
                if (parsed != null) {
                    parsed.normalize();
                    return parsed;
                }
            }
        } catch (Exception e) {
            System.err.println("[Unbounded Height] 读取配置失败，使用默认值: " + e);
        }
        UhConfig fresh = UhConfig.defaults();
        fresh.normalize();
        save(file, fresh);
        return fresh;
    }

    public static void save(Path file, UhConfig config) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[Unbounded Height] 写出配置失败: " + e);
        }
    }

    /** 从内存 JSON 反序列化（供原生 TOML 层合并用）。 */
    public static UhConfig fromJson(String json) {
        try {
            UhConfig parsed = GSON.fromJson(json, UhConfig.class);
            if (parsed != null) {
                parsed.normalize();
                return parsed;
            }
        } catch (Exception ignored) {
        }
        return UhConfig.defaults();
    }

    public static String toJson(UhConfig config) {
        return GSON.toJson(config);
    }
}
