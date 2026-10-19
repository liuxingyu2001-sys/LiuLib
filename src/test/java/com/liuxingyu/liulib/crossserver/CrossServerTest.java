package com.liuxingyu.liulib.crossserver;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerTest {

    @Test
    void roundTripsStructuredMessage() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("player", "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        payload.put("amount", "64");
        String encoded = CrossServer.encode("server-a", "shop.buy", payload, 123456789L);
        assertNotNull(encoded);

        CrossServer.Message message = CrossServer.decode(encoded);
        assertNotNull(message);
        assertEquals("server-a", message.source());
        assertEquals("shop.buy", message.action());
        assertEquals("64", message.get("amount"));
        assertEquals("fallback", message.get("missing", "fallback"));
        assertEquals(123456789L, message.timestamp());
    }

    @Test
    void rejectsMalformedAndOversizedInput() {
        assertNull(CrossServer.decode(null));
        assertNull(CrossServer.decode(""));
        assertNull(CrossServer.decode("not-base64"));
        assertNull(CrossServer.decode("AAAA"));
        assertNull(CrossServer.encode("a", "x".repeat(129), Map.of(), 1L));
        assertNull(CrossServer.encode("a", "action", Map.of("key", "x".repeat(8193)), 1L));
    }

    @Test
    void payloadIsImmutableAndEmptySafe() {
        CrossServer.Message message = new CrossServer.Message("s", "a", null, 0L);
        assertTrue(message.payload().isEmpty());
        assertEquals("", message.get("anything"));
        assertEquals("s", message.source());
    }
}
