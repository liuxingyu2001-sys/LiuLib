package com.liuxingyu.liulib.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Minecraft 官方语言文件加载器（{@code plugins/LiuLib/lang/<locale>.json}）。
 *
 * <p>{@code ItemStack#getI18NDisplayName()} 跟随服务器默认 locale（通常是英文），
 * 而插件消息/GUI 需要按 {@code config.yml: language} 显示。把对应语言文件放入
 * {@code lang/} 后，{@link ItemNames} 会优先用它解析原版物品/附魔名称。</p>
 *
 * <p>语言文件可从客户端版本 jar 或 {@code assets/minecraft/lang/} 提取；
 * 缺失时静默回退，不影响插件运行。</p>
 */
public final class MinecraftLang {

    private final Logger logger;
    private final File directory;
    private volatile Map<String, Map<String, String>> languages = Map.of();

    public MinecraftLang(Logger logger, File directory) {
        this.logger = logger;
        this.directory = directory;
    }

    /** 重新扫描 lang/*.json；失败的文件只记录 FINE 日志。 */
    public void reload() {
        Map<String, Map<String, String>> loaded = new HashMap<>();
        File[] files = directory == null ? null
                : directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try {
                    JsonObject object = JsonParser.parseString(
                            Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                    String locale = file.getName().replaceAll("(?i)\\.json$", "").toLowerCase(Locale.ROOT);
                    Map<String, String> values = new HashMap<>(object.size());
                    object.entrySet().forEach(entry -> {
                        if (entry.getValue().isJsonPrimitive()) {
                            values.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    });
                    loaded.put(locale, values);
                } catch (Throwable error) {
                    logger.fine("语言文件解析失败 " + file.getName() + ": " + error);
                }
            }
        }
        languages = Map.copyOf(loaded);
    }

    public boolean isEmpty() {
        return languages.isEmpty();
    }

    public java.util.Set<String> locales() {
        return languages.keySet();
    }

    /**
     * 按语言取译文：当前语言 → 英文 → 任意语言；无则 null。
     */
    public String get(String key, String language) {
        if (key == null || languages.isEmpty()) {
            return null;
        }
        String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        String[] tries = normalized.isBlank()
                ? new String[]{"zh_cn", "en_us", "en"}
                : new String[]{normalized, normalized.replace('-', '_'), "en_us", "en"};
        for (String locale : tries) {
            Map<String, String> map = languages.get(locale);
            if (map != null) {
                String value = map.get(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        for (Map<String, String> map : languages.values()) {
            String value = map.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
