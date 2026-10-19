package com.liuxingyu.liulib.playtime;

import java.time.LocalDate;
import java.time.ZoneId;

/** 在线时长使用的日期换算，统一按配置时区，避免各插件用系统默认时区导致跨天不一致。 */
public final class PlaytimeClock {

    private PlaytimeClock() {
    }

    /** 今日 ISO 日期串（{@code yyyy-MM-dd}）。 */
    public static String today(ZoneId zone) {
        return LocalDate.now(zone).toString();
    }

    /** 指定时间戳所在日期的 ISO 日期串。 */
    public static String dayOf(ZoneId zone, long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString();
    }

    /** 本周周一 ISO 日期串。 */
    public static String weekStart(ZoneId zone) {
        return PlaytimePeriod.weekStart(zone);
    }

    /** 今天 00:00 的时间戳（毫秒）。 */
    public static long dayStart(ZoneId zone) {
        return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
