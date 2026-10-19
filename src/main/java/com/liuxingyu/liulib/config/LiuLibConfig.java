package com.liuxingyu.liulib.config;

import com.liuxingyu.liulib.playtime.PlaytimeSettings;
import com.liuxingyu.liulib.storage.RedisConfig;
import com.liuxingyu.liulib.storage.SqlConfig;
import com.liuxingyu.liulib.storage.SqliteConfig;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * LiuLib 配置快照。不可变；重载时整体替换，避免运行中读到半套新旧值。
 */
public record LiuLibConfig(
        boolean debug,
        String language,
        Item item,
        SqliteConfig sqlite,
        SqlConfig mysql,
        RedisConfig redis,
        Ui ui,
        Text text,
        PlaytimeSettings playtime
) {

    public static LiuLibConfig load(FileConfiguration yaml) {
        Item item = new Item(
                Math.max(1024, yaml.getInt("item.max-encoded-length", 60_000)),
                yaml.getString("item.unknown-name", "未命名物品"),
                yaml.getString("item.craft-engine.default-namespace", "craftengine"),
                yaml.getBoolean("item.craft-engine.scan-language-files", true)
        );
        Ui ui = new Ui(
                yaml.getBoolean("ui.sparrow.enabled", true),
                yaml.getBoolean("ui.sparrow.catch-exceptions", true)
        );
        Text text = new Text(
                yaml.getString("text.prefix", ""),
                yaml.getString("text.missing-key", "<red>缺失消息: {key}"),
                yaml.getBoolean("text.warn-missing", true),
                yaml.getBoolean("text.legacy-input", true)
        );
        return new LiuLibConfig(
                yaml.getBoolean("debug", false),
                yaml.getString("language", "zh_CN"),
                item,
                SqliteConfig.from(yaml.getConfigurationSection("sqlite")),
                SqlConfig.from(yaml.getConfigurationSection("mysql")),
                RedisConfig.from(yaml.getConfigurationSection("redis")),
                ui,
                text,
                PlaytimeSettings.from(yaml.getConfigurationSection("playtime"))
        );
    }

    /** 兼容旧构造：SQLite 默认关闭。 */
    public LiuLibConfig(boolean debug, String language, Item item, SqlConfig mysql,
                        RedisConfig redis, Ui ui, Text text, PlaytimeSettings playtime) {
        this(debug, language, item, SqliteConfig.disabled(), mysql, redis, ui, text, playtime);
    }

    public record Item(
            int maxEncodedLength,
            String unknownName,
            String craftEngineNamespace,
            boolean scanCraftEngineLanguageFiles
    ) {
        public Item {
            unknownName = unknownName == null || unknownName.isBlank() ? "未命名物品" : unknownName;
            craftEngineNamespace = craftEngineNamespace == null || craftEngineNamespace.isBlank()
                    ? "craftengine" : craftEngineNamespace;
        }
    }

    public record Ui(boolean sparrowEnabled, boolean catchSparrowExceptions) {
    }

    /** 文本模块配置；前缀支持 MiniMessage 与传统色码。 */
    public record Text(String prefix, String missingKey, boolean warnMissing, boolean legacyInput) {

        public Text {
            prefix = prefix == null ? "" : prefix;
            missingKey = missingKey == null || missingKey.isBlank()
                    ? "<red>缺失消息: {key}" : missingKey;
        }

        public static Text defaults() {
            return new Text("", "<red>缺失消息: {key}", true, true);
        }
    }
}
