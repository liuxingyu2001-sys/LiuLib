package com.liuxingyu.liulib.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 名称解析：物品显示名 / CE 翻译键 / MiniMessage / 原版本地化名 / 纯文本兜底。
 *
 * <p>这是全系列插件里复制次数最多的一段逻辑，统一后的解析顺序：</p>
 * <ol>
 *   <li>物品自带的 {@code item_name}（1.20.5+ 数据组件）→ {@code display_name} → 旧式名称；</li>
 *   <li>名称中的 {@code <lang:key>} / CE translatable 组件 → CE 翻译；</li>
 *   <li>翻译仍不可用时：CE 物品 ID → 实际材质名 → 材质 ID 人类化；</li>
 *   <li>原版物品：{@code lang/<locale>.json} → {@code getI18NDisplayName()} → 材质 ID 人类化。</li>
 * </ol>
 *
 * <p><b>保证不把 {@code <lang:KEY>} 或 {@code item.default.xxx} 原始键输出给玩家。</b></p>
 */
public final class ItemNames {

    private static final Pattern TRANSLATION_KEY =
            Pattern.compile("^(?:item|block|entity|enchantment|effect|biome|painting|instrument)\\.[a-z0-9_.:/-]+$");
    private static final Pattern CE_NAMESPACED_ID =
            Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Logger logger;
    private final CraftEngineItems craftEngine;
    private final MinecraftLang minecraftLang;
    private volatile String language;
    private volatile String unknownName;

    public ItemNames(Logger logger, CraftEngineItems craftEngine, MinecraftLang minecraftLang,
                     String language, String unknownName) {
        this.logger = logger;
        this.craftEngine = craftEngine;
        this.minecraftLang = minecraftLang;
        setLanguage(language);
        this.unknownName = unknownName == null || unknownName.isBlank() ? "未命名物品" : unknownName;
    }

    public void setLanguage(String language) {
        this.language = language == null || language.isBlank() ? "zh_CN" : language;
        if (craftEngine != null) {
            craftEngine.setServerLanguage(this.language);
        }
    }

    public String language() {
        return language;
    }

    public void setUnknownName(String name) {
        this.unknownName = name == null || name.isBlank() ? "未命名物品" : name;
    }

    public CraftEngineItems craftEngine() {
        return craftEngine;
    }

    public MinecraftLang minecraftLang() {
        return minecraftLang;
    }

    // ==================================================================
    // 文本 / 组件
    // ==================================================================

    /** 递归解析组件树里的 CE translatable，并替换文本中的 {@code <lang:key>}。 */
    public Component resolve(Component component) {
        if (component == null) {
            return null;
        }
        Component out = craftEngine == null ? component : craftEngine.resolveTranslatables(component);
        String plain = PLAIN.serialize(out);
        if (plain.indexOf('<') >= 0 && craftEngine != null) {
            String replaced = craftEngine.resolveLangTags(plain);
            if (!replaced.equals(plain)) {
                // 重新解析以保留颜色/样式；替换文本可能含 MiniMessage 标签
                return parse(replaced);
            }
        }
        return out;
    }

    /** 组件 → 纯文本（去色、解析 CE 翻译键）。 */
    public String plain(Component component) {
        if (component == null) {
            return null;
        }
        Component resolved = resolve(component);
        String text = PLAIN.serialize(resolved);
        return craftEngine == null ? text : craftEngine.resolveLangTags(text);
    }

    /** 组件 → § 颜色文本（解析 CE 翻译键）。 */
    public String legacy(Component component) {
        if (component == null) {
            return null;
        }
        Component resolved = resolve(component);
        String text = LEGACY.serialize(resolved);
        return craftEngine == null ? text : craftEngine.resolveLangTags(text);
    }

