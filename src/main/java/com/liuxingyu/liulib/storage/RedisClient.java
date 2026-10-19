package com.liuxingyu.liulib.storage;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis 公共封装：缓存、跨服 Pub/Sub、分布式锁。
 *
 * <p>从 liupass 的 RedisClient、LiuChat 的 RedisBus、Liudungeon/LiuGuild 的 RedisBridge
 * 收敛而来。调用方只依赖本类，Jedis 已随 LiuLib 打包并 relocate，不会与服务端或
 * 其它插件的 Jedis 版本冲突。</p>
 *
 * <p>所有 key 与 channel 自动加 {@link RedisConfig#keyPrefix()} 前缀，避免同一台
 * Redis 被多个代理网络/插件共用时串服。前缀只加一次，业务代码传逻辑名即可。</p>
 *
 * <p><b>失败语义</b>：Redis 不可用时读返回 null / 0 / false，写静默失败并记录一次警告；
 * 跨服功能应据此退回本服逻辑，而不是阻塞主线程等待超时。</p>
 */
public final class RedisClient implements AutoCloseable {

    /** Pub/Sub 订阅线程的关闭标记。 */
    private static final class Subscription {
        final String channel;
        volatile Consumer<String> listener;
        volatile JedisPubSub pubSub;
        volatile Thread thread;
        volatile boolean active = true;

        Subscription(String channel, Consumer<String> listener) {
            this.channel = channel;
            this.listener = listener;
        }
    }

    private final Logger logger;
    private final RedisConfig config;
    private final UUID serverId = UUID.randomUUID();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> lockTokens = new ConcurrentHashMap<>();
    private final AtomicBoolean warned = new AtomicBoolean();

    private volatile JedisPool pool;
    private volatile DefaultJedisClientConfig clientConfig;
    private volatile boolean ready;

    public RedisClient(RedisConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 建立连接池并真实 PING；失败返回 false，插件继续以单服模式运行。 */
    public synchronized boolean init() {
        if (!config.enabled()) {
            logger.info("Redis 未启用，跨服缓存/广播由调用方降级");
            ready = false;
            return false;
        }
        if (ready && pool != null) {
            return true;
        }
        if (closed.get()) {
            return false;
        }
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(config.poolSize());
            poolConfig.setMaxIdle(config.poolSize());
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setJmxEnabled(false);
            poolConfig.setMaxWait(Duration.ofMillis(config.timeoutMs()));

            DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                    .database(config.database())
                    .connectionTimeoutMillis(config.timeoutMs())
                    .socketTimeoutMillis(config.timeoutMs());
            if (!config.password().isEmpty()) {
                builder.password(config.password());
            }
            this.clientConfig = builder.build();
            this.pool = new JedisPool(poolConfig, new HostAndPort(config.host(), config.port()),
                    clientConfig);
            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    throw new IllegalStateException("PING 返回 " + pong);
                }
            }
            ready = true;
            warned.set(false);
            logger.info("Redis 已连接: " + config.host() + ":" + config.port()
                    + "/" + config.database() + " prefix=" + config.keyPrefix());
            startSubscriptions();
            return true;
        } catch (Throwable error) {
            ready = false;
            logger.log(Level.WARNING, "Redis 连接失败，跨服缓存/广播不可用（插件继续运行）: "
                    + config.host() + ":" + config.port() + " - " + error.getMessage());
            closePool();
            return false;
        }
    }

    public boolean isReady() {
        JedisPool current = pool;
        return ready && current != null && !current.isClosed() && !closed.get();
    }

    /** 真实探活；失败返回 false。 */
    public boolean healthCheck() {
        if (!isReady()) {
            return false;
        }
        try {
            return call(jedis -> "PONG".equalsIgnoreCase(jedis.ping()), false);
        } catch (Throwable error) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ready = false;
        for (Subscription subscription : subscriptions.values()) {
            stop(subscription);
        }
        subscriptions.clear();
        closePool();
    }

    private void closePool() {
        JedisPool current = pool;
        pool = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException error) {
                logger.log(Level.FINE, "关闭 Redis 连接池失败", error);
            }
        }
    }

    public RedisConfig config() {
        return config;
    }

    /** 本服唯一标识，可放进跨服消息里过滤自己发出的广播。 */
    public UUID serverId() {
        return serverId;
    }

    /** 逻辑 key → 带前缀的真实 key。 */
    public String key(String logicalKey) {
        return config.key(logicalKey);
    }

    /** 逻辑 channel → 带前缀的真实 channel。 */
    public String channel(String logicalChannel) {
        return config.key(logicalChannel);
    }

    // ==================================================================
    // 字符串 / 计数
    // ==================================================================

    public String get(String key) {
        return call(jedis -> jedis.get(key(key)), null);
    }

    public byte[] getBytes(String key) {
        return call(jedis -> jedis.get(key(key).getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
    }

    public String set(String key, String value) {
        return call(jedis -> jedis.set(key(key), value), null);
    }

    public void setex(String key, long seconds, String value) {
        if (seconds <= 0) {
            return;
        }
        call(jedis -> jedis.setex(key(key), seconds, value), null);
    }

    public boolean setnx(String key, String value) {
        return call(jedis -> jedis.setnx(key(key), value) == 1L, false);
    }

    public long del(String... keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }
        String[] real = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            real[i] = key(keys[i]);
        }
        return call(jedis -> jedis.del(real), 0L);
    }

    public boolean exists(String key) {
        return call(jedis -> jedis.exists(key(key)), false);
    }

    public long incr(String key) {
        return call(jedis -> jedis.incr(key(key)), 0L);
    }

    public long incrBy(String key, long delta) {
        return call(jedis -> jedis.incrBy(key(key), delta), 0L);
    }

    public long decr(String key) {
        return call(jedis -> jedis.decr(key(key)), 0L);
    }

    public long expire(String key, long seconds) {
        return call(jedis -> jedis.expire(key(key), seconds), 0L);
    }

    public long ttl(String key) {
        return call(jedis -> jedis.ttl(key(key)), -2L);
    }

    // ==================================================================
    // Hash / Set / List
    // ==================================================================

    public String hget(String key, String field) {
        return call(jedis -> jedis.hget(key(key), field), null);
    }

    public long hset(String key, String field, String value) {
        return call(jedis -> jedis.hset(key(key), field, value), 0L);
    }

    public long hset(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return call(jedis -> jedis.hset(key(key), values), 0L);
    }

    public long hdel(String key, String... fields) {
        return call(jedis -> jedis.hdel(key(key), fields), 0L);
    }

    public Map<String, String> hgetAll(String key) {
        return call(jedis -> jedis.hgetAll(key(key)), Map.of());
    }

    public long hincrBy(String key, String field, long delta) {
        return call(jedis -> jedis.hincrBy(key(key), field, delta), 0L);
    }

    public long sadd(String key, String... members) {
        return call(jedis -> jedis.sadd(key(key), members), 0L);
    }

    public long srem(String key, String... members) {
        return call(jedis -> jedis.srem(key(key), members), 0L);
    }

    public boolean sismember(String key, String member) {
        return call(jedis -> jedis.sismember(key(key), member), false);
    }

    public long scard(String key) {
        return call(jedis -> jedis.scard(key(key)), 0L);
    }

    public Set<String> smembers(String key) {
        return call(jedis -> jedis.smembers(key(key)), Set.of());
    }

    public long lpush(String key, String... values) {
        return call(jedis -> jedis.lpush(key(key), values), 0L);
    }

    public long rpush(String key, String... values) {
        return call(jedis -> jedis.rpush(key(key), values), 0L);
    }

    public long llen(String key) {
        return call(jedis -> jedis.llen(key(key)), 0L);
    }

    public List<String> lrange(String key, long start, long stop) {
        return call(jedis -> jedis.lrange(key(key), start, stop), List.of());
    }

    public String lpop(String key) {
        return call(jedis -> jedis.lpop(key(key)), null);
    }

    public String rpop(String key) {
        return call(jedis -> jedis.rpop(key(key)), null);
    }

    /** 执行 Lua 脚本；keys/args 会自动加 key 前缀。 */
    public Object eval(String script, List<String> keys, List<String> args) {
        List<String> realKeys = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                realKeys.add(key(key));
            }
        }
        return call(jedis -> jedis.eval(script,
                realKeys.isEmpty() ? List.of() : realKeys,
                args == null ? List.of() : args), null);
    }

    // ==================================================================
    // Pub/Sub
    // ==================================================================

    /**
     * 订阅逻辑 channel。可多次调用订阅多个 channel；同一 channel 重复订阅会替换监听器。
     * 回调在订阅线程执行，调用方需要时自行切线程。
     */
    public void subscribe(String channel, Consumer<String> listener) {
        if (channel == null || channel.isBlank() || listener == null) {
            return;
        }
        String full = channel(channel);
        Subscription subscription = subscriptions.computeIfAbsent(full,
                ignored -> new Subscription(full, listener));
        subscription.listener = listener;
        subscription.active = true;
        if (isReady()) {
            start(subscription);
        }
    }

    /** 取消订阅。 */
    public void unsubscribe(String channel) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        Subscription subscription = subscriptions.remove(channel(channel));
        if (subscription != null) {
            stop(subscription);
        }
    }

    /** 当前已订阅的 channel（逻辑名，不含前缀）。 */
    public Set<String> subscribedChannels() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String channel : subscriptions.keySet()) {
            String prefix = config.keyPrefix();
            out.add(prefix.isEmpty() || !channel.startsWith(prefix)
                    ? channel : channel.substring(prefix.length()));
        }
        return Collections.unmodifiableSet(out);
    }

    /** 发布消息；返回接收者数量，失败返回 0。 */
    public long publish(String channel, String message) {
        if (channel == null || channel.isBlank() || message == null) {
            return 0;
        }
        return call(jedis -> jedis.publish(channel(channel), message), 0L);
    }

    public boolean isSubscribed() {
        return subscriptions.values().stream().anyMatch(RedisClient::isActive);
    }

    /** 指定逻辑 channel 是否已真正进入订阅状态。 */
    public boolean isSubscribed(String channel) {
        if (channel == null) {
            return false;
        }
        Subscription subscription = subscriptions.get(channel(channel));
        return subscription != null && isActive(subscription);
    }

    private static boolean isActive(Subscription subscription) {
        JedisPubSub pubSub = subscription.pubSub;
        return pubSub != null && pubSub.isSubscribed();
    }

    private void startSubscriptions() {
        for (Subscription subscription : subscriptions.values()) {
            start(subscription);
        }
    }

    private void start(Subscription subscription) {
        if (!subscription.active || closed.get()) {
            return;
        }
        Thread existing = subscription.thread;
        if (existing != null && existing.isAlive()) {
            return;
        }
        synchronized (subscription) {
            existing = subscription.thread;
            if (existing != null && existing.isAlive()) {
                return;
            }
            Thread thread = new Thread(() -> subscribeLoop(subscription),
                    "LiuLib-redis-sub-" + subscription.channel);
            thread.setDaemon(true);
            subscription.thread = thread;
            thread.start();
        }
    }

    private void stop(Subscription subscription) {
        subscription.active = false;
        JedisPubSub pubSub = subscription.pubSub;
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (RuntimeException ignored) {
                // 连接已断开
            }
        }
        Thread thread = subscription.thread;
        subscription.thread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void subscribeLoop(Subscription subscription) {
        long backoff = 1000L;
        while (subscription.active && !closed.get()) {
            Jedis jedis = null;
            try {
                DefaultJedisClientConfig client = clientConfig;
                if (client == null) {
                    return;
                }
                jedis = new Jedis(new HostAndPort(config.host(), config.port()), client);
                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        Consumer<String> listener = subscription.listener;
                        if (listener == null) {
                            return;
                        }
                        try {
                            listener.accept(message);
                        } catch (Throwable error) {
                            logger.log(Level.WARNING, "Redis 订阅回调失败 channel=" + channel, error);
                        }
                    }

                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        logger.info("Redis 已订阅: " + channel);
                        warned.set(false);
                    }
                };
                if (!subscription.active || closed.get()) {
                    break;
                }
                subscription.pubSub = pubSub;
                backoff = 1000L;
                jedis.subscribe(pubSub, subscription.channel);
                // 正常 unsubscribe 后返回；running 仍为 true 时按断线重连处理
            } catch (Throwable error) {
                if (closed.get() || !subscription.active) {
                    break;
                }
                warnOnce(error);
            } finally {
                subscription.pubSub = null;
                if (jedis != null) {
                    try {
                        jedis.close();
                    } catch (RuntimeException ignored) {
                        // 忽略
                    }
                }
            }
            if (closed.get() || !subscription.active) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, 30_000L);
        }
    }

    // ==================================================================
    // 分布式锁
    // ==================================================================

    /**
     * 尝试获取分布式锁（SET NX EX），成功返回 true。
     *
     * <p>锁 key 自动加 {@code lock:} 前缀；Redis 不可用时返回 false（调用方按“抢锁失败”处理）。</p>
     */
    public boolean tryLock(String key, int ttlSeconds) {
        if (!isReady() || key == null || ttlSeconds <= 0) {
            return false;
        }
        String token = UUID.randomUUID().toString();
        boolean locked = call(jedis -> "OK".equals(jedis.set(key("lock:" + key), token,
                redis.clients.jedis.params.SetParams.setParams().nx().ex(ttlSeconds))), false);
        if (locked) {
            lockTokens.put(key, token);
        }
        return locked;
    }

    /** 释放锁（校验 token，避免误删别人的锁）。 */
    public void unlock(String key) {
        String token = lockTokens.remove(key);
        if (token == null || !isReady()) {
            return;
        }
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then "
                + "return redis.call('del', KEYS[1]) else return 0 end";
        call(jedis -> jedis.eval(script, List.of(key("lock:" + key)), List.of(token)), null);
    }

    // ==================================================================
    // 内部执行
    // ==================================================================

    private <T> T call(Function<Jedis, T> action, T fallback) {
        JedisPool current = pool;
        if (!isReady() || current == null) {
            return fallback;
        }
        try (Jedis jedis = current.getResource()) {
            return action.apply(jedis);
        } catch (Throwable error) {
            warnOnce(error);
            return fallback;
        }
    }

    private void warnOnce(Throwable error) {
        if (warned.compareAndSet(false, true)) {
            logger.log(Level.WARNING, "Redis 操作失败（跨服功能临时降级，后台自动重试）: "
                    + config.host() + ":" + config.port(), error);
        } else {
            logger.log(Level.FINE, "Redis 操作失败（重试中）", error);
        }
    }
}
