package com.liuxingyu.liulib.playtime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 秒数格式化：{@code 3661 -> 1小时1分}。 */
public final class PlaytimeFormatter {

    private PlaytimeFormatter() {
    }

    /** 人类可读格式；不足 1 小时时保留秒，超过 1 小时只显示到分。 */
    public static String format(long seconds) {
        long total = Math.max(0, seconds);
        if (total < 60) {
            return total + "秒";
        }
        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        long remainSeconds = total % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("天");
        }
        if (hours > 0) {
            out.append(hours).append("小时");
        }
        if (minutes > 0 || out.isEmpty()) {
            out.append(minutes).append('分');
        }
        if (days == 0 && hours == 0 && remainSeconds > 0) {
            out.append(remainSeconds).append('秒');
        }
        return out.toString();
    }

    /** 小时数（保留一位小数），用于“总时长 12.5 小时”这类文案。 */
    public static String hours(long seconds) {
        BigDecimal value = BigDecimal.valueOf(Math.max(0, seconds))
                .divide(BigDecimal.valueOf(3_600), 1, RoundingMode.HALF_UP);
        return value.stripTrailingZeros().toPlainString();
    }

    /** 天数（保留一位小数）。 */
    public static String days(long seconds) {
        BigDecimal value = BigDecimal.valueOf(Math.max(0, seconds))
                .divide(BigDecimal.valueOf(86_400), 1, RoundingMode.HALF_UP);
        return value.stripTrailingZeros().toPlainString();
    }
}
