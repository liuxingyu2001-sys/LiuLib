package com.liuxingyu.liulib.text;

import com.liuxingyu.liulib.config.LiuLibConfig;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextServiceTest {

    private final TextService text = new TextService(LiuLibConfig.Text.defaults());

    @Test
    void parsesLegacyAndMiniMessage() {
        assertEquals("Hello", text.plain("&aHello"));
        assertEquals("Hello", text.plain("§bHello"));
        assertEquals("Hello", text.plain("<red>Hello</red>"));

        Component mixed = text.parse("&aHello <gradient:red:blue>World</gradient>");
        assertEquals("Hello World", text.plain(mixed));
        assertTrue(text.legacy(mixed).contains("§a"));
    }

    @Test
    void convertsHexColors() {
        String legacy = text.legacy("&#FF5500Hello");
        assertTrue(legacy.contains("§x§f§f§5§5§0§0"), legacy);
        assertEquals("Hello", text.plain("&#FF5500Hello"));

        assertEquals("Hello", text.plain("&x&F&F&5&5&0&0Hello"));
    }

    @Test
    void fillsPlaceholdersBeforeParsing() {
        Component component = text.component("<green>{player} 有 {amount} 金币",
                "player", "Steve", "amount", 3);
        assertEquals("Steve 有 3 金币", text.plain(component));

        assertEquals("a-b", text.fill("{x}-{y}", Map.of("x", "a", "y", "b")));
        assertEquals("a-", text.fill("{x}-{y}", "x", "a", "y", null));
    }

    @Test
    void stripsColorsAndTags() {
        assertEquals("黄玉剑", text.strip("§b<bold>黄玉剑</bold>"));
        assertEquals("hello", text.strip("&ahello"));
    }

    @Test
    void toleratesBrokenInput() {
        assertEquals("", text.plain((String) null));
        assertEquals("", text.plain(""));
        assertNotNull(text.parse("<not_a_real_tag>"));
        assertNotNull(text.parse("&"));
        assertNotNull(text.parse("<gradient:red>未闭合"));
    }

    @Test
    void parsesLineLists() {
        List<Component> lines = text.parseLines(List.of("&a第一行", "<red>第二行"));
        assertEquals(2, lines.size());
        assertEquals("第一行", text.plain(lines.get(0)));
        assertEquals("第二行", text.plain(lines.get(1)));
    }

    @Test
    void prefixIsParsed() {
        TextService prefixed = new TextService(new LiuLibConfig.Text(
                "<gray>[测试]</gray> ", "<red>缺失: {key}", true, true));
        assertEquals("[测试] 你好", prefixed.plain(prefixed.prefix().append(prefixed.parse("你好"))));
    }
}
