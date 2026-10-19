package com.liuxingyu.liulib.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 调度任务句柄：用于在插件停用、重载或业务结束时取消任务。
 *
 * <p>与直接用 {@link ScheduledTask} 相比，句柄额外保证：</p>
 * <ul>
 *   <li>{@link #cancel()} 可重复调用；</li>
 *   <li>调度器关闭后拿到的句柄是“已取消”的空句柄，调用方不需要判空。</li>
 * </ul>
 */
public final class TaskHandle implements AutoCloseable {

    private static final TaskHandle NONE = new TaskHandle(null, null);

    private final ScheduledTask task;
    private final Runnable onCancel;

    TaskHandle(ScheduledTask task, Runnable onCancel) {
        this.task = task;
        this.onCancel = onCancel;
    }

    static TaskHandle none() {
        return NONE;
    }

    /** 取消任务；已取消或空句柄返回 false。 */
    public boolean cancel() {
        if (task == null) {
            return false;
        }
        ScheduledTask.CancelledState state = task.cancel();
        boolean cancelled = state == ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                || state == ScheduledTask.CancelledState.NEXT_RUNS_CANCELLED;
        if (onCancel != null && (cancelled || task.isCancelled())) {
            onCancel.run();
        }
        return cancelled;
    }

    /** 任务是否已被取消（空句柄视为已取消）。 */
    public boolean isCancelled() {
        return task == null || task.isCancelled();
    }

    /** 任务是否仍在等待执行。 */
    public boolean isPending() {
        return task != null && !task.isCancelled();
    }

    @Override
    public void close() {
        cancel();
    }
}
