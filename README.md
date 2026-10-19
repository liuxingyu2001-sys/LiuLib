# LiuLib

liuxingyu / liuxingyu2001 系列插件的公共库，把各处反复复制的**物品解析、名称解析、物品序列化、
物品操作、文本、调度、冷却、共享配置、在线时长、经济、跨服消息、MySQL/SQLite/Redis、Sparrow UI**
收敛成一套 API，作为一个独立插件加载，其它插件 `depend: [LiuLib]` 即可复用。

- 目标服务端：Paper / Folia 1.21+
- 编译：Java 21，Maven
- 产物：`target/LiuLib-1.0.0.jar`（已内置并 relocate HikariCP / Jedis / Sparrow UI，服主无需另装）

---

## 1. 覆盖的公共代码

| 模块 | LiuLib 入口 | 收敛自 |
|---|---|---|
| CraftEngine 物品解析 / 反查 / 翻译 | `ItemService.craftEngine()`、`CraftEngineItems` | liushop `hook/CraftEngineHook`、liupass `hook/CraftEngineHook`、LiuGuild / soulspace / liudungeon / skillcore / mcBuildTools 的 CE Hook |
| 名称解析（`<lang:key>`、MiniMessage、原版本地化、纯文本兜底） | `ItemService.names()`、`ItemNames`、`MinecraftLang` | liushop `util/Items`、liupointshop `util/Items`、LiuChat `CraftEngineNames` |
| 物品序列化 / 反序列化（NBT / Base64 / 旧对象流 / YAML） | `ItemService.codec()`、`ItemCodec` | LiuGuild `ItemCodec`、rentitem `ItemSerializer`、duels `ItemData`、LiuChat `ItemSerializer`、soulspace `ItemStackSerializer` |
| 物品配置串解析（`ce:ns:id`、`DIAMOND:3`、`base64:`） | `ItemService.parser()`、`ItemSpecParser`、`ItemSpec` | liushop / liupointshop `ItemRegistry` + 各插件手写解析 |
| MySQL（Hikari 连接池、异步写队列、事务模板） | `MySql` | liupass `MySqlDataStore`、soulspace `MySqlStorage`、TLoot `MySQLStorage`、LiuChat `AbstractJdbcDatabase` |
| Redis（缓存、Pub/Sub、分布式锁、熔断降级） | `RedisClient` | liupass `RedisClient`、LiuChat `RedisBus`、LiuGuild / liudungeon `RedisBridge`、duels `RedisCacheFactory` |
| Sparrow UI（初始化、异常接管、简易菜单） | `SparrowUi` | LiuChat `SparrowUiSupport`、LiuGuild `SparrowUiHook`、soulspace `SparrowBootstrap` |
| 文本（MiniMessage + 传统色码、messages.yml） | `TextService`、`Messages` | liushop `Msg`、LiuChat `TextUtil`、LiuGuild `Text`、liuinvite `MessageUtils` |
| 调度（Paper / Folia 异步、全局、实体、区域） | `LiuLibScheduler`、`TaskHandle` | LiuSignin / LiuChat / LiuRedPacket `Schedulers`、luckfurnace `SchedulerUtils`、soulspace `SchedulerUtil` |
| 共享配置（目录解析、默认复制、补键、mtime 热重载） | `Configs`、`ConfigWatcher` | luckfurnace / liupass / mcskills 三份同款实现 |
| 在线时长（AFK、按天/周、排行、PAPI） | `PlaytimeService` | 新增公共能力，签到 / 商店 / 副本可直接依赖 |
| 物品构建 / 库存操作 | `ItemBuilder`、`ItemOps` | 各插件重复的 `ItemBuilder`、`takeItems`、`hasItem` 逻辑 |
| 冷却表 | `Cooldowns` | LiuShop / LiuSignin / LiuChat 的命令冷却、购买冷却 |
| PAPI 注册表 | `Placeholders` | 各插件重复的 `PlaceholderExpansion` 子类 |
| 插件间服务 | `ServiceRegistry` | Bukkit ServicesManager 的 Optional/批量封装 |
| 跨服消息 | `CrossServer` | Redis pub/sub 上的结构化消息，自动过滤本服回声 |
| 经济 | `EconomyService` | Vault 扣款/存款/余额的可选封装 |
| SQLite（单文件库、异步写、事务） | `Sqlite` | 单服小数据量场景，免装数据库 |
| MySQL 命名锁 | `MySqlLock` | 单库跨进程互斥（无 Redis 时） |
| SQL 迁移 | `SqlMigrations` | 各插件手写 `CREATE TABLE` / ALTER 的版本管理 |

