package com.liuxingyu.liulib.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * CraftEngine 软依赖钩子（全部反射，不硬依赖 CE 本体）。
 *
 * <p>覆盖三类公共需求：</p>
 * <ul>
 *   <li><b>物品解析</b>：{@code CraftEngineItems.byId(String)} →
 *       {@code BukkitItemDefinition.buildBukkitItem()} / {@code buildBukkitItem(Player)}；</li>
 *   <li><b>物品识别</b>：{@code getCustomItemId(ItemStack)} → {@code namespace:id}；</li>
 *   <li><b>名称解析</b>：CE 物品名常为 {@code <lang:item.default.xxx>}。
 *       CE 的 TranslationManager 服务端只加载 UI 字符串，物品键默认只在资源包
 *       {@code resources/**}{@code /*.yml} 里，因此这里两级解析：
 *       ① TranslationManager；② 自载资源包/translations 语言文件兜底。</li>
 * </ul>
 *
 * <p>CE 未安装、版本不匹配或任一反射调用失败时都不抛异常：物品构建返回 null，
 * 名称解析返回 null，由 {@link ItemNames} 继续走原版兜底链。</p>
 */
public final class CraftEngineItems {

    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}([_-][a-z0-9]{2,4})?$");
    private static final Set<String> SKIP_DIRS =
            Set.of("libs", "cache", "generated", "backups", "logs", "data");

    private final Logger logger;
    private final String defaultNamespace;
    private final boolean scanLanguageFiles;

    private boolean available;
    private Method byIdString;
    private Method byIdKey;
    private Method buildBukkitItem;
    private Method buildBukkitItemPlayer;
    private Method getCustomItemId;

    // TranslationManager（CE 内部 shaded Adventure，类型不公开，一律反射）
    private Method tmInstance;
    private final List<Method> tmPlainTranslations = new ArrayList<>();
    private final List<Method> tmMiniMessageTranslations = new ArrayList<>();

    private Locale serverLocale = Locale.SIMPLIFIED_CHINESE;
    private volatile Map<String, Map<String, String>> localTranslations = Map.of();

    public CraftEngineItems(Logger logger) {
        this(logger, "craftengine", true);
    }

    public CraftEngineItems(Logger logger, String defaultNamespace, boolean scanLanguageFiles) {
        this.logger = logger;
        this.defaultNamespace = defaultNamespace == null || defaultNamespace.isBlank()
                ? "craftengine" : defaultNamespace;
        this.scanLanguageFiles = scanLanguageFiles;
        detect();
        if (scanLanguageFiles) {
            reloadTranslations();
        }
    }

    // ==================================================================
    // 能力探测
    // ==================================================================

    private void detect() {
        available = false;
        if (Bukkit.getPluginManager().getPlugin("CraftEngine") == null) {
            return;
        }
        try {
            Class<?> items = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            byIdString = staticMethod(items, "byId", String.class);
            try {
                Class<?> keyClass = Class.forName("net.momirealms.craftengine.core.util.Key");
                byIdKey = staticMethod(items, "byId", keyClass);
            } catch (ClassNotFoundException ignored) {
                // 旧版本没有 Key，只保留 String 重载
            }
            Class<?> definition = Class.forName(
                    "net.momirealms.craftengine.bukkit.item.BukkitItemDefinition");
            try {
                buildBukkitItem = definition.getMethod("buildBukkitItem");
            } catch (NoSuchMethodException ignored) {
                // 某些版本只有带 Player 的重载
            }
            try {
                buildBukkitItemPlayer = definition.getMethod("buildBukkitItem", Player.class);
            } catch (NoSuchMethodException ignored) {
                // 旧版本没有 Player 重载
            }
            try {
                getCustomItemId = items.getMethod("getCustomItemId", ItemStack.class);
            } catch (NoSuchMethodException ignored) {
                // 极旧版本没有反查 API
            }
            available = (buildBukkitItem != null || buildBukkitItemPlayer != null)
                    && (byIdString != null || byIdKey != null);
        } catch (Throwable error) {
            available = false;
            debug("CraftEngine API 探测失败: " + error);
        }
        detectTranslations();
    }

    private void detectTranslations() {
        tmPlainTranslations.clear();
        tmMiniMessageTranslations.clear();
        try {
            Class<?> type = Class.forName(
                    "net.momirealms.craftengine.core.plugin.locale.TranslationManager");
            tmInstance = staticMethod(type, "instance");
            for (Method method : type.getMethods()) {
                if ("plainTranslation".equals(method.getName()) && method.getParameterCount() >= 2
                        && method.getParameterTypes()[1] == Locale.class) {
                    tmPlainTranslations.add(method);
                } else if ("miniMessageTranslation".equals(method.getName())
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[1] == Locale.class) {
                    tmMiniMessageTranslations.add(method);
                }
            }
        } catch (Throwable ignored) {
            // CE 未安装或版本不含 TranslationManager
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 反查 API 是否可用（部分旧 CE 只能按 ID 构建，不能从物品反查）。 */
    public boolean canIdentify() {
        return available && getCustomItemId != null;
    }

    public String diagnostics() {
        return "available=" + available
                + " identify=" + (getCustomItemId != null)
                + " build=" + (buildBukkitItem != null || buildBukkitItemPlayer != null)
                + " translationManager=" + (tmInstance != null)
                + " localLocales=" + localTranslations.size();
    }

    // ==================================================================
    // 物品构建 / 识别
    // ==================================================================

    /** 通过 CE ID 构建物品；CE 未安装或 ID 不存在时返回 null。 */
    public ItemStack build(String rawId, int amount) {
        return build(rawId, amount, null);
    }

    /**
     * 通过 CE ID 构建物品。
     *
     * @param viewer 可为 null；CE 支持按玩家构建时（例如玩家专属材质）会传入
     */
    public ItemStack build(String rawId, int amount, Player viewer) {
        if (!available || rawId == null || rawId.isBlank()) {
            return null;
        }
        String id = normalizeId(rawId);
        try {
            Object definition = lookup(id);
            if (definition == null && !id.equals(rawId)) {
                definition = lookup(rawId.trim());
            }
            if (definition == null) {
                return null;
            }
            Object value = null;
            if (viewer != null && buildBukkitItemPlayer != null) {
                value = buildBukkitItemPlayer.invoke(definition, viewer);
            }
            if (value == null && buildBukkitItem != null) {
                value = buildBukkitItem.invoke(definition);
            }
            if (value instanceof Optional<?> optional) {
                value = optional.orElse(null);
            }
            if (!(value instanceof ItemStack stack)) {
                return null;
            }
            stack = stack.clone();
            stack.setAmount(Math.max(1, Math.min(amount, Math.max(1, stack.getMaxStackSize()))));
            return stack;
        } catch (Throwable error) {
            debug("CraftEngine 物品构建失败 id=" + rawId + ": " + error);
            return null;
        }
    }

    private Object lookup(String id) throws ReflectiveOperationException {
        Object definition = null;
        if (byIdString != null) {
            definition = byIdString.invoke(null, id);
        }
        if (definition == null && byIdKey != null) {
            Class<?> keyType = byIdKey.getParameterTypes()[0];
            try {
                Method of = keyType.getMethod("of", String.class);
                Object key = of.invoke(null, id);
                if (key != null) {
                    definition = byIdKey.invoke(null, key);
                }
            } catch (NoSuchMethodException ignored) {
                // 某些 Key 实现只有 of(String, char) / parse
                try {
                    Method of = keyType.getMethod("of", String.class, char.class);
                    Object key = of.invoke(null, id, ':');
                    if (key != null) {
                        definition = byIdKey.invoke(null, key);
                    }
                } catch (NoSuchMethodException ignoredAgain) {
                    // 放弃 Key 兜底
                }
            }
        }
        return definition;
    }

    /**
     * 识别物品的 CE ID（{@code namespace:id}）；非 CE 物品返回 null。
     */
    public String id(ItemStack stack) {
        if (!available || getCustomItemId == null || stack == null || stack.getType().isAir()) {
            return null;
        }
        try {
            Object value = getCustomItemId.invoke(null, stack);
            if (value instanceof Optional<?> optional) {
                value = optional.orElse(null);
            }
            if (value == null) {
                return null;
            }
            if (value instanceof String text) {
                return blankToNull(text);
            }
            try {
                Method asString = value.getClass().getMethod("asString");
                Object text = asString.invoke(value);
                if (text != null) {
                    return blankToNull(text.toString());
                }
            } catch (NoSuchMethodException ignored) {
                // 继续用 toString
            }
            return blankToNull(value.toString());
        } catch (Throwable error) {
            return null;
        }
    }

    /** 是否为 CraftEngine 自定义物品。 */
    public boolean isCustomItem(ItemStack stack) {
        return id(stack) != null;
    }

    /**
     * 规范化 CE ID：去掉 {@code ce:} / {@code craftengine:} 前缀；无命名空间时补默认命名空间。
     */
    public String normalizeId(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim();
        if (id.regionMatches(true, 0, "ce:", 0, 3)) {
            id = id.substring(3);
        } else if (id.regionMatches(true, 0, "craftengine:", 0, 12)) {
            id = id.substring(12);
        }
        if (id.isBlank()) {
            return null;
        }
        if (id.indexOf(':') < 0) {
            id = defaultNamespace + ":" + id;
        }
        return id;
    }

    // ==================================================================
    // 名称翻译
    // ==================================================================

    public void setServerLanguage(String language) {
        if (language == null || language.isBlank()) {
            return;
        }
        try {
            Locale parsed = Locale.forLanguageTag(language.trim().replace('_', '-'));
            if (parsed.getLanguage() != null && !parsed.getLanguage().isBlank()) {
                serverLocale = parsed;
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面的手工拆分
        }
        String[] parts = language.trim().split("[_-]");
        serverLocale = parts.length >= 2
                ? new Locale(parts[0].toLowerCase(Locale.ROOT), parts[1].toUpperCase(Locale.ROOT))
                : new Locale(parts[0].toLowerCase(Locale.ROOT));
    }

    public Locale serverLocale() {
        return serverLocale;
    }

    /** 重载 CE 资源包/translations 语言文件；CE 侧改语言后调用。 */
    public void reloadTranslations() {
        if (!scanLanguageFiles) {
            return;
        }
        File base = null;
        try {
            Plugin ce = Bukkit.getPluginManager().getPlugin("CraftEngine");
            if (ce != null) {
                base = ce.getDataFolder();
            }
        } catch (Throwable ignored) {
            // 插件卸载中
        }
        if (base == null || !base.isDirectory()) {
            localTranslations = Map.of();
            return;
        }
        Map<String, Map<String, String>> out = new HashMap<>();
        List<File> files = new ArrayList<>();
        collectYaml(new File(base, "resources"), files, 0);
        File translations = new File(base, "translations");
        File[] server = translations.isDirectory()
                ? translations.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"))
                : null;
        if (server != null) {
            Collections.addAll(files, server);
        }
        for (File file : files) {
            try {
                loadLanguageFile(file, out);
            } catch (Throwable error) {
                debug("CE 语言文件解析失败 " + file.getName() + ": " + error);
            }
        }
        localTranslations = Map.copyOf(out);
    }

    private static void collectYaml(File dir, List<File> out, int depth) {
        if (depth > 8 || dir == null || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (!SKIP_DIRS.contains(child.getName().toLowerCase(Locale.ROOT))) {
                    collectYaml(child, out, depth + 1);
                }
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".yml")) {
                out.add(child);
            }
        }
    }

    /**
     * 解析单个 CE 语言文件。
     *
     * <p>两种结构都支持：</p>
     * <ul>
     *   <li>{@code lang#items.zh_cn.item.default.foo: 译文}（资源包，段名+#+locale+键）；</li>
     *   <li>{@code translations/zh_cn.yml} 顶层平铺键（locale 即文件名）。</li>
     * </ul>
     */
    private static void loadLanguageFile(File file, Map<String, Map<String, String>> out) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String stem = file.getName().replaceAll("(?i)\\.yml$", "").toLowerCase(Locale.ROOT);
        boolean localeFile = LOCALE.matcher(stem).matches();
        for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Map || value instanceof List
                    || value instanceof org.bukkit.configuration.ConfigurationSection) {
                continue;
            }
            String path = entry.getKey();
            String locale;
            String key;
            int hash = path.indexOf('#');
            if (hash >= 0) {
                int sectionEnd = path.indexOf('.', hash);
                if (sectionEnd < 0) {
                    continue;
                }
                String rest = path.substring(sectionEnd + 1);
                int separator = rest.indexOf('.');
                if (separator >= 0) {
                    locale = rest.substring(0, separator);
                    key = rest.substring(separator + 1);
                } else {
                    locale = stem;
                    key = rest;
                }
                if (!LOCALE.matcher(locale).matches() || key.isEmpty()) {
                    continue;
                }
            } else {
                if (!localeFile) {
                    continue;
                }
                locale = stem;
                key = path;
            }
            out.computeIfAbsent(locale, ignored -> new HashMap<>())
                    .put(key, stripTags(String.valueOf(value)));
        }
    }

    /**
     * 解析 CE 翻译键，如 {@code item.default.topaz_trident → 黄玉三叉戟}。
     * 无翻译/CE 未安装返回 null。
     */
    public String resolveTranslation(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim();
        if (normalized.regionMatches(true, 0, "ce:", 0, 3)) {
            normalized = normalized.substring(3);
        } else if (normalized.regionMatches(true, 0, "craftengine:", 0, 12)) {
            normalized = normalized.substring(12);
        }
        if (tmInstance != null) {
            try {
                Object manager = tmInstance.invoke(null);
                if (manager != null) {
                    for (String variant : translationVariants(normalized)) {
                        String value = translate(manager, variant, serverLocale);
                        if (value != null) {
                            return value;
                        }
                        String language = serverLocale.getLanguage();
                        if (!language.isBlank() && !language.equals(serverLocale.toString())) {
                            value = translate(manager, variant, new Locale(language));
                            if (value != null) {
                                return value;
                            }
                        }
                    }
                }
            } catch (Throwable error) {
                debug("CE TranslationManager 解析失败: " + error);
            }
        }
        return localResolve(normalized);
    }

    private String localResolve(String key) {
        for (String variant : translationVariants(key)) {
            for (String locale : localeTries(serverLocale)) {
                Map<String, String> map = localTranslations.get(locale);
                if (map != null) {
                    String value = map.get(variant);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        for (Map<String, String> map : localTranslations.values()) {
            String value = map.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String translate(Object manager, String key, Locale locale) {
        for (Method method : tmPlainTranslations) {
            try {
                Object value = method.getParameterCount() == 3
                        ? method.invoke(manager, key, locale, new String[0])
                        : method.invoke(manager, key, locale);
                String cleaned = clean(value, key);
                if (cleaned != null) {
                    return cleaned;
                }
            } catch (Throwable ignored) {
                // 尝试下一个重载
            }
        }
        for (Method method : tmMiniMessageTranslations) {
            try {
                String cleaned = clean(method.invoke(manager, key, locale), key);
                if (cleaned != null) {
                    return cleaned;
                }
            } catch (Throwable ignored) {
                // 尝试下一个重载
            }
        }
        return null;
    }

    /** 递归把组件树里的 translatable 换成 CE 翻译文本（保留样式）。 */
    public Component resolveTranslatables(Component component) {
        if (component == null) {
            return null;
        }
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(resolveTranslatables(child));
        }
        if (component instanceof TranslatableComponent translatable) {
            String resolved = resolveTranslation(translatable.key());
            if (resolved != null) {
                return Component.text(resolved).style(component.style());
            }
        }
        return component.children(children);
    }

    /**
     * 替换字符串里的 {@code <lang:key>} 以及裸的 {@code item.*}/{@code block.*} 键。
     * 无翻译时保留原样（调用方负责最终兜底，绝不把原始键当成名称输出）。
     */
    public String resolveLangTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        int from = 0;
        while (true) {
            int start = out.indexOf("<lang:", from);
            if (start < 0) {
                break;
            }
            int end = out.indexOf('>', start);
            if (end < 0) {
                break;
            }
            String key = out.substring(start + 6, end);
            String value = resolveTranslation(key);
            String replacement = value == null ? out.substring(start, end + 1) : value;
            out = out.substring(0, start) + replacement + out.substring(end + 1);
            from = start + replacement.length();
        }
        // 裸键：item.default.xxx / block.ns:id（只替换能解析的，避免误伤普通文本）
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(?:item|block)\\.[a-z0-9_.:-]+").matcher(out);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String value = resolveTranslation(matcher.group());
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(
                    value == null ? matcher.group() : value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** 当前已加载的 locale（排障用）。 */
    public Set<String> loadedLocales() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(localTranslations.keySet()));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static List<String> localeTries(Locale locale) {
        LinkedHashSet<String> tries = new LinkedHashSet<>();
        if (locale != null) {
            String language = locale.getLanguage().toLowerCase(Locale.ROOT);
            if (!locale.getCountry().isBlank()) {
                tries.add(language + "_" + locale.getCountry().toLowerCase(Locale.ROOT));
            }
            if (!language.isBlank()) {
                tries.add(language);
            }
        }
        tries.add("zh_cn");
        tries.add("en_us");
        tries.add("en");
        return new ArrayList<>(tries);
    }

    private static String[] translationVariants(String key) {
        if (key.indexOf('.') < 0 && key.indexOf(':') < 0) {
            return new String[]{key};
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(key);
        variants.add(key.replace(':', '.'));
        variants.add(key.replace('.', ':'));
        return variants.toArray(new String[0]);
    }

    private static String clean(Object value, String key) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        if (text.isBlank() || text.equalsIgnoreCase(key)) {
            return null;
        }
        return text;
    }

    /** MiniMessage 标签（{@code <white>}、{@code <lang:...>}、{@code <!i>}）→ 纯文本。 */
    private static String stripTags(String text) {
        return text.replaceAll("<[^>]*>", "");
    }

    private static Method staticMethod(Class<?> type, String name, Class<?>... params) {
        try {
            Method method = type.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void debug(String message) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(message);
        }
    }
}
