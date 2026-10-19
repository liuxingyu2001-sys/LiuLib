package com.liuxingyu.liulib.api;

import com.liuxingyu.liulib.config.Configs;
import com.liuxingyu.liulib.config.LiuLibConfig;
import com.liuxingyu.liulib.cooldown.Cooldowns;
import com.liuxingyu.liulib.crossserver.CrossServer;
import com.liuxingyu.liulib.economy.EconomyService;
import com.liuxingyu.liulib.item.ItemOps;
import com.liuxingyu.liulib.item.ItemService;
import com.liuxingyu.liulib.placeholder.Placeholders;
import com.liuxingyu.liulib.playtime.PlaytimeService;
import com.liuxingyu.liulib.scheduler.LiuLibScheduler;
import com.liuxingyu.liulib.service.ServiceRegistry;
import com.liuxingyu.liulib.storage.MySql;
import com.liuxingyu.liulib.storage.RedisClient;
import com.liuxingyu.liulib.storage.Sqlite;
import com.liuxingyu.liulib.text.TextService;
import com.liuxingyu.liulib.ui.SparrowUi;

import java.io.File;

/**
 * LiuLib 对其它插件暴露的稳定入口。
 *
 * <p>推荐通过 {@link com.liuxingyu.liulib.LiuLib#api()} 获取，而不是直接依赖
 * {@code LiuLibPlugin}，这样实现类可以继续演进。</p>
 *
 * <p>注意：所有 getter 在 LiuLib 启用期间都非 null；MySQL / Redis 未启用或连接失败时
 * 返回的对象仍然可用，但 {@code isReady()} 为 false，调用方应按“无缓存/单服模式”降级。</p>
 */
public interface LiuLibApi {

    /** 物品模块：CE 解析、名称解析、序列化、物品规格解析。 */
    ItemService items();

    /** 文本模块：MiniMessage + 传统色码、占位符、messages.yml。 */
    TextService text();

    /** 物品库存操作：统计、检查、扣除、给予、容量判断。 */
    ItemOps itemOps();

    /** 通用冷却表：按玩家 + 动作隔离。 */
    Cooldowns cooldowns();

    /** PlaceholderAPI 统一注册表；未安装 PAPI 时 available()=false。 */
    Placeholders placeholders();

    /** Redis 跨服消息总线；Redis 未就绪时 available()=false。 */
    CrossServer crossServer();

    /** Vault 经济服务；未安装 Vault 时 available()=false。 */
    EconomyService economy();

    /** 插件间服务注册/发现。 */
    ServiceRegistry services();

    /** 调度模块：Paper / Folia 统一的异步、全局、实体与区域调度。 */
    LiuLibScheduler scheduler();

    /** 配置模块：共享配置目录、默认配置复制、缺失键补全、mtime 热重载。 */
    Configs configs();

    /** 在线时长模块；配置关闭时 isEnabled()=false。 */
    PlaytimeService playtime();

    /** MySQL 封装；未启用时 isReady()=false。 */
    MySql mysql();

    /** SQLite 封装；未启用时 isReady()=false。 */
    Sqlite sqlite();

    /** Redis 封装；未启用时 isReady()=false。 */
    RedisClient redis();

    /** Sparrow UI 封装；未初始化时 available()=false。 */
    SparrowUi sparrow();

    /** 当前配置快照。 */
    LiuLibConfig config();

    /** 当前配置根目录：共享目录或插件数据目录。 */
    File configRoot();

    /** 插件版本（plugin.yml 的 version）。 */
    String version();

    /** 是否开启调试日志。 */
    boolean debug();

    /** 重新读取本地入口与共享 config.yml，并重建连接；返回是否全部成功。 */
    boolean reload();
}
