package com.liuxingyu.liulib.playtime;

import java.util.UUID;

/**
 * 一次查询得到的累计时长。
 *
 * @param uuid        玩家 UUID
 * @param name        最近使用的名字（可能为空）
 * @param totalSeconds 总在线秒数
 * @param todaySeconds 指定 day 当天的在线秒数
 * @param day          统计日期（{@code yyyy-MM-dd}，按配置时区）
 */
public record PlaytimeRecord(UUID uuid, String name, long totalSeconds, long todaySeconds, String day) {

    public PlaytimeRecord {
        name = name == null ? "" : name;
        totalSeconds = Math.max(0, totalSeconds);
        todaySeconds = Math.max(0, todaySeconds);
        day = day == null ? "" : day;
    }

    public static PlaytimeRecord empty(UUID uuid, String day) {
        return new PlaytimeRecord(uuid, "", 0, 0, day);
    }
}
