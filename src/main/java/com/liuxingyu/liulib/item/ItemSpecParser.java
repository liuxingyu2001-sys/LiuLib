package com.liuxingyu.liulib.item;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物品配置串解析器：把 {@code DIAMOND}、{@code DIAMOND:3}、{@code ce:ns:id}、
 * {@code ns:id*4}、{@code base64:...} 统一解析成 {@link ItemSpec} 并构建物品。
 *
 * <p>支持的写法：</p>
 * <pre>
 *   DIAMOND                      原版材质
 *   minecraft:diamond            原版材质（大小写通用）
 *   DIAMOND:3                    原版材质 + 数量（旧写法）
 *   DIAMOND*3                    原版材质 + 数量
 *   ce:namespace:id              CraftEngine 物品
 *   craftengine:namespace:id     CraftEngine 物品（别名）
 *   namespace:id                 CraftEngine 物品（无 ce: 前缀，材质解析失败时）
 *   ce:namespace:id*4            CraftEngine 物品 + 数量
 *   base64:... / nbt:...         完整物品（Paper 二进制 / 旧对象流 Base64）
 * </pre>
 *
 * <p><b>数量约定</b>：CraftEngine 的 ID 本身可能含数字段，因此 CE 物品只认
 * {@code *n} 后缀；{@code :n} 仅用于原版材质。</p>
 */
public final class ItemSpecParser {

    private static final Pattern STAR_AMOUNT = Pattern.compile("^(.*?)\\s*\\*\\s*(\\d+)$");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final Logger logger;
    private final ItemCodec codec;
    private final CraftEngineItems craftEngine;
    private final ItemNames names;
    private final String defaultNamespace;
    private final Map<String, ItemResolver> resolvers;

    public ItemSpecParser(Logger logger, ItemCodec codec, CraftEngineItems craftEngine,
                          ItemNames names, String defaultNamespace, Map<String, ItemResolver> resolvers) {
        this.logger = logger;
        this.codec = codec;
        this.craftEngine = craftEngine;
        this.names = names;
        this.defaultNamespace = defaultNamespace == null || defaultNamespace.isBlank()
                ? "craftengine" : defaultNamespace;
        this.resolvers = resolvers;
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /** 解析配置串；格式错误抛出带原文的 {@link IllegalArgumentException}。 */
    public ItemSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("物品配置为空");
        }
        String text = raw.trim();

        // 扩展前缀（如 liuitemlib 的 lib:gift:spring）：交给注册的解析器，支持 *数量 后缀
        int customColon = text.indexOf(':');
        if (customColon > 0 && resolvers != null) {
            String prefix = text.substring(0, customColon).toLowerCase(Locale.ROOT);
            if (resolvers.containsKey(prefix)) {
                String body = text.substring(customColon + 1).trim();
                int customAmount = 1;
                Matcher customStar = STAR_AMOUNT.matcher(body);
                if (customStar.matches()) {
                    body = customStar.group(1).trim();
                    customAmount = positive(customStar.group(2), raw);
                }
                return new ItemSpec(ItemSpec.Source.CUSTOM, prefix + ":" + body, customAmount, text);
            }
        }

