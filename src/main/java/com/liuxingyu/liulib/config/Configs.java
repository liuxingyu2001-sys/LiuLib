package com.liuxingyu.liulib.config;

import com.liuxingyu.liulib.scheduler.LiuLibScheduler;
import com.liuxingyu.liulib.scheduler.TaskHandle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * 配置模块：共享配置目录、默认配置复制、缺失键补全与 mtime 热重载。
 *
 * <p>对应 {@code shared-config-guide.md} 里被 luckfurnace / liupass / mcskills
 * 复制了三遍的五个步骤：</p>
 * <ol>
 *   <li>{@link #resolveRoot(Plugin, String, String)}：显式路径 → 自动检测 → 插件目录；</li>
 *   <li>{@link #copyIfMissing(Plugin, String, File)}：首次运行把 jar 内默认配置复制到共享目录；</li>
 *   <li>{@link #mergeMissingKeys(ConfigurationSection, ConfigurationSection)}：升级补键，不覆盖用户值；</li>
 *   <li>{@link #fingerprint(File)}：目录内 yml 的 mtime 指纹；</li>
 *   <li>{@link #watch(Plugin, File, long, Runnable)}：异步轮询 → 主线程重载。</li>
 * </ol>
 *
 * <p>约束：轮询线程只做文件 IO/指纹比较；{@code reload} 回调由全局调度器在主线程执行，
 * 调用方可以在回调里安全地操作 Bukkit 对象。手动重载后请调用
 * {@link ConfigWatcher#baseline()}，避免立刻被轮询再次触发。</p>
 */
public final class Configs {

    private final LiuLibScheduler scheduler;

    public Configs(LiuLibScheduler scheduler) {
        this.scheduler = scheduler;
    }

    // ==================================================================
    // 目录解析与默认配置
    // ==================================================================

    /**
     * 解析配置根目录。
     *
     * @param plugin         调用方插件
     * @param configuredPath 配置里的显式路径（空/空白表示未配置）
     * @param autoPath       自动检测的共享目录（如 {@code /mc/shared/liushop-config}，空表示不检测）
     * @return 配置根目录；显式路径创建失败或自动路径不存在时回退插件数据目录
     */
    public File resolveRoot(Plugin plugin, String configuredPath, String autoPath) {
        File dataFolder = plugin.getDataFolder();
        if (configuredPath != null && !configuredPath.isBlank()) {
            File explicit = new File(configuredPath.trim());
            if (explicit.isDirectory()) {
                plugin.getLogger().info("使用共享配置目录: " + explicit.getAbsolutePath());
                return explicit;
            }
            if (explicit.mkdirs()) {
                plugin.getLogger().info("已创建共享配置目录: " + explicit.getAbsolutePath());
                return explicit;
            }
            plugin.getLogger().warning("共享配置目录不可用，回退插件目录: " + explicit.getAbsolutePath());
            return dataFolder;
        }
        if (autoPath != null && !autoPath.isBlank()) {
            File detected = new File(autoPath.trim());
            if (detected.isDirectory()) {
                plugin.getLogger().info("检测到共享配置目录: " + detected.getAbsolutePath());
                return detected;
            }
        }
        return dataFolder;
    }

    /**
     * 把 jar 内资源复制到目标目录；目标已存在时不覆盖。返回是否复制。
     *
     * @param resourcePath jar 内路径（{@code /} 分隔，如 {@code examples/pass.yml}）
     */
    public boolean copyIfMissing(Plugin plugin, String resourcePath, File directory) {
        if (resourcePath == null || resourcePath.isBlank() || directory == null) {
            return false;
        }
        File target = new File(directory, resourcePath.replace('/', File.separatorChar));
        if (target.exists()) {
            return false;
        }
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream == null) {
                plugin.getLogger().warning("jar 内缺少默认配置资源: " + resourcePath);
                return false;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建配置目录: " + parent);
                return false;
            }
            Files.copy(stream, target.toPath());
            return true;
        } catch (IOException error) {
            plugin.getLogger().log(Level.WARNING, "复制默认配置失败: " + resourcePath, error);
            return false;
        }
    }

    /**
     * 补全缺失键：只写 current 中不存在的键，不覆盖用户值。返回新增键数。
     *
     * <p>调用方负责保存（例如 {@code current.save(file)}），便于把“补键”和“落盘”
     * 放在同一个原子重载流程里。</p>
     */
    public int mergeMissingKeys(ConfigurationSection current, ConfigurationSection defaults) {
        if (current == null || defaults == null) {
            return 0;
        }
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            try {
                if (defaults.isConfigurationSection(key)) {
                    if (!current.isConfigurationSection(key) && !current.contains(key)) {
                        current.createSection(key);
                        added++;
                    }
                    continue;
                }
                if (!current.contains(key)) {
                    current.set(key, defaults.get(key));
                    added++;
                }
            } catch (RuntimeException conflict) {
                // 用户把 section 写成了标量（或反之）：保留用户值，不强行覆盖
            }
        }
        return added;
    }

    // ==================================================================
    // 文件读写
    // ==================================================================

    /** 读取 YAML；文件不存在返回空配置，不抛异常。 */
    public YamlConfiguration load(File file) {
        return file == null || !file.isFile()
                ? new YamlConfiguration()
                : YamlConfiguration.loadConfiguration(file);
    }

    /** 原子保存 YAML：先写临时文件再替换，避免崩溃留下半个文件。 */
    public boolean saveAtomic(File file, YamlConfiguration yaml) {
        if (file == null || yaml == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            Path target = file.toPath();
            Path temporary = target.resolveSibling(file.getName() + ".tmp");
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    // ==================================================================
    // 指纹与监听
    // ==================================================================

    /**
     * 目录内所有 {@code .yml/.yaml} 的最大修改时间；目录不存在返回 0。
     *
     * <p>新增文件会带来更新的 mtime，删除文件会让最大值回退，两种情况都能被检测到。
     * 符号链接不会被跟随。</p>
     */
    public static long fingerprint(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return 0L;
        }
        final AtomicLong latest = new AtomicLong(0L);
        try (Stream<Path> stream = Files.walk(directory.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isYaml(path.getFileName().toString()))
                    .forEach(path -> latest.accumulateAndGet(path.toFile().lastModified(), Math::max));
        } catch (Throwable error) {
            return 0L;
        }
        return latest.get();
    }

    private static boolean isYaml(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    /**
     * 监听目录内容变化并自动重载。
     *
     * @param plugin       调用方插件
     * @param directory    配置目录
     * @param intervalTicks 轮询间隔（tick，最小 20 = 1 秒）
     * @param reload       主线程重载回调
     */
    public ConfigWatcher watch(Plugin plugin, File directory, long intervalTicks, Runnable reload) {
        return new ConfigWatcher(plugin, scheduler, directory, intervalTicks, reload);
    }

    /** 目录监听句柄。 */
    public static final class ConfigWatcher implements AutoCloseable {

        private final Plugin plugin;
        private final LiuLibScheduler scheduler;
        private final File directory;
        private final Runnable reload;
        private final AtomicLong lastFingerprint = new AtomicLong();
        private final AtomicBoolean reloading = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final TaskHandle task;

        private ConfigWatcher(Plugin plugin, LiuLibScheduler scheduler, File directory,
                              long intervalTicks, Runnable reload) {
            this.plugin = plugin;
            this.scheduler = scheduler;
            this.directory = directory;
            this.reload = reload;
            this.lastFingerprint.set(fingerprint(directory));
            long ticks = Math.max(20L, intervalTicks);
            this.task = scheduler.asyncTimer(ticks, ticks, this::poll);
            plugin.getLogger().info("已监听配置目录: " + directory
                    + "（每 " + (ticks / 20L) + " 秒检查一次）");
        }

        private void poll() {
            if (closed.get()) {
                return;
            }
            long stamp = fingerprint(directory);
            long previous = lastFingerprint.getAndSet(stamp);
            if (stamp == previous) {
                return;
            }
            if (!reloading.compareAndSet(false, true)) {
                return;
            }
            boolean scheduled = scheduler.global(() -> {
                try {
                    plugin.getLogger().info("检测到配置变化，开始重载: " + directory);
                    reload.run();
                    plugin.getLogger().info("配置重载完成");
                } catch (Throwable error) {
                    plugin.getLogger().log(Level.SEVERE, "配置自动重载失败，保留旧配置", error);
                } finally {
                    baseline();
                    reloading.set(false);
                }
            });
            if (!scheduled) {
                reloading.set(false);
            }
        }

        /** 重新记录指纹基线（手动 reload 后调用，避免立刻再触发一次）。 */
        public void baseline() {
            lastFingerprint.set(fingerprint(directory));
        }

        public long lastFingerprintValue() {
            return lastFingerprint.get();
        }

        public boolean isClosed() {
            return closed.get();
        }

        /** 停止监听。 */
        public void stop() {
            if (closed.compareAndSet(false, true)) {
                task.cancel();
            }
        }

        @Override
        public void close() {
            stop();
        }
    }
}
