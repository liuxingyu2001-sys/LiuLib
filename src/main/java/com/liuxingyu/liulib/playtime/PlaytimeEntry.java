package com.liuxingyu.liulib.playtime;

import java.util.UUID;

/** 排行榜条目。 */
public record PlaytimeEntry(UUID uuid, String name, long seconds) {

    public PlaytimeEntry {
        name = name == null || name.isBlank() ? shortUuid(uuid) : name;
        seconds = Math.max(0, seconds);
    }

    private static String shortUuid(UUID uuid) {
        if (uuid == null) {
            return "???";
        }
        String raw = uuid.toString();
        return raw.substring(0, 8);
    }
}
