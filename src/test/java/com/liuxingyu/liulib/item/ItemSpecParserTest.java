package com.liuxingyu.liulib.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSpecParserTest {

    private final ItemSpecParser parser = new ItemSpecParser(
            java.util.logging.Logger.getLogger("test"), new ItemCodec(), null, null, "craftengine",
            new java.util.concurrent.ConcurrentHashMap<>());

    @Test
    void parsesCraftEngineIds() {
        ItemSpec spec = parser.parse("ce:myitems:flame_sword*4");
        assertEquals(ItemSpec.Source.CRAFT_ENGINE, spec.source());
        assertEquals("myitems:flame_sword", spec.id());
        assertEquals(4, spec.amount());
        assertEquals("ce:myitems:flame_sword", spec.canonicalId());
    }

    @Test
    void parsesCraftEngineAliasAndBareId() {
        assertEquals("myitems:sword", parser.parse("craftengine:myitems:sword").id());
        assertEquals("craftengine:sword", parser.parse("sword").id());
        assertEquals("myitems:sword", parser.parse("myitems:sword").id());
    }

    @Test
    void keepsNumericSegmentInsideCraftEngineId() {
        // CE ID 里的数字段不能被误判成数量（数量只认 *n）
        ItemSpec spec = parser.parse("ce:myitems:2024_sword");
        assertEquals("myitems:2024_sword", spec.id());
        assertEquals(1, spec.amount());
    }

    @Test
    void parsesEncodedItems() {
        ItemSpec spec = parser.parse("base64:AAAA");
        assertEquals(ItemSpec.Source.ENCODED, spec.source());
        assertEquals("AAAA", spec.id());
        assertNull(spec.canonicalId());
        assertEquals("base64:AAAA", spec.configString());
    }

    @Test
    void rejectsBadAmount() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("ce:myitems:sword*0"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(""));
        assertNull(parser.parseOrNull(""));
    }

    @Test
    void configStringRoundTrips() {
        assertEquals("ce:myitems:sword*3", parser.parse("ce:myitems:sword*3").configString());
        assertEquals("ce:myitems:sword", parser.parse("ce:myitems:sword").configString());
    }

    @Test
    void parsesVanillaMaterials() {
        ItemSpec spec = parser.parse("DIAMOND");
        if (spec.source() == ItemSpec.Source.VANILLA) {
            assertEquals("DIAMOND", spec.id());
            assertEquals("minecraft:DIAMOND", spec.canonicalId());
        }
        ItemSpec withAmount = parser.parse("minecraft:diamond:3");
        if (withAmount.source() == ItemSpec.Source.VANILLA) {
            assertEquals(3, withAmount.amount());
        }
        assertFalse(parser.parse("ce:myitems:sword").isVanilla());
        assertTrue(parser.parse("ce:myitems:sword").isCraftEngine());
    }

    @Test
    void parsesCustomResolverPrefix() {
        java.util.Map<String, ItemResolver> resolvers = new java.util.concurrent.ConcurrentHashMap<>();
        resolvers.put("lib", body -> null); // 无服务端运行时，只验证解析与分发
        ItemSpecParser custom = new ItemSpecParser(
                java.util.logging.Logger.getLogger("test"), new ItemCodec(), null, null, "craftengine",
                resolvers);
        ItemSpec spec = custom.parse("lib:gift:spring*3");
        assertEquals(ItemSpec.Source.CUSTOM, spec.source());
        assertEquals("lib:gift:spring", spec.id());
        assertEquals(3, spec.amount());
        assertTrue(spec.isCustom());
        assertNull(spec.canonicalId());
        assertEquals("lib:gift:spring*3", spec.configString());
        assertNull(custom.build(spec));
    }
}
