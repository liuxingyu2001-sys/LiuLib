package com.liuxingyu.liulib.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void fingerprintTracksPathsSizesAndMtimes(@TempDir Path directory) throws Exception {
        File first = Files.createFile(directory.resolve("a.yml")).toFile();
        File second = Files.createFile(directory.resolve("nested.yml")).toFile();
        File ignored = Files.createFile(directory.resolve("notes.txt")).toFile();
        assertTrue(first.setLastModified(1_000L));
        assertTrue(second.setLastModified(2_000L));
        assertTrue(ignored.setLastModified(9_000L));

        long initial = Configs.fingerprint(directory.toFile());
        // 同一批文件重复计算必须稳定；非 YAML 文件不参与。
        assertEquals(initial, Configs.fingerprint(directory.toFile()));
        assertTrue(ignored.setLastModified(10_000L));
        assertEquals(initial, Configs.fingerprint(directory.toFile()));

        // 旧文件被原地修改（mtime 不再是最新）也必须触发变化，不能只看最大 mtime。
        assertTrue(first.setLastModified(1_500L));
        long firstTouched = Configs.fingerprint(directory.toFile());
        assertNotEquals(initial, firstTouched);

        assertTrue(second.delete());
        assertNotEquals(firstTouched, Configs.fingerprint(directory.toFile()));
    }

    @Test
    void resolveRootCreatesExplicitAndFallsBack(@TempDir Path directory) throws Exception {
        File data = directory.resolve("data").toFile();
        assertTrue(data.mkdirs());
        Plugin plugin = fakePlugin(data);

        File explicit = directory.resolve("shared").toFile();
        File resolved = configs.resolveRoot(plugin, explicit.getAbsolutePath(), "");
        assertEquals(explicit.getCanonicalFile(), resolved.getCanonicalFile());
        assertTrue(explicit.isDirectory());

        File auto = directory.resolve("auto").toFile();
        assertTrue(auto.mkdirs());
        File detected = configs.resolveRoot(plugin, "", auto.getAbsolutePath());
        assertEquals(auto.getCanonicalFile(), detected.getCanonicalFile());

        File fallback = configs.resolveRoot(plugin, "", directory.resolve("missing").toString());
        assertEquals(data.getCanonicalFile(), fallback.getCanonicalFile());
    }

    private static Plugin fakePlugin(File dataFolder) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dataFolder;
                    case "getLogger" -> Logger.getLogger("ConfigsTest");
                    case "getName" -> "ConfigsTest";
                    case "isEnabled" -> true;
                    default -> null;
                });
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
