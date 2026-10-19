package com.liuxingyu.liulib.playtime;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 在线时长模块配置快照。
 *
 * <p>所有值在构造时做范围修正，运行期不再出现负数 tick、负数超时；
 * 重载时整体替换，避免读到半套新旧值。</p>
 */
public record PlaytimeSettings(
        boolean enabled,
        int tickSeconds,
        int afkTimeoutSeconds,
        String afkBypassPermission,
        int minSessionSeconds,
        int flushIntervalSeconds,
        int quitFlushTimeoutMillis,
        ZoneId zone,
        List<String> ignoredWorlds,
        Mode storage,
        int topCacheSeconds,
        int yamlRetentionDays,
        boolean placeholders
) {

    /** 存储模式：auto = MySQL 就绪则用 MySQL，否则 YAML。 */
    public enum Mode {
        AUTO, MYSQL, YAML;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return AUTO;
            }
        }
    }

    public PlaytimeSettings {
        tickSeconds = clamp(tickSeconds, 1, 60);
        afkTimeoutSeconds = clamp(afkTimeoutSeconds, 0, 86_400);
        afkBypassPermission = afkBypassPermission == null ? "" : afkBypassPermission.trim();
        minSessionSeconds = clamp(minSessionSeconds, 0, 86_400);
        flushIntervalSeconds = clamp(flushIntervalSeconds, 5, 86_400);
        quitFlushTimeoutMillis = clamp(quitFlushTimeoutMillis, 0, 30_000);
        zone = zone == null ? ZoneId.of("Asia/Shanghai") : zone;
        ignoredWorlds = ignoredWorlds == null ? List.of() : List.copyOf(ignoredWorlds);
        storage = storage == null ? Mode.AUTO : storage;
        topCacheSeconds = clamp(topCacheSeconds, 0, 3_600);
        yamlRetentionDays = clamp(yamlRetentionDays, 1, 3_650);
        if (afkTimeoutSeconds == 0) {
            afkBypassPermission = "";
        }
    }

    public static PlaytimeSettings defaults() {
        return new PlaytimeSettings(
                true, 1, 300, "liulib.playtime.afk-bypass",
                10, 300, 3_000, ZoneId.of("Asia/Shanghai"),
                List.of(), Mode.AUTO, 60, 60, true
        );
    }

    public static PlaytimeSettings from(ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }
        return new PlaytimeSettings(
                section.getBoolean("enabled", true),
                section.getInt("tick-seconds", 1),
                section.getInt("afk-timeout-seconds", 300),
                section.getString("afk-bypass-permission", "liulib.playtime.afk-bypass"),
                section.getInt("min-session-seconds", 10),
                section.getInt("flush-interval-seconds", 300),
                section.getInt("quit-flush-timeout-ms", 3_000),
                parseZone(section.getString("timezone", "Asia/Shanghai")),
                lowerCase(section.getStringList("ignored-worlds")),
                Mode.parse(section.getString("storage", "auto")),
                section.getInt("top-cache-seconds", 60),
                section.getInt("yaml-retention-days", 60),
                section.getBoolean("placeholders", true)
        );
    }

    /** AFK 检测是否开启（超时为 0 表示不检测，挂机也计时）。 */
    public boolean detectsAfk() {
        return afkTimeoutSeconds > 0;
    }

    public boolean ignoresWorld(String worldName) {
        if (worldName == null || ignoredWorlds.isEmpty()) {
            return false;
        }
        for (String ignored : ignoredWorlds) {
            if (ignored.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    private static ZoneId parseZone(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZoneId.of("Asia/Shanghai");
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static List<String> lowerCase(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
