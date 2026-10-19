package com.liuxingyu.liulib.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigTest {

    @Test
    void sqlDefaultsAndUrl() {
        SqlConfig config = SqlConfig.builder().database("test").build();
        assertTrue(config.jdbcUrl().contains("/test?"));
        assertTrue(config.jdbcUrl().contains("useSSL=false"));
        assertEquals("liulib_user", config.table("user"));
        assertEquals("127.0.0.1", config.host());
        assertEquals(3306, config.port());
    }

    @Test
    void redisKeyPrefixAppliedOnce() {
        RedisConfig config = RedisConfig.builder().keyPrefix("net1:").build();
        assertEquals("net1:player:1", config.key("player:1"));
        assertEquals("liulib:player:1", RedisConfig.disabled().key("player:1"));
        assertFalse(config.enabled() && config.port() == 0);
    }

    @Test
    void disabledConfigsDoNotConnect() {
        assertFalse(SqlConfig.disabled().enabled());
        assertFalse(RedisConfig.disabled().enabled());
    }
}
