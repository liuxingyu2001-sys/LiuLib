package com.liuxingyu.liulib.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMigrationsTest {

    @Test
    void validatesTableNames() {
        assertEquals("myshop_schema", SqlMigrations.normalizeTable("myshop_schema"));
        assertEquals("liulib_schema", SqlMigrations.normalizeTable(" "));
        assertThrows(IllegalArgumentException.class,
                () -> SqlMigrations.normalizeTable("bad;drop"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlMigrations.normalizeTable("`quoted`"));
    }

    @Test
    void migrationCopiesStatementsAndRejectsBadVersion() {
        List<String> statements = new ArrayList<>();
        statements.add("CREATE TABLE a (id INT)");
        SqlMigrations.Migration migration = new SqlMigrations.Migration(1, "init", statements);
        statements.add("ALTER TABLE a ADD COLUMN name VARCHAR(16)");
        assertEquals(1, migration.version());
        assertEquals(1, migration.statements().size());
        assertThrows(IllegalArgumentException.class,
                () -> new SqlMigrations.Migration(0, "bad"));
    }

    @Test
    void unavailableMySqlIsFailSoft() {
        SqlMigrations migrations = new SqlMigrations(null, null);
        assertEquals(0, migrations.currentVersion());
        assertTrue(migrations.appliedVersions().isEmpty());
        // 无 MySQL 时迁移返回 false，但不抛异常
        assertFalse(migrations.migrate(List.of()));
    }
}
