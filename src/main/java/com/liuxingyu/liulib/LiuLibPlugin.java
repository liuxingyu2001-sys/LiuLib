package com.liuxingyu.liulib;

import com.liuxingyu.liulib.api.LiuLibApi;
import com.liuxingyu.liulib.config.Configs;
import com.liuxingyu.liulib.config.LiuLibConfig;
import com.liuxingyu.liulib.cooldown.Cooldowns;
import com.liuxingyu.liulib.crossserver.CrossServer;
import com.liuxingyu.liulib.economy.EconomyService;
import com.liuxingyu.liulib.item.ItemOps;
import com.liuxingyu.liulib.item.ItemService;
import com.liuxingyu.liulib.placeholder.Placeholders;
import com.liuxingyu.liulib.playtime.PlaytimeFormatter;
import com.liuxingyu.liulib.playtime.PlaytimeService;
import com.liuxingyu.liulib.scheduler.LiuLibScheduler;
import com.liuxingyu.liulib.service.ServiceRegistry;
import com.liuxingyu.liulib.storage.MySql;
import com.liuxingyu.liulib.storage.RedisClient;
import com.liuxingyu.liulib.storage.Sqlite;
import com.liuxingyu.liulib.text.TextService;
import com.liuxingyu.liulib.ui.SparrowUi;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * LiuLib 主类。
 *
 * <p>职责只有三件事：加载配置、组装各模块、把 {@link LiuLibApi} 暴露给其它插件。
 * 不包含任何玩法逻辑；MySQL / Redis / Sparrow 均可在配置中关闭，关闭后对应 API
 * 仍然存在但 {@code isReady()/available()} 为 false。</p>
 */
public final class LiuLibPlugin extends JavaPlugin implements LiuLibApi {