> 只提取**跨插件公共且语义一致**的部分；商店价格、地牢队伍、公会数据模型等业务逻辑仍留在各自插件。

---

## 2. 安装与依赖

1. 把 `LiuLib-1.0.0.jar` 放进服务端 `plugins/`。
2. 启动后执行 `/liulib status` 确认各模块状态。
3. 业务插件在 `plugin.yml` 中声明：

```yaml
depend: [LiuLib]
# 或者只在需要时软依赖：
# softdepend: [LiuLib]
```

4. 可选依赖（全部软依赖，不装也能启动）：

| 插件 | 作用 | 未安装时 |
| --- | --- | --- |
| CraftEngine | `ce:` 物品解析 | 回退原版材质 |
| PlaceholderAPI | `%liulib_*%` 与业务变量 | `placeholders().available()=false` |
| Vault + 经济插件 | `economy()` 扣款/存款 | `economy().available()=false` |

5. 业务插件 `pom.xml` 用 `provided` 引入 LiuLib（把 jar 安装到本地仓库或使用你自己的私服）：

```bash
mvn install:install-file -Dfile=LiuLib-1.0.0.jar \
  -DgroupId=com.liuxingyu -DartifactId=LiuLib -Dversion=1.0.0 -Dpackaging=jar
```

```xml
<dependency>
  <groupId>com.liuxingyu</groupId>
  <artifactId>LiuLib</artifactId>
  <version>1.0.0</version>
  <scope>provided</scope>
</dependency>
```

Java 代码入口：

```java
import com.liuxingyu.liulib.LiuLib;

ItemService items = LiuLib.items();
ItemOps itemOps = LiuLib.itemOps();
TextService text = LiuLib.text();
Cooldowns cooldowns = LiuLib.cooldowns();
Placeholders placeholders = LiuLib.placeholders();
ServiceRegistry services = LiuLib.services();
EconomyService economy = LiuLib.economy();
CrossServer crossServer = LiuLib.crossServer();
LiuLibScheduler scheduler = LiuLib.scheduler();
Configs configs = LiuLib.configs();
PlaytimeService playtime = LiuLib.playtime();
MySql mysql = LiuLib.mysql();
Sqlite sqlite = LiuLib.sqlite();
File configRoot = LiuLib.configRoot();
RedisClient redis = LiuLib.redis();
SparrowUi sparrow = LiuLib.sparrow();
```

也支持 Bukkit 服务接口：

```java
LiuLibApi api = Bukkit.getServicesManager().load(LiuLibApi.class);
```

---

## 3. 物品模块

### 3.1 配置串解析

```java
ItemService items = LiuLib.items();

// 原版材质
ItemStack diamond = items.build("DIAMOND");
ItemStack swords = items.build("DIAMOND_SWORD*3");     // 或 DIAMOND_SWORD:3

// CraftEngine 物品（CE 未安装时返回 null / 抛 IllegalArgumentException）
ItemStack ce = items.build("ce:myitems:flame_sword");
List<ItemStack> many = items.buildStacks("ce:myitems:flame_sword*8"); // 自动按 64 拆堆

// 完整物品（Paper 二进制 NBT 的 Base64；兼容旧对象流）
ItemSpec spec = items.parser().parse("base64:" + encoded);
```

语法：

| 写法 | 含义 |
|---|---|
| `DIAMOND` / `minecraft:diamond` | 原版材质 |
| `DIAMOND:3` / `DIAMOND*3` | 原版材质 + 数量 |
| `ce:namespace:id` / `craftengine:namespace:id` | CraftEngine 物品 |
| `namespace:id` | CE（原版材质解析失败时） |
| `ce:namespace:id*4` | CE + 数量（CE 只认 `*n`，避免 ID 数字段歧义） |
| `base64:...` / `nbt:...` / `bytes:...` | 完整物品编码 |

反查物品 ID：

```java
String id = items.id(stack);          // ce:myitems:flame_sword 或 minecraft:DIAMOND
boolean ce = items.isCraftEngineId(id);
```

发放物品（**必须主线程**）：

```java
items.give(player, "ce:myitems:flame_sword*1");
```

### 3.2 名称解析

```java
String plain = items.label(stack);          // 纯文本，自动解析 <lang:key>
String legacy = items.legacyLabel(stack);   // § 颜色文本
String byId = items.names().labelById("ce:myitems:flame_sword");

Component component = items.names().parse("<gradient:red:blue>标题</gradient>");
String text = items.names().plain(component);
```

