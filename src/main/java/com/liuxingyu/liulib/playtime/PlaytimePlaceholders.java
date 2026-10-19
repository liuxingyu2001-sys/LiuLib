package com.liuxingyu.liulib.playtime;

import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * 在线时长 PlaceholderAPI 变量解析器（由 LiuLib 的 {@code Placeholders} 统一注册）。
 *
 * <p>标识符 {@code liulib}，可用变量：</p>
 * <table border="1">
 *   <caption>变量列表</caption>
 *   <tr><th>变量</th><th>含义</th></tr>
 *   <tr><td>{@code %liulib_playtime_total%}</td><td>总秒数</td></tr>
 *   <tr><td>{@code %liulib_playtime_total_formatted%}</td><td>总时长（1天2小时）</td></tr>
 *   <tr><td>{@code %liulib_playtime_total_hours%}</td><td>总小时数（1.5）</td></tr>
 *   <tr><td>{@code %liulib_playtime_total_days%}</td><td>总天数（1.2）</td></tr>
 *   <tr><td>{@code %liulib_playtime_today%}</td><td>今日秒数</td></tr>
 *   <tr><td>{@code %liulib_playtime_today_formatted%}</td><td>今日时长</td></tr>
 *   <tr><td>{@code %liulib_playtime_session%}</td><td>本次会话秒数</td></tr>
 *   <tr><td>{@code %liulib_playtime_session_formatted%}</td><td>本次会话时长</td></tr>
 *   <tr><td>{@code %liulib_playtime_afk%}</td><td>是否 AFK（true/false）</td></tr>
 * </table>
 *
 * <p>离线玩家的总时长取“最近一次查询/加载”的缓存；需要精确离线数据时调用
 * {@link PlaytimeService#stored(java.util.UUID)}。</p>
 */
public final class PlaytimePlaceholders {

    /** PAPI 标识符；保留给 LiuLib，业务插件请使用自己的 identifier。 */
    public static final String IDENTIFIER = "liulib";

    private final PlaytimeService service;

    public PlaytimePlaceholders(PlaytimeService service) {
        this.service = service;
    }

    /** 兼容旧构造；version 参数已不再使用。 */
    public PlaytimePlaceholders(PlaytimeService service, String ignoredVersion) {
        this(service);
    }

    /** 解析一个变量；不识别返回 null。 */
    public String resolve(OfflinePlayer player, String params) {
        if (player == null || params == null || params.isBlank()) {
            return null;
        }
        String key = params.toLowerCase(Locale.ROOT);
        if (key.startsWith("playtime_")) {
            key = key.substring("playtime_".length());
        }
        long total = service.totalSeconds(player.getUniqueId());
        long today = service.todaySeconds(player.getUniqueId());
        long session = service.sessionSeconds(player.getUniqueId());
        return switch (key) {
            case "total", "total_seconds" -> Long.toString(total);
            case "total_formatted" -> service.format(total);
            case "total_hours" -> PlaytimeFormatter.hours(total);
            case "total_days" -> PlaytimeFormatter.days(total);
            case "today", "today_seconds" -> Long.toString(today);
            case "today_formatted" -> service.format(today);
            case "today_hours" -> PlaytimeFormatter.hours(today);
            case "session", "session_seconds" -> Long.toString(session);
            case "session_formatted" -> service.format(session);
            case "afk" -> Boolean.toString(service.isAfk(player.getUniqueId()));
            default -> null;
        };
    }
}
