package com.liuxingyu.liulib.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paper / Folia 统一调度器。
 *
 * <p>Bukkit 的 {@code BukkitScheduler} 在 Folia 上不可用，而 Paper 1.20+ 已经提供
 * Async / GlobalRegion / Region / Entity 四种调度器，因此这里直接使用 Paper API，
 * 业务插件不需要再判断“是不是 Folia”。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>数据库、HTTP、文件 IO 用 {@link #async(Runnable)}；</li>
 *   <li>无实体归属的 Bukkit 操作（广播、控制台命令）用 {@link #global(Runnable)}；</li>
 *   <li>玩家相关操作用 {@link #entity(Entity, Runnable)}，玩家离线时自动丢弃；</li>
 *   <li>方块/容器操作用 {@link #region(Location, Runnable)}；</li>
 *   <li>所有延迟/循环任务返回 {@link TaskHandle}，{@link #shutdown()} 统一取消。</li>
 * </ul>
 */
public final class LiuLibScheduler {

    private final Plugin plugin;
    private final Logger logger;
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final boolean folia;

    public LiuLibScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.folia = detectFolia();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return Bukkit.getServer() != null && "Folia".equalsIgnoreCase(Bukkit.getServer().getName());
        }
    }

    /** 当前服务端是否为 Folia（含 Folia 分支）。 */
    public boolean isFolia() {
        return folia;
    }

    /** 调度器是否已关闭（插件停用后为 true）。 */
    public boolean isClosed() {
        return closed.get() || !plugin.isEnabled();
    }

    // ==================================================================
    // 异步
    // ==================================================================

    /** 异步执行，返回的 Future 可用于串接后续逻辑；异常会记录并传给 Future。 */
    public CompletableFuture<Void> async(Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (isClosed()) {
            future.completeExceptionally(new IllegalStateException("调度器已关闭"));
            return future;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    action.run();
                    future.complete(null);
                } catch (Throwable error) {
                    logger.log(Level.WARNING, "异步任务异常", error);
                    future.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    /** 异步执行并返回结果。 */
    public <T> CompletableFuture<T> async(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        async(() -> future.complete(supplier.get())).whenComplete((ignored, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** 异步延迟执行（tick）。 */
    public TaskHandle asyncLater(long delayTicks, Runnable action) {
        if (isClosed()) {
            return TaskHandle.none();
        }
        try {
            ScheduledTask task = plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                    ignored -> runSafely(action), Math.max(0L, delayTicks) * 50L, TimeUnit.MILLISECONDS);
            return wrap(task);
        } catch (Throwable error) {
            logger.log(Level.WARNING, "提交异步延迟任务失败", error);
            return TaskHandle.none();
        }
    }

    /** 异步循环执行（tick 间隔）。 */
    public TaskHandle asyncTimer(long delayTicks, long periodTicks, Runnable action) {
        if (isClosed()) {
            return TaskHandle.none();
        }
        try {
            ScheduledTask task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                    ignored -> runSafely(action),
                    Math.max(0L, delayTicks) * 50L,
                    Math.max(1L, periodTicks) * 50L,
                    TimeUnit.MILLISECONDS);
            return track(task);
        } catch (Throwable error) {
            logger.log(Level.WARNING, "提交异步循环任务失败", error);
            return TaskHandle.none();
        }
    }

    // ==================================================================
    // 全局主线程
    // ==================================================================

    /** 在全局区域线程立即执行；调度器关闭返回 false。 */
    public boolean global(Runnable action) {
        if (isClosed()) {
            return false;
        }
        try {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> runSafely(action));
            return true;
        } catch (Throwable error) {
            logger.log(Level.WARNING, "提交全局任务失败", error);
            return false;
        }
    }

    /** 全局区域线程延迟执行（tick）。 */
    public TaskHandle globalLater(long delayTicks, Runnable action) {
        if (isClosed()) {
            return TaskHandle.none();
        }
        try {
            ScheduledTask task = plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                    ignored -> runSafely(action), Math.max(0L, delayTicks));
            return wrap(task);
        } catch (Throwable error) {
            logger.log(Level.WARNING, "提交全局延迟任务失败", error);
            return TaskHandle.none();
        }
    }

    /** 全局区域线程循环执行（tick 间隔）。 */
    public TaskHandle globalTimer(long delayTicks, long periodTicks, Runnable action) {
        if (isClosed()) {
            return TaskHandle.none();
        }
        try {
            ScheduledTask task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    ignored -> runSafely(action), Math.max(0L, delayTicks), Math.max(1L, periodTicks));
            return track(task);
        } catch (Throwable error) {
            logger.log(Level.WARNING, "提交全局循环任务失败", error);
            return TaskHandle.none();
        }
    }

    // ==================================================================
    // 实体 / 区域
    // ==================================================================

    /** 在实体所在线程执行；实体离线/已移除返回 false。 */
    public boolean entity(Entity entity, Runnable action) {
        if (isClosed() || !valid(entity)) {
            return false;
        }
        try {
            entity.getScheduler().run(plugin, ignored -> runSafely(action), null);
            return true;
        } catch (Throwable error) {
            logger.log(Level.FINE, "提交实体任务失败", error);
            return false;
        }
    }

    /** 在实体所在线程延迟执行；实体离线/已移除返回空句柄。 */
    public TaskHandle entityLater(Entity entity, long delayTicks, Runnable action) {
        if (isClosed() || !valid(entity)) {
            return TaskHandle.none();
        }
        try {
            ScheduledTask task = entity.getScheduler().runDelayed(plugin,
                    ignored -> runSafely(action), null, Math.max(0L, delayTicks));
            return wrap(task);
        } catch (Throwable error) {
            logger.log(Level.FINE, "提交实体延迟任务失败", error);
            return TaskHandle.none();
        }
    }

    /** 在实体所在线程循环执行；实体离线后任务自动结束。 */
    public TaskHandle entityTimer(Entity entity, long delayTicks, long periodTicks, Runnable action) {
        if (isClosed() || !valid(entity)) {
            return TaskHandle.none();
        }
        try {
            ScheduledTask task = entity.getScheduler().runAtFixedRate(plugin,
                    ignored -> runSafely(action), null,
                    Math.max(0L, delayTicks), Math.max(1L, periodTicks));
            return track(task);
        } catch (Throwable error) {
            logger.log(Level.FINE, "提交实体循环任务失败", error);
            return TaskHandle.none();
        }
    }

    /** 在坐标所属区域线程执行；世界为空返回 false。 */
    public boolean region(Location location, Runnable action) {
        if (isClosed() || location == null || location.getWorld() == null) {
            return false;
        }
        try {
            plugin.getServer().getRegionScheduler().run(plugin, location, ignored -> runSafely(action));
            return true;
        } catch (Throwable error) {
            logger.log(Level.FINE, "提交区域任务失败", error);
            return false;
        }
    }

    /** 在方块坐标所属区域线程延迟执行。 */
    public TaskHandle regionLater(Location location, long delayTicks, Runnable action) {
        if (isClosed() || location == null || location.getWorld() == null) {
            return TaskHandle.none();
        }
        try {
            ScheduledTask task = plugin.getServer().getRegionScheduler().runDelayed(plugin, location,
                    ignored -> runSafely(action), Math.max(0L, delayTicks));
            return wrap(task);
        } catch (Throwable error) {
            logger.log(Level.FINE, "提交区域延迟任务失败", error);
            return TaskHandle.none();
        }
    }

    /** 在区块所属区域线程执行。 */
    public boolean region(World world, int chunkX, int chunkZ, Runnable action) {
        if (isClosed() || world == null) {
            return false;
        }
        try {
            plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ,
                    ignored -> runSafely(action));
            return true;
        } catch (Throwable error) {
            logger.log(Level.FINE, "提交区块任务失败", error);
            return false;
        }
    }

    /** 关闭调度器，取消所有由本类提交的任务；可重复调用。 */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ScheduledTask task : tasks) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 插件停用阶段不再抛异常
            }
        }
        tasks.clear();
    }

    /** 一次性任务不需要常驻任务表；Bukkit 停用插件时会统一取消。 */
    private TaskHandle wrap(ScheduledTask task) {
        return task == null ? TaskHandle.none() : new TaskHandle(task, null);
    }

    /** 循环任务登记到任务表，shutdown() 时统一取消。 */
    private TaskHandle track(ScheduledTask task) {
        if (task == null) {
            return TaskHandle.none();
        }
        tasks.add(task);
        return new TaskHandle(task, () -> tasks.remove(task));
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            logger.log(Level.WARNING, "调度任务异常", error);
        }
    }

    private boolean valid(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return false;
        }
        return !(entity instanceof Player player) || player.isOnline();
    }
}
