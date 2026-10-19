package com.liuxingyu.liulib.cooldown;

import com.liuxingyu.liulib.scheduler.LiuLibScheduler;
import com.liuxingyu.liulib.scheduler.TaskHandle;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 通用冷却表：命令、商店购买、签到、传送等场景共用。
 *
 * <p>冷却按 {@code (玩家, 动作)} 维度隔离，不会出现“用了 A 命令导致 B 命令进冷却”。
 * 计时使用 {@link System#nanoTime()}，不受系统时间调整影响；过期条目由可选的后台
 * 清理任务移除，也可以完全不启用清理（读取时按过期判断）。</p>
 *
 * <p>线程：所有方法线程安全；{@link #trigger} 的“检查并写入”是原子的，
 * 多个线程同时触发同一个冷却只有一个会成功。</p>
 */
public final class Cooldowns implements AutoCloseable {

    private final LiuLibScheduler scheduler;
    private final LongSupplier clock;
    private final Map<Key, Long> expiries = new ConcurrentHashMap<>();

    private volatile TaskHandle cleanupTask;

    public Cooldowns(LiuLibScheduler scheduler) {
        this(scheduler, System::nanoTime);
    }

    /** 测试/自定义时钟用；生产环境请使用 {@link #Cooldowns(LiuLibScheduler)}。 */
    Cooldowns(LiuLibScheduler scheduler, LongSupplier clock) {
        this.scheduler = scheduler;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 是否仍在冷却。 */
    public boolean isCooling(String action, UUID player) {
        return remainingMillis(action, player) > 0;
    }

    public boolean isCooling(String action, Player player) {
        return player != null && isCooling(action, player.getUniqueId());
    }

    /** 是否可以使用（与 {@link #isCooling} 相反）。 */
    public boolean check(String action, UUID player) {
        return !isCooling(action, player);
    }

    public boolean check(String action, Player player) {
        return player != null && check(action, player.getUniqueId());
    }

    /** 剩余毫秒；未冷却返回 0。 */
    public long remainingMillis(String action, UUID player) {
        if (action == null || action.isBlank() || player == null) {
            return 0L;
        }
        Long expiry = expiries.get(new Key(player, action));
        if (expiry == null) {
            return 0L;
        }
        long now = clock.getAsLong();
        if (expiry <= now) {
            expiries.remove(new Key(player, action), expiry);
            return 0L;
        }
        long remain;
        try {
            remain = Math.subtractExact(expiry, now);
        } catch (ArithmeticException overflow) {
            remain = Long.MAX_VALUE;
        }
        return remain / 1_000_000L;
    }

    public long remainingMillis(String action, Player player) {
        return player == null ? 0L : remainingMillis(action, player.getUniqueId());
    }

    /** 剩余秒数（向上取整，便于显示“还需 3 秒”）。 */
    public long remainingSeconds(String action, UUID player) {
        long millis = remainingMillis(action, player);
        if (millis <= 0) {
            return 0L;
        }
        long seconds = millis / 1000L;
        return millis % 1000L == 0 ? seconds : seconds + 1L;
    }

    public long remainingSeconds(String action, Player player) {
        return player == null ? 0L : remainingSeconds(action, player.getUniqueId());
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 尝试触发：不在冷却时写入并返回 true；仍在冷却返回 false。
     *
     * @param action   动作名（建议加业务前缀，如 {@code shop.buy}）
     * @param player   玩家
     * @param duration 冷却时长
     * @param unit     时间单位
     */
    public boolean trigger(String action, UUID player, long duration, TimeUnit unit) {
        if (action == null || action.isBlank() || player == null || unit == null) {
            return false;
        }
        long nanos = unit.toNanos(Math.max(0L, duration));
        if (nanos <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        AtomicBoolean applied = new AtomicBoolean();
        Key key = new Key(player, action);
        expiries.compute(key, (ignored, expiry) -> {
            if (expiry != null && expiry > now) {
                return expiry;
            }
            applied.set(true);
            long target;
            try {
                target = Math.addExact(now, nanos);
            } catch (ArithmeticException overflow) {
                target = Long.MAX_VALUE;
            }
            return target;
        });
        return applied.get();
    }

    public boolean trigger(String action, Player player, long duration, TimeUnit unit) {
        return player != null && trigger(action, player.getUniqueId(), duration, unit);
    }

    /** 直接设置冷却（无论当前是否在冷却），用于管理命令/惩罚。 */
    public void set(String action, UUID player, long duration, TimeUnit unit) {
        if (action == null || action.isBlank() || player == null || unit == null) {
            return;
        }
        long nanos = unit.toNanos(Math.max(0L, duration));
        long now = clock.getAsLong();
        long target;
        try {
            target = Math.addExact(now, nanos);
        } catch (ArithmeticException overflow) {
            target = Long.MAX_VALUE;
        }
        if (target <= now) {
            expiries.remove(new Key(player, action));
        } else {
            expiries.put(new Key(player, action), target);
        }
    }

    public void set(String action, Player player, long duration, TimeUnit unit) {
        if (player != null) {
            set(action, player.getUniqueId(), duration, unit);
        }
    }

    /** 清除一个冷却。 */
    public void clear(String action, UUID player) {
        if (action != null && player != null) {
            expiries.remove(new Key(player, action));
        }
    }

    public void clear(String action, Player player) {
        if (player != null) {
            clear(action, player.getUniqueId());
        }
    }

    /** 清除某玩家的全部冷却（退出/重生/权限变更时用）。 */
    public void clear(UUID player) {
        if (player != null) {
            expiries.keySet().removeIf(key -> key.player().equals(player));
        }
    }

    public void clear(Player player) {
        if (player != null) {
            clear(player.getUniqueId());
        }
    }

    /** 清除某个动作的全部冷却（重载配置时用）。 */
    public void clearAction(String action) {
        if (action != null) {
            expiries.keySet().removeIf(key -> key.action().equals(action));
        }
    }

    /** 当前记录的冷却条目数（包含刚过期但尚未清理的）。 */
    public int size() {
        return expiries.size();
    }

    /** 立即清理已过期条目。 */
    public int prune() {
        long now = clock.getAsLong();
        int before = expiries.size();
        expiries.entrySet().removeIf(entry -> entry.getValue() <= now);
        return Math.max(0, before - expiries.size());
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 启动后台清理任务；已经启动时先取消旧任务。
     *
     * <p>不调用也能正常工作（读取时判断过期），只是过期条目会暂时占用内存。</p>
     */
    public void startCleanup(long intervalTicks) {
        if (scheduler == null || scheduler.isClosed()) {
            return;
        }
        TaskHandle old = cleanupTask;
        if (old != null) {
            old.cancel();
        }
        long ticks = Math.max(20L, intervalTicks);
        cleanupTask = scheduler.asyncTimer(ticks, ticks, this::prune);
    }

    public void stopCleanup() {
        TaskHandle task = cleanupTask;
        cleanupTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    /** 停止清理任务并清空全部冷却。 */
    public void shutdown() {
        stopCleanup();
        expiries.clear();
    }

    @Override
    public void close() {
        shutdown();
    }

    private record Key(UUID player, String action) {
    }
}