解析顺序：`item_name` → `display_name` → CE 翻译 → `lang/<locale>.json` → `getI18NDisplayName()` →
材质 ID 人类化。**保证不会把 `<lang:KEY>` / `item.default.xxx` 原始键输出给玩家。**

原版语言文件放到 `plugins/LiuLib/lang/zh_cn.json`、`en_us.json` 即可（从客户端版本 jar 的
`assets/minecraft/lang/` 提取）。

CraftEngine 翻译：

```java
CraftEngineItems ce = items.craftEngine();
boolean available = ce.isAvailable();
String name = ce.resolveTranslation("item.default.flame_sword");
String id = ce.id(stack);                  // 反查 CE ID，非 CE 物品返回 null
ItemStack built = ce.build("myitems:flame_sword", 1, player); // 支持按玩家构建
ce.reloadTranslations();                   // CE 资源包/translations 热重载
```

LiuLib 会扫描 `plugins/CraftEngine/resources/**/*.yml` 与 `plugins/CraftEngine/translations/*.yml`，
补齐 CE 服务端 `TranslationManager` 默认不加载的物品键；CE 未安装时全部自动降级。

### 3.3 序列化 / 反序列化

```java
ItemCodec codec = items.codec();

String base64 = codec.encode(stack);            // 空气返回 null
ItemStack back = codec.decode(base64);          // 损坏返回 null，不抛异常
ItemStack clamped = codec.decodeClamped(base64);

byte[] raw = codec.encodeBytes(stack);
ItemStack fromBytes = codec.decodeBytes(raw);   // 自动识别 NBT / 旧 Java 序列化流

String many = codec.encodeMany(inventory.getContents());
ItemStack[] array = codec.decodeArray(many);    // 空槽为 null

Map<String, Object> yamlMap = codec.toMap(stack);
ItemStack fromYaml = codec.fromSection(configSection);
```

- 优先 Paper `serializeAsBytes()`（完整保留 1.20.5+ 数据组件、CE 物品、头颅皮肤）；
- 旧 `BukkitObjectOutputStream` 数据（`0xAC 0xED` 开头）自动兼容读取；
- 单件编码默认上限 60000 字符，可在 `config.yml: item.max-encoded-length` 调整。

游戏内可用 `/liulib item [main|offhand]` 获取手持物品的 Base64：

- 完整串同时输出到控制台；
- 聊天栏提供 `[复制 Base64]` 和 `[复制 base64: 形式]` 两个可点击按钮；
- `base64:` 形式可直接写入支持完整物品配置的字段（如 `items.xxx`）；
- 需要权限 `liulib.item`（默认 OP）。

### 3.4 物品构建与库存操作

```java
ItemStack sword = ItemBuilder.of(Material.DIAMOND_SWORD)
        .name(Component.text("烈焰剑"))
        .lore(Component.text("攻击力 +10"))
        .glow(true)
        .unbreakable(true)
        .enchant(Enchantment.SHARPNESS, 5)
        .build();

ItemOps ops = items.ops();                 // 等价 LiuLib.itemOps()
int count = ops.count(player, "ce:myitems:token");   // 统计主背包
boolean enough = ops.has(player, "DIAMOND", 32);
boolean paid = ops.take(player, "DIAMOND", 32);      // 不足时不扣
ops.removeAll(player, "ce:myitems:token");
ops.give(player, "ce:myitems:reward*2");             // 背包满自动掉落
boolean fit = ops.fits(player, "DIAMOND*64");
```

- 按 `ItemStack#isSimilar` 匹配，附魔 / lore / CE 数据都会参与，不会误扣同名物品；
- 只统计主背包 36 格，不扣盔甲与副手；
- `ItemBuilder.glow` 使用 Paper 的 glint override，不添加假附魔；
- `ItemBuilder` 的字符串名称在传入 `TextService` 时支持 `&` 色码，否则只按 MiniMessage 解析。

---

## 4. 文本与消息

```java
TextService text = LiuLib.text();

Component c = text.parse("&a你好 <gradient:red:blue>世界</gradient>");
String plain = text.plain(c);          // 你好 世界
String legacy = text.legacy(c);        // §a你好 ...
String stripped = text.strip("§b<bold>标题</bold>");

Component filled = text.component("<green>{player} 有 {amount} 金币",
        "player", "Steve", "amount", 3);
```

