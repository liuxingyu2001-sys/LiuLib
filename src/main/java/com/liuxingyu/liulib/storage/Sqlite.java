package com.liuxingyu.liulib.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 公共封装：单文件、无外部服务，适合单服小数据量。
 *
 * <p>与 {@link MySql} 保持同一套查询/事务/异步写模板，调用方可以按配置在两者之间切换。
 * SQLite 没有连接池，每次操作打开一个连接；这是 SQLite 的常规用法，配合 WAL 和
 * {@code busy_timeout} 可以避免大部分锁等待。</p>
 *
 * <p>驱动由 {@code plugin.yml: libraries} 交给 Paper 下载，编译期不打包；未启用或初始化
 * 失败时所有方法快速失败，调用方应回退到 YAML/内存模式。</p>
 */
public final class Sqlite implements AutoCloseable {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    private final SqliteConfig config;
    private final Path file;
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();

    private volatile Driver driver;
    private volatile boolean ready;
    private volatile ThreadPoolExecutor executor;

    public Sqlite(JavaPlugin plugin, SqliteConfig config, Logger logger) {
        this(config, resolveFile(plugin, config), logger);
    }

    Sqlite(SqliteConfig config, Path file, Logger logger) {
        this.config = config == null ? SqliteConfig.disabled() : config;
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.logger = logger == null ? Logger.getLogger("LiuLib-SQLite") : logger;
    }

    private static Path resolveFile(JavaPlugin plugin, SqliteConfig config) {
        Path configured = Path.of(config == null ? "data.db" : config.file());
        if (configured.isAbsolute()) {
            return configured;
        }
        if (plugin == null) {
            return configured;
        }
        return plugin.getDataFolder().toPath().resolve(configured);
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 建立数据库文件并真实探活；返回是否成功。重复调用幂等。 */
    public synchronized boolean init() {
        if (!config.enabled()) {
            logger.info("SQLite 未启用");
            return false;
        }
        if (ready && isReady()) {
            return true;
        }
        if (closed.get()) {
            return false;
        }
        try {
            Class<?> type = Class.forName("org.sqlite.JDBC");
            this.driver = (Driver) type.getDeclaredConstructor().newInstance();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Connection connection = open()) {
                if (!connection.isValid(3)) {
                    throw new SQLException("连接校验失败");
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=WAL");
                    statement.execute("PRAGMA synchronous=NORMAL");
                }
            }
            this.executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2048),
                    runnable -> {
                        Thread thread = new Thread(runnable, "LiuLib-sqlite-writer");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            this.ready = true;
            logger.info("SQLite 已连接: " + file + "（表前缀 " + config.tablePrefix() + "）");
            return true;
        } catch (Throwable error) {
            logger.log(Level.SEVERE, "SQLite 初始化失败: " + file, error);
            this.ready = false;
            this.driver = null;
            closeExecutor();
            return false;
        }
    }

    public boolean isReady() {
        return ready && !closed.get() && driver != null;
    }

    /** 真实探活；失败返回 false。 */
    public boolean healthCheck() {
        if (!isReady()) {
            return false;
        }
        try (Connection connection = open()) {
            return connection.isValid(3);
        } catch (SQLException error) {
            logger.log(Level.WARNING, "SQLite 连接检查失败", error);
            return false;
        }
    }

    public Path file() {
        return file;
    }

    /**
     * 打开一条新的 SQLite 连接；调用方负责关闭。
     *
     * <p>面向已有 JDBC 代码迁移：保留调用方的 try-with-resources 结构，同时统一
     * busy_timeout / foreign_keys 等连接参数。新代码优先使用
     * {@link #query}/{@link #update}/{@link #transaction} 模板。</p>
     */
    public Connection getConnection() throws SQLException {
        if (!isReady()) {
            throw new SQLException("SQLite 未就绪: " + file);
        }
        return open();
    }

    public SqliteConfig config() {
        return config;
    }

    /** 表名统一加前缀。 */
    public String table(String name) {
        return config.table(name);
    }

    @Override
    public synchronized void close() {
        // 与 MySql 一致：先停止接收新任务，排空队列期间 isReady 仍为 true，
        // 否则排队中的写入会在 withConnection() 里直接失败。
        if (closed.get() || !closing.compareAndSet(false, true)) {
            return;
        }
        closeExecutor();
        ready = false;
        closed.set(true);
        driver = null;
    }

    private void closeExecutor() {
        ThreadPoolExecutor worker = executor;
        executor = null;
        if (worker == null) {
            return;
        }
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

    private Connection open() throws SQLException {
        Driver current = driver;
        if (current == null) {
            throw new SQLException("SQLite 驱动未加载");
        }
        Connection connection = current.connect("jdbc:sqlite:" + file, new Properties());
        if (connection == null) {
            throw new SQLException("SQLite 驱动拒绝了连接 URL");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException error) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
        return connection;
    }

    // ==================================================================
    // 查询 / 更新
    // ==================================================================

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

    public <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Object... params) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        });
    }

    /** 执行 DDL / 无参数 SQL。 */
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
                    // 连接即将关闭
                }
            }
        });
    }

    /** 按前缀建表；columns 为列定义（不含括号）。 */
    public void createTable(String name, String columns) {
        execute("CREATE TABLE IF NOT EXISTS `" + table(name) + "` (" + columns + ")");
    }

    /**
     * 串行后台写；返回的 Future 在提交失败时以异常完成，便于调用方记录。
     */
    public CompletableFuture<Void> executeAsync(Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ThreadPoolExecutor worker = executor;
        if (worker == null || closed.get() || closing.get() || task == null) {
            future.completeExceptionally(new LiuSqlException("SQLite 未就绪"));
            return future;
        }
        try {
            worker.execute(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private <T> T withConnection(SqlFunction<T> action) {
        if (!isReady()) {
            throw new LiuSqlException("SQLite 未就绪");
        }
        try (Connection connection = open()) {
            return action.apply(connection);
        } catch (SQLException error) {
            throw new LiuSqlException("SQLite 操作失败: " + error.getMessage(), error);
        }
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
