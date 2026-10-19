package com.liuxingyu.liulib;

import com.liuxingyu.liulib.playtime.PlaytimeEntry;
import com.liuxingyu.liulib.playtime.PlaytimeFormatter;
import com.liuxingyu.liulib.playtime.PlaytimePeriod;
import com.liuxingyu.liulib.playtime.PlaytimeRecord;
import com.liuxingyu.liulib.playtime.PlaytimeService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** {@code /liulib status|selftest|reload|playtime}。 */
public final class LiuLibCommand implements CommandExecutor, TabCompleter {

    private final LiuLibPlugin plugin;

    public LiuLibCommand(LiuLibPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("liulib.admin")) {
                sender.sendMessage("§c没有权限：liulib.admin");
                return true;
            }
            sender.sendMessage("§bLiuLib §7" + plugin.version());
            for (String line : plugin.status().split("\n")) {
                if (!line.startsWith("版本")) {
                    sender.sendMessage("§7" + line);
                }
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("selftest")) {
            if (!sender.hasPermission("liulib.admin")) {
                sender.sendMessage("§c没有权限：liulib.admin");
                return true;
            }
            for (String line : plugin.selfTest()) {
                sender.sendMessage("§7" + line);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("liulib.admin")) {
                sender.sendMessage("§c没有权限：liulib.admin");
                return true;
            }
            boolean ok = plugin.reload();
            sender.sendMessage(ok ? "§aLiuLib 重载完成" : "§eLiuLib 重载完成，但部分模块未就绪（详见控制台）");
            return true;
        }
        if (args[0].equalsIgnoreCase("playtime") || args[0].equalsIgnoreCase("pt")) {
            return playtime(sender, args);
        }
        sender.sendMessage("§e用法：/liulib <status|selftest|reload|playtime>");
        return true;
    }

    // ==================================================================
    // 在线时长
    // ==================================================================

    private boolean playtime(CommandSender sender, String[] args) {
        PlaytimeService service = plugin.playtime();
        if (service == null || !service.isEnabled()) {
            sender.sendMessage("§e在线时长模块未启用（config.yml: playtime.enabled）");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("top")) {
            return top(sender, service, args);
        }
        if (args.length >= 2) {
            if (!sender.hasPermission("liulib.playtime.others")) {
                sender.sendMessage("§c没有权限：liulib.playtime.others");
                return true;
            }
            return other(sender, service, args[1]);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§e用法：/liulib playtime <玩家|top [today|week|total] [数量]>");
            return true;
        }
        if (!sender.hasPermission("liulib.playtime")) {
            sender.sendMessage("§c没有权限：liulib.playtime");
            return true;
        }
        show(sender, service, player.getName(), player.getUniqueId(), true);
        return true;
    }

    private boolean other(CommandSender sender, PlaytimeService service, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            show(sender, service, online.getName(), online.getUniqueId(), true);
            return true;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) {
            sender.sendMessage("§e未找到玩家 " + name + "（离线玩家需先在本服上线过一次）");
            return true;
        }
        UUID uuid = cached.getUniqueId();
        service.stored(uuid).whenComplete((record, error) -> plugin.scheduler().global(() -> {
            if (error != null) {
                sender.sendMessage("§c读取 " + name + " 的在线时长失败：" + error.getMessage());
                return;
            }
            if (record == null) {
                sender.sendMessage("§e未找到 " + name + " 的在线时长记录");
                return;
            }
            showRecord(sender, name, record, false);
        }));
        return true;
    }

    private boolean top(CommandSender sender, PlaytimeService service, String[] args) {
        if (!sender.hasPermission("liulib.playtime.top")) {
            sender.sendMessage("§c没有权限：liulib.playtime.top");
            return true;
        }
        PlaytimePeriod period = PlaytimePeriod.parse(
                args.length >= 3 ? args[2] : "total", PlaytimePeriod.TOTAL);
        int limit = 10;
        if (args.length >= 4) {
            try {
                limit = Math.max(1, Math.min(100, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                limit = 10;
            }
        }
        int size = limit;
        sender.sendMessage("§b在线时长排行 §7(" + period.display() + ")");
        service.top(period, size).whenComplete((entries, error) -> plugin.scheduler().global(() -> {
            if (error != null) {
                sender.sendMessage("§c读取排行失败：" + error.getMessage());
                return;
            }
            if (entries.isEmpty()) {
                sender.sendMessage("§7暂无数据");
                return;
            }
            int index = 1;
            for (PlaytimeEntry entry : entries) {
                sender.sendMessage("§e#" + index++ + " §f" + entry.name()
                        + " §7- §f" + PlaytimeFormatter.format(entry.seconds()));
            }
        }));
        return true;
    }

    private void show(CommandSender sender, PlaytimeService service, String name, UUID uuid, boolean online) {
        long total = service.totalSeconds(uuid);
        long today = service.todaySeconds(uuid);
        long session = service.sessionSeconds(uuid);
        sender.sendMessage("§b在线时长 §7" + name);
        sender.sendMessage("§7总计：§f" + PlaytimeFormatter.format(total));
        sender.sendMessage("§7今日：§f" + PlaytimeFormatter.format(today));
        if (online) {
            sender.sendMessage("§7本次：§f" + PlaytimeFormatter.format(session)
                    + " §7状态：" + (service.isAfk(uuid) ? "§eAFK（不计时）" : "§a活跃"));
        } else {
            sender.sendMessage("§7状态：§8离线");
        }
    }

    private void showRecord(CommandSender sender, String name, PlaytimeRecord record, boolean online) {
        sender.sendMessage("§b在线时长 §7" + name);
        sender.sendMessage("§7总计：§f" + PlaytimeFormatter.format(record.totalSeconds()));
        sender.sendMessage("§7今日：§f" + PlaytimeFormatter.format(record.todaySeconds()));
        sender.sendMessage("§7状态：" + (online ? "§a在线" : "§8离线"));
    }

    private static boolean isPlaytime(String arg) {
        return arg.equalsIgnoreCase("playtime") || arg.equalsIgnoreCase("pt");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "selftest", "reload", "playtime").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && isPlaytime(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> values = new ArrayList<>();
            values.add("top");
            for (Player player : Bukkit.getOnlinePlayers()) {
                values.add(player.getName());
            }
            return values.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && isPlaytime(args[0])
                && args[1].equalsIgnoreCase("top")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("today", "week", "total").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 4 && isPlaytime(args[0])
                && args[1].equalsIgnoreCase("top")) {
            String prefix = args[3];
            return List.of("5", "10", "20", "50").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