- 同时支持 MiniMessage、`&` / `§` 色码、`&#RRGGBB`、`&x&R&R&G&G&B&B`；
- 解析失败逐级降级（MiniMessage → 传统色码 → 纯文本），**不会把异常抛给业务**；
- `{key}` 占位符在解析前替换，value 为 `null` 时替换为空串；
- `parseLines` 批量解析 GUI lore / 帮助列表；
- 与 `ItemNames` 的区别：本模块面向普通消息，不处理 `<lang:...>` 物品翻译键。

`messages.yml`：

```yaml
prefix: '<gray>[商店]</gray> '
buy:
  success: '<green>已购买 {amount} 个 {item}'
help:
  - '<yellow>/shop buy <物品> <数量>'
  - '<yellow>/shop sell all'
```

```java
Messages messages = text.messages(plugin);          // plugins/<插件>/messages.yml
messages.send(player, "buy.success", "amount", 3, "item", "钻石");
messages.sendList(player, "help");
messages.reload();                                  // 插件 /reload 时调用
```

缺少 key 时输出 `text.missing-key`（`{key}` 自动替换）并只警告一次，避免把原始键发给玩家。
配置项：`text.prefix`、`text.missing-key`、`text.warn-missing`、`text.legacy-input`。

---

## 5. 调度（Paper / Folia）

```java
LiuLibScheduler scheduler = LiuLib.scheduler();

scheduler.async(() -> repository.load(uuid));        // 异步：数据库 / 文件 / HTTP
scheduler.global(() -> Bukkit.broadcast(...));       // 全局主线程
scheduler.entity(player, p -> p.sendMessage(...));   // 玩家所在线程；离线自动丢弃
scheduler.region(block.getLocation(), () -> ...);    // 方块所在区域线程
scheduler.region(world, chunkX, chunkZ, () -> ...);  // 区块所在区域线程

TaskHandle handle = scheduler.globalTimer(20L, 20L, this::tick);
handle.cancel();                                     // 或由 LiuLib 停用/重载统一取消
```

- Paper 与 Folia 使用同一套代码，业务插件不需要判断 `isFolia()`（仅诊断用）；
- 延迟/循环任务统一返回 `TaskHandle`，`shutdown()` 会取消所有由本类提交的任务；
- 异步版返回 `CompletableFuture`，可以直接串接后续逻辑；
- 不要再用 `Bukkit.getScheduler()`，它在 Folia 上不可用。

---

## 6. 共享配置与热重载

```java
Configs configs = LiuLib.configs();

// 1) 解析共享目录：显式路径 > 自动检测 > 插件目录
File root = configs.resolveRoot(plugin,
        "settings.shared-config-path",       // config 里的显式路径，可空
        "/mc/shared/shop-config");           // 自动检测路径，可空

// 2) 首次运行复制 jar 内默认配置（不覆盖已有文件）
configs.copyIfMissing(plugin, "config.yml", root);
configs.copyIfMissing(plugin, "examples/shop.yml", root);

// 3) 升级补键：只补缺失键，不覆盖用户改过的值
YamlConfiguration current = configs.load(new File(root, "config.yml"));
configs.mergeMissingKeys(current, defaults);
configs.saveAtomic(new File(root, "config.yml"), current);

// 4) 监听目录变化：异步轮询 → 主线程重载
ConfigWatcher watcher = configs.watch(plugin, root, 200L, this::reloadAll);

// 5) 手动 /reload 后重置指纹基线，避免立刻再触发一次
watcher.baseline();
```

- 指纹取目录内所有 `.yml/.yaml` 的最大 `lastModified()`，新增、删除、修改都会触发；
- 轮询线程只做文件 IO，`reloadAll` 在全局主线程执行，可安全操作 Bukkit 对象；
- 自动重载失败只记录日志并保留旧配置，不会让插件处于半套配置状态；
- 与 `config.yml` 的 `settings.shared-config-path` / `settings.auto-reload-config` 约定一致。

### 6.1 LiuLib 自身配置共享

多服部署时，可以让所有子服读取同一份 `config.yml`：

```yaml
# 每个子服的 plugins/LiuLib/config.yml 只需要这一行不同；其余配置全部从共享目录读取
shared-config-path: '/mc/shared/liulib-config'
```

- 留空时自动检测 `/mc/shared/liulib-config`，目录存在才启用；
- 显式路径不存在会自动创建，创建失败回退本地插件目录；
- 共享目录缺少 `config.yml` 时自动复制 jar 内默认配置；
- 启动/`/liulib reload` 时补全新增键，不覆盖已有值；
- `shared-config-path` 只从**各服本地**的 `plugins/LiuLib/config.yml` 读取，共享文件里的同名键不会影响路径选择；
- `LiuLib.configRoot()` 可查询当前实际使用的目录，`/liulib status` 也会显示“共享/本地”。

