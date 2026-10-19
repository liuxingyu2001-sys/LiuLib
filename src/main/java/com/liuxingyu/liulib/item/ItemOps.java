package com.liuxingyu.liulib.item;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.Objects;

/**
 * 物品库存操作：统计、检查、扣除、给予、判断能否放下。
 *
 * <p>所有“按配置串”的方法都会先通过 {@link ItemSpecParser} 构建模板物品，
 * 再按 {@link ItemStack#isSimilar(ItemStack)} 匹配（忽略数量，比较材质与全部
 * 组件/自定义名称）。因此 {@code ce:ns:id}、附魔、lore 等都会参与匹配，
 * 不会出现“同名但 NBT 不同”被误扣。</p>
 *
 * <p>线程：这些方法直接读写玩家背包，必须主线程（Folia 为玩家所在区域线程）调用。
 * 只统计/扣除主背包 36 格，不含盔甲与副手，避免把装备当成货币扣掉。</p>
 */
public final class ItemOps {

    private final ItemService items;

    public ItemOps(ItemService items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    // ==================================================================
    // 统计 / 检查
    // ==================================================================

    /** 统计玩家主背包中与模板相同的物品数量。 */
    public int count(Player player, ItemStack template) {
        if (player == null || ItemCodec.isEmpty(template)) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (matches(stack, template)) {
                total = saturatedAdd(total, stack.getAmount());
            }
        }
        return total;
    }

    /** 统计玩家主背包中指定配置串对应的物品数量；解析失败返回 0。 */
    public int count(Player player, String spec) {
        return count(player, template(spec));
    }

    public boolean has(Player player, ItemStack template, int amount) {
        if (amount <= 0) {
            return true;
        }
        return count(player, template) >= amount;
    }

    public boolean has(Player player, String spec, int amount) {
        return has(player, template(spec), amount);
    }

    // ==================================================================
    // 扣除
    // ==================================================================

    /**
     * 扣除指定数量；数量不足时返回 false 且不修改背包（先统计再扣）。
     *
     * @return 是否扣除成功
     */
    public boolean take(Player player, ItemStack template, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (player == null || ItemCodec.isEmpty(template)) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        if (count(contents, template) < amount) {
            return false;
        }
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!matches(stack, template)) {
                continue;
            }
            int taken = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - taken);
            inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);
            remaining -= taken;
        }
        return remaining <= 0;
    }

    public boolean take(Player player, String spec, int amount) {
        return take(player, template(spec), amount);
    }

    /** 尽可能扣除，返回实际扣除数量。 */
    public int removeAll(Player player, ItemStack template) {
        int available = count(player, template);
        if (available <= 0) {
            return 0;
        }
        return take(player, template, available) ? available : 0;
    }

    public int removeAll(Player player, String spec) {
        return removeAll(player, template(spec));
    }

    // ==================================================================
    // 给予
    // ==================================================================

    /** 按配置串给予；解析/构建失败时静默返回 false。 */
    public boolean give(Player player, String spec) {
        if (player == null) {
            return false;
        }
        ItemSpec parsed = items.parser().parseOrNull(spec);
        if (parsed == null) {
            return false;
        }
        try {
            items.give(player, parsed);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** 给予一个物品堆；背包满时掉落，返回是否有物品被掉落。 */
    public boolean give(Player player, ItemStack stack) {
        if (player == null || ItemCodec.isEmpty(stack)) {
            return false;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack.clone());
        if (leftovers.isEmpty()) {
            return false;
        }
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        return true;
    }

    // ==================================================================
    // 容量
    // ==================================================================

    /** 主背包是否能完全放下该物品（空格或可堆叠余量）。 */
    public boolean fits(Player player, ItemStack stack) {
        if (player == null || ItemCodec.isEmpty(stack)) {
            return false;
        }
        int remaining = stack.getAmount();
        int maxStack = stack.getMaxStackSize();
        for (ItemStack content : player.getInventory().getStorageContents()) {
            if (ItemCodec.isEmpty(content)) {
                remaining -= Math.min(maxStack, remaining);
            } else if (matches(content, stack)) {
                remaining -= Math.min(Math.max(0, maxStack - content.getAmount()), remaining);
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    public boolean fits(Player player, String spec) {
        return fits(player, template(spec));
    }

    // ==================================================================
    // 匹配
    // ==================================================================

    /** 两个物品是否可视为同一种物品（忽略数量）；任一为空返回 false。 */
    public static boolean matches(ItemStack first, ItemStack second) {
        return !ItemCodec.isEmpty(first)
                && !ItemCodec.isEmpty(second)
                && first.isSimilar(second);
    }

    /** 从配置串构建数量为 1 的模板；失败返回 null。 */
    public ItemStack template(String spec) {
        ItemSpec parsed = items.parser().parseOrNull(spec);
        if (parsed == null) {
            return null;
        }
        try {
            ItemStack stack = items.build(parsed);
            if (stack != null) {
                stack.setAmount(1);
            }
            return stack;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static int count(ItemStack[] contents, ItemStack template) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (matches(stack, template)) {
                total = saturatedAdd(total, stack.getAmount());
            }
        }
        return total;
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
