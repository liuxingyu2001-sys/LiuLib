package com.liuxingyu.liulib.placeholder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholdersTest {

    @Test
    void unavailableWithoutServerIsFailSoft() {
        Placeholders placeholders = new Placeholders(null);
        assertFalse(placeholders.available());
        assertFalse(placeholders.register("test", (player, params) -> "value"));
        assertFalse(placeholders.register("bad id!", (player, params) -> "value"));
        assertFalse(placeholders.unregister("test"));
        assertTrue(placeholders.identifiers().isEmpty());
        assertFalse(placeholders.isRegistered("test"));
        placeholders.close();
    }
}
