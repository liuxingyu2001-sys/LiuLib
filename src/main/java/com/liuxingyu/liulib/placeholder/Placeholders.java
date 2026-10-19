package com.liuxingyu.liulib.placeholder;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * PlaceholderAPI 统一注册表：业务插件不再直接依赖 PAPI，也不需要自己写
 * {@code PlaceholderExpansion} 子类。
 *
 * <pre>{@code
 * Placeholders placeholders = LiuLib.placeholders();
 * if (placeholders.available()) {
 *     placeholders.register("myshop", (player, params) -> switch (params) {
 *         case "points" -> String.valueOf(points(player));
 *         case "rank" -> rank(player);
 *         default -> null;
 *     });
 * }
 * }</pre>
 *
 * <p>未安装 PAPI 时 {@link #available()} 为 false，{@link #register} 直接返回 false，
 * 不会加载 PAPI 类，也不会影响插件启动。</p>
 *
 * <p>同一 identifier 重复注册会先注销旧扩展再注册新扩展，便于业务插件重载；
 * {@link #close()} 会注销本类注册的全部扩展。</p>
 */
public final class Placeholders implements AutoCloseable {

    private final Plugin plugin;
    private final boolean available;
    private final Map<String, ExpansionHandle> expansions = new ConcurrentHashMap<>();

    public Placeholders(Plugin plugin) {
        this.plugin = plugin;
        this.available = detect();
    }

    private static boolean detect() {
        try {
            return Bukkit.getServer() != null
                    && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** PAPI 是否可用；不可用时所有注册都会返回 false。 */
    public boolean available() {
        return available;
    }

    /**
     * 注册一个扩展。
     *
     * @param identifier 变量前缀，只能包含字母、数字、下划线；例如 {@code myshop}
     * @param handler    解析器
     * @return 是否注册成功
     */
    public boolean register(String identifier, PlaceholderHandler handler) {
        return register(identifier, null, null, handler);
    }

    public boolean register(String identifier, String author, String version,
                            PlaceholderHandler handler) {
        if (!available || handler == null) {
            return false;
        }
        String id = normalize(identifier);
        if (id == null) {
            warn("非法的 PlaceholderAPI identifier: " + identifier);
            return false;
        }
        unregister(id);
        ExpansionHandle expansion = create(id, author,
                version == null && plugin != null ? pluginVersion() : version, handler);
        if (expansion == null) {
            return false;
        }
        try {
            if (!expansion.register()) {
                expansion.close();
                warn("PlaceholderAPI 扩展注册失败（identifier 可能已被其它插件占用）: " + id);
                return false;
            }
            expansions.put(id, expansion);
            return true;
        } catch (Throwable error) {
            expansion.close();
            warn("PlaceholderAPI 扩展加载失败: " + id + " - " + error);
            return false;
        }
    }

    /**
     * 通过反射创建 PAPI 扩展：只有 PAPI 可用时才加载 {@code PapiExpansion}，
     * 未安装 PAPI 的服务器不会因为缺类而启动失败。
     */
    private ExpansionHandle create(String id, String author, String version,
                                   PlaceholderHandler handler) {
        try {
            Class<?> type = Class.forName("com.liuxingyu.liulib.placeholder.PapiExpansion");
            var constructor = type.getDeclaredConstructor(
                    String.class, String.class, String.class, PlaceholderHandler.class);
            constructor.setAccessible(true);
            return (ExpansionHandle) constructor.newInstance(id, author, version, handler);
        } catch (Throwable error) {
            warn("无法创建 PlaceholderAPI 扩展（PAPI 未安装或版本不兼容）: " + id + " - " + error);
            return null;
        }
    }

    /** 注销本类注册的扩展。 */
    public boolean unregister(String identifier) {
        String id = normalize(identifier);
        if (id == null) {
            return false;
        }
        ExpansionHandle expansion = expansions.remove(id);
        if (expansion == null) {
            return false;
        }
        expansion.close();
        return true;
    }

    /** 注销全部扩展。 */
    public void unregisterAll() {
        for (String id : Set.copyOf(expansions.keySet())) {
            unregister(id);
        }
    }

    /** 当前由本类注册的 identifier。 */
    public Set<String> identifiers() {
        return Collections.unmodifiableSet(expansions.keySet());
    }

    public boolean isRegistered(String identifier) {
        String id = normalize(identifier);
        return id != null && expansions.containsKey(id);
    }

    public int size() {
        return expansions.size();
    }

    @Override
    public void close() {
        unregisterAll();
    }

    private String pluginVersion() {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (Throwable ignored) {
            return "1.0.0";
        }
    }

    private static String normalize(String identifier) {
        if (identifier == null) {
            return null;
        }
        String id = identifier.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.length() > 64) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return null;
            }
        }
        return id;
    }

    private void warn(String message) {
        if (plugin != null) {
            plugin.getLogger().log(Level.WARNING, message);
        }
    }
}
