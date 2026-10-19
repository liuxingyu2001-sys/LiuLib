package com.liuxingyu.liulib.playtime;

import com.liuxingyu.liulib.placeholder.Placeholders;
import com.liuxingyu.liulib.scheduler.LiuLibScheduler;
import com.liuxingyu.liulib.scheduler.TaskHandle;
import com.liuxingyu.liulib.storage.MySql;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 玩家在线时长统计。
 *
 * <p>特性：</p>
 * <ul>
 *   <li><b>实时累计</b>：每秒按实际在线时间累加，不依赖登录/退出配对，崩溃也不会丢掉已刷新部分；</li>
 *   <li><b>AFK 检测</b>：超过 {@code afk-timeout-seconds} 无移动/聊天/命令/交互则停止计时，
 *       可用权限 {@code liulib.playtime.afk-bypass} 豁免；</li>
 *   <li><b>按天/按周</b>：按配置时区自然日切分，跨天自动把旧数据先刷新到旧日期；</li>
 *   <li><b>多服聚合</b>：MySQL 模式使用原子自增，多台服务器同时计时不会互相覆盖；</li>
 *   <li><b>可降级</b>：MySQL 未就绪时回退 YAML，模块关闭时 API 仍存在但 {@link #isEnabled()}=false；</li>
 *   <li><b>可扩展</b>：其他插件可调用 {@link #addBonus(UUID, String, long)} 发放时长奖励，
 *       通过 {@link #totalSeconds(UUID)} / {@link #top(PlaytimePeriod, int)} 读取。</li>
 * </ul>
 *
 * <p>线程：{@link #totalSeconds} 等方法可在任意线程读取；{@link #top}、{@link #stored}
 * 返回 Future，不阻塞主线程；写入统一走异步队列，插件停用时同步刷新。</p>
 */
public final class PlaytimeService {

    /** 单次 tick 最多补记 120 秒，避免服务器时间被手动调整后一次性写入巨量时长。 */
    private static final long MAX_CREDIT_MILLIS = 120_000L;

    private record PendingWrite(UUID uuid, String name, String day, long seconds) {
    }

    private record CachedTop(List<PlaytimeEntry> entries, long expiresAt) {
    }

    private static final class Session {
        private final UUID uuid;
        private volatile String name;
        private volatile long lastTick;
        private volatile long lastActivity;
        private volatile long remainderMillis;
        private volatile long baselineTotal;
        private volatile long baselineToday;
        private volatile long sessionSeconds;
        private volatile long todaySeconds;
        private volatile long pending;
        private volatile String day;
        private volatile boolean afk;
        private volatile boolean loaded;

        private Session(UUID uuid, String name, long now, String day) {
            this.uuid = uuid;
            this.name = name == null ? "" : name;
            this.lastTick = now;
            this.lastActivity = now;
            this.day = day;
        }
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final LiuLibScheduler scheduler;
    private final MySql mysql;
    private final Placeholders placeholders;
    private final PlaytimeSettings settings;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlaytimeRecord> known = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> lastWrites = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> inflight = new CopyOnWriteArraySet<>();
    private final Map<String, CachedTop> topCache = new ConcurrentHashMap<>();
    private final List<PendingWrite> retries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile PlaytimeStore store;
    private volatile TaskHandle tickTask;
    private volatile TaskHandle flushTask;
    private volatile PlaytimeListener listener;
    private volatile boolean enabled;

    /** 兼容旧构造：不注册 PAPI 变量（等价 placeholders=null）。 */
    public PlaytimeService(JavaPlugin plugin, PlaytimeSettings settings, LiuLibScheduler scheduler,
                           MySql mysql) {
        this(plugin, settings, scheduler, mysql, null);
    }

    public PlaytimeService(JavaPlugin plugin, PlaytimeSettings settings, LiuLibScheduler scheduler,
                           MySql mysql, Placeholders placeholders) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings == null ? PlaytimeSettings.defaults() : settings;
        this.scheduler = scheduler;
        this.mysql = mysql;
        this.placeholders = placeholders;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 初始化存储、监听器与定时任务；幂等。返回模块是否启用。 */
    public boolean init() {
        if (!started.compareAndSet(false, true)) {
            return enabled;
        }
        if (!settings.enabled()) {
            logger.info("在线时长统计已在配置中关闭");
            return false;
        }
        PlaytimeStore created = createStore();
        if (created == null) {
            logger.warning("在线时长没有可用存储，模块不启用");
            return false;
        }
        this.store = created;
        this.enabled = true;

        PlaytimeListener registered = new PlaytimeListener(this);
        this.listener = registered;
        plugin.getServer().getPluginManager().registerEvents(registered, plugin);
        // 重载时玩家已经在服内：直接接管，避免等他们重新上线才开始计时
        for (Player player : Bukkit.getOnlinePlayers()) {
            onJoin(player);
        }

        long tickTicks = Math.max(1, settings.tickSeconds()) * 20L;
        this.tickTask = scheduler.globalTimer(tickTicks, tickTicks, this::tick);
        long flushTicks = Math.max(5, settings.flushIntervalSeconds()) * 20L;
        this.flushTask = scheduler.asyncTimer(flushTicks, flushTicks, () -> flushAll(false));

        registerPlaceholders();
        logger.info("在线时长已启用：存储=" + created.kind()
                + "，AFK=" + (settings.detectsAfk() ? settings.afkTimeoutSeconds() + "秒" : "关闭")
                + "，时区=" + settings.zone());
        return true;
    }

    private PlaytimeStore createStore() {
        PlaytimeSettings.Mode mode = settings.storage();
        if (mode == PlaytimeSettings.Mode.MYSQL && (mysql == null || !mysql.isReady())) {
            logger.warning("playtime.storage=mysql 但 MySQL 未就绪，回退 YAML 存储");
        }
        if (mode != PlaytimeSettings.Mode.YAML && mysql != null && mysql.isReady()) {
            MySqlPlaytimeStore sql = new MySqlPlaytimeStore(mysql, logger);
            if (sql.init()) {
                return sql;
            }
            logger.warning("在线时长 MySQL 存储不可用，回退 YAML（如需强制 MySQL 请检查数据库配置）");
        }
        YamlPlaytimeStore yaml = new YamlPlaytimeStore(plugin, settings, logger);
        return yaml.init() ? yaml : null;
    }

    private void registerPlaceholders() {
        if (!settings.placeholders() || placeholders == null || !placeholders.available()) {
            return;
        }
        PlaytimePlaceholders handler = new PlaytimePlaceholders(this);
        if (!placeholders.register(PlaytimePlaceholders.IDENTIFIER, handler::resolve)) {
            logger.fine("注册在线时长 PAPI 变量失败（identifier 可能被占用）");
        }
    }

    private void unregisterPlaceholders() {
        if (placeholders != null) {
            placeholders.unregister(PlaytimePlaceholders.IDENTIFIER);
        }
    }

    /**
     * 关闭：取消定时任务 → 同步刷新所有在线会话 → 等待异步写入 → 落盘 → 关闭存储。
     * 只能调用一次；之后 {@link #isEnabled()} 为 false。
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        enabled = false;
        cancel(tickTask);
        cancel(flushTask);
        PlaytimeListener registered = listener;
        if (registered != null) {
            HandlerList.unregisterAll(registered);
            listener = null;
        }
        unregisterPlaceholders();

        long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            credit(session, now);
            flushSession(session, true, true);
        }
        processRetries(true);
        awaitInflight(settings.quitFlushTimeoutMillis());

        PlaytimeStore current = store;
        if (current != null) {
            try {
                current.flush();
            } catch (Throwable error) {
                logger.log(Level.WARNING, "在线时长落盘失败", error);
            }
            try {
                current.close();
            } catch (Throwable error) {
                logger.log(Level.WARNING, "关闭在线时长存储失败", error);
            }
        }
        sessions.clear();
        known.clear();
        lastWrites.clear();
        inflight.clear();
        topCache.clear();
        retries.clear();
        store = null;
        logger.info("在线时长已关闭");
    }

    private static void cancel(TaskHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    // ==================================================================
    // 会话
    // ==================================================================

    void onJoin(Player player) {
        if (isClosed() || player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Session session = new Session(player.getUniqueId(), player.getName(), now,
                PlaytimeClock.today(settings.zone()));
        Session previous = sessions.put(player.getUniqueId(), session);
        if (previous != null) {
            flushSession(previous, true, false);
        }
        loadBaseline(session);
    }

    void onQuit(Player player) {
        if (isClosed() || player == null) {
            return;
        }
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        credit(session, System.currentTimeMillis());
        if (session.sessionSeconds < settings.minSessionSeconds()) {
            if (settings.minSessionSeconds() > 0) {
                logger.fine("会话短于 " + settings.minSessionSeconds() + " 秒，忽略本次在线时长: "
                        + session.name);
            }
            return;
        }
        cacheSession(session);
        flushSession(session, true, false);
    }

    /** 退出时把最终值放进离线缓存，避免其它插件立刻读到旧数据。 */
    private void cacheSession(Session session) {
        if (!session.loaded) {
            return;
        }
        known.put(session.uuid, new PlaytimeRecord(session.uuid, session.name,
                session.baselineTotal + session.sessionSeconds,
                session.baselineToday + session.todaySeconds,
                session.day));
    }

    /** 记录玩家活动，用于 AFK 判定。可在异步线程调用。 */
    void touch(Player player) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.lastActivity = System.currentTimeMillis();
            session.afk = false;
        }
    }

    private void loadBaseline(Session session) {
        CompletableFuture<Void> previous = lastWrites.get(session.uuid);
        CompletableFuture<Void> ordered = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((ignored, error) -> null);
        ordered.thenCompose(ignored -> scheduler.async(() -> loadRecord(session)))
                .whenComplete((record, error) -> {
                    if (error != null) {
                        logger.log(Level.FINE, "读取在线时长基线失败: " + session.uuid, error);
                        session.loaded = true;
                        return;
                    }
                    known.put(session.uuid, record);
                    if (sessions.get(session.uuid) != session) {
                        return;
                    }
                    session.baselineTotal = record.totalSeconds();
                    session.baselineToday = record.day().equals(session.day)
                            ? record.todaySeconds() : 0;
                    if (!record.name().isBlank()) {
                        session.name = record.name();
                    }
                    session.loaded = true;
                });
    }

    private PlaytimeRecord loadRecord(Session session) {
        PlaytimeStore current = store;
        if (current == null) {
            return PlaytimeRecord.empty(session.uuid, session.day);
        }
        try {
            return current.load(session.uuid, session.day);
        } catch (Exception error) {
            throw new IllegalStateException("读取在线时长失败", error);
        }
    }

    // ==================================================================
    // 计时
    // ==================================================================

    private void tick() {
        if (isClosed()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            credit(session, now);
        }
    }

    private void credit(Session session, long now) {
        long elapsed = now - session.lastTick;
        if (elapsed <= 0) {
            return;
        }
        session.lastTick = now;
        if (elapsed > MAX_CREDIT_MILLIS) {
            elapsed = MAX_CREDIT_MILLIS;
        }
        Player player = Bukkit.getPlayer(session.uuid);
        if (player == null) {
            return;
        }
        if (settings.ignoresWorld(player.getWorld().getName())) {
            session.remainderMillis = 0;
            session.afk = false;
            return;
        }
        boolean bypass = settings.detectsAfk()
                && !settings.afkBypassPermission().isEmpty()
                && player.hasPermission(settings.afkBypassPermission());
        boolean afk = settings.detectsAfk() && !bypass
                && now - session.lastActivity > settings.afkTimeoutSeconds() * 1000L;
        session.afk = afk;
        if (afk) {
            session.remainderMillis = 0;
            return;
        }
        String today = PlaytimeClock.today(settings.zone());
        if (!today.equals(session.day)) {
            // 先把跨天前累计的部分写到旧日期，再切换到新的一天
            flushSession(session, false, false);
            synchronized (session) {
                session.day = today;
                session.baselineToday = 0;
                session.todaySeconds = 0;
            }
            topCache.clear();
        }
        session.remainderMillis += elapsed;
        long seconds = session.remainderMillis / 1000L;
        if (seconds <= 0) {
            return;
        }
        session.remainderMillis -= seconds * 1000L;
        synchronized (session) {
            session.sessionSeconds += seconds;
            session.todaySeconds += seconds;
            session.pending += seconds;
        }
    }

    // ==================================================================
    // 刷新
    // ==================================================================

    private void flushAll(boolean direct) {
        if (isClosed() && !direct) {
            return;
        }
        processRetries(direct);
        for (Session session : sessions.values()) {
            flushSession(session, false, direct);
        }
    }

    private void flushSession(Session session, boolean quitting, boolean direct) {
        if (session == null) {
            return;
        }
        long delta;
        String day;
        String name;
        synchronized (session) {
            delta = session.pending;
            if (delta <= 0) {
                return;
            }
            session.pending = 0;
            day = session.day;
            name = session.name;
        }
        submit(new PendingWrite(session.uuid, name, day, delta), direct, quitting);
    }

    private void submit(PendingWrite write, boolean direct, boolean quitting) {
        if (direct) {
            writeDirect(write);
            return;
        }
        CompletableFuture<Void> future = scheduler.async(() -> writeDirect(write));
        lastWrites.put(write.uuid(), future);
        inflight.add(future);
        future.whenComplete((ignored, error) -> {
            inflight.remove(future);
            lastWrites.remove(write.uuid(), future);
            if (error != null) {
                retries.add(write);
                if (!quitting) {
                    logger.log(Level.FINE, "在线时长写入失败，已加入重试: " + write.uuid(), error);
                }
            } else {
                topCache.clear();
            }
        });
    }

    private void writeDirect(PendingWrite write) {
        PlaytimeStore current = store;
        if (current == null) {
            return;
        }
        try {
            current.add(write.uuid(), write.name(), write.day(), write.seconds());
        } catch (Exception error) {
            retries.add(write);
            logger.log(Level.WARNING, "在线时长写入失败: " + write.uuid(), error);
        }
    }

    private void processRetries(boolean direct) {
        if (retries.isEmpty()) {
            return;
        }
        List<PendingWrite> pending = new ArrayList<>(retries);
        retries.clear();
        for (PendingWrite write : pending) {
            PlaytimeStore current = store;
            if (current == null) {
                return;
            }
            try {
                current.add(write.uuid(), write.name(), write.day(), write.seconds());
            } catch (Exception error) {
                retries.add(write);
                if (direct) {
                    logger.log(Level.WARNING, "在线时长重试写入失败: " + write.uuid(), error);
                }
                return;
            }
        }
    }

    private void awaitInflight(int timeoutMillis) {
        if (timeoutMillis <= 0 || inflight.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        for (CompletableFuture<Void> future : inflight) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                logger.warning("等待在线时长写入超时，仍有 " + inflight.size() + " 个任务未完成");
                return;
            }
            try {
                future.get(remain, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // 失败的任务已经进入重试队列
            }
        }
    }

    /** 立即异步刷新一次（管理命令/插件停用前调用）。 */
    public void flushNow() {
        if (isEnabled()) {
            scheduler.async(() -> flushAll(false));
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public boolean isEnabled() {
        return enabled && !closed.get();
    }

    public boolean isStorageReady() {
        PlaytimeStore current = store;
        return current != null && current.isReady();
    }

    public String storageKind() {
        PlaytimeStore current = store;
        return current == null ? "none" : current.kind();
    }

    public PlaytimeSettings settings() {
        return settings;
    }

    public int onlineSessions() {
        return sessions.size();
    }

    /**
     * 总在线秒数（在线玩家 = 已加载基线 + 本次会话）。
     * 离线玩家返回最近一次加载/查询得到的缓存值；从未查询过则为 0。
     */
    public long totalSeconds(UUID uuid) {
        Session session = uuid == null ? null : sessions.get(uuid);
        if (session != null) {
            if (!session.loaded) {
                PlaytimeRecord cached = known.get(uuid);
                if (cached != null) {
                    return cached.totalSeconds() + session.sessionSeconds;
                }
            }
            return session.baselineTotal + session.sessionSeconds;
        }
        PlaytimeRecord record = uuid == null ? null : known.get(uuid);
        return record == null ? 0 : record.totalSeconds();
    }

    /** 今日在线秒数（按配置时区；离线玩家使用最近一次缓存）。 */
    public long todaySeconds(UUID uuid) {
        Session session = uuid == null ? null : sessions.get(uuid);
        if (session != null) {
            if (!session.loaded) {
                PlaytimeRecord cached = known.get(uuid);
                if (cached != null && cached.day().equals(session.day)) {
                    return cached.todaySeconds() + session.todaySeconds;
                }
            }
            return session.baselineToday + session.todaySeconds;
        }
        PlaytimeRecord record = uuid == null ? null : known.get(uuid);
        String today = PlaytimeClock.today(settings.zone());
        return record == null || !today.equals(record.day()) ? 0 : record.todaySeconds();
    }

    /** 本次会话在线秒数（离线玩家返回 0）。 */
    public long sessionSeconds(UUID uuid) {
        Session session = uuid == null ? null : sessions.get(uuid);
        return session == null ? 0 : session.sessionSeconds;
    }

    /** 是否处于 AFK（离线返回 false）。 */
    public boolean isAfk(UUID uuid) {
        Session session = uuid == null ? null : sessions.get(uuid);
        return session != null && session.afk;
    }

    /** 基线是否已从存储加载完成。 */
    public boolean isLoaded(UUID uuid) {
        Session session = uuid == null ? null : sessions.get(uuid);
        return session != null && session.loaded;
    }

    /** 从存储读取累计数据（不阻塞调用线程）。 */
    public CompletableFuture<PlaytimeRecord> stored(UUID uuid) {
        PlaytimeStore current = store;
        if (!isEnabled() || current == null || !current.isReady() || uuid == null) {
            return CompletableFuture.completedFuture(PlaytimeRecord.empty(uuid,
                    PlaytimeClock.today(settings.zone())));
        }
        CompletableFuture<Void> previous = lastWrites.get(uuid);
        CompletableFuture<Void> ordered = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((ignored, error) -> null);
        return ordered.thenCompose(ignored -> scheduler.async(() -> {
            try {
                return current.load(uuid, PlaytimeClock.today(settings.zone()));
            } catch (Exception error) {
                throw new IllegalStateException("读取在线时长失败", error);
            }
        })).thenApply(record -> {
            known.put(uuid, record);
            return record;
        });
    }

    /** 排行榜（带缓存）；存储未就绪返回空列表。 */
    public CompletableFuture<List<PlaytimeEntry>> top(PlaytimePeriod period, int limit) {
        PlaytimePeriod actual = period == null ? PlaytimePeriod.TOTAL : period;
        int size = Math.max(1, Math.min(100, limit));
        PlaytimeStore current = store;
        if (!isEnabled() || current == null || !current.isReady()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String day = PlaytimeClock.today(settings.zone());
        String weekStart = PlaytimeClock.weekStart(settings.zone());
        String bucket = switch (actual) {
            case TODAY -> day;
            case WEEK -> weekStart;
            case TOTAL -> "";
        };
        String key = actual + ":" + size + ":" + bucket;
        long now = System.currentTimeMillis();
        CachedTop cached = topCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return CompletableFuture.completedFuture(cached.entries());
        }
        return scheduler.async(() -> {
            try {
                return current.top(actual, day, weekStart, size);
            } catch (Exception error) {
                throw new IllegalStateException("读取在线时长排行失败", error);
            }
        }).thenApply(entries -> {
            List<PlaytimeEntry> snapshot = List.copyOf(entries);
            if (settings.topCacheSeconds() > 0) {
                topCache.put(key, new CachedTop(snapshot,
                        System.currentTimeMillis() + settings.topCacheSeconds() * 1000L));
            }
            return snapshot;
        });
    }

    /**
     * 追加时长奖励（签到、活动补偿等）：在线玩家并入当前会话，离线玩家直接写库。
     *
     * @param uuid    玩家 UUID
     * @param name    玩家名（离线写入时记录，可空）
     * @param seconds 奖励秒数（必须为正）
     */
    public void addBonus(UUID uuid, String name, long seconds) {
        if (!isEnabled() || uuid == null || seconds <= 0) {
            return;
        }
        Session session = sessions.get(uuid);
        if (session != null) {
            synchronized (session) {
                session.sessionSeconds += seconds;
                session.todaySeconds += seconds;
                session.pending += seconds;
            }
            if (name != null && !name.isBlank()) {
                session.name = name;
            }
            topCache.clear();
            return;
        }
        String bonusDay = PlaytimeClock.today(settings.zone());
        known.compute(uuid, (id, old) -> {
            String resolvedName = name == null || name.isBlank()
                    ? (old == null ? "" : old.name()) : name;
            if (old == null) {
                return new PlaytimeRecord(uuid, resolvedName, seconds, seconds, bonusDay);
            }
            long today = bonusDay.equals(old.day())
                    ? old.todaySeconds() + seconds : seconds;
            return new PlaytimeRecord(uuid, resolvedName,
                    old.totalSeconds() + seconds, today, bonusDay);
        });
        submit(new PendingWrite(uuid, name == null ? "" : name, bonusDay, seconds), false, false);
    }

    /** 追加时长奖励的便捷重载。 */
    public void addBonus(Player player, long seconds) {
        if (player != null) {
            addBonus(player.getUniqueId(), player.getName(), seconds);
        }
    }

    /** 人类可读格式化，例如 {@code 1天2小时}。 */
    public String format(long seconds) {
        return PlaytimeFormatter.format(seconds);
    }

    private boolean isClosed() {
        return closed.get();
    }
}
