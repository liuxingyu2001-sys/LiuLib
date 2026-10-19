package com.liuxingyu.liulib.playtime;

import com.liuxingyu.liulib.storage.MySql;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL 在线时长存储。
 *
 * <p>两张表：{@code playtime}（总时长）与 {@code playtime_daily}（按天明细）。
 * 写入使用 {@code ON DUPLICATE KEY UPDATE ... + VALUES(...)} 原子累加，
 * 多台服务器同时对同一玩家累加也不会互相覆盖。</p>
 *
 * <p>表名统一走 {@link MySql#table(String)}，因此与 LiuLib 的 {@code table-prefix} 一致。</p>
 */
public final class MySqlPlaytimeStore implements PlaytimeStore {

    private final MySql mysql;
    private final Logger logger;
    private volatile boolean ready;

    public MySqlPlaytimeStore(MySql mysql, Logger logger) {
        this.mysql = Objects.requireNonNull(mysql, "mysql");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** 建表并检查连接；失败返回 false，由服务层回退 YAML。 */
    public boolean init() {
        if (mysql == null || !mysql.isReady()) {
            return false;
        }
        try {
            mysql.createTable("playtime",
                    "uuid CHAR(36) PRIMARY KEY, "
                            + "name VARCHAR(32) NOT NULL DEFAULT '', "
                            + "total_seconds BIGINT NOT NULL DEFAULT 0, "
                            + "last_seen BIGINT NOT NULL DEFAULT 0");
            mysql.createTable("playtime_daily",
                    "uuid CHAR(36) NOT NULL, "
                            + "day CHAR(10) NOT NULL, "
                            + "seconds BIGINT NOT NULL DEFAULT 0, "
                            + "PRIMARY KEY (uuid, day), "
                            + "INDEX idx_playtime_day (day)");
            ready = true;
        } catch (Throwable error) {
            ready = false;
            logger.log(Level.WARNING, "在线时长 MySQL 表初始化失败，将回退 YAML 存储", error);
        }
        return ready;
    }

    @Override
    public boolean isReady() {
        return ready && mysql.isReady();
    }

    @Override
    public String kind() {
        return "mysql";
    }

    @Override
    public PlaytimeRecord load(UUID uuid, String day) {
        if (!isReady() || uuid == null) {
            return PlaytimeRecord.empty(uuid, day);
        }
        String id = uuid.toString();
        String name = mysql.queryOne("SELECT name FROM " + mysql.table("playtime") + " WHERE uuid = ?",
                resultSet -> resultSet.getString(1), id);
        Long total = mysql.queryOne("SELECT total_seconds FROM " + mysql.table("playtime") + " WHERE uuid = ?",
                resultSet -> resultSet.getLong(1), id);
        Long today = mysql.queryOne("SELECT seconds FROM " + mysql.table("playtime_daily")
                        + " WHERE uuid = ? AND day = ?",
                resultSet -> resultSet.getLong(1), id, day);
        return new PlaytimeRecord(uuid, name, total == null ? 0 : total,
                today == null ? 0 : today, day);
    }

    @Override
    public void add(UUID uuid, String name, String day, long seconds) {
        if (!ready || uuid == null || seconds <= 0) {
            return;
        }
        if (!mysql.isReady()) {
            // 连接暂时断开时抛给服务层，进入重试队列；不能静默丢数据
            throw new IllegalStateException("MySQL 未就绪");
        }
        String id = uuid.toString();
        String playerName = name == null ? "" : name;
        mysql.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + mysql.table("playtime")
                            + " (uuid, name, total_seconds, last_seen) VALUES (?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE name = VALUES(name), "
                            + "total_seconds = total_seconds + VALUES(total_seconds), "
                            + "last_seen = VALUES(last_seen)")) {
                statement.setString(1, id);
                statement.setString(2, playerName);
                statement.setLong(3, seconds);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + mysql.table("playtime_daily")
                            + " (uuid, day, seconds) VALUES (?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE seconds = seconds + VALUES(seconds)")) {
                statement.setString(1, id);
                statement.setString(2, day);
                statement.setLong(3, seconds);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<PlaytimeEntry> top(PlaytimePeriod period, String day, String weekStart, int limit) {
        if (!isReady()) {
            return List.of();
        }
        int size = Math.max(1, Math.min(100, limit));
        String sql;
        Object[] params;
        switch (period == null ? PlaytimePeriod.TOTAL : period) {
            case TODAY -> {
                sql = "SELECT d.uuid, COALESCE(p.name, '') AS name, d.seconds "
                        + "FROM " + mysql.table("playtime_daily") + " d "
                        + "LEFT JOIN " + mysql.table("playtime") + " p ON p.uuid = d.uuid "
                        + "WHERE d.day = ? ORDER BY d.seconds DESC LIMIT ?";
                params = new Object[]{day, size};
            }
            case WEEK -> {
                sql = "SELECT d.uuid, COALESCE(p.name, '') AS name, SUM(d.seconds) AS total "
                        + "FROM " + mysql.table("playtime_daily") + " d "
                        + "LEFT JOIN " + mysql.table("playtime") + " p ON p.uuid = d.uuid "
                        + "WHERE d.day >= ? GROUP BY d.uuid, p.name ORDER BY total DESC LIMIT ?";
                params = new Object[]{weekStart, size};
            }
            default -> {
                sql = "SELECT uuid, name, total_seconds FROM " + mysql.table("playtime")
                        + " ORDER BY total_seconds DESC LIMIT ?";
                params = new Object[]{size};
            }
        }
        List<PlaytimeEntry> rows = mysql.query(sql, resultSet -> {
            try {
                return new PlaytimeEntry(UUID.fromString(resultSet.getString(1)),
                        resultSet.getString(2), resultSet.getLong(3));
            } catch (RuntimeException malformed) {
                return null;
            }
        }, params);
        return rows.stream().filter(Objects::nonNull).toList();
    }
}
