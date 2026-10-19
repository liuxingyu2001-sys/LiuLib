package com.liuxingyu.liulib.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * 菜单点击事件（与 Sparrow UI 解耦的稳定类型）。
 *
 * @param player      点击的玩家
 * @param slot        GUI 槽位（0..rows*9-1）
 * @param clickType   Bukkit 点击类型
 * @param cursor      点击时光标上的物品（可能为 null）
 * @param hotbarButton 数字键交换时的热键槽位，-1 表示不是数字键
 */
public record MenuClick(
        Player player,
        int slot,
        ClickType clickType,
        ItemStack cursor,
        int hotbarButton
) {

    public boolean leftClick() {
        return clickType != null && clickType.isLeftClick();
    }

    public boolean rightClick() {
        return clickType != null && clickType.isRightClick();
    }

    public boolean shiftClick() {
        return clickType != null && clickType.isShiftClick();
    }

    public boolean numberKey() {
        return clickType != null && clickType == ClickType.NUMBER_KEY;
    }
}