---

## 7. 在线时长

```java
PlaytimeService playtime = LiuLib.playtime();

long total = playtime.totalSeconds(uuid);       // 总计（在线玩家 = 基线 + 本次会话）
long today = playtime.todaySeconds(uuid);       // 今日（配置时区自然日）
long session = playtime.sessionSeconds(uuid);   // 本次会话
boolean afk = playtime.isAfk(uuid);             // 是否 AFK

// 排行榜（带缓存，异步）
playtime.top(PlaytimePeriod.WEEK, 10)
        .thenAccept(entries -> entries.forEach(entry ->
                player.sendMessage(entry.name() + " - " + playtime.format(entry.seconds()))));

// 给在线/离线玩家补时长（活动补偿、兑换）
playtime.addBonus(player, 600);
playtime.addBonus(uuid, "Steve", 600);

// 离线玩家精确查询
playtime.stored(uuid).thenAccept(record ->
        player.sendMessage("总计 " + playtime.format(record.totalSeconds())));
```

行为约定：

- 每秒按实际在线时间累计；跨天时先把旧日期数据写库，再切到新的一天；
- AFK：默认 300 秒无移动/聊天/命令/交互停止计时；`liulib.playtime.afk-bypass` 豁免，
  `afk-timeout-seconds: 0` 完全关闭 AFK 检测；
- 会话短于 `min-session-seconds` 不写库，避免反复进出刷时长；
- 存储 `auto`：MySQL 就绪时用 MySQL（两张表 `playtime` / `playtime_daily`，原子自增，
  多服同时计时不会互相覆盖）；否则用 `plugins/LiuLib/playtime.yml`；
- 默认每 `flush-interval-seconds` 异步刷新一次，退出立即刷新，插件停用同步 flush 并等待
  `quit-flush-timeout-ms`；写入失败进入重试队列，不会因一次数据库抖动丢数据；
- 不统计 `ignored-worlds` 中的世界；
- PlaceholderAPI（可选，软依赖）：
  `%liulib_playtime_total%`、`%liulib_playtime_total_formatted%`、
  `%liulib_playtime_total_hours%`、`%liulib_playtime_total_days%`、
  `%liulib_playtime_today%`、`%liulib_playtime_today_formatted%`、
  `%liulib_playtime_session%`、`%liulib_playtime_session_formatted%`、
  `%liulib_playtime_afk%`。

```yaml
playtime:
  enabled: true
  tick-seconds: 1
  afk-timeout-seconds: 300
  afk-bypass-permission: 'liulib.playtime.afk-bypass'
  min-session-seconds: 10
  flush-interval-seconds: 300
  quit-flush-timeout-ms: 3000
  timezone: 'Asia/Shanghai'
  ignored-worlds: []
  storage: auto            # auto | mysql | yaml
  top-cache-seconds: 60
  yaml-retention-days: 60
  placeholders: true
```

> 离线玩家的 `totalSeconds` 返回最近一次加载/查询的缓存值；需要精确值请用 `stored(uuid)`。
> 排行榜读取的是已刷新到存储的数据，最多滞后一个 `flush-interval-seconds`。

---

## 8. 冷却、PAPI、经济与服务注册

```java
Cooldowns cooldowns = LiuLib.cooldowns();

// 检查并触发（原子）：仍在冷却返回 false
if (cooldowns.trigger("shop.buy", player, 3, TimeUnit.SECONDS)) {
    // 执行购买
}
long remain = cooldowns.remainingSeconds("shop.buy", player);
cooldowns.clear("shop.buy", player);
cooldowns.clearAction("shop.buy");          // 重载配置时清空某个动作
```

- 按 `(玩家, 动作)` 隔离，计时用 `nanoTime`，不受系统时间调整影响；
- 过期条目读取时判断，后台清理任务每 60 秒运行一次（`startCleanup`）；
- `trigger` 的“检查 + 写入”是原子的，多线程同时触发只有一个成功。

```java
Placeholders placeholders = LiuLib.placeholders();
if (placeholders.available()) {
    placeholders.register("myshop", (player, params) -> switch (params) {
        case "points" -> String.valueOf(points(player));
        case "rank" -> rank(player);
        default -> null;
    });
}
```

