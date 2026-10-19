package com.liuxingyu.liulib.storage;

import org.bukkit.configuration.ConfigurationSection;

/**
 * SQLite 连接配置。
 *
 * <p>SQLite 是单文件数据库，适合单服小数据量场景；需要多服共享时仍应使用 MySQL。</p>
 */
public record SqliteConfig(boolean enabled, String file, String tablePrefix) {

    public SqliteConfig {
        file = file == null || file.isBlank() ? "data.db" : file.trim();
        tablePrefix = tablePrefix == null ? "" : tablePrefix;
    }

    public String table(String name) {
        return tablePrefix + name;
    }

    public static SqliteConfig from(ConfigurationSection section) {
        if (section == null) {
            return disabled();
        }
        return new SqliteConfig(
                section.getBoolean("enabled", false),
                section.getString("file", "data.db"),
                section.getString("table-prefix", "liulib_")
        );
    }

    public static SqliteConfig disabled() {
        return new SqliteConfig(false, "data.db", "liulib_");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private String file = "data.db";
        private String tablePrefix = "liulib_";

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder file(String value) {
            this.file = value;
            return this;
        }

        public Builder tablePrefix(String value) {
            this.tablePrefix = value;
            return this;
        }

        public SqliteConfig build() {
            return new SqliteConfig(enabled, file, tablePrefix);
        }
    }
}
