package com.liuxingyu.liulib.storage;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 轻量级 SQL 版本迁移：在 MySQL 中维护一张 schema 版本表，按版本号顺序补齐缺失脚本。
 *
 * <pre>{@code
 * SqlMigrations migrations = new SqlMigrations(mysql, "myshop_schema");
 * boolean ok = migrations.migrate(List.of(
 *         new SqlMigrations.Migration(1, "创建商品表", """
 *                 CREATE TABLE IF NOT EXISTS myshop_items (
 *                     id VARCHAR(64) PRIMARY KEY,
 *                     price DOUBLE NOT NULL
 *                 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
 *                 """),
 *         new SqlMigrations.Migration(2, "增加库存列", """
 *                 ALTER TABLE myshop_items ADD COLUMN stock INT NOT NULL DEFAULT 0
 *                 """)));
 * }</pre>
 *
 * <p>每个版本在独立事务中执行，脚本失败会回滚且不会写入版本记录；重复调用只会执行
 * 尚未应用的版本。适合在插件启用时异步调用一次，不要在主线程执行。</p>
 *
 * <p>只支持 MySQL，表名只允许字母、数字、下划线；SQLite 等其它存储请用各自的迁移工具。</p>
 */
public final class SqlMigrations {

    /** 一个版本迁移；statements 按顺序执行。 */
    public record Migration(int version, String description, List<String> statements) {

        public Migration {
            if (version <= 0) {
                throw new IllegalArgumentException("迁移版本必须为正数: " + version);
            }
            description = description == null ? "" : description;
            statements = statements == null ? List.of() : List.copyOf(statements);
        }

        public Migration(int version, String description, String... statements) {
            this(version, description, statements == null ? List.of() : List.of(statements));
        }
    }

    private static final String DEFAULT_TABLE = "liulib_schema";

    private final MySql mysql;
    private final Logger logger;
    private final String table;

    public SqlMigrations(MySql mysql, Logger logger) {
        this(mysql, logger, DEFAULT_TABLE);
    }

    public SqlMigrations(MySql mysql, Logger logger, String table) {
        this.mysql = mysql;
        this.logger = logger == null ? Logger.getLogger("LiuLib-SqlMigrations") : logger;
        this.table = normalizeTable(table);
    }

    /** 当前已应用的最高版本；没有记录或 MySQL 未就绪返回 0。 */
    public int currentVersion() {
        if (mysql == null || !mysql.isReady()) {
            return 0;
        }
        try {
            List<Integer> versions = appliedVersions();
            return versions.stream().mapToInt(Integer::intValue).max().orElse(0);
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "读取 schema 版本失败", error);
            return 0;
        }
    }

    /** 已应用的版本号，升序。 */
    public List<Integer> appliedVersions() {
        if (mysql == null || !mysql.isReady()) {
            return List.of();
        }
        ensureTable();
        List<Integer> versions = new ArrayList<>(mysql.query(
                "SELECT version FROM `" + table + "` ORDER BY version",
                resultSet -> resultSet.getInt(1)));
        versions.sort(Comparator.naturalOrder());
        return versions;
    }

    /**
     * 应用缺失的迁移。
     *
     * @return 全部成功或已是最新返回 true；MySQL 未就绪、脚本失败返回 false
     */
    public boolean migrate(List<Migration> migrations) {
        if (mysql == null || !mysql.isReady()) {
            logger.warning("MySQL 未就绪，跳过 schema 迁移");
            return false;
        }
        List<Migration> ordered;
        try {
            ordered = validate(migrations);
        } catch (RuntimeException error) {
            logger.log(Level.SEVERE, "迁移列表非法: " + error.getMessage(), error);
            return false;
        }
        if (ordered.isEmpty()) {
            ensureTable();
            return true;
        }
        try {
            ensureTable();
            Set<Integer> applied = new HashSet<>(appliedVersions());
            for (Migration migration : ordered) {
                if (applied.contains(migration.version())) {
                    continue;
                }
                if (!apply(migration)) {
                    return false;
                }
                applied.add(migration.version());
            }
            return true;
        } catch (RuntimeException error) {
            logger.log(Level.SEVERE, "schema 迁移失败", error);
            return false;
        }
    }

    private boolean apply(Migration migration) {
        try {
            mysql.transaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migration.statements()) {
                        if (sql != null && !sql.isBlank()) {
                            statement.execute(sql);
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO `" + table + "` (version, description, applied_at) "
                                + "VALUES (?, ?, ?)")) {
                    insert.setInt(1, migration.version());
                    insert.setString(2, truncate(migration.description(), 255));
                    insert.setLong(3, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                return Boolean.TRUE;
            });
            logger.info("已应用 schema 迁移 v" + migration.version()
                    + (migration.description().isBlank() ? "" : "（" + migration.description() + "）"));
            return true;
        } catch (RuntimeException error) {
            logger.log(Level.SEVERE, "应用 schema 迁移 v" + migration.version() + " 失败，已回滚", error);
            return false;
        }
    }

    private void ensureTable() {
        mysql.execute("CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "version INT NOT NULL PRIMARY KEY,"
                + "description VARCHAR(255) NOT NULL DEFAULT '',"
                + "applied_at BIGINT NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private static List<Migration> validate(List<Migration> migrations) {
        if (migrations == null || migrations.isEmpty()) {
            return List.of();
        }
        List<Migration> ordered = new ArrayList<>(migrations);
        ordered.sort(Comparator.comparingInt(Migration::version));
        Set<Integer> versions = new HashSet<>();
        for (Migration migration : ordered) {
            Objects.requireNonNull(migration, "migration");
            if (!versions.add(migration.version())) {
                throw new IllegalArgumentException("重复的迁移版本: " + migration.version());
            }
        }
        return ordered;
    }

    static String normalizeTable(String table) {
        String value = table == null || table.isBlank() ? DEFAULT_TABLE : table.trim();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                throw new IllegalArgumentException("非法表名（只允许字母、数字、下划线）: " + table);
            }
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException("表名过长（最多 64 字符）: " + table);
        }
        return value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
