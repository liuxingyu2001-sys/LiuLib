package com.liuxingyu.liulib.text;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code messages.yml} 渲染入口。
 *
 * <p>文本支持 MiniMessage 与传统色码，占位符使用 {@code {name}}；前缀取
 * {@code text.prefix}。缺少 key 时输出 {@code text.missing-key} 并只警告一次，
 * 避免依赖方把 {@code <key>} 直接发给玩家。</p>
 */
public final class Messages {

    private final TextService text;
    private final Plugin plugin;
    private final String fileName;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private volatile YamlConfiguration yaml;

    public Messages(TextService text, Plugin plugin, String fileName) {
        this.text = text;
        this.plugin = plugin;
        this.fileName = fileName == null || fileName.isBlank() ? "messages.yml" : fileName;
        reload();
    }

    /** 重新读取消息文件；文件不存在时按空配置处理，不覆盖磁盘内容。 */
    public synchronized void reload() {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException ignored) {
                // jar 内没有默认文件：保持空配置，由调用方自行提供
            }
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public boolean has(String key) {
        return key != null && yaml.isString(key);
    }

    /** 不带前缀的原始字符串（已替换占位符）。 */
    public String raw(String key, Object... placeholders) {
        return text.fill(rawString(key), placeholders);
    }

    /** 带前缀的组件；key 为空或空串时返回空前缀组件。 */
    public Component component(String key, Object... placeholders) {
        return text.prefix().append(body(key, placeholders));
    }

    /** 不带前缀的组件。 */
    public Component body(String key, Object... placeholders) {
        return text.parse(raw(key, placeholders));
    }

    /** 列表消息（帮助、排行榜等），逐行解析。 */
    public List<Component> list(String key, Object... placeholders) {
        List<Component> out = new ArrayList<>();
        List<String> lines = yaml.getStringList(key);
        if (lines.isEmpty() && !yaml.isList(key)) {
            String missing = missing(key);
            if (!missing.isEmpty()) {
                out.add(text.parse(text.fill(missing, placeholders)));
            }
            return out;
        }
        for (String line : lines) {
            out.add(text.parse(text.fill(line, placeholders)));
        }
        return out;
    }

    /** 带前缀发送单条消息；空消息不发送。 */
    public void send(CommandSender sender, String key, Object... placeholders) {
        if (sender == null) {
            return;
        }
        String raw = raw(key, placeholders);
        if (raw.isEmpty()) {
            return;
        }
        sender.sendMessage(component(key, placeholders));
    }

    /** 发送列表消息。 */
    public void sendList(CommandSender sender, String key, Object... placeholders) {
        if (sender == null) {
            return;
        }
        for (Component line : list(key, placeholders)) {
            sender.sendMessage(line);
        }
    }

    public String fileName() {
        return fileName;
    }

    private String rawString(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String value = yaml.getString(key);
        return value == null ? missing(key) : value;
    }

    private String missing(String key) {
        if (text.settings().warnMissing() && warned.add(key)) {
            plugin.getLogger().warning("消息文件 " + fileName + " 缺少 key: " + key);
        }
        return text.settings().missingKey().replace("{key}", key == null ? "" : key);
    }
}
