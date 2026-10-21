package com.liuxingyu.liulib.item;

import com.liuxingyu.liulib.config.LiuLibConfig;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 物品模块门面：把 CE 解析、名称解析、序列化、规格解析组装成一套稳定 API。
 *
 * <pre>{@code
 * ItemService items = LiuLib.api().items();
 * ItemStack sword = items.build("ce:myitems:flame_sword*1");
 * String name = items.label(sword);                 // 自动解析 <lang:...>
 * String data = items.codec().encode(sword);        // Base64（NBT 保真）
 * ItemStack back = items.codec().decode(data);
 * }</pre>
 */
public final class ItemService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final LiuLibConfig config;
    private final ItemCodec codec;
    private final CraftEngineItems craftEngine;
    private final MinecraftLang minecraftLang;
    private final ItemNames names;
    private final ItemSpecParser parser;
    private final ItemOps ops;
    private final Map<String, ItemResolver> resolvers = new ConcurrentHashMap<>();

    public ItemService(JavaPlugin plugin, LiuLibConfig config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.codec = new ItemCodec(config.item().maxEncodedLength(), logger);
        this.craftEngine = new CraftEngineItems(logger, config.item().craftEngineNamespace(),
                config.item().scanCraftEngineLanguageFiles());
        File langDirectory = new File(plugin.getDataFolder(), "lang");
        if (!langDirectory.exists() && !langDirectory.mkdirs()) {
            logger.warning("无法创建语言目录: " + langDirectory);
        }
        this.minecraftLang = new MinecraftLang(logger, langDirectory);
        this.minecraftLang.reload();
        this.names = new ItemNames(logger, craftEngine, minecraftLang, config.language(),
                config.item().unknownName());
        this.parser = new ItemSpecParser(logger, codec, craftEngine, names,
                config.item().craftEngineNamespace(), resolvers);
        this.ops = new ItemOps(this);
    }

    /** 热重载：重新扫描原版语言文件与 CE 资源包语言文件。 */
    public void reload() {
        minecraftLang.reload();
        craftEngine.reloadTranslations();
        if (config.debug()) {
            logger.info("物品模块已重载: " + craftEngine.diagnostics()
                    + " minecraftLocales=" + minecraftLang.locales());
        }
    }

    public ItemCodec codec() {
        return codec;
    }

    public CraftEngineItems craftEngine() {
        return craftEngine;
    }

    public ItemNames names() {
        return names;
    }

    public ItemSpecParser parser() {
        return parser;
    }

    /**
     * 注册自定义物品前缀（扩展点）。前缀大小写不敏感，例如 {@code "lib"}。
     * 注册后所有 {@code build/buildOrNull} 调用都能解析 {@code lib:xxx}；覆盖注册会替换旧解析器。
     */
    public void registerResolver(String prefix, ItemResolver resolver) {
        if (prefix == null || prefix.isBlank() || resolver == null) {
            return;
        }
        resolvers.put(prefix.trim().toLowerCase(java.util.Locale.ROOT), resolver);
    }

    /** 注销自定义前缀（插件停用时调用）。 */
    public void unregisterResolver(String prefix) {
        if (prefix != null) {
            resolvers.remove(prefix.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** 已注册的自定义前缀。 */
    public java.util.Set<String> resolverPrefixes() {
        return java.util.Set.copyOf(resolvers.keySet());
    }

    /** 物品库存操作（统计/检查/扣除/给予）。 */
    public ItemOps ops() {
        return ops;
    }

    // ------------------------------------------------------------------
    // 常用快捷入口
    // ------------------------------------------------------------------

    /** 解析配置串并构建物品；失败抛 {@link IllegalArgumentException}。 */
    public ItemStack build(String raw) {
        return parser.build(raw);
    }

    /** 按已解析的规格构建单个物品堆（数量不超过最大堆叠）。 */
    public ItemStack build(ItemSpec spec) {
        return parser.build(spec);
    }

    /** 解析配置串并构建物品；失败返回 null。 */
    public ItemStack buildOrNull(String raw) {
        return parser.buildOrNull(raw);
    }

    /** 构建并拆成多堆（数量可超过 64）。 */
    public List<ItemStack> buildStacks(String raw) {
        ItemSpec spec = parser.parseOrNull(raw);
        return spec == null ? List.of() : parser.buildStacks(spec);
    }

    /** 按规格拆成多堆（数量可超过 64）。 */
    public List<ItemStack> buildStacks(ItemSpec spec) {
        return spec == null ? List.of() : parser.buildStacks(spec);
    }

    /** 给予玩家物品；必须主线程调用。 */
    public void give(Player player, String raw) {
        parser.give(player, raw);
    }

    /** 按规格给予玩家物品；必须主线程调用。 */
    public void give(Player player, ItemSpec spec) {
        parser.give(player, spec);
    }

    /** 物品显示名（纯文本，自动解析 CE 翻译键）。 */
    public String label(ItemStack stack) {
        return names.label(stack);
    }

    /** 物品显示名（§ 颜色）。 */
    public String legacyLabel(ItemStack stack) {
        return names.legacyLabel(stack);
    }

    /**
     * 物品的规范化 ID：{@code ce:namespace:id} 或 {@code minecraft:MATERIAL}；
     * 空气返回 null。识别顺序 CE → 原版（CE 物品底层也是原版材质，必须优先）。
     */
    public String id(ItemStack stack) {
        if (ItemCodec.isEmpty(stack)) {
            return null;
        }
        String ceId = craftEngine.id(stack);
        if (ceId != null) {
            return "ce:" + ceId;
        }
        return "minecraft:" + stack.getType().name();
    }

    /** 判断规范化 ID 是否为 CE 物品。 */
    public static boolean isCraftEngineId(String canonicalId) {
        return canonicalId != null && canonicalId.startsWith("ce:");
    }

    public boolean isCraftEngineAvailable() {
        return craftEngine.isAvailable();
    }

    public LiuLibConfig config() {
        return config;
    }
}
