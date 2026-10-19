package com.liuxingyu.liulib.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL 公共封装（HikariCP 连接池 + 单线程写队列）。
 *
 * <p>从 LiuChat / liupass / TLoot / soulspace 各自的 MySQL 实现收敛而来：</p>
 * <ul>
 *   <li>连接池负责断线重连、连接校验与超时，调用方不再自己处理 {@code isClosed()}；</li>
 *   <li>{@link #executeAsync(Runnable)} 把写操作串行放到后台线程，主线程不做网络往返；</li>
 *   <li>提供 {@link #query}/{@link #update}/{@link #transaction} 模板，业务插件只写 SQL；</li>
 *   <li>可自动建库、按前缀建表；未启用时所有方法快速失败，不阻塞启动。</li>
 * </ul>
 *
 * <p>HikariCP 已随 LiuLib 打包并 relocate，服务端只需提供 MySQL JDBC 驱动
 * （plugin.yml 的 {@code libraries} 会自动下载）。</p>
 */
public final class MySql implements AutoCloseable {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    private final Logger logger;
    private final SqlConfig config;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile HikariDataSource dataSource;
    private volatile ThreadPoolExecutor executor;

    public MySql(SqlConfig config, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 建立连接池并真实探活；返回是否成功。重复调用幂等。 */
    public synchronized boolean init() {
        if (!config.enabled()) {
            logger.info("MySQL 未启用，数据存储由调用方自行降级");
            return false;
        }
        if (dataSource != null) {
            return true;
        }
        if (closed.get()) {
            return false;
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException error) {
            logger.log(Level.SEVERE, "缺少 MySQL JDBC 驱动（com.mysql.cj.jdbc.Driver），"
                    + "请确认 plugin.yml 的 libraries 段已生效", error);
            return false;
        }
        if (config.autoCreateDatabase()) {
            createDatabase();
        }
        try {
            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("LiuLib-MySQL");
            hikari.setJdbcUrl(config.jdbcUrl());
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setMaximumPoolSize(config.poolSize());
            hikari.setMinimumIdle(1);
            hikari.setConnectionTimeout(config.connectionTimeoutMs());
            hikari.setValidationTimeout(Math.max(250, Math.min(5000, config.connectionTimeoutMs())));
            hikari.setIdleTimeout(300_000);
            hikari.setMaxLifetime(600_000);
            hikari.setInitializationFailTimeout(-1);
            hikari.addDataSourceProperty("cachePrepStmts", "true");
            hikari.addDataSourceProperty("prepStmtCacheSize", "256");
            hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikari.addDataSourceProperty("useServerPrepStmts", "true");
            this.dataSource = new HikariDataSource(hikari);
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(3)) {
                    throw new SQLException("连接校验失败");
                }
            }
            this.executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4096),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LiuLib-mysql-writer");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            logger.info("MySQL 已连接: " + config.host() + ":" + config.port()
                    + "/" + config.database() + "（表前缀 " + config.tablePrefix() + "）");
            return true;
        } catch (Throwable error) {
            logger.log(Level.SEVERE, "MySQL 初始化失败: " + config.host() + ":" + config.port()
                    + "/" + config.database() + "，相关数据暂不可用", error);
            closePool();
            return false;
        }
    }

    private void createDatabase() {
        try (Connection connection = DriverManager.getConnection(
                config.baseJdbcUrl(), config.username(), config.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + config.database()
                    + "` DEFAULT CHARACTER SET utf8mb4");
        } catch (SQLException error) {
            // 没权限时只警告，是否可用交给后续连接决定
            logger.log(Level.WARNING, "自动创建数据库失败（若库已存在可忽略；否则请手动执行："
                    + "CREATE DATABASE `" + config.database() + "` DEFAULT CHARACTER SET utf8mb4）", error);
        }
    }

    public boolean isReady() {
        HikariDataSource pool = dataSource;
        return pool != null && !pool.isClosed() && !closed.get();
    }

    /** 真实探活；失败返回 false 并触发 Hikari 重建连接。 */
    public boolean healthCheck() {
        if (!isReady()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(3);
        } catch (SQLException error) {
            logger.log(Level.WARNING, "MySQL 连接检查失败", error);
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ThreadPoolExecutor worker = executor;
        executor = null;
        if (worker != null) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException error) {
                worker.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        closePool();
    }

    private void closePool() {
        HikariDataSource pool = dataSource;
        dataSource = null;
        if (pool != null) {
            try {
                pool.close();
            } catch (RuntimeException error) {
                logger.log(Level.FINE, "关闭 MySQL 连接池失败", error);
            }
        }
    }

    // ==================================================================
    // SQL 模板
    // ==================================================================

    /** 借一条连接执行；连接由池管理，不要关闭。 */
    public <T> T withConnection(SqlFunction<T> action) {
        HikariDataSource pool = dataSource;
        if (pool == null || closed.get()) {
            throw new LiuSqlException("MySQL 未就绪");
        }
        try (Connection connection = pool.getConnection()) {
            return action.apply(connection);
        } catch (SQLException error) {
            throw new LiuSqlException("MySQL 操作失败: " + error.getMessage(), error);
        }
    }

    /** 查询多行。 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (resultSet.next()) {
                        out.add(mapper.map(resultSet));
                    }
                    return out;
                }
            }
        });
    }

    /** 查询单行；无结果返回 null。 */
    public <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 执行 INSERT/UPDATE/DELETE，返回影响行数。 */
    public int update(String sql, Object... params) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        });
    }

    /** 执行 DDL / 多语句（无参数）。 */
    public void execute(String sql) {
        withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                return null;
            }
        });
    }

    /** 事务；回调抛异常时自动回滚。 */
    public <T> T transaction(SqlFunction<T> action) {
        return withConnection(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = action.apply(connection);
                connection.commit();
                return result;
            } catch (Throwable error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                if (error instanceof SQLException sqlError) {
                    throw sqlError;
                }
                if (error instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new SQLException(error);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // 连接归还池时会重置
                }
            }
        });
    }

    /** 按配置前缀建表；columns 为列定义（不含括号）。 */
    public void createTable(String name, String columns) {
        execute("CREATE TABLE IF NOT EXISTS " + config.table(name) + " (" + columns
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** 按配置前缀删除表（测试/重置用，慎用）。 */
    public void dropTable(String name) {
        execute("DROP TABLE IF EXISTS " + config.table(name));
    }

    /** 表名加配置前缀。 */
    public String table(String name) {
        return config.table(name);
    }

    // ==================================================================
    // 异步写
    // ==================================================================

    /**
     * 异步执行写任务（串行、有界队列）。
     *
     * <p>队列满或已关闭时丢弃并记录警告，避免主线程被数据库拖住。
     * 返回的 Future 仅用于诊断，不要求调用方等待。</p>
     */
    public CompletableFuture<Void> executeAsync(Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ThreadPoolExecutor worker = executor;
        if (worker == null || closed.get()) {
            future.completeExceptionally(new LiuSqlException("MySQL 未就绪"));
            return future;
        }
        try {
            worker.execute(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable error) {
                    logger.log(Level.WARNING, "MySQL 异步写任务失败", error);
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            logger.warning("MySQL 写队列已满，本次写入被丢弃");
            future.completeExceptionally(error);
        }
        return future;
    }

    /** 异步查询（同一写队列，保证读己之写）。 */
    public <T> CompletableFuture<T> supplyAsync(JavaSqlSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ThreadPoolExecutor worker = executor;
        if (worker == null || closed.get()) {
            future.completeExceptionally(new LiuSqlException("MySQL 未就绪"));
            return future;
        }
        try {
            worker.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @FunctionalInterface
    public interface JavaSqlSupplier<T> {
        T get() throws Exception;
    }

    public SqlConfig config() {
        return config;
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }
}
