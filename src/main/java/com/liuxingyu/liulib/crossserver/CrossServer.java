package com.liuxingyu.liulib.crossserver;

import com.liuxingyu.liulib.storage.RedisClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨服消息总线：在已有 {@link RedisClient} 的 pub/sub 之上提供结构化消息。
 *
 * <pre>{@code
 * CrossServer cross = LiuLib.crossServer();
 * if (cross.available()) {
 *     cross.subscribe("myshop", message -> {
 *         // 回调在 Redis 订阅线程，操作 Bukkit 对象请切回主线程
 *         LiuLib.scheduler().global(() -> handle(message));
 *     });
 *     cross.send("myshop", "refresh", Map.of("player", uuid.toString()));
 * }
 * }</pre>
 *
 * <p>消息包含来源服务器 ID，默认过滤掉自己发出的消息，避免“本服广播又处理一次”。
 * 需要处理本地消息时，在发送处直接调用本地逻辑，或使用 {@link #sendLocal}。</p>
 *
 * <p>编码使用 DataOutputStream + Base64，不依赖 JSON 库；单条消息最大 64 KiB，
 * 键/值长度也有限制，防止恶意 Redis 消息撑爆内存。</p>
 */
public final class CrossServer implements AutoCloseable {

    private static final int MAGIC = 0x4C4C4353; // LLCS
    private static final int VERSION = 1;
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final int MAX_ACTION_LENGTH = 128;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 8 * 1024;
    private static final int MAX_PAYLOAD_ENTRIES = 128;

    /** 跨服消息。 */
    public record Message(String source, String action, Map<String, String> payload, long timestamp) {

        public Message {
            source = source == null ? "" : source;
            action = action == null ? "" : action;
            payload = payload == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
            timestamp = timestamp <= 0 ? System.currentTimeMillis() : timestamp;
        }

        /** 按 key 取字符串；缺失返回空串。 */
        public String get(String key) {
            String value = payload.get(key);
            return value == null ? "" : value;
        }

        public String get(String key, String fallback) {
            String value = payload.get(key);
            return value == null ? fallback : value;
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onMessage(Message message);
    }

    private final RedisClient redis;
    private final String serverId;
    private final Map<String, CopyOnWriteArrayList<Listener>> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CrossServer(RedisClient redis) {
        this.redis = redis;
        this.serverId = redis == null ? "" : redis.serverId().toString();
    }

    /** Redis 是否可用；不可用时 subscribe/send 都是空操作。 */
    public boolean available() {
        return redis != null && redis.isReady();
    }

    public String serverId() {
        return serverId;
    }

    // ==================================================================
    // 订阅
    // ==================================================================

    /**
     * 订阅一个 channel；同一 channel 可有多个监听器，共用一条 Redis 订阅。
     *
     * @return 可用于取消本次订阅的句柄
     */
    public Subscription subscribe(String channel, Listener listener) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(listener, "listener");
        String key = channel.trim();
        if (key.isEmpty() || closed.get()) {
            return Subscription.NOOP;
        }
        boolean first;
        synchronized (listeners) {
            CopyOnWriteArrayList<Listener> list = listeners.computeIfAbsent(key,
                    ignored -> new CopyOnWriteArrayList<>());
            first = list.isEmpty();
            list.add(listener);
        }
        if (first && available()) {
            redis.subscribe(key, raw -> dispatch(key, raw));
        }
        return new Subscription(this, key, listener);
    }

    /** 取消该 channel 的全部监听器并退订。 */
    public int unsubscribe(String channel) {
        if (channel == null) {
            return 0;
        }
        String key = channel.trim();
        CopyOnWriteArrayList<Listener> list;
        synchronized (listeners) {
            list = listeners.remove(key);
        }
        if (list == null) {
            return 0;
        }
        if (redis != null) {
            redis.unsubscribe(key);
        }
        return list.size();
    }

    /** 当前监听的 channel。 */
    public Set<String> channels() {
        return Collections.unmodifiableSet(listeners.keySet());
    }

    public int listenerCount() {
        int count = 0;
        for (CopyOnWriteArrayList<Listener> list : listeners.values()) {
            count += list.size();
        }
        return count;
    }

    private void dispatch(String channel, String raw) {
        Message message = decode(raw);
        if (message == null) {
            return;
        }
        if (message.source().equals(serverId)) {
            return; // 忽略自己发出的消息
        }
        CopyOnWriteArrayList<Listener> list = listeners.get(channel);
        if (list == null) {
            return;
        }
        for (Listener listener : list) {
            try {
                listener.onMessage(message);
            } catch (Throwable ignored) {
                // 单个监听器异常不影响其它监听器
            }
        }
    }

    // ==================================================================
    // 发送
    // ==================================================================

    /**
     * 广播到所有服务器（包括本服；本服的 {@link #subscribe} 会忽略它）。
     *
     * @return Redis 报告的接收者数量；Redis 不可用时为 0
     */
    public long send(String channel, String action, Map<String, String> payload) {
        if (!available() || closed.get() || channel == null || channel.isBlank()) {
            return 0L;
        }
        String encoded = encode(serverId, action, payload, System.currentTimeMillis());
        if (encoded == null) {
            return 0L;
        }
        return redis.publish(channel.trim(), encoded);
    }

    public long send(String channel, String action) {
        return send(channel, action, Map.of());
    }

    /**
     * 只在本进程内分发，不经过 Redis；用于“本服立即执行，其它服走广播”的场景。
     *
     * @return 收到消息的本地监听器数量
     */
    public int sendLocal(String channel, Message message) {
        if (channel == null || message == null) {
            return 0;
        }
        CopyOnWriteArrayList<Listener> list = listeners.get(channel.trim());
        if (list == null) {
            return 0;
        }
        int delivered = 0;
        for (Listener listener : list) {
            try {
                listener.onMessage(message);
                delivered++;
            } catch (Throwable ignored) {
                // 忽略单个监听器异常
            }
        }
        return delivered;
    }

    // ==================================================================
    // 编码
    // ==================================================================

    static String encode(String source, String action, Map<String, String> payload, long timestamp) {
        String safeAction = action == null ? "" : action;
        if (safeAction.length() > MAX_ACTION_LENGTH) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (payload != null) {
            for (Map.Entry<String, String> entry : payload.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (entry.getKey().length() > MAX_KEY_LENGTH
                        || entry.getValue().length() > MAX_VALUE_LENGTH) {
                    return null;
                }
                values.put(entry.getKey(), entry.getValue());
                if (values.size() > MAX_PAYLOAD_ENTRIES) {
                    return null;
                }
            }
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeUTF(source == null ? "" : source);
            out.writeUTF(safeAction);
            out.writeLong(timestamp);
            out.writeInt(values.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
            out.flush();
            byte[] raw = bytes.toByteArray();
            if (raw.length > MAX_MESSAGE_BYTES) {
                return null;
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (IOException error) {
            return null;
        }
    }

    static Message decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (raw.length > MAX_MESSAGE_BYTES) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
                if (in.readInt() != MAGIC || in.readByte() != VERSION) {
                    return null;
                }
                String source = in.readUTF();
                String action = in.readUTF();
                long timestamp = in.readLong();
                int size = in.readInt();
                if (size < 0 || size > MAX_PAYLOAD_ENTRIES) {
                    return null;
                }
                Map<String, String> payload = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    String key = in.readUTF();
                    String value = in.readUTF();
                    if (key.length() > MAX_KEY_LENGTH || value.length() > MAX_VALUE_LENGTH) {
                        return null;
                    }
                    payload.put(key, value);
                }
                if (in.available() > 0) {
                    return null; // 多余字节，拒绝
                }
                return new Message(source, action, payload, timestamp);
            }
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (String channel : new ArrayList<>(listeners.keySet())) {
            unsubscribe(channel);
        }
        listeners.clear();
    }

    /** 单次订阅句柄。 */
    public static final class Subscription implements AutoCloseable {

        private static final Subscription NOOP = new Subscription(null, null, null);

        private final CrossServer owner;
        private final String channel;
        private final Listener listener;
        private final AtomicBoolean removed = new AtomicBoolean();

        private Subscription(CrossServer owner, String channel, Listener listener) {
            this.owner = owner;
            this.channel = channel;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (owner == null || !removed.compareAndSet(false, true)) {
                return;
            }
            boolean empty = false;
            synchronized (owner.listeners) {
                CopyOnWriteArrayList<Listener> list = owner.listeners.get(channel);
                if (list != null) {
                    list.remove(listener);
                    if (list.isEmpty()) {
                        owner.listeners.remove(channel, list);
                        empty = true;
                    }
                }
            }
            if (empty && owner.redis != null) {
                owner.redis.unsubscribe(channel);
            }
        }

        public boolean isActive() {
            return owner != null && !removed.get();
        }
    }
}