    /**
     * 解析配置文本：{@code lang:key} → 客户端 translatable；含 MiniMessage 标签时按 MiniMessage，
     * 否则按 {@code &} 颜色码解析。永远不抛异常。
     */
    public Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String value = text.trim();
        if (value.regionMatches(true, 0, "lang:", 0, 5)) {
            return Component.translatable(value.substring(5));
        }
        if (value.indexOf('<') >= 0 && value.indexOf('>') > 0) {
            try {
                return MINI.deserialize(value);
            } catch (RuntimeException ignored) {
                // 不是合法 MiniMessage，按普通颜色文本处理
            }
        }
        if (value.indexOf('&') >= 0) {
            try {
                return AMPERSAND.deserialize(value);
            } catch (RuntimeException ignored) {
                // 落到纯文本
            }
        }
        return Component.text(value);
    }

    /** 解析配置文本并输出纯文本（服务端聊天/网页用）。 */
    public String plainText(String text) {
        return plain(parse(text));
    }

    /** 解析配置文本并输出 § 颜色文本（旧式消息 API 用）。 */
    public String legacyText(String text) {
        return legacy(parse(text));
    }

    /** 去掉所有颜色码（§ / & / 十六进制）与 MiniMessage 标签的纯文本。 */
    public static String stripColors(String text) {
        if (text == null) {
            return null;
        }
        String noTags = text.replaceAll("<[^>]*>", "");
        String noSection = ChatColor.stripColor(noTags);
        // ChatColor.stripColor 只处理 §；配置里常见的 & 颜色码在这里一并去掉
        noSection = noSection.replaceAll("(?i)&#[0-9a-f]{6}", "");
        return noSection.replaceAll("(?i)&[0-9a-fk-orx]", "");
    }

    // ==================================================================
    // 物品名称
    // ==================================================================

    /** 物品自带的名称组件；无自定义名返回 null。 */
    public Component component(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        try {
            if (meta.hasItemName()) {
                Component name = meta.itemName();
                if (name != null && !name.equals(Component.empty())) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // 旧版本没有 itemName()
        }
        try {
            if (meta.hasDisplayName()) {
                Component name = meta.displayName();
                if (name != null && !name.equals(Component.empty())) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // 继续旧式 API
        }
        try {
            String legacy = meta.getDisplayName();
            if (legacy != null && !legacy.isBlank() && !legacy.equals(stack.getType().name())) {
                return Component.text(legacy);
            }
        } catch (Throwable ignored) {
            // 某些自定义物品实现会抛异常
        }
        return null;
    }

    /** 物品显示名（纯文本）；保证返回非空、且不是翻译键。 */
    public String label(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return unknownName;
        }
        Component custom = component(stack);
        if (custom != null) {
            String text = plain(custom);
            if (usable(text)) {
                return text;
            }
        }
        String ceId = craftEngine == null ? null : craftEngine.id(stack);
        if (ceId != null) {
            String translated = craftEngine.resolveTranslation(ceId);
            if (usable(translated)) {
                return translated;
            }
        }
        return materialLabel(stack.getType());
    }

    /** 物品显示名（§ 颜色文本）；无自定义名时返回原版本地化名。 */
    public String legacyLabel(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return unknownName;
        }
        Component custom = component(stack);
        if (custom != null) {
            String text = legacy(custom);
            if (usable(stripColors(text))) {
                return text;
            }
        }
        return materialLabel(stack.getType());
    }

    /** CE / 原版物品 ID → 可读名称；无法识别时返回 ID 本身的人类化形式。 */
    public String labelById(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return unknownName;
        }
        String id = itemId.trim();
        if (id.regionMatches(true, 0, "ce:", 0, 3)
                || id.regionMatches(true, 0, "craftengine:", 0, 12)) {
            String ceId = craftEngine == null ? id : craftEngine.normalizeId(id);
            return craftEngineLabel(ceId);
        }
        // 先尝试原版材质
        Material material = material(id);
        if (material != null) {
            return materialLabel(material);
        }
        // 再尝试 CE（namespace:id 或裸 id）
        if (CE_NAMESPACED_ID.matcher(id).matches() || (craftEngine != null && craftEngine.isAvailable())) {
            return craftEngineLabel(craftEngine == null ? id : craftEngine.normalizeId(id));
        }
        int colon = id.indexOf(':');
        return humanize(colon >= 0 ? id.substring(colon + 1) : id);
    }

    private String craftEngineLabel(String ceId) {
        if (craftEngine == null) {
            return humanize(ceId);
        }
        String translated = craftEngine.resolveTranslation(ceId);
        if (usable(translated)) {
            return translated;
        }
        ItemStack built = craftEngine.build(ceId, 1);
        if (built != null) {
            Component name = component(built);
            if (name != null) {
                String text = plain(name);
                if (usable(text)) {
                    return text;
                }
            }
            return materialLabel(built.getType());
        }
        String inner = ceId;
        int colon = inner.indexOf(':');
        if (colon >= 0) {
            inner = inner.substring(colon + 1);
        }
        return humanize(inner);
    }

    /** 原版材质名称：语言文件 → getI18NDisplayName → 材质 ID 人类化。 */
    public String materialLabel(Material material) {
        if (material == null || material.isAir()) {
            return unknownName;
        }
        try {
            String key = material.getTranslationKey();
            String localized = minecraftLang == null ? null : minecraftLang.get(key, language);
            if (usable(localized)) {
                return localized;
            }
        } catch (Throwable ignored) {
            // 继续
        }
        try {
            String i18n = new ItemStack(material).getI18NDisplayName();
            if (usable(i18n) && !isTranslationKey(stripColors(i18n))) {
                return stripColors(i18n);
            }
        } catch (Throwable ignored) {
            // 继续
        }
        return humanize(material.name());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 大小写通用的材质解析；非法返回 null。 */
    public static Material material(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String value = name.trim();
        if (value.regionMatches(true, 0, "minecraft:", 0, 10)) {
            value = value.substring(10);
        }
        if (value.regionMatches(true, 0, "ce:", 0, 3)
                || value.regionMatches(true, 0, "craftengine:", 0, 12)) {
            return null;
        }
        Material material = Material.matchMaterial(value);
        return material != null ? material : Material.matchMaterial(value.toUpperCase(Locale.ROOT));
    }

    /** 原版材质的翻译键。 */
    public static String translationKey(String materialName) {
        Material material = material(materialName);
        return material == null ? null : material.getTranslationKey();
    }

    /** 是否为未解析的翻译键 / 原始 CE 键。 */
    public static boolean isTranslationKey(String text) {
        if (text == null) {
            return false;
        }
        String raw = text.trim();
        if (raw.contains("<lang:") || raw.startsWith("lang:")) {
            return true;
        }
        String value = stripColors(raw).trim();
        return TRANSLATION_KEY.matcher(value).matches();
    }

    /** 材质 ID / CE ID 的人类化名称：{@code DIAMOND_SWORD → Diamond Sword}。 */
    public static String humanize(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String value = id;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("[_\\-]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.isEmpty() ? id : out.toString();
    }

    /** 名称文本是否可用（非空、无颜色、不是翻译键）。 */
    private boolean usable(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return !isTranslationKey(stripColors(text));
    }

    /** 给名称加默认非斜体（GUI 物品常用）。 */
    public static Component nonItalic(Component component) {
        return component == null ? null
                : component.decoration(TextDecoration.ITALIC, false);
    }
}