- 未安装 PlaceholderAPI 时 `available()` 为 false，注册直接返回 false，不加载 PAPI 类；
- 同一 identifier 重复注册会先注销旧扩展，重载时不会冲突；
- 在线时长的 `%liulib_playtime_*%` 也走这个注册表，`liulib` 标识符已保留。

```java
ServiceRegistry services = LiuLib.services();
services.register(MyApi.class, myApi);            // 默认 Normal 优先级
services.register(MyApi.class, fallbackApi, ServicePriority.Low);
Optional<MyApi> api = services.find(MyApi.class);
MyApi required = services.require(MyApi.class);   // 缺失时抛出带接口名的异常
List<MyApi> all = services.all(MyApi.class);
services.unregisterAll();                         // 只清理本类注册的实例
```

- 服务由 Bukkit 按 LiuLib 生命周期注销；`unregisterAll` 不会误删其它插件的实现；
- 适合给“可选依赖”场景提供稳定的接口发现，而不是把实现类塞进 `plugin.yml`。

### 8.1 经济（Vault）

```java
EconomyService economy = LiuLib.economy();

if (economy.available()) {
    double balance = economy.balance(player);
    if (economy.has(player, 100)) {
        EconomyService.Result result = economy.withdraw(player, 100);
        if (!result.success()) {
            player.sendMessage(Component.text("扣款失败: " + result.error()));
        }
    }
    economy.deposit(player, 50);
    String text = economy.format(100);       // 例如 "$100.00"
}
```

- 通过反射调用 Vault API，LiuLib 编译期不依赖 Vault；未安装 Vault 时 `available()=false`；
- `withdraw`/`deposit` 返回 `Result`（成功、余额、金额、错误），金额非法或服务不可用时失败而不抛异常；
- Vault 实现不一定线程安全，默认在主线程调用；
- 若经济插件比 LiuLib 更晚加载，调用 `LiuLib.economy().refresh()`（或 `/liulib reload`）重新发现实现。

---

## 9. MySQL

```yaml
mysql:
  enabled: true
  host: 127.0.0.1
  port: 3306
  database: liulib
  username: root
  password: ''
  table-prefix: 'liulib_'
  pool-size: 6
  connection-timeout-ms: 5000
  use-ssl: false
  auto-create-database: true
```

```java
MySql mysql = LiuLib.mysql();
if (mysql.isReady()) {
    mysql.createTable("player_data",
            "uuid CHAR(36) PRIMARY KEY, coins BIGINT NOT NULL DEFAULT 0");

    String table = mysql.table("player_data");
    mysql.update("INSERT INTO " + table + " (uuid, coins) VALUES (?, ?) "
            + "ON DUPLICATE KEY UPDATE coins = VALUES(coins)", uuid, 100);

    long coins = mysql.queryOne("SELECT coins FROM " + table + " WHERE uuid = ?",
            rs -> rs.getLong(1), uuid);

    mysql.transaction(connection -> {
        try (var ps = connection.prepareStatement(
                "UPDATE " + table + " SET coins = coins - ? WHERE uuid = ?")) {
            ps.setLong(1, 50);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
        return null;
    });

    // 串行后台写，主线程不等网络
    mysql.executeAsync(() -> mysql.update("DELETE FROM " + table + " WHERE uuid = ?", uuid));
}
```

- HikariCP 已随 LiuLib 打包并 relocate，不会与其它插件的 Hikari 冲突；
- MySQL JDBC 驱动由 `plugin.yml: libraries` 交给 Paper 下载；
- 未启用/连接失败时 `isReady()=false`，所有方法快速失败，调用方按“仅内存模式”降级；
- 连接池负责断线重连，`healthCheck()` 可主动探活；
- `executeAsync` 使用单线程有界队列，保证写顺序且不阻塞主线程。

schema 迁移：

```java
SqlMigrations migrations = new SqlMigrations(mysql, getLogger(), "myshop_schema");
boolean ok = migrations.migrate(List.of(
        new SqlMigrations.Migration(1, "创建商品表", """
                CREATE TABLE IF NOT EXISTS myshop_items (
                    id VARCHAR(64) PRIMARY KEY,
                    price DOUBLE NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """),
        new SqlMigrations.Migration(2, "增加库存", """
                ALTER TABLE myshop_items ADD COLUMN stock INT NOT NULL DEFAULT 0
                """)));
int version = migrations.currentVersion();
```

- 版本表默认 `liulib_schema`，每个版本独立事务，失败回滚且不记录版本；
- 重复调用只执行缺失版本；表名只允许字母/数字/下划线，避免 SQL 注入；
- 迁移应在异步线程执行一次，不要放在主线程。

