package com.liuxingyu.liulib.storage;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Redis 连接配置。
 *
 * <p>与 {@link SqlConfig} 一样同时支持 config.yml 段和代码构造。</p>
 */
public record RedisConfig(
        boolean enabled,
        String host,
        int port,
        String password,
        int database,
        int timeoutMs,
        int poolSize,
        String keyPrefix
) {

    public RedisConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        port = port <= 0 || port > 65535 ? 6379 : port;
        password = password == null ? "" : password;
        database = Math.max(0, database);
        timeoutMs = Math.max(250, timeoutMs);
        poolSize = Math.max(1, poolSize);
        keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    /** 给业务 key 统一加前缀；空白 key 原样返回。 */
    public String key(String key) {
        if (key == null || key.isBlank() || keyPrefix.isEmpty()) {
            return key;
        }
        return keyPrefix + key;
    }

    public static RedisConfig from(ConfigurationSection section) {
        if (section == null) {
            return disabled();
        }
        return new RedisConfig(
                section.getBoolean("enabled", false),
                section.getString("host", "127.0.0.1"),
                section.getInt("port", 6379),
                section.getString("password", ""),
                section.getInt("database", 0),
                section.getInt("timeout-ms", 2000),
                section.getInt("pool-size", 8),
                section.getString("key-prefix", "liulib:")
        );
    }

    public static RedisConfig disabled() {
        return new RedisConfig(false, "127.0.0.1", 6379, "", 0, 2000, 8, "liulib:");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        private int timeoutMs = 2000;
        private int poolSize = 8;
        private String keyPrefix = "liulib:";

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder host(String value) {
            this.host = value;
            return this;
        }

        public Builder port(int value) {
            this.port = value;
            return this;
        }

        public Builder password(String value) {
            this.password = value;
            return this;
        }

        public Builder database(int value) {
            this.database = value;
            return this;
        }

        public Builder timeoutMs(int value) {
            this.timeoutMs = value;
            return this;
        }

        public Builder poolSize(int value) {
            this.poolSize = value;
            return this;
        }

        public Builder keyPrefix(String value) {
            this.keyPrefix = value;
            return this;
        }

        public RedisConfig build() {
            return new RedisConfig(enabled, host, port, password, database, timeoutMs, poolSize, keyPrefix);
        }
    }
}