        // 完整物品编码：原样保存，数量由物品自身决定
        for (String prefix : new String[]{"base64:", "nbt:", "bytes:", "item:"}) {
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return new ItemSpec(ItemSpec.Source.ENCODED, text.substring(prefix.length()).trim(), 1, text);
            }
        }

        int amount = 1;
        Matcher star = STAR_AMOUNT.matcher(text);
        if (star.matches()) {
            text = star.group(1).trim();
            amount = positive(star.group(2), raw);
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ce:")) {
            return craftEngine(text.substring(3), amount, raw);
        }
        if (lower.startsWith("craftengine:")) {
            return craftEngine(text.substring("craftengine:".length()), amount, raw);
        }
        if (lower.startsWith("minecraft:")) {
            return vanillaWithAmount(text.substring("minecraft:".length()), amount, raw);
        }
        if (lower.startsWith("vanilla:")) {
            return vanillaWithAmount(text.substring("vanilla:".length()), amount, raw);
        }

        int colon = text.indexOf(':');
        if (colon < 0) {
            Material material = ItemNames.material(text);
            if (material != null) {
                return new ItemSpec(ItemSpec.Source.VANILLA, material.name(), amount, raw);
            }
            // 不是原版材质：按 CE 的默认命名空间补全
            return craftEngine(text, amount, raw);
        }

        // 一段冒号：diamond:3（原版 + 数量）或 namespace:id（CE）
        String head = text.substring(0, colon);
        String tail = text.substring(colon + 1);
        if (NUMBER.matcher(tail.trim()).matches()) {
            Material material = ItemNames.material(head);
            if (material != null) {
                return new ItemSpec(ItemSpec.Source.VANILLA, material.name(),
                        positive(tail.trim(), raw), raw);
            }
        }
        return craftEngine(text, amount, raw);
    }

    /** 宽松解析：失败返回 null 并记录 FINE 日志。 */
    public ItemSpec parseOrNull(String raw) {
        try {
            return parse(raw);
        } catch (RuntimeException error) {
            logger.fine("物品配置解析失败 raw=" + raw + ": " + error.getMessage());
            return null;
        }
    }

    /** 支持 {@code minecraft:diamond:3} 这种带数量后缀的原版写法。 */
    private ItemSpec vanillaWithAmount(String id, int amount, String raw) {
        int colon = id.lastIndexOf(':');
        if (colon > 0) {
            String tail = id.substring(colon + 1).trim();
            if (NUMBER.matcher(tail).matches()) {
                Material material = ItemNames.material(id.substring(0, colon));
                if (material != null) {
                    return new ItemSpec(ItemSpec.Source.VANILLA, material.name(),
                            positive(tail, raw), raw);
                }
            }
        }
        return vanilla(id, amount, raw);
    }

    private ItemSpec vanilla(String id, int amount, String raw) {
        Material material = ItemNames.material(id);
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("未知原版材质: " + id + "（原文 " + raw + "）");
        }
        return new ItemSpec(ItemSpec.Source.VANILLA, material.name(), amount, raw);
    }

    private ItemSpec craftEngine(String id, int amount, String raw) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("CraftEngine 物品 ID 为空（原文 " + raw + "）");
        }
        String normalized = trimmed.indexOf(':') < 0 ? defaultNamespace + ":" + trimmed : trimmed;
        return new ItemSpec(ItemSpec.Source.CRAFT_ENGINE, normalized, amount, raw);
    }

    private static int positive(String value, String raw) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException("物品数量必须 > 0: " + raw);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("物品数量不是整数: " + raw, error);
        }
    }

    // ==================================================================
    // 构建
    // ==================================================================

    /** 按配置串构建物品；失败抛 {@link IllegalArgumentException}。 */
    public ItemStack build(String raw) {
        ItemSpec spec = parse(raw);
        ItemStack stack = build(spec);
        if (stack == null) {
            throw new IllegalArgumentException("物品构建失败: " + raw);
        }
        return stack;
    }

    /** 按配置串构建，失败返回 null（批量发放/可选配置用）。 */
    public ItemStack buildOrNull(String raw) {
        ItemSpec spec = parseOrNull(raw);
        return spec == null ? null : build(spec);
    }

    /** 按规格构建单个物品堆（数量不超过最大堆叠；超出请用 {@link #buildStacks(ItemSpec)}）。 */
    public ItemStack build(ItemSpec spec) {
        if (spec == null) {
            return null;
        }
        return switch (spec.source()) {
            case ENCODED -> {
                ItemStack decoded = ItemCodec.clampAmount(codec.decode(spec.id()));
                if (decoded != null && spec.amount() > 1) {
                    decoded.setAmount(Math.min(spec.amount(), Math.max(1, decoded.getMaxStackSize())));
                }
                yield decoded;
            }
            case VANILLA -> {
                Material material = ItemNames.material(spec.id());
                if (material == null || material.isAir()) {
                    yield null;
                }
                ItemStack stack = new ItemStack(material);
                stack.setAmount(Math.min(spec.amount(), Math.max(1, stack.getMaxStackSize())));
                yield stack;
            }
            case CRAFT_ENGINE -> craftEngine == null ? null
                    : craftEngine.build(spec.id(), spec.amount());
            case CUSTOM -> {
                int colon = spec.id().indexOf(':');
                String prefix = colon < 0 ? "" : spec.id().substring(0, colon).toLowerCase(Locale.ROOT);
                String body = colon < 0 ? spec.id() : spec.id().substring(colon + 1);
                ItemResolver resolver = resolvers == null ? null : resolvers.get(prefix);
                ItemStack resolved = resolver == null ? null : resolver.resolve(body);
                if (resolved == null) {
                    yield null;
                }
                ItemStack copy = resolved.clone();
                if (spec.amount() > 1) {
                    copy.setAmount(Math.min(spec.amount(), Math.max(1, copy.getMaxStackSize())));
                }
                yield copy;
            }
        };
    }

    /** 按规格拆成多堆（数量 > 最大堆叠时）。 */
    public List<ItemStack> buildStacks(ItemSpec spec) {
        ItemStack stack = build(spec);
        if (stack == null) {
            return List.of();
        }
        if (spec.source() == ItemSpec.Source.ENCODED) {
            // 编码物品的数量由物品自身携带，不按配置数量重复；超过最大堆叠时拆堆
            return ItemCodec.split(stack);
        }
        List<ItemStack> out = new ArrayList<>();
        int remaining = spec.amount();
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

    /** 给予物品；放不下的拆堆后掉落在玩家位置。必须主线程调用。 */
    public void give(Player player, ItemSpec spec) {
        if (player == null || spec == null) {
            return;
        }
        for (ItemStack stack : buildStacks(spec)) {
            var leftovers = player.getInventory().addItem(stack);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    /** 给予物品（配置串）。 */
    public void give(Player player, String raw) {
        ItemSpec spec = parseOrNull(raw);
        if (spec != null) {
            give(player, spec);
        }
    }

    // ==================================================================
    // 反查
    // ==================================================================

    /**
     * 物品 → 规格：优先 CE ID，其次原版材质；完整物品（含 NBT/组件）使用 Base64 保真。
     *
     * @param preserveData true 时对带自定义组件的物品编码为 Base64，避免丢失 NBT
     */
    public ItemSpec fromItem(ItemStack stack, boolean preserveData) {
        if (ItemCodec.isEmpty(stack)) {
            return null;
        }
        String ceId = craftEngine == null ? null : craftEngine.id(stack);
        if (ceId != null) {
            return new ItemSpec(ItemSpec.Source.CRAFT_ENGINE, ceId, stack.getAmount(),
                    stack.getType().name());
        }
        if (preserveData && stack.hasItemMeta()) {
            String encoded = codec.encode(stack);
            if (encoded != null) {
                return new ItemSpec(ItemSpec.Source.ENCODED, encoded, stack.getAmount(), encoded);
            }
        }
        return new ItemSpec(ItemSpec.Source.VANILLA, stack.getType().name(), stack.getAmount(),
                stack.getType().name());
    }

    /** 物品 → 可读描述（日志/调试用）。 */
    public String describe(ItemSpec spec) {
        if (spec == null) {
            return "null";
        }
        if (spec.source() == ItemSpec.Source.ENCODED) {
            return "encoded(" + spec.id().length() + " chars) x" + spec.amount();
        }
        ItemStack stack = build(spec);
        String name = stack == null ? "?" : names.label(stack);
        return spec.source() + " " + spec.id() + " x" + spec.amount() + " [" + name + "]";
    }
}
