package com.liuxingyu.liulib.playtime;

import java.util.List;
import java.util.UUID;

/**
 * 在线时长存储后端。
 *
 * <p>实现方只做“读写”，会话累计、AFK 判定与刷新节奏由 {@link PlaytimeService} 负责。
 * 方法允许抛异常，服务层会记录日志并在下一次刷新重试，不会因为一次数据库抖动丢数据。</p>
 */
public interface PlaytimeStore extends AutoCloseable {

    /** 后端是否可用。 */
    boolean isReady();

    /** 后端类型，用于状态展示（{@code mysql} / {@code yaml}）。 */
    String kind();

    /** 读取累计数据；无记录返回空记录（total/today 为 0）。 */
    PlaytimeRecord load(UUID uuid, String day) throws Exception;

    /** 累加时长（total 与 day 当天同时累加）。 */
    void add(UUID uuid, String name, String day, long seconds) throws Exception;

    /**
     * 排行榜。
     *
     * @param period    统计周期
     * @param day       今日日期（{@code yyyy-MM-dd}，按配置时区）
     * @param weekStart 本周周一日期，由服务层按配置时区算好，避免存储层依赖系统时区
     * @param limit     最大条数（1..100）
     */
    List<PlaytimeEntry> top(PlaytimePeriod period, String day, String weekStart, int limit) throws Exception;

    /** 落盘/提交（YAML 用；MySQL 默认无操作）。 */
    default void flush() throws Exception {
    }

    @Override
    default void close() throws Exception {
    }
}
