package com.liuxingyu.liulib;

import com.liuxingyu.liulib.api.LiuLibApi;
import com.liuxingyu.liulib.config.Configs;
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
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;

/**
 * 其它插件的静态入口。
 *
 * <pre>{@code
 * // plugin.yml: depend: [LiuLib]
 * ItemService items = LiuLib.items();
 * String name = items.label(player.getInventory().getItemInMainHand());
 * long playtime = LiuLib.playtime().totalSeconds(player.getUniqueId());
 * }</pre>
 *
 * <p>优先从 Bukkit ServicesManager 取；取不到时回退到 LiuLib 插件实例，
 * 两种方式都要求 LiuLib 已启用。</p>
 */
public final class LiuLib {

    private static volatile LiuLibApi instance;

    private LiuLib() {
    }

    /** 由 LiuLib 插件在启用/停用时维护。 */
    static void install(LiuLibApi api) {
        instance = api;
    }

    static void uninstall(LiuLibApi api) {
        if (instance == api) {
            instance = null;
        }
    }

    /** 获取 API；LiuLib 未安装/未启用时抛出带处理建议的异常。 */
    public static LiuLibApi api() {
        LiuLibApi local = instance;
        if (local != null) {
            return local;
        }
        RegisteredServiceProvider<LiuLibApi> provider =
                Bukkit.getServicesManager().getRegistration(LiuLibApi.class);
        if (provider != null && provider.getProvider() != null) {
            instance = provider.getProvider();
            return instance;
        }
        throw new IllegalStateException("LiuLib 未启用：请在 plugin.yml 添加 depend: [LiuLib]");
    }

    /** 获取 API；未启用返回 null（可选集成场景）。 */
    public static LiuLibApi apiOrNull() {
        try {
            return api();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    public static ItemService items() {
        return api().items();
    }

    public static TextService text() {
        return api().text();
    }

    public static ItemOps itemOps() {
        return api().itemOps();
    }

    public static Cooldowns cooldowns() {
        return api().cooldowns();
    }

    public static Placeholders placeholders() {
        return api().placeholders();
    }

    public static CrossServer crossServer() {
        return api().crossServer();
    }

    public static EconomyService economy() {
        return api().economy();
    }

    public static ServiceRegistry services() {
        return api().services();
    }

    public static LiuLibScheduler scheduler() {
        return api().scheduler();
    }

    public static Configs configs() {
        return api().configs();
    }

    public static File configRoot() {
        return api().configRoot();
    }

    public static PlaytimeService playtime() {
        return api().playtime();
    }

    public static MySql mysql() {
        return api().mysql();
    }

    public static Sqlite sqlite() {
        return api().sqlite();
    }

    public static RedisClient redis() {
        return api().redis();
    }

    public static SparrowUi sparrow() {
        return api().sparrow();
    }

    public static boolean isReady() {
        LiuLibApi api = apiOrNull();
        return api != null;
    }
}
