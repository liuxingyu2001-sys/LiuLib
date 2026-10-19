package com.liuxingyu.liulib.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTest {

    @TempDir
    Path temp;

    @Test
    void readsWritesAndCommitsTransactions() {
        Path db = temp.resolve("test.db");
        Sqlite sqlite = new Sqlite(new SqliteConfig(true, db.toString(), "test_"),
                db, Logger.getLogger("test"));
        assertTrue(sqlite.init());
        assertTrue(sqlite.isReady());
        assertNotNull(sqlite.file());

        sqlite.createTable("coins", "uuid TEXT PRIMARY KEY, amount INTEGER NOT NULL DEFAULT 0");
        assertEquals(1, sqlite.update("INSERT INTO `test_coins` (uuid, amount) VALUES (?, ?)",
                "a", 10));
        assertEquals(10, queryInt(sqlite,
                "SELECT amount FROM `test_coins` WHERE uuid = ?", "a"));

        sqlite.transaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "UPDATE `test_coins` SET amount = amount + ? WHERE uuid = ?")) {
                ps.setInt(1, 5);
                ps.setString(2, "a");
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(15, queryInt(sqlite,
                "SELECT amount FROM `test_coins` WHERE uuid = ?", "a"));

        List<String> names = sqlite.query("SELECT uuid FROM `test_coins` ORDER BY uuid",
                resultSet -> resultSet.getString(1));
        assertEquals(List.of("a"), names);
        sqlite.close();
        assertFalse(sqlite.isReady());
    }

    @Test
    void rollsBackFailedTransaction() {
        Path db = temp.resolve("rollback.db");
        Sqlite sqlite = new Sqlite(new SqliteConfig(true, db.toString(), ""),
                db, Logger.getLogger("test"));
        assertTrue(sqlite.init());
        sqlite.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, value TEXT)");
        try {
            sqlite.transaction(connection -> {
                try (var ps = connection.prepareStatement("INSERT INTO t (id, value) VALUES (1, 'x')")) {
                    ps.executeUpdate();
                }
                throw new SQLException("boom");
            });
        } catch (RuntimeException expected) {
            // 期望事务失败
        }
        assertEquals(0, queryInt(sqlite, "SELECT COUNT(*) FROM t"));
        sqlite.close();
    }

    private static int queryInt(Sqlite sqlite, String sql, Object... params) {
        return sqlite.queryOne(sql, resultSet -> resultSet.getInt(1), params);
    }

    @Test
    void asyncWriteAndDisabledState() throws Exception {
        Path db = temp.resolve("async.db");
        Sqlite sqlite = new Sqlite(new SqliteConfig(true, db.toString(), ""),
                db, Logger.getLogger("test"));
        assertTrue(sqlite.init());
        sqlite.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
        sqlite.executeAsync(() -> sqlite.update("INSERT INTO t (id) VALUES (?)", 1))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, queryInt(sqlite, "SELECT COUNT(*) FROM t"));
        sqlite.close();

        Sqlite disabled = new Sqlite(SqliteConfig.disabled(), temp.resolve("disabled.db"),
                Logger.getLogger("test"));
        assertFalse(disabled.init());
        assertFalse(disabled.isReady());
        disabled.close();
    }
}
