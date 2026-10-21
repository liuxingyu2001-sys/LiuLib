package com.liuxingyu.liulib.item;

import org.bukkit.inventory.ItemStack;

/**
 * 自定义物品前缀解析器（扩展点）。
 *
 * <p>扩展插件在 onEnable 时注册自己的前缀，例如 liuitemlib 注册 {@code lib}：</p>
 * <pre>
 *   LiuLib.items().registerResolver("lib", body -&gt; LiuItemLibraryApi.build(body));
 * </pre>
 * <p>注册后，所有走 LiuLib {@code ItemService#build/buildOrNull} 的插件都能直接写
 * {@code lib:gift:spring_2026}、{@code lib:gift:spring_2026*3}。</p>
 */
@FunctionalInterface
public interface ItemResolver {

    /**
     * 解析前缀后的原文。
     *
     * @param body 例如 {@code lib} 前缀的 {@code gift:spring_2026}
     * @return 构建好的物品；返回 {@code null} 表示无法解析（调用方按构建失败处理）
     */
    ItemStack resolve(String body);
}
