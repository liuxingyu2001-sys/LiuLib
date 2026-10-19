package com.liuxingyu.liulib.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlLockTest {

    @Test
    void validatesLockNames() {
        assertEquals("liulib:daily", MySqlLock.normalize(" liulib:daily "));
        assertNull(MySqlLock.normalize(null));
        assertNull(MySqlLock.normalize("  "));
        assertNull(MySqlLock.normalize("x".repeat(65)));
        assertTrue(MySqlLock.tryAcquire(null, "x").isEmpty());
    }
}
