package com.liuxingyu.liulib.playtime;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaytimeSettingsTest {

    @Test
    void defaultsAreSane() {
        PlaytimeSettings settings = PlaytimeSettings.defaults();
        assertTrue(settings.enabled());
        assertEquals(1, settings.tickSeconds());
        assertTrue(settings.detectsAfk());
        assertEquals(PlaytimeSettings.Mode.AUTO, settings.storage());
        assertEquals(ZoneId.of("Asia/Shanghai"), settings.zone());
    }

    @Test
    void clampsAndFallsBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("playtime.tick-seconds", 0);
        yaml.set("playtime.afk-timeout-seconds", 0);
        yaml.set("playtime.flush-interval-seconds", 1);
        yaml.set("playtime.timezone", "Not/AZone");
        yaml.set("playtime.storage", "unknown");
        yaml.set("playtime.yaml-retention-days", 0);
        yaml.set("playtime.ignored-worlds", List.of("  SkyBlock  ", ""));
        PlaytimeSettings settings = PlaytimeSettings.from(yaml.getConfigurationSection("playtime"));

        assertEquals(1, settings.tickSeconds());
        assertEquals(5, settings.flushIntervalSeconds());
        assertFalse(settings.detectsAfk());
        assertEquals("", settings.afkBypassPermission());
        assertEquals(ZoneId.of("Asia/Shanghai"), settings.zone());
        assertEquals(PlaytimeSettings.Mode.AUTO, settings.storage());
        assertEquals(1, settings.yamlRetentionDays());
        assertTrue(settings.ignoresWorld("skyblock"));
        assertFalse(settings.ignoresWorld("world"));
    }

    @Test
    void weekStartsOnMonday() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        String expected = LocalDate.now(zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
        assertEquals(expected, PlaytimePeriod.weekStart(zone));
        assertEquals(expected, PlaytimeClock.weekStart(zone));
    }

    @Test
    void parsesPeriodAliases() {
        assertEquals(PlaytimePeriod.TODAY, PlaytimePeriod.parse("today", PlaytimePeriod.TOTAL));
        assertEquals(PlaytimePeriod.WEEK, PlaytimePeriod.parse("周", PlaytimePeriod.TOTAL));
        assertEquals(PlaytimePeriod.TOTAL, PlaytimePeriod.parse(null, PlaytimePeriod.TOTAL));
        assertEquals(PlaytimePeriod.WEEK, PlaytimePeriod.parse("garbage", PlaytimePeriod.WEEK));
    }
}
