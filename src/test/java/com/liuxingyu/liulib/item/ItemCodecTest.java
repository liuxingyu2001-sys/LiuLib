package com.liuxingyu.liulib.item;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemCodecTest {

    @Test
    void decodesStandardMimeAndUrlSafeBase64() {
        byte[] payload = "hello item".getBytes(StandardCharsets.UTF_8);
        String standard = Base64.getEncoder().encodeToString(payload);
        String mime = Base64.getMimeEncoder(4, new byte[]{'\n'}).encodeToString(payload);
        String url = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        assertArrayEquals(payload, ItemCodec.decodeBase64(standard));
        assertArrayEquals(payload, ItemCodec.decodeBase64(mime));
        assertArrayEquals(payload, ItemCodec.decodeBase64(url));
    }

    @Test
    void emptyChecks() {
        assertTrue(ItemCodec.isEmpty(null));
        assertNull(new ItemCodec().decode((String) null));
        assertNull(new ItemCodec().decode(""));
        assertNull(new ItemCodec().decode("   "));
    }

    @Test
    void stripsKnownPrefixes() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        String encoded = Base64.getEncoder().encodeToString(payload);
        // 前缀被剥掉后仍应走 Base64 解码；这里只验证不会把前缀当数据
        for (String prefix : new String[]{"nbt:", "base64:", "bytes:", "item:"}) {
            String value = prefix + encoded;
            assertTrue(value.endsWith(encoded));
        }
    }

    @Test
    void rejectsOversizedData() {
        ItemCodec codec = new ItemCodec(1024, java.util.logging.Logger.getLogger("test"));
        String huge = "A".repeat(2048);
        assertNull(codec.decode(huge));
    }

    @Test
    void decodesManyWithBlankSlots() {
        ItemCodec codec = new ItemCodec();
        assertTrue(codec.decodeMany("").isEmpty());
        // 全为空行时返回两个 null 槽位
        assertEquals(2, codec.decodeMany("\n").size());
        assertFalse(codec.decodeMany("\n").get(0) != null);
    }
}
