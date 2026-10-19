package com.liuxingyu.liulib.item;

import com.liuxingyu.liulib.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 物品构建器：链式设置名称、lore、数量、附魔、发光、不可破坏等常用属性。
 *
 * <p>只封装 Bukkit API，不负责 CraftEngine/Base64 解析；需要从配置串构建时先用
 * {@link ItemService#build(String)}，再交给 {@link #of(ItemStack)} 继续加工。</p>
 *
 * <p>名称/lore 以 Adventure {@link Component} 为主；字符串重载在没有传入
 * {@link TextService} 时按 MiniMessage 解析。需要兼容 {@code &} 色码时使用
 * {@code ItemBuilder.of(material, LiuLib.text())}。</p>
 */
public final class ItemBuilder {

    private final ItemStack stack;
    private final TextService text;

    private ItemBuilder(ItemStack stack, TextService text) {
        this.stack = stack;
        this.text = text;
    }

    // ==================================================================
    // 创建
    // ==================================================================

    public static ItemBuilder of(Material material) {
        return of(material, 1, null);
    }

    public static ItemBuilder of(Material material, int amount) {
        return of(material, amount, null);
    }

    public static ItemBuilder of(Material material, TextService text) {
        return of(material, 1, text);
    }

    public static ItemBuilder of(Material material, int amount, TextService text) {
        Material type = Objects.requireNonNull(material, "material");
        int clamped = Math.max(1, Math.min(type.getMaxStackSize(), amount));
        return new ItemBuilder(new ItemStack(type, clamped), text);
    }

    /** 复制一个物品继续加工；原物品不会被修改。 */
    public static ItemBuilder of(ItemStack template) {
        return of(template, null);
    }

    public static ItemBuilder of(ItemStack template, TextService text) {
        Objects.requireNonNull(template, "template");
        return new ItemBuilder(template.clone(), text);
    }

    // ==================================================================
    // 基础属性
    // ==================================================================

    public ItemBuilder amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), amount)));
        return this;
    }

    public ItemBuilder name(Component name) {
        return edit(meta -> meta.displayName(name));
    }

    /** 字符串名称：有 TextService 时支持 MiniMessage + 传统色码，否则只按 MiniMessage 解析。 */
    public ItemBuilder name(String name) {
        if (name == null) {
            return name((Component) null);
        }
        return name(text == null
                ? MiniMessage.miniMessage().deserialize(name)
                : text.component(name));
    }

    public ItemBuilder lore(List<Component> lines) {
        List<Component> copy = lines == null ? List.of() : List.copyOf(lines);
        return edit(meta -> meta.lore(copy.isEmpty() ? null : copy));
    }

    public ItemBuilder lore(Component... lines) {
        return lore(lines == null ? List.of() : Arrays.asList(lines));
    }

    /** 字符串 lore：按名称同样的规则解析每一行。 */
    public ItemBuilder loreStrings(List<String> lines) {
        if (lines == null) {
            return lore(List.of());
        }
        List<Component> parsed = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            parsed.add(text == null
                    ? MiniMessage.miniMessage().deserialize(line)
                    : text.component(line));
        }
        return lore(parsed);
    }

    public ItemBuilder loreStrings(String... lines) {
        return loreStrings(lines == null ? List.of() : Arrays.asList(lines));
    }

    /** 追加一行 lore 到现有内容末尾。 */
    public ItemBuilder addLore(Component line) {
        return edit(meta -> {
            List<Component> existing = new ArrayList<>(meta.lore() == null
                    ? List.of() : meta.lore());
            existing.add(line);
            meta.lore(existing);
        });
    }

    /** 清空 lore。 */
    public ItemBuilder clearLore() {
        return edit(meta -> meta.lore(null));
    }

    /**
     * 发光。使用 Paper 的 glint override，不添加假附魔，不会影响物品实际属性。
     *
     * @param glow true 显示附魔光效；false 恢复默认判断
     */
    public ItemBuilder glow(boolean glow) {
        return edit(meta -> meta.setEnchantmentGlintOverride(glow ? Boolean.TRUE : null));
    }

    public ItemBuilder unbreakable(boolean unbreakable) {
        return edit(meta -> meta.setUnbreakable(unbreakable));
    }

    public ItemBuilder customModelData(Integer data) {
        return edit(meta -> meta.setCustomModelData(data));
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        if (enchantment == null || level <= 0) {
            return this;
        }
        return edit(meta -> meta.addEnchant(enchantment, level, true));
    }

    public ItemBuilder flags(ItemFlag... flags) {
        if (flags == null || flags.length == 0) {
            return this;
        }
        return edit(meta -> meta.addItemFlags(flags));
    }

    /** 隐藏所有默认提示（属性、附魔、药水等）。 */
    public ItemBuilder hideAll() {
        return flags(ItemFlag.values());
    }

    /** 直接编辑 ItemMeta，用于本类尚未覆盖的属性。 */
    public ItemBuilder meta(Consumer<ItemMeta> editor) {
        if (editor != null) {
            edit(editor);
        }
        return this;
    }

    /** 当前构建结果（副本）。 */
    public ItemStack build() {
        return stack.clone();
    }

    /** 当前构建结果（不复制，调用方获得内部实例；仅用于立即读取）。 */
    public ItemStack unsafe() {
        return stack;
    }

    public Material type() {
        return stack.getType();
    }

    public int amount() {
        return stack.getAmount();
    }

    private ItemBuilder edit(Consumer<ItemMeta> editor) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            editor.accept(meta);
            stack.setItemMeta(meta);
        }
        return this;
    }
}
