package com.liuxingyu.liulib.item;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemNamesTest {

    @Test
    void humanizesMaterialIds() {
        assertEquals("Diamond Sword", ItemNames.humanize("DIAMOND_SWORD"));
        assertEquals("Flame Sword", ItemNames.humanize("myitems:flame_sword"));
        assertEquals("", ItemNames.humanize(""));
    }

    @Test
    void detectsTranslationKeys() {
        assertTrue(ItemNames.isTranslationKey("<lang:item.default.sword>"));
        assertTrue(ItemNames.isTranslationKey("item.default.sword"));
        assertTrue(ItemNames.isTranslationKey("block.minecraft.stone"));
        assertFalse(ItemNames.isTranslationKey("黄玉剑"));
        assertFalse(ItemNames.isTranslationKey("§b黄玉剑"));
    }

    @Test
    void stripsColorsAndTags() {
        assertEquals("黄玉剑", ItemNames.stripColors("§b<bold>黄玉剑</bold>"));
        assertEquals("hello", ItemNames.stripColors("&ahello"));
    }

    @Test
    void parsesMiniMessageAndLegacyText() {
        ItemNames names = new ItemNames(java.util.logging.Logger.getLogger("test"), null, null,
                "zh_CN", "未命名物品");
        Component mini = names.parse("<red>Hello</red>");
        assertNotNull(mini);
        assertEquals("Hello", names.plain(mini));

        Component legacy = names.parse("&bHello");
        assertEquals("Hello", names.plain(legacy));

        assertEquals("Hello", names.plainText("<gradient:red:blue>Hello</gradient>"));
    }

    @Test
    void resolvesLangPlaceholderWithoutTranslations() {
        ItemNames names = new ItemNames(java.util.logging.Logger.getLogger("test"), null, null,
                "zh_CN", "未命名物品");
        // 没有 CE 时 plainText 会退化为翻译键（label() 负责继续走材质兜底，不输出键）
        String text = names.plainText("<lang:item.default.sword>");
        assertNotNull(text);
        assertTrue(text.contains("item.default.sword") || text.contains("<lang:"));
    }
}
