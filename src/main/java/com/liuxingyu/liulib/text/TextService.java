package com.liuxingyu.liulib.text;

import com.liuxingyu.liulib.config.LiuLibConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一文本入口：同时支持 MiniMessage、传统 {@code &} / {@code §} 色码与十六进制。
 *
 * <p>全系列插件此前各自维护一份 {@code Text}/{@code Msg}/{@code MessageUtils}，
 * 同一段配置在不同插件里可能显示不同。这里固化一套行为：</p>
 * <ol>
 *   <li>{@code &#RRGGBB}、{@code &x&R&R&G&G&B&B}、{@code §x§R...} 先转成 MiniMessage 颜色标签；</li>
 *   <li>{@code &a} / {@code §a} 等传统色码转成对应的 MiniMessage 标签；</li>
 *   <li>交给 MiniMessage 解析；失败时退回传统解析，再失败则按纯文本输出，绝不抛异常；</li>
 *   <li>{@code {key}} 占位符由 {@link #fill(String, Object...)} 替换，与 MiniMessage 标签互不干扰。</li>
 * </ol>
 *
 * <p>线程：本类无状态（除配置快照外），可在任意线程调用；{@link Messages#send} 只做字符串/组件构造，
 * 不直接操作世界。</p>
 */
public final class TextService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.AMPERSAND_CHAR)
            .hexColors()
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final Pattern HEX_EXTENDED =
            Pattern.compile("[&§]x((?:[&§][0-9a-fA-F]){6})");
    private static final Pattern HEX_SHORT =
            Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE =
            Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"),
            Map.entry('2', "<dark_green>"), Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"), Map.entry('9', "<blue>"),
            Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"),
            Map.entry('e', "<yellow>"), Map.entry('f', "<white>"),
            Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"), Map.entry('r', "<reset>")
    );

    private volatile LiuLibConfig.Text settings;

    public TextService(LiuLibConfig.Text settings) {
        this.settings = settings == null ? LiuLibConfig.Text.defaults() : settings;
    }

    /** 热重载文本配置。 */
    public void reload(LiuLibConfig.Text fresh) {
        if (fresh != null) {
            this.settings = fresh;
        }
    }

    public LiuLibConfig.Text settings() {
        return settings;
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /** 解析为组件；{@code null}/空串返回空组件。 */
    public Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String source = settings.legacyInput() ? legacyToMini(text) : text;
        try {
            return MINI.deserialize(source);
        } catch (RuntimeException miniFailure) {
            // 兜底 1：整条按传统色码解析（& 或 §）
            try {
                return text.indexOf('&') >= 0 && text.indexOf('§') < 0
                        ? AMPERSAND.deserialize(text)
                        : LEGACY.deserialize(text);
            } catch (RuntimeException ignored) {
                // 兜底 2：纯文本，保证不把异常抛给业务
                return Component.text(text);
            }
        }
    }

    /** 先替换 {@code {key}} 占位符再解析。 */
    public Component component(String text, Object... placeholders) {
        return parse(fill(text, placeholders));
    }

    /** 批量解析配置里的多行文本（GUI lore、帮助列表）。 */
    public List<Component> parseLines(List<String> lines) {
        List<Component> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            out.add(parse(line));
        }
        return out;
    }

    /** 组件转纯文本（不含颜色/标签）。 */
    public String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    /** 文本转纯文本（先解析再取纯文本）。 */
    public String plain(String text) {
        return plain(parse(text));
    }

    /** 组件转 {@code §} 颜色文本（背包标题等只接受 String 的场景）。 */
    public String legacy(Component component) {
        return component == null ? "" : LEGACY.serialize(component);
    }

    /** 文本转 {@code §} 颜色文本。 */
    public String legacy(String text) {
        return legacy(parse(text));
    }

    /** 去掉颜色与 MiniMessage 标签，只留纯文本。 */
    public String strip(String text) {
        return plain(text);
    }

    // ==================================================================
    // 占位符
    // ==================================================================

    /** 替换 {@code {key}}：pairs 为 key/value 交替，value 为 null 时替换为空串。 */
    public String fill(String template, Object... pairs) {
        if (template == null || template.isEmpty() || pairs == null || pairs.length < 2) {
            return template == null ? "" : template;
        }
        String out = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object key = pairs[i];
            if (key == null) {
                continue;
            }
            Object value = pairs[i + 1];
            out = out.replace("{" + key + "}", value == null ? "" : String.valueOf(value));
        }
        return out;
    }

    /** 替换 {@code {key}}：values 的 key 不带花括号。 */
    public String fill(String template, Map<String, ?> values) {
        if (template == null || template.isEmpty() || values == null || values.isEmpty()) {
            return template == null ? "" : template;
        }
        String out = template;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out = out.replace("{" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }

    // ==================================================================
    // 消息文件
    // ==================================================================

    /** 读取插件目录下的 {@code messages.yml}。 */
    public Messages messages(Plugin plugin) {
        return messages(plugin, "messages.yml");
    }

    /** 读取插件目录下的指定消息文件。 */
    public Messages messages(Plugin plugin, String fileName) {
        return new Messages(this, plugin, fileName);
    }

    /** 带前缀发送一条消息；空消息不发送。 */
    public void send(CommandSender sender, String text) {
        if (sender == null || text == null || text.isEmpty()) {
            return;
        }
        sender.sendMessage(component(settings.prefix() + text));
    }

    /** 解析配置前缀。 */
    public Component prefix() {
        return parse(settings.prefix());
    }

    // ==================================================================
    // 传统色码 -> MiniMessage
    // ==================================================================

    private String legacyToMini(String text) {
        if (text.indexOf('&') < 0 && text.indexOf('§') < 0) {
            return text;
        }
        String out = HEX_EXTENDED.matcher(text).replaceAll(match -> hexTag(match.group(1)));
        out = HEX_SHORT.matcher(out).replaceAll("<#$1>");
        Matcher matcher = LEGACY_CODE.matcher(out);
        StringBuilder builder = new StringBuilder(out.length() + 16);
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            String tag = CODES.getOrDefault(code, null);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(tag == null ? matcher.group() : tag));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String hexTag(String sequence) {
        StringBuilder hex = new StringBuilder(6);
        for (int i = 0; i < sequence.length(); i++) {
            char c = sequence.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                hex.append(c);
            }
        }
        return hex.length() == 6 ? "<#" + hex.toString().toUpperCase(Locale.ROOT) + ">" : sequence;
    }
}
