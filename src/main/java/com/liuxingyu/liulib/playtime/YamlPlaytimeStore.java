package com.liuxingyu.liulib.playtime;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * YAML 在线时长存储：单服/未启用 MySQL 时的默认后端。
 *
 * <p>文件为 {@code plugins/LiuLib/playtime.yml}，只保留最近
 * {@code playtime.yaml-retention-days} 天的按天明细；写入在异步线程完成，
 * 先用临时文件再原子替换，避免服务器崩溃留下半个文件。</p>
 */
public final class YamlPlaytimeStore implements PlaytimeStore {

    private final Plugin plugin;
    private final Logger logger;
    private final PlaytimeSettings settings;
    private final File file;

    private YamlConfiguration yaml;
    private volatile boolean ready;
    private boolean dirty;

    public YamlPlaytimeStore(Plugin plugin, PlaytimeSettings settings, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = new File(plugin.getDataFolder(), "playtime.yml");
    }

    public boolean init() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                logger.warning("无法创建 LiuLib 数据目录，在线时长将只保存在内存");
                yaml = new YamlConfiguration();
                ready = true;
                return true;
            }
            yaml = YamlConfiguration.loadConfiguration(file);
            ready = true;
        } catch (Throwable error) {
            logger.log(Level.WARNING, "在线时长 YAML 加载失败，将只保存在内存", error);
            yaml = new YamlConfiguration();
            ready = false;
        }
        return ready;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public String kind() {
        return "yaml";
    }

    @Override
    public synchronized PlaytimeRecord load(UUID uuid, String day) {
        if (uuid == null) {
            return PlaytimeRecord.empty(null, day);
        }
        String base = "players." + uuid;
        String name = yaml.getString(base + ".name", "");
        long total = Math.max(0, yaml.getLong(base + ".total", 0));
        long today = Math.max(0, yaml.getLong("daily." + day + "." + uuid, 0));
        return new PlaytimeRecord(uuid, name, total, today, day);
    }

    @Override
    public synchronized void add(UUID uuid, String name, String day, long seconds) {
        if (uuid == null || seconds <= 0) {
            return;
        }
        String base = "players." + uuid;
        yaml.set(base + ".name", name == null ? "" : name);
        yaml.set(base + ".total", Math.max(0, yaml.getLong(base + ".total", 0)) + seconds);
        String dayPath = "daily." + day + "." + uuid;
        yaml.set(dayPath, Math.max(0, yaml.getLong(dayPath, 0)) + seconds);
        dirty = true;
    }

    @Override
    public synchronized List<PlaytimeEntry> top(PlaytimePeriod period, String day, String weekStart, int limit) {
        int size = Math.max(1, Math.min(100, limit));
        Map<UUID, Long> seconds = new HashMap<>();
        switch (period == null ? PlaytimePeriod.TOTAL : period) {
            case TODAY -> collectDaily(day, day, seconds);
            case WEEK -> collectDaily(weekStart, day, seconds);
            case TOTAL -> collectTotals(seconds);
        }
        List<PlaytimeEntry> out = new ArrayList<>(seconds.size());
        for (Map.Entry<UUID, Long> entry : seconds.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            out.add(new PlaytimeEntry(entry.getKey(), nameOf(entry.getKey()), entry.getValue()));
        }
        out.sort(Comparator.comparingLong(PlaytimeEntry::seconds).reversed());
        return out.size() <= size ? out : new ArrayList<>(out.subList(0, size));
    }

    private void collectTotals(Map<UUID, Long> out) {
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String raw : players.getKeys(false)) {
            UUID uuid = parseUuid(raw);
            if (uuid != null) {
                out.put(uuid, Math.max(0, players.getLong(raw + ".total", 0)));
            }
        }
    }

    private void collectDaily(String fromDay, String toDay, Map<UUID, Long> out) {
        ConfigurationSection daily = yaml.getConfigurationSection("daily");
        if (daily == null) {
            return;
        }
        for (String day : daily.getKeys(false)) {
            if (day.compareTo(fromDay) < 0 || day.compareTo(toDay) > 0) {
                continue;
            }
            ConfigurationSection players = daily.getConfigurationSection(day);
            if (players == null) {
                continue;
            }
            for (String raw : players.getKeys(false)) {
                UUID uuid = parseUuid(raw);
                if (uuid != null) {
                    long value = Math.max(0, players.getLong(raw, 0));
                    out.merge(uuid, value, Long::sum);
                }
            }
        }
    }

    private String nameOf(UUID uuid) {
        return yaml.getString("players." + uuid + ".name", "");
    }

    @Override
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        try {
            prune();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("无法创建在线时长数据目录: " + parent);
                return;
            }
            Path target = file.toPath();
            Path temporary = target.resolveSibling(file.getName() + ".tmp");
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Throwable error) {
            logger.log(Level.WARNING, "在线时长 YAML 保存失败", error);
        }
    }

    /** 删除超过保留天数的按天明细。 */
    private void prune() {
        ConfigurationSection daily = yaml.getConfigurationSection("daily");
        if (daily == null) {
            return;
        }
        LocalDate cutoff = LocalDate.now(settings.zone()).minusDays(settings.yamlRetentionDays());
        for (String day : new ArrayList<>(daily.getKeys(false))) {
            try {
                if (LocalDate.parse(day).isBefore(cutoff)) {
                    yaml.set("daily." + day, null);
                    dirty = true;
                }
            } catch (DateTimeParseException ignored) {
                // 无法识别的键保持原样，避免误删人工数据
            }
        }
    }

    @Override
    public synchronized void close() {
        flush();
        ready = false;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