MySQL 命名锁（无 Redis 时的跨进程互斥）：

```java
Optional<MySqlLock> lock = MySqlLock.tryAcquire(mysql, "liulib:daily", 5);
if (lock.isPresent()) {
    try (MySqlLock held = lock.get()) {
        // 临界区：同一时刻只有一个服/线程能进入
    }
}
```

- 锁与会话绑定，`MySqlLock` 持有专用连接，`close()` 时释放并归还；
- 等待 `timeoutSeconds` 秒后仍拿不到返回空，不抛异常；
- 锁名最多 64 字符；Redis 可用时优先用 `RedisClient.tryLock`。

### 9.1 SQLite

```yaml
sqlite:
  enabled: false
  file: data.db          # 相对 plugins/LiuLib/
  table-prefix: 'liulib_'
```

```java
Sqlite sqlite = LiuLib.sqlite();
if (sqlite.isReady()) {
    sqlite.createTable("coins", "uuid TEXT PRIMARY KEY, amount INTEGER NOT NULL DEFAULT 0");
    sqlite.update("INSERT INTO `liulib_coins` (uuid, amount) VALUES (?, ?) "
            + "ON CONFLICT(uuid) DO UPDATE SET amount = amount + excluded.amount", uuid, 10);
    int amount = sqlite.queryOne("SELECT amount FROM `liulib_coins` WHERE uuid = ?",
            rs -> rs.getInt(1), uuid);

    sqlite.transaction(connection -> {
        try (var ps = connection.prepareStatement(
                "UPDATE `liulib_coins` SET amount = amount - ? WHERE uuid = ?")) {
            ps.setInt(1, 5);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
        return null;
    });

    sqlite.executeAsync(() -> sqlite.update("DELETE FROM `liulib_coins` WHERE uuid = ?", uuid));
}
```

- 单文件、无需额外服务，适合单服小数据量；多服共享仍用 MySQL；
- 每次操作打开连接，WAL + `busy_timeout=5000` 降低锁冲突；
- 驱动 `org.xerial:sqlite-jdbc` 由 `plugin.yml: libraries` 交给 Paper 下载，不打包进 jar；
- `executeAsync` 单线程串行写；未启用/初始化失败时 `isReady()=false`，调用方回退 YAML/内存。

---

## 10. Redis

```yaml
redis:
  enabled: true
  host: 127.0.0.1
  port: 6379
  password: ''
  database: 0
  timeout-ms: 2000
  pool-size: 8
  key-prefix: 'liulib:'
```

```java
RedisClient redis = LiuLib.redis();

redis.setex("player:" + uuid, 300, "online");
String value = redis.get("player:" + uuid);        // 自动加 key-prefix
redis.del("player:" + uuid);

redis.hset("guild:" + id, "level", "3");
Map<String, String> guild = redis.hgetAll("guild:" + id);

// 跨服广播：回调在订阅线程执行，需要时自行切回主线程
redis.subscribe("sync", message -> {
    // ...
});
redis.publish("sync", payload);

// 分布式锁
if (redis.tryLock("shop:" + uuid, 10)) {
    try {
        // ...
    } finally {
        redis.unlock("shop:" + uuid);
    }
}
```

- key 与 channel 都自动加 `key-prefix`（只加一次），避免同一台 Redis 多网络串服；
- 未启用/连接失败时读返回 `null` / `0` / `false`，写静默失败并只警告一次；
- 订阅线程断线指数退避重连（1s → 30s）；
- Jedis / commons-pool2 / org.json 已随 LiuLib 打包并 relocate；
- `reload()` 会重建 Redis 客户端，已有订阅会断开，依赖方需重新 `subscribe`。

### 10.1 跨服消息

```java
CrossServer cross = LiuLib.crossServer();

if (cross.available()) {
    CrossServer.Subscription sub = cross.subscribe("myshop", message -> {
        // 回调在 Redis 订阅线程，操作玩家/世界请切回主线程
        LiuLib.scheduler().global(() -> apply(message));
    });

    cross.send("myshop", "refresh", Map.of("player", uuid.toString()));
    long receivers = cross.send("myshop", "ping");

    sub.close();                 // 取消单个监听
    cross.unsubscribe("myshop"); // 取消整个频道
}
```

- 消息用 DataOutputStream + Base64 编码，含来源服务器 ID，默认忽略自己发出的消息；
- 单条上限 64 KiB、128 个键值对，超限直接拒绝，防止恶意 Redis 消息；
- 同一频道多个监听器共用一条 Redis 订阅，`Subscription.close()` 不会误退订其它监听器；
- 本服需要立即处理时用 `cross.sendLocal(channel, message)`，避免等待 Redis 回声；
- `reload()` 会重建 Redis 与 CrossServer，依赖方需重新 `subscribe`。

