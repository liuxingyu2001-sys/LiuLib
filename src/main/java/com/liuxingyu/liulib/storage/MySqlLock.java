package com.liuxingyu.liulib.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MySQL 命名锁（{@code GET_LOCK} / {@code RELEASE_LOCK}）的 AutoCloseable 封装。
 *
 * <p>适合没有 Redis 的单库环境做跨进程互斥，例如“只允许一个服执行每日结算”。锁与会话绑定，
 * 因此本类持有专用连接，直到 {@link #close()} 才归还；不要把它当成普通查询连接使用。</p>
 *
 * <pre>{@code
 * Optional<MySqlLock> lock = MySqlLock.tryAcquire(mysql, "liulib:daily", 5);
 * if (lock.isPresent()) {
 *     try (MySqlLock held = lock.get()) {
 *         // 临界区
 *     }
 * }
 * }</pre>
 *
 * <p>MySQL 未就绪、锁名非法或等待超时都返回 {@link Optional#empty()}，不抛异常。</p>
 */
public final class MySqlLock implements AutoCloseable {

    private static final int MAX_NAME_LENGTH = 64;

    private final Connection connection;
    private final String name;
    private final AtomicBoolean released = new AtomicBoolean();

    private MySqlLock(Connection connection, String name) {
        this.connection = connection;
        this.name = name;
    }

    /**
     * 尝试获取锁，立即返回（不等待）。
     */
    public static Optional<MySqlLock> tryAcquire(MySql mysql, String name) {
        return tryAcquire(mysql, name, 0);
    }

    /**
     * 尝试获取锁。
     *
     * @param mysql          MySQL 封装
     * @param name           锁名，最多 64 字符
     * @param timeoutSeconds 等待秒数；0 表示不等待
     */
    public static Optional<MySqlLock> tryAcquire(MySql mysql, String name, int timeoutSeconds) {
        String key = normalize(name);
        if (mysql == null || !mysql.isReady() || key == null) {
            return Optional.empty();
        }
        Connection connection = null;
        try {
            connection = mysql.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT GET_LOCK(?, ?)")) {
                statement.setString(1, key);
                statement.setInt(2, Math.max(0, Math.min(3_600, timeoutSeconds)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getInt(1) == 1) {
                        return Optional.of(new MySqlLock(connection, key));
                    }
                }
            }
            closeQuietly(connection);
            return Optional.empty();
        } catch (SQLException error) {
            closeQuietly(connection);
            return Optional.empty();
        }
    }

    public String name() {
        return name;
    }

    public boolean isHeld() {
        return !released.get();
    }

    /** 释放锁并归还连接；重复调用无副作用。 */
    public boolean release() {
        if (!released.compareAndSet(false, true)) {
            return false;
        }
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, name);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && resultSet.getInt(1) == 1;
                }
            }
        } catch (SQLException error) {
            return false;
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public void close() {
        release();
    }

    static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim();
        if (key.isEmpty() || key.length() > MAX_NAME_LENGTH) {
            return null;
        }
        return key;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 连接已断开
        }
    }
}