    private volatile LiuLibConfig config;
    private volatile ItemService items;
    private volatile ItemOps itemOps;
    private volatile TextService text;
    private volatile Cooldowns cooldowns;
    private volatile Placeholders placeholders;
    private volatile ServiceRegistry services;
    private volatile EconomyService economy;
    private volatile LiuLibScheduler scheduler;
    private volatile Configs configs;
    private volatile File configRoot;
    private volatile YamlConfiguration mainConfig;
    private volatile PlaytimeService playtime;
    private volatile MySql mysql;
    private volatile Sqlite sqlite;
    private volatile RedisClient redis;
    private volatile CrossServer crossServer;
    private volatile SparrowUi sparrow;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.scheduler = new LiuLibScheduler(this);
        this.configs = new Configs(scheduler);
        this.configRoot = resolveConfigRoot();
        try {
            this.mainConfig = loadMainConfig(configRoot);
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "共享配置加载失败，回退本地配置", error);
            this.configRoot = getDataFolder();
            try {
                this.mainConfig = loadMainConfig(configRoot);
            } catch (Throwable localError) {
                getLogger().log(Level.SEVERE, "本地配置也无法读取，使用内置默认配置", localError);
                this.mainConfig = loadDefaultConfig();
            }
        }
        this.config = LiuLibConfig.load(mainConfig);
        this.text = new TextService(config.text());
        this.items = new ItemService(this, config);
        this.itemOps = items.ops();
        this.cooldowns = new Cooldowns(scheduler);
        this.cooldowns.startCleanup(1_200L);
        this.placeholders = new Placeholders(this);
        this.services = new ServiceRegistry(this);
        this.economy = new EconomyService(this);
        this.mysql = new MySql(config.mysql(), getLogger());
        this.mysql.init();
        this.sqlite = new Sqlite(this, config.sqlite(), getLogger());
        this.sqlite.init();
        this.redis = new RedisClient(config.redis(), getLogger());
        this.redis.init();
        this.crossServer = new CrossServer(redis);
        this.sparrow = new SparrowUi(this, config.ui());
        this.sparrow.init();
        this.playtime = new PlaytimeService(this, config.playtime(), scheduler, mysql, placeholders);
        this.playtime.init();

        PluginCommand command = getCommand("liulib");
        if (command != null) {
            LiuLibCommand executor = new LiuLibCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getServer().getServicesManager().register(LiuLibApi.class, this, this, ServicePriority.Normal);
        LiuLib.install(this);

        getLogger().info("LiuLib 已启用：CE=" + (items.isCraftEngineAvailable() ? "可用" : "未安装")
                + " MySQL=" + (mysql.isReady() ? "已连接" : "未启用")
                + " SQLite=" + (sqlite.isReady() ? "已连接" : "未启用")
                + " Redis=" + (redis.isReady() ? "已连接" : "未启用")
                + " Sparrow=" + (sparrow.available() ? "可用" : "未启用")
                + " Playtime=" + (playtime.isEnabled() ? playtime.storageKind() : "未启用")
                + " CrossServer=" + (crossServer.available() ? "可用" : "未启用"));
    }

    @Override
    public void onDisable() {
        LiuLib.uninstall(this);
        getServer().getServicesManager().unregister(LiuLibApi.class, this);
        if (playtime != null) {
            playtime.shutdown();
        }
        if (cooldowns != null) {
            cooldowns.shutdown();
        }
        if (placeholders != null) {
            placeholders.close();
        }
        if (services != null) {
            services.unregisterAll();
        }
        if (sparrow != null) {
            sparrow.shutdown();
        }
        if (crossServer != null) {
            crossServer.close();
        }
        if (redis != null) {
            redis.close();
        }
        if (sqlite != null) {
            sqlite.close();
        }
        if (mysql != null) {
            mysql.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        getLogger().info("LiuLib 已停用");
    }

    // ==================================================================
    // LiuLibApi
    // ==================================================================

    @Override
    public ItemService items() {
        ItemService service = items;
        if (service == null) {
            throw new IllegalStateException("LiuLib 尚未启用");
        }
        return service;
    }

    @Override
    public TextService text() {
        return text;
    }

    @Override
    public ItemOps itemOps() {
        return itemOps;
    }

    @Override
    public Cooldowns cooldowns() {
        return cooldowns;
    }

    @Override
    public Placeholders placeholders() {
        return placeholders;
    }

    @Override
    public CrossServer crossServer() {
        return crossServer;
    }

    @Override
    public EconomyService economy() {
        return economy;
    }

    @Override
    public ServiceRegistry services() {
        return services;
    }

    @Override
    public LiuLibScheduler scheduler() {
        return scheduler;
    }

    @Override
    public Configs configs() {
        return configs;
    }

    @Override
    public PlaytimeService playtime() {
        return playtime;
    }

    @Override
    public MySql mysql() {
        return mysql;
    }

    @Override
    public Sqlite sqlite() {
        return sqlite;
    }

    @Override
    public RedisClient redis() {
        return redis;
    }

    @Override
    public SparrowUi sparrow() {
        return sparrow;
    }

    @Override
    public LiuLibConfig config() {
        return config;
    }

    @Override
    public File configRoot() {
        File root = configRoot;
        return root == null ? getDataFolder() : root;
    }

    @Override
    public String version() {
        return getPluginMeta().getVersion();
    }

    @Override
    public boolean debug() {
        LiuLibConfig snapshot = config;
        return snapshot != null && snapshot.debug();
    }

    /** 默认共享配置目录；存在时才自动启用，显式配置优先。 */
    private static final String AUTO_SHARED_CONFIG = "/mc/shared/liulib-config";

    /**
     * 解析配置根目录：本地 config.yml 的 {@code shared-config-path} →
     * 自动检测 {@code /mc/shared/liulib-config} → 插件数据目录。
     *
     * <p>{@code shared-config-path} 只从各服本地那份 config.yml 读取，因此每个服只需配置一次路径；
     * 共享目录里的其余配置（包括同一键）不会反过来影响路径选择。</p>
     */
    private File resolveConfigRoot() {
        String configured = getConfig().getString("shared-config-path",
                getConfig().getString("settings.shared-config-path", ""));
        File root = configs.resolveRoot(this, configured, AUTO_SHARED_CONFIG);
        try {
            return root.getCanonicalFile();
        } catch (Throwable ignored) {
            return root.getAbsoluteFile();
        }
    }

    /**
     * 读取根目录下的 config.yml；缺失时先复制 jar 内默认配置，再补全新增键。
     *
     * @throws Exception 文件存在但 YAML 无法解析；调用方保留旧配置
     */
    private YamlConfiguration loadMainConfig(File root) throws Exception {
        File file = new File(root, "config.yml");
        if (!file.isFile()) {
            configs.copyIfMissing(this, "config.yml", root);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        if (file.isFile()) {
            yaml.load(file);
        }
        YamlConfiguration defaults = loadDefaultConfig();
        int added = configs.mergeMissingKeys(yaml, defaults);
        if (added > 0) {
            if (configs.saveAtomic(file, yaml)) {
                getLogger().info("已为 " + file + " 补全 " + added + " 个缺失配置键");
            } else {
                getLogger().warning("配置缺失键补全后保存失败: " + file);
            }
        }
        return yaml;
    }

    /** 从 jar 读取内置默认 config.yml；失败返回空配置。 */
    private YamlConfiguration loadDefaultConfig() {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "读取内置 config.yml 失败", error);
            return new YamlConfiguration();
        }
    }

    /**
     * 重新读取 config.yml，重建物品/文本/配置/在线时长/数据库/Redis/Sparrow 模块。
     *
     * <p>Redis 重建会断开已有订阅；依赖方应在自己的 reload 流程里重新
     * {@code redis().subscribe(...)}。</p>
     */
    @Override
    public synchronized boolean reload() {
        try {
            saveDefaultConfig();
            reloadConfig();
            File freshRoot = resolveConfigRoot();
            YamlConfiguration freshMain = loadMainConfig(freshRoot);
            LiuLibConfig fresh = LiuLibConfig.load(freshMain);

            // 先构造新模块，成功后再关闭旧模块；构造失败时旧服务保持可用。
            ItemService newItems = new ItemService(this, fresh);
            ItemOps newItemOps = newItems.ops();
            Configs newConfigs = new Configs(scheduler);
            TextService newText = new TextService(fresh.text());
            MySql newMysql = new MySql(fresh.mysql(), getLogger());
            boolean mysqlOk = newMysql.init();
            Sqlite newSqlite = new Sqlite(this, fresh.sqlite(), getLogger());
            boolean sqliteOk = newSqlite.init();
            RedisClient newRedis = new RedisClient(fresh.redis(), getLogger());
            boolean redisOk = newRedis.init();
            CrossServer newCrossServer = new CrossServer(newRedis);
            SparrowUi newSparrow = new SparrowUi(this, fresh.ui());
            boolean sparrowOk = newSparrow.init();
            PlaytimeService newPlaytime = new PlaytimeService(this, fresh.playtime(), scheduler, newMysql, placeholders);
            EconomyService newEconomy = new EconomyService(this);

            // 必需模块初始化失败时不能换掉旧模块：否则一次手滑的配置会把正在运行的
            // MySQL/Redis 连接关掉，所有依赖插件立即失去存储。先验证，再关闭旧实例。
            boolean requiredOk = true;
            if (fresh.mysql().enabled() && !mysqlOk) {
                requiredOk = false;
            }
            if (fresh.sqlite().enabled() && !sqliteOk) {
                requiredOk = false;
            }
            if (fresh.redis().enabled() && !redisOk) {
                requiredOk = false;
            }
            if (fresh.ui().sparrowEnabled() && !sparrowOk) {
                requiredOk = false;
            }
            if (!requiredOk) {
                getLogger().severe("新配置的必需存储/连接初始化失败，本次重载已取消，继续使用旧模块");
                closeQuietly(newCrossServer, newSparrow, newRedis, newSqlite, newMysql);
                return false;
            }

            MySql oldMysql = mysql;
            Sqlite oldSqlite = sqlite;
            RedisClient oldRedis = redis;
            CrossServer oldCrossServer = crossServer;
            SparrowUi oldSparrow = sparrow;
            PlaytimeService oldPlaytime = playtime;

            // 先停旧实例：释放 PAPI 变量与监听器，旧实例会同步刷新数据到旧 MySQL。
            if (oldPlaytime != null) {
                try {
                    oldPlaytime.shutdown();
                } catch (Throwable error) {
                    getLogger().log(Level.WARNING, "关闭旧在线时长模块失败", error);
                }
            }
            boolean playtimeOk;
            try {
                playtimeOk = newPlaytime.init();
            } catch (Throwable error) {
                getLogger().log(Level.SEVERE, "初始化新在线时长模块失败", error);
                playtimeOk = false;
            }

            this.config = fresh;
            this.configRoot = freshRoot;
            this.mainConfig = freshMain;
            this.items = newItems;
            this.itemOps = newItemOps;
            this.configs = newConfigs;
            this.text = newText;
            this.mysql = newMysql;
            this.sqlite = newSqlite;
            this.redis = newRedis;
            this.crossServer = newCrossServer;
            this.sparrow = newSparrow;
            this.playtime = newPlaytime;
            this.economy = newEconomy;

            closeQuietly(oldCrossServer, oldSparrow, oldRedis, oldSqlite, oldMysql);

            boolean ok = true;
            if (fresh.playtime().enabled() && !playtimeOk) {
                getLogger().warning("在线时长模块未能按新配置启用，本次重载后该模块保持关闭");
                ok = false;
            }
            return ok;
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "LiuLib 重载失败，保留旧配置", error);
            return false;
        }
    }

    /** 关闭一组新构造但未启用的模块，避免重载失败时泄漏连接池/线程。 */
    private void closeQuietly(CrossServer crossServer, SparrowUi sparrow, RedisClient redis,
                              Sqlite sqlite, MySql mysql) {
        try {
            if (crossServer != null) {
                crossServer.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (sparrow != null) {
                sparrow.shutdown();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (redis != null) {
                redis.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (sqlite != null) {
                sqlite.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mysql != null) {
                mysql.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 运行时自检：真实走一遍物品编解码、名称解析、CraftEngine、Redis（含 Pub/Sub）、MySQL。
     * 只由管理员命令触发，不写业务数据；Redis 使用随机临时 key，MySQL 使用随机临时表并清理。
     */
    public List<String> selfTest() {
        List<String> out = new ArrayList<>();
        try {
            ItemStack stack = new ItemStack(Material.DIAMOND_SWORD, 3);
            String encoded = items.codec().encode(stack);
            ItemStack decoded = items.codec().decode(encoded);
            out.add("ItemCodec(Paper NBT): " + (same(decoded, stack) ? "OK" : "FAIL")
                    + " (" + (encoded == null ? 0 : encoded.length()) + " chars)");

            ByteArrayOutputStream legacyBytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(legacyBytes)) {
                stream.writeObject(stack);
            }
            ItemStack legacy = items.codec().decode("base64:"
                    + Base64.getEncoder().encodeToString(legacyBytes.toByteArray()));
            out.add("ItemCodec(legacy stream): " + (same(legacy, stack) ? "OK" : "FAIL"));

            ItemStack fromMap = items.codec().fromMap(items.codec().toMap(stack));
            out.add("ItemCodec(YAML map): " + (same(fromMap, stack) ? "OK" : "FAIL"));
            out.add("ItemNames: " + items.label(stack));

            if (items.isCraftEngineAvailable()) {
                String ceId = "ce:default:topaz_helmet";
                ItemStack ce = items.buildOrNull(ceId);
                out.add("CraftEngine build: " + (ce != null ? "OK" : "FAIL"));
                if (ce != null) {
                    out.add("CraftEngine id: " + items.id(ce));
                    out.add("CraftEngine name: " + items.label(ce));
                }
                out.add("CraftEngine translation: "
                        + items.craftEngine().resolveTranslation("item.default.topaz_helmet"));
            } else {
                out.add("CraftEngine: 未安装");
            }
        } catch (Throwable error) {
            out.add("ItemCodec: FAIL - " + error);
            getLogger().log(Level.WARNING, "LiuLib 物品自检失败", error);
        }

        if (redis.isReady()) {
            String key = "selftest:" + UUID.randomUUID();
            String channel = "selftest:" + UUID.randomUUID();
            try {
                redis.setex(key, 30, "ok");
                boolean valueOk = "ok".equals(redis.get(key));
                redis.del(key);
                out.add("Redis value: " + (valueOk ? "OK" : "FAIL"));

                CountDownLatch latch = new CountDownLatch(1);
                redis.subscribe(channel, message -> {
                    if ("ping".equals(message)) {
                        latch.countDown();
                    }
                });
                for (int i = 0; i < 20 && !redis.isSubscribed(channel); i++) {
                    Thread.sleep(50L);
                }
                long receivers = redis.publish(channel, "ping");
                boolean received = latch.await(2, TimeUnit.SECONDS);
                redis.unsubscribe(channel);
                out.add("Redis pub/sub: " + (received ? "OK" : "FAIL")
                        + " (receivers=" + receivers + ")");
            } catch (Throwable error) {
                out.add("Redis: FAIL - " + error);
                redis.unsubscribe(channel);
            }
        } else {
            out.add("Redis: 未启用/未连接");
        }

        if (mysql.isReady()) {
            String table = "selftest_" + UUID.randomUUID().toString().replace("-", "");
            try {
                Long one = mysql.queryOne("SELECT 1", resultSet -> resultSet.getLong(1));
                out.add("MySQL SELECT 1: " + (one != null && one == 1L ? "OK" : "FAIL"));

                mysql.createTable(table, "id INT PRIMARY KEY, value VARCHAR(32) NOT NULL");
                mysql.update("INSERT INTO " + mysql.table(table) + " (id, value) VALUES (?, ?)", 1, "ok");
                String value = mysql.queryOne("SELECT value FROM " + mysql.table(table)
                        + " WHERE id = ?", resultSet -> resultSet.getString(1), 1);
                out.add("MySQL CRUD: " + ("ok".equals(value) ? "OK" : "FAIL"));
            } catch (Throwable error) {
                out.add("MySQL: FAIL - " + error);
            } finally {
                try {
                    mysql.dropTable(table);
                } catch (Throwable ignored) {
                    // 自检清理失败不影响结果
                }
            }
        } else {
            out.add("MySQL: 未启用/未连接");
        }
        out.add("Sparrow UI: " + (sparrow.available() ? "OK" : "不可用"));
        out.add("Text: " + text.plain("&a前缀 <gradient:red:blue>渐变</gradient>"));
        out.add("Playtime: " + (playtime.isEnabled()
                ? "OK (" + playtime.storageKind() + ", 在线会话=" + playtime.onlineSessions() + ")"
                : "未启用")
                + "，格式化样例=" + PlaytimeFormatter.format(3661));
        UUID cooldownId = UUID.randomUUID();
        boolean triggered = cooldowns.trigger("selftest", cooldownId, 1, TimeUnit.SECONDS);
        boolean cooling = cooldowns.isCooling("selftest", cooldownId);
        cooldowns.clear(cooldownId);
        out.add("Cooldowns: " + (triggered && cooling ? "OK" : "FAIL"));
        out.add("Placeholders: " + (placeholders.available() ? "PAPI 已连接" : "未安装 PAPI"));
        out.add("Services: " + services.size() + " 个自注册服务");
        out.add("CrossServer: " + (crossServer.available() ? "OK" : "Redis 未启用"));
        out.add("Economy: " + (economy.available() ? "Vault 已连接" : "未启用"));
        out.add("SQLite: " + (sqlite.isReady()
                ? "OK (" + sqlite.file().getFileName() + ")" : "未启用"));
        return out;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left != null && right != null
                && left.getType() == right.getType()
                && left.getAmount() == right.getAmount();
    }

    /** 状态摘要（命令与排障用）。 */
    public String status() {
        StringBuilder out = new StringBuilder();
        out.append("版本 ").append(version()).append('\n');
        out.append("配置: ").append(configRoot().getAbsolutePath())
                .append(configRoot().equals(getDataFolder()) ? "（本地）" : "（共享）").append('\n');
        out.append("CraftEngine: ").append(items.isCraftEngineAvailable()
                ? items.craftEngine().diagnostics() : "未安装").append('\n');
        out.append("MySQL: ").append(mysql.isReady() ? "已连接" : "未启用/连接失败").append('\n');
        out.append("Redis: ").append(redis.isReady() ? "已连接" : "未启用/连接失败").append('\n');
        out.append("Sparrow UI: ").append(sparrow.available() ? "可用" : "未启用/初始化失败").append('\n');
        out.append("调度: ").append(scheduler.isFolia() ? "Folia" : "Paper").append('\n');
        out.append("文本: 已加载").append('\n');
        out.append("在线时长: ").append(playtime.isEnabled()
                ? "已启用 (存储=" + playtime.storageKind() + ", 在线=" + playtime.onlineSessions() + ")"
                : "未启用").append('\n');
        out.append("PlaceholderAPI: ").append(placeholders.available() ? "可用" : "未安装").append('\n');
        out.append("物品操作/冷却/服务: 就绪 (服务=" + services.size() + ")").append('\n');
        out.append("跨服消息: ").append(crossServer.available() ? "可用" : "未启用").append('\n');
        out.append("经济: ").append(economy.available() ? "Vault 可用" : "未启用").append('\n');
        out.append("SQLite: ").append(sqlite.isReady() ? "可用" : "未启用");
        return out.toString();
    }
}
