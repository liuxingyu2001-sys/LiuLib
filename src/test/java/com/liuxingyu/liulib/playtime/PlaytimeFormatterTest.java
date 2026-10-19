package com.liuxingyu.liulib.playtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeFormatterTest {

    @Test
    void formatsHumanReadableDurations() {
        assertEquals("0秒", PlaytimeFormatter.format(0));
        assertEquals("59秒", PlaytimeFormatter.format(59));
        assertEquals("1分", PlaytimeFormatter.format(60));
        assertEquals("1分30秒", PlaytimeFormatter.format(90));
        assertEquals("1小时", PlaytimeFormatter.format(3_600));
        assertEquals("1小时1分", PlaytimeFormatter.format(3_661));
        assertEquals("1天", PlaytimeFormatter.format(86_400));
        assertEquals("1天1小时", PlaytimeFormatter.format(90_000));
        assertEquals("0秒", PlaytimeFormatter.format(-10));
    }

    @Test
    void formatsHoursAndDays() {
        assertEquals("1", PlaytimeFormatter.hours(3_600));
        assertEquals("1.5", PlaytimeFormatter.hours(5_400));
        assertEquals("1.5", PlaytimeFormatter.days(129_600));
        assertEquals("0", PlaytimeFormatter.hours(-1));
    }
}
