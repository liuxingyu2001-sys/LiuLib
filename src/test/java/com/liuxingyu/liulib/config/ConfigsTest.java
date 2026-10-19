package com.liuxingyu.liulib.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigsTest {

    private final Configs configs = new Configs(null);

    @Test
    void mergeAddsMissingKeysWithoutOverwriting() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("shop.buy-multiplier", 1.0);
        defaults.set("shop.max-items", 100);
        defaults.set("messages.prefix", "<gray>[Shop]");

        YamlConfiguration current = new YamlConfiguration();
        current.set("shop.max-items", 42);

        int added = configs.mergeMissingKeys(current, defaults);
        assertTrue(added >= 2);
        assertEquals(42, current.getInt("shop.max-items"));
        assertEquals(1.0, current.getDouble("shop.buy-multiplier"));
        assertEquals("<gray>[Shop]", current.getString("messages.prefix"));
    }

    @Test
    void fingerprintUsesNewestYamlOnly(@TempDir Path directory) throws Exception {
        File first = Files.createFile(directory.resolve("a.yml")).toFile();
        File second = Files.createFile(directory.resolve("nested.yml")).toFile();
        File ignored = Files.createFile(directory.resolve("notes.txt")).toFile();
        assertTrue(first.setLastModified(1_000L));
        assertTrue(second.setLastModified(2_000L));
        assertTrue(ignored.setLastModified(9_000L));

        assertEquals(2_000L, Configs.fingerprint(directory.toFile()));

        assertTrue(second.delete());
        assertEquals(1_000L, Configs.fingerprint(directory.toFile()));
    }

    @Test
    void savesAndLoadsYamlAtomically(@TempDir Path directory) {
        File file = directory.resolve("sub/config.yml").toFile();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", true);
        yaml.set("name", "测试");

        assertTrue(configs.saveAtomic(file, yaml));
        assertTrue(file.isFile());
        assertFalse(new File(file.getPath() + ".tmp").exists());

        YamlConfiguration loaded = configs.load(file);
        assertTrue(loaded.getBoolean("enabled"));
        assertEquals("测试", loaded.getString("name"));
    }
}
