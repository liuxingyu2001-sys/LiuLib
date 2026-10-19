package com.liuxingyu.liulib.item;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 物品序列化 / 反序列化（全系列插件共用）。
 *
 * <p>编码优先级：</p>
 * <ol>
 *   <li>Paper {@code ItemStack.serializeAsBytes()} —— 版本稳定的二进制 NBT，完整保留
 *       1.20.5+ 数据组件、CraftEngine 物品、头颅皮肤等；</li>
 *   <li>旧版 {@link BukkitObjectOutputStream} Java 序列化流（以 {@code 0xAC 0xED} 开头）。</li>
 * </ol>
 *
 * <p>解码自动识别两种格式，因此旧数据不需要迁移即可继续读取。所有方法都不会把
 * 损坏数据抛给调用方：失败返回 {@code null}，由调用方按空槽处理。</p>
 *
 * <p>编码结果默认带长度上限（防止恶意超大 NBT 写库）；可用
 * {@link #ItemCodec(int, Logger)} 按插件配置调整。</p>
 */
public final class ItemCodec {

    /** 默认单件物品编码上限（字符数）。 */
    public static final int DEFAULT_MAX_ENCODED_LENGTH = 60_000;

    private static final Method SERIALIZE;
    private static final Method DESERIALIZE;

    static {
        Method serialize = null;
        Method deserialize = null;
        try {
            serialize = ItemStack.class.getMethod("serializeAsBytes");
            deserialize = ItemStack.class.getMethod("deserializeBytes", byte[].class);
        } catch (NoSuchMethodException ignored) {
            // 旧 Spigot：只用 Bukkit 对象流回退
        }
        SERIALIZE = serialize;
        DESERIALIZE = deserialize;
    }

    private final int maxEncodedLength;
    private final Logger logger;

    public ItemCodec() {
        this(DEFAULT_MAX_ENCODED_LENGTH, Logger.getLogger("LiuLib"));
    }

    public ItemCodec(int maxEncodedLength, Logger logger) {
        this.maxEncodedLength = Math.max(1024, maxEncodedLength);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ------------------------------------------------------------------
    // 基础判断
    // ------------------------------------------------------------------

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** 反序列化结果的数量修正：1..maxStackSize。 */
    public static ItemStack clampAmount(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        int max = Math.max(1, stack.getMaxStackSize());
        if (stack.getAmount() <= 0) {
            stack.setAmount(1);
        } else if (stack.getAmount() > max) {
            // 单堆不能超过最大堆叠；超出部分由 split()/give() 负责
            stack.setAmount(max);
        }
        return stack;
    }

    /** 把数量超过最大堆叠的物品拆成多堆（每堆 <= maxStackSize）。 */
    public static List<ItemStack> split(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        if (isEmpty(stack)) {
            return out;
        }
        int remaining = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        while (remaining > 0) {
            ItemStack piece = stack.clone();
            int amount = Math.min(remaining, max);
            piece.setAmount(amount);
            out.add(piece);
            remaining -= amount;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 字节 / Base64
    // ------------------------------------------------------------------

    /** 序列化为二进制；空气返回 null。 */
    public byte[] encodeBytes(ItemStack stack) {
        if (isEmpty(stack)) {
            return null;
        }
        ItemStack copy = stack.clone();
        if (SERIALIZE != null) {
            try {
                byte[] bytes = (byte[]) SERIALIZE.invoke(copy);
                if (bytes != null && bytes.length > 0) {
                    return bytes;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 极少数自定义物品会让 serializeAsBytes 失败，走对象流回退
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(out)) {
                stream.writeObject(copy);
            }
            return out.toByteArray();
        } catch (Throwable error) {
            logger.log(Level.WARNING, "物品序列化失败，已跳过该物品: " + copy.getType(), error);
            return null;
        }
    }

    /** 从二进制还原；损坏/空数据返回 null。自动兼容旧对象流。 */
    public ItemStack decodeBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (isJavaSerialized(data)) {
            return decodeLegacy(data);
        }
        if (DESERIALIZE != null) {
            try {
                Object value = DESERIALIZE.invoke(null, (Object) data);
                return value instanceof ItemStack stack ? stack : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 继续尝试对象流：历史数据可能没有 0xAC 0xED 前缀（少见）
            }
        }
        return decodeLegacy(data);
    }

    private ItemStack decodeLegacy(byte[] data) {
        try (BukkitObjectInputStream stream =
                     new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            Object value = stream.readObject();
            return value instanceof ItemStack stack ? stack : null;
        } catch (Throwable error) {
            logger.log(Level.WARNING, "物品反序列化失败，该槽位按空处理。", error);
            return null;
        }
    }

    /** 兼容旧数据：Java 序列化流固定以 0xAC 0xED 开头。 */
    private static boolean isJavaSerialized(byte[] data) {
        return data.length >= 2 && data[0] == (byte) 0xAC && data[1] == (byte) 0xED;
    }

    /**
     * 序列化为 Base64 文本；空物品返回 {@code null}。
     *
     * <p>使用无填充标准编码（更短）；解码同时接受带填充、MIME 换行和 URL-safe 字符。</p>
     */
    public String encode(ItemStack stack) {
        byte[] bytes = encodeBytes(stack);
        if (bytes == null) {
            return null;
        }
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(bytes);
        if (encoded.length() > maxEncodedLength) {
            logger.warning("物品编码过大（" + encoded.length() + " 字符 > 上限 "
                    + maxEncodedLength + "），已拒绝写入。");
            return null;
        }
        return encoded;
    }

    /**
     * 反序列化 Base64 文本。
     *
     * <p>支持前缀 {@code nbt:} / {@code base64:} / {@code bytes:}，也接受裸 Base64；
     * 兼容旧格式的 MIME 换行 Base64。</p>
     */
    public ItemStack decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String value = encoded.trim();
        for (String prefix : new String[]{"nbt:", "base64:", "bytes:", "item:"}) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                value = value.substring(prefix.length()).trim();
                break;
            }
        }
        if (value.length() > maxEncodedLength) {
            logger.warning("物品数据过大（" + value.length() + " 字符），已拒绝反序列化。");
            return null;
        }
        byte[] data;
        try {
            data = decodeBase64(value);
        } catch (IllegalArgumentException error) {
            logger.log(Level.FINE, "物品 Base64 解码失败，该槽位按空处理。", error);
            return null;
        }
        return decodeBytes(data);
    }

    /** 反序列化并修正数量。 */
    public ItemStack decodeClamped(String encoded) {
        return clampAmount(decode(encoded));
    }

    /** 宽松 Base64：标准 → MIME → URL-safe。 */
    public static byte[] decodeBase64(String value) {
        String compact = value.replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException ignored) {
            // 继续尝试其它变体
        }
        try {
            return Base64.getMimeDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            // 继续尝试其它变体
        }
        return Base64.getUrlDecoder().decode(compact);
    }

    // ------------------------------------------------------------------
    // 多物品 / 容器
    // ------------------------------------------------------------------

    /**
     * 多物品编码：每件一行 Base64，避免 JSON 依赖。
     * 空物品写成空行，保证下标与输入数组一致。
     */
    public String encodeMany(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String encoded = encode(items[i]);
            if (encoded != null) {
                out.append(encoded);
            }
        }
        return out.toString();
    }

    public String encodeMany(Collection<ItemStack> items) {
        return encodeMany(items == null ? new ItemStack[0] : items.toArray(new ItemStack[0]));
    }

    /** 解码 {@link #encodeMany(ItemStack[])} 的结果；空行对应 null。 */
    public List<ItemStack> decodeMany(String encoded) {
        List<ItemStack> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        // 兼容旧数据使用 \u0000 分隔
        String[] lines = encoded.indexOf('\n') >= 0 ? encoded.split("\\R", -1) : encoded.split("\u0000", -1);
        for (String line : lines) {
            out.add(line.isBlank() ? null : decode(line));
        }
        return out;
    }

    /** 反序列化为与输入等长的数组；损坏项为 null。 */
    public ItemStack[] decodeArray(String encoded) {
        List<ItemStack> list = decodeMany(encoded);
        return list.toArray(new ItemStack[0]);
    }

    // ------------------------------------------------------------------
    // YAML / Map
    // ------------------------------------------------------------------

    /** 物品 → 可写入 YAML 的 Map。 */
    public Map<String, Object> toMap(ItemStack stack) {
        return isEmpty(stack) ? Map.of() : stack.serialize();
    }

    /** Map → 物品；格式错误返回 null。 */
    public ItemStack fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return ItemStack.deserialize(map);
        } catch (Throwable error) {
            logger.log(Level.WARNING, "物品 Map 反序列化失败。", error);
            return null;
        }
    }

    /**
     * 从配置段读取物品：支持 {@code item:} 子段、Bukkit 的 {@code ==: org.bukkit.inventory.ItemStack}
     * 结构，以及普通 Map 形式。
     */
    public ItemStack fromSection(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        try {
            if (section.isItemStack("item")) {
                return section.getItemStack("item");
            }
        } catch (Throwable ignored) {
            // 某些实现没有 isItemStack，继续按 Map 解析
        }
        return fromMap(section.getValues(false));
    }
}