---

## 11. Sparrow UI

```java
SparrowUi sparrow = LiuLib.sparrow();

if (sparrow.available()) {
    sparrow.menu(Component.text("我的菜单"), 3)
            .set(13, stack, click -> {
                if (click.leftClick()) {
                    player.sendMessage("左键点击");
                }
            })
            .fillEmpty(filler)
            .open(player);
}
```

- `SparrowUi` 已内置并 relocate 到 `com.liuxingyu.liulib.libs.sparrow.ui`，不会与其它插件冲突；
- 业务插件**不需要**直接依赖 Sparrow API，菜单只接收 `ItemStack` 与 `MenuClick`；
- `open(player)` 返回 false 时，调用方回退原版 GUI；
- 初始化失败只记录警告，不影响 LiuLib 其它模块；
- 可用 `sparrow.close(player)` 主动关闭本类打开的窗口。

---

## 12. 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/liulib status` | 查看 CE / MySQL / Redis / Sparrow / 调度 / 在线时长状态 | `liulib.admin` |
| `/liulib selftest` | 运行时自检：物品编解码 / CE / Redis / MySQL / Sparrow / 文本 / 在线时长 | `liulib.admin` |
| `/liulib reload` | 重载配置并重建模块 | `liulib.admin` |
| `/liulib playtime` | 查看自己的总 / 今日 / 本次在线时长 | `liulib.playtime` |
| `/liulib playtime <玩家>` | 查看其他玩家（离线玩家需有缓存） | `liulib.playtime.others` |
| `/liulib playtime top [today\|week\|total] [数量]` | 在线时长排行榜 | `liulib.playtime.top` |
| `/liulib item [main\|offhand]` | 获取手中物品的 Base64，控制台输出 + 聊天点击复制 | `liulib.item` 或 `liulib.admin` |
| `/liulib playtime` 别名 | `/liulib pt` | 同上 |

`liulib.playtime.afk-bypass`：拥有该权限的玩家挂机也计入在线时长。

---

## 13. 构建

```bash
cd /home/liu/plugins/LiuLib
mvn clean package
# 产物：target/LiuLib-1.0.0.jar
```

构建时会把以下依赖打进 jar 并 relocate：

| 原包 | relocate 后 |
|---|---|
| `net.momirealms.sparrow.ui` | `com.liuxingyu.liulib.libs.sparrow.ui` |
| `com.zaxxer.hikari` | `com.liuxingyu.liulib.libs.hikari` |
| `redis.clients.jedis` | `com.liuxingyu.liulib.libs.jedis` |
| `org.apache.commons.pool2` | `com.liuxingyu.liulib.libs.pool2` |
| `org.json` | `com.liuxingyu.liulib.libs.json` |

PlaceholderAPI 是 `provided` 软依赖：没有装 PAPI 时在线时长功能照常工作，只是不注册 `%liulib_playtime_*%`；
SQLite / MySQL 驱动仍由 `plugin.yml` 的 `libraries` 交给 Paper 下载。

---

## 14. 注意事项

- `ItemSpecParser.give`、`ItemNames` 读取 `ItemMeta` 等 Bukkit 操作需在主线程调用；
  MySQL / Redis 的网络操作不要放在主线程（`MySql.executeAsync` 已处理写操作）。
- 所有调度统一走 `LiuLib.scheduler()`；Folia 下不要跨线程直接改实体/方块，`entity`/`region`
  会自动切到正确线程。
- 共享配置的监听回调在全局主线程执行；手动 reload 后记得 `ConfigWatcher#baseline()`，
  否则下一次轮询会再触发一次重载。
- 在线时长默认每 5 分钟刷新一次；强制刷新用 `playtime.flushNow()`。多服聚合依赖 MySQL，
  请确保所有服务器使用同一个 `mysql.database` 与 `table-prefix`。
- 离线玩家的 `totalSeconds` 是缓存值；精确查询用 `stored(uuid)`。
- CE / MySQL / Redis / Sparrow / PAPI 均为可选；任一不可用时对应 API 仍存在，但状态为未就绪。
- `/liulib reload` 会重建模块；Redis 订阅、Sparrow 窗口、在线时长的监听器都会重建，
  依赖插件应同步重载。
- 不要把 MySQL 密码、Redis 密码写进日志或提交到仓库。
