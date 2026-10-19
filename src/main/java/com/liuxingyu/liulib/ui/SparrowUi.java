package com.liuxingyu.liulib.ui;

import com.liuxingyu.liulib.config.LiuLibConfig;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sparrow UI 公共封装：初始化、异常接管、以及“物品槽位 + 点击回调”的简易菜单。
 *
 * <p>Sparrow UI 已随 LiuLib 打包并 relocate 到 {@code com.liuxingyu.liulib.libs.sparrow.ui}，
 * 因此不会与其它插件内置的 Sparrow UI 冲突。业务插件无需直接依赖 Sparrow API，
 * 只通过本类的 {@link MenuBuilder} 构建界面；初始化失败时返回 false，调用方回退原版 GUI。</p>
 *
 * <p>线程约定：Sparrow 自己会把窗口操作切到玩家实体线程；本类只做组装，
 * {@link MenuBuilder#open(Player)} 可在异步线程调用（Sparrow 负责调度）。</p>
 */
public final class SparrowUi {

    private final Plugin plugin;
    private final Logger logger;
    private final LiuLibConfig.Ui config;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    private volatile boolean ready;
    private volatile boolean attempted;
    private volatile boolean failed;

    public SparrowUi(Plugin plugin, LiuLibConfig.Ui config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
    }

    /**
     * 初始化 Sparrow UI；幂等。返回是否可用。
     * 失败不抛异常：记录警告后由调用方回退原版界面。
     */
    public synchronized boolean init() {
        if (ready) {
            return true;
        }
        if (failed || attempted) {
            return false;
        }
        attempted = true;
        if (!config.sparrowEnabled()) {
            logger.info("Sparrow UI 已在配置中关闭，使用调用方自带界面");
            return false;
        }
        try {
            SparrowUI ui = SparrowUI.getInstance();
            // setUp 只能执行一次；重载时复用已经初始化好的单例。
            // 未初始化时 getPlugin() 会抛 IllegalStateException（而不是返回 null）。
            boolean initialized;
            try {
                initialized = ui.getPlugin() != null;
            } catch (IllegalStateException notYet) {
                initialized = false;
            }
            if (!initialized) {
                ui.setUp(plugin);
            }
            if (config.catchSparrowExceptions()) {
                ui.setExceptionHandler((context, error) ->
                        logger.log(Level.WARNING, "Sparrow UI 异常 [" + context + "]", error));
            }
            ready = true;
            logger.info("Sparrow UI 已初始化");
        } catch (Throwable error) {
            failed = true;
            ready = false;
            logger.log(Level.WARNING, "Sparrow UI 初始化失败，调用方应回退原版界面", error);
        }
        return ready;
    }

    public boolean available() {
        return ready && !failed;
    }

    /** 创建菜单构建器；rows 为 1..6（超出自动夹取）。 */
    public MenuBuilder menu(int rows) {
        return new MenuBuilder(this, Component.text("Menu"), rows);
    }

    public MenuBuilder menu(Component title, int rows) {
        return new MenuBuilder(this, title, rows);
    }

    public MenuBuilder menu(String title, int rows) {
        return new MenuBuilder(this, title == null ? Component.text("Menu") : Component.text(title), rows);
    }

    /** 关闭本类打开的菜单。 */
    public void close(Player player) {
        if (player == null) {
            return;
        }
        Window window = windows.remove(player.getUniqueId());
        if (window != null) {
            try {
                window.close();
            } catch (RuntimeException error) {
                logger.log(Level.FINE, "关闭 Sparrow 窗口失败", error);
            }
        }
    }

    /** 当前跟踪的打开窗口数（诊断用）。 */
    public int openWindows() {
        return windows.size();
    }

    /** 插件停用/重载：关闭所有跟踪窗口。不会反初始化 Sparrow 单例。 */
    public void shutdown() {
        for (UUID uuid : windows.keySet()) {
            Window window = windows.remove(uuid);
            if (window == null) {
                continue;
            }
            try {
                window.close();
            } catch (RuntimeException ignored) {
                // 停服阶段忽略
            }
        }
        ready = false;
    }

    private boolean open(MenuBuilder menu, Player player) {
        if (!available() || player == null || !player.isOnline()) {
            return false;
        }
        try {
            NormalPane pane = NormalPane.empty(new PaneSize(9, menu.rows));
            if (menu.background != null) {
                pane.setBackgroundItem(menu.background);
            }
            for (int slot = 0; slot < menu.items.length; slot++) {
                ItemStack stack = menu.items[slot];
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                Consumer<MenuClick> handler = menu.handlers[slot];
                int slotIndex = slot;
                Item item = Item.builder()
                        .setItemProviderConstant(stack.clone())
                        .addClickHandler(click -> {
                            MenuClick event = new MenuClick(
                                    click.player(),
                                    slotIndex,
                                    click.clickType(),
                                    click.cursor(),
                                    click.hotbarButton());
                            Consumer<MenuClick> slotHandler = menu.handlers[slotIndex];
                            if (slotHandler != null) {
                                slotHandler.accept(event);
                            }
                            if (menu.globalHandler != null) {
                                menu.globalHandler.accept(event);
                            }
                        })
                        .build();
                pane.setElement(slot, Element.item(item));
            }
            NormalWindow window = NormalWindow.builder()
                    .setTitle(menu.title)
                    .setUpperPane(pane)
                    .build(player);
            if (window == null) {
                return false;
            }
            Window previous = windows.put(player.getUniqueId(), window);
            if (previous != null) {
                try {
                    previous.close();
                } catch (RuntimeException ignored) {
                    // 旧窗口可能已关闭
                }
            }
            window.open();
            return true;
        } catch (Throwable error) {
            logger.log(Level.WARNING, "打开 Sparrow 菜单失败，调用方应回退原版界面", error);
            return false;
        }
    }

    /**
     * 简易菜单构建器：固定 rows*9 个槽位，按槽位设置物品与点击回调。
     * 同一个 builder 可重复 {@link #open(Player)}（会重新构建 Item，避免共享可变状态）。
     */
    public static final class MenuBuilder {
        private final SparrowUi owner;
        private final int rows;
        private final ItemStack[] items;
        private final Consumer<MenuClick>[] handlers;
        private Component title;
        private ItemStack background;
        private Consumer<MenuClick> globalHandler;

        @SuppressWarnings("unchecked")
        private MenuBuilder(SparrowUi owner, Component title, int rows) {
            this.owner = owner;
            this.rows = Math.max(1, Math.min(6, rows));
            this.title = title == null ? Component.text("Menu") : title;
            this.items = new ItemStack[this.rows * 9];
            this.handlers = new Consumer[this.rows * 9];
        }

        public MenuBuilder title(Component title) {
            this.title = title == null ? Component.empty() : title;
            return this;
        }

        public MenuBuilder title(String title) {
            return title(title == null ? Component.empty() : Component.text(title));
        }

        /** 铺底物品（无点击行为）。 */
        public MenuBuilder background(ItemStack stack) {
            this.background = stack == null ? null : stack.clone();
            return this;
        }

        /** 设置槽位物品与点击回调；slot 越界忽略。 */
        public MenuBuilder set(int slot, ItemStack stack, Consumer<MenuClick> handler) {
            if (slot < 0 || slot >= items.length) {
                return this;
            }
            items[slot] = stack == null ? null : stack.clone();
            handlers[slot] = handler;
            return this;
        }

        public MenuBuilder set(int slot, ItemStack stack) {
            return set(slot, stack, null);
        }

        /** 全菜单点击回调（槽位回调之后执行）。 */
        public MenuBuilder onClick(Consumer<MenuClick> handler) {
            this.globalHandler = handler;
            return this;
        }

        /** 填充整行（0 起）。 */
        public MenuBuilder fillRow(int row, ItemStack stack) {
            for (int column = 0; column < 9; column++) {
                set(row * 9 + column, stack);
            }
            return this;
        }

        /** 用背景填满所有空槽位。 */
        public MenuBuilder fillEmpty(ItemStack stack) {
            if (stack == null) {
                return this;
            }
            for (int i = 0; i < items.length; i++) {
                if (items[i] == null) {
                    items[i] = stack.clone();
                }
            }
            return this;
        }

        public int rows() {
            return rows;
        }

        public boolean open(Player player) {
            return owner.open(this, player);
        }
    }
}
