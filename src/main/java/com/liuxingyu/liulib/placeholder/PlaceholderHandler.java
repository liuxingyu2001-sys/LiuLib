package com.liuxingyu.liulib.placeholder;

import org.bukkit.OfflinePlayer;

/**
 * 单个 PlaceholderAPI 变量的解析器。
 *
 * <p>{@code params} 是 {@code %identifier_params%} 中下划线后的部分，可能为空。
 * 返回 {@code null} 表示该变量不识别，PAPI 会继续尝试其它扩展；返回空串表示
 * 识别但当前没有值。实现应尽量快，不要在这里做阻塞 IO。</p>
 */
@FunctionalInterface
public interface PlaceholderHandler {

    String resolve(OfflinePlayer player, String params);
}
