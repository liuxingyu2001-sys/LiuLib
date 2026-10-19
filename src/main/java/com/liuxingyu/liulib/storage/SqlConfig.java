package com.liuxingyu.liulib.storage;

import org.bukkit.configuration.ConfigurationSection;

/**
 * MySQL 连接配置。
 *
 * <p>只依赖 Bukkit 的 {@link ConfigurationSection}，方便调用方从自己的 config.yml
 * 里读取同名结构，或直接 {@link #builder()} 构造。</p>
 */
public record SqlConfig(
        boolean enabled,
        String host,
        int port,
        String database,
        String username,
        String password,
        String tablePrefix,
        int poolSize,
        int connectionTimeoutMs,
        boolean useSsl,
        boolean autoCreateDatabase
) {

    public SqlConfig {
        host = blank(host) ? "127.0.0.1" : host.trim();
        port = port <= 0 || port > 65535 ? 3306 : port;
        database = blank(database) ? "liulib" : database.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        tablePrefix = tablePrefix == null ? "" : tablePrefix;
        poolSize = Math.max(1, poolSize);
        connectionTimeoutMs = Math.max(250, connectionTimeoutMs);
    }

    /** 带库名的 JDBC URL（Hikari 使用）。 */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC"
                + "&connectTimeout=" + connectionTimeoutMs
                + "&socketTimeout=" + connectionTimeoutMs
                + "&rewriteBatchedStatements=true";
    }

    /** 不带库名的 JDBC URL，用于自动建库。 */
    public String baseJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/"
                + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + connectionTimeoutMs
                + "&socketTimeout=" + connectionTimeoutMs;
    }

    /** 表名统一加前缀。 */
    public String table(String name) {
        return tablePrefix + name;
    }

    public static SqlConfig from(ConfigurationSection section) {
        if (section == null) {
            return disabled();
        }
        return new SqlConfig(
                section.getBoolean("enabled", false),
                section.getString("host", "127.0.0.1"),
                section.getInt("port", 3306),
                section.getString("database", "liulib"),
                section.getString("username", "root"),
                section.getString("password", ""),
                section.getString("table-prefix", "liulib_"),
                section.getInt("pool-size", 6),
                section.getInt("connection-timeout-ms", 5000),
                section.getBoolean("use-ssl", false),
                section.getBoolean("auto-create-database", true)
        );
    }

    public static SqlConfig disabled() {
        return new SqlConfig(false, "127.0.0.1", 3306, "liulib", "root", "", "liulib_",
                6, 5000, false, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 便捷构造器；未设置的项使用与 config.yml 相同的默认值。 */
    public static final class Builder {
        private boolean enabled = true;
        private String host = "127.0.0.1";
        private int port = 3306;
        private String database = "liulib";
        private String username = "root";
        private String password = "";
        private String tablePrefix = "liulib_";
        private int poolSize = 6;
        private int connectionTimeoutMs = 5000;
        private boolean useSsl = false;
        private boolean autoCreateDatabase = true;

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

        public Builder database(String value) {
            this.database = value;
            return this;
        }

        public Builder username(String value) {
            this.username = value;
            return this;
        }

        public Builder password(String value) {
            this.password = value;
            return this;
        }

        public Builder tablePrefix(String value) {
            this.tablePrefix = value;
            return this;
        }

        public Builder poolSize(int value) {
            this.poolSize = value;
            return this;
        }

        public Builder connectionTimeoutMs(int value) {
            this.connectionTimeoutMs = value;
            return this;
        }

        public Builder useSsl(boolean value) {
            this.useSsl = value;
            return this;
        }

        public Builder autoCreateDatabase(boolean value) {
            this.autoCreateDatabase = value;
            return this;
        }

        public SqlConfig build() {
            return new SqlConfig(enabled, host, port, database, username, password, tablePrefix,
                    poolSize, connectionTimeoutMs, useSsl, autoCreateDatabase);
        }
    }
}
