package com.liuxingyu.liulib.playtime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** 排行榜统计周期。 */
public enum PlaytimePeriod {

    /** 当天（配置时区的自然日）。 */
    TODAY,
    /** 本周（周一 00:00 起）。 */
    WEEK,
    /** 历史总计。 */
    TOTAL;

    public static PlaytimePeriod parse(String raw, PlaytimePeriod fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TODAY", "DAY", "今日", "今天" -> TODAY;
            case "WEEK", "WEEKLY", "本周", "周" -> WEEK;
            case "TOTAL", "ALL", "总计", "全部" -> TOTAL;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    yield fallback;
                }
            }
        };
    }

    /** 周起始日（周一）的 ISO 日期串。 */
    public static String weekStart(ZoneId zone) {
        return LocalDate.now(zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toString();
    }

    public String display() {
        return switch (this) {
            case TODAY -> "今日";
            case WEEK -> "本周";
            case TOTAL -> "总计";
        };
    }
}
