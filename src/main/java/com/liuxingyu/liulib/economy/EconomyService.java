package com.liuxingyu.liulib.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vault 经济服务的可选封装。
 *
 * <p>通过反射调用 Vault API，因此 LiuLib 编译期不依赖 Vault；未安装 Vault 或没有
 * 经济实现时 {@link #available()} 为 false，所有操作返回失败/0，不会抛异常。</p>
 *
 * <pre>{@code
 * EconomyService economy = LiuLib.economy();
 * if (economy.available()) {
 *     EconomyService.Result result = economy.withdraw(player, 100);
 *     if (result.success()) {
 *         // 扣款成功
 *     }
 * }
 * }</pre>
 *
 * <p>Vault 的经济实现可能不是线程安全的，默认应在主线程调用；需要异步时由调用方
 * 自行调度回主线程。</p>
 */
public final class EconomyService {

    /** 一次经济操作的结果。 */
    public record Result(boolean success, double balance, double amount, String error) {

        public static Result failure(double balance, double amount, String error) {
            return new Result(false, balance, amount, error == null ? "" : error);
        }

        public static Result success(double balance, double amount) {
            return new Result(true, balance, amount, "");
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private volatile Object economy;
    private volatile Class<?> economyClass;
    private volatile Method isEnabledMethod;
    private volatile Method getBalanceMethod;
    private volatile Method hasMethod;
    private volatile Method withdrawMethod;
    private volatile Method depositMethod;
    private volatile Method formatMethod;
    private volatile Method currencySingularMethod;
    private volatile Method currencyPluralMethod;

    public EconomyService(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin == null
                ? Logger.getLogger("LiuLib-Economy")
                : plugin.getLogger();
        refresh();
    }

    /**
     * 重新发现 Vault 经济实现；Vault 在 LiuLib 之后加载或更换实现时调用。
     *
     * @return 是否找到可用实现
     */
    public synchronized boolean refresh() {
        this.economy = null;
        this.economyClass = null;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
                ServicesManager manager = Bukkit.getServicesManager();
                Object registration = ServicesManager.class
                        .getMethod("getRegistration", Class.class)
                        .invoke(manager, type);
                if (registration instanceof RegisteredServiceProvider<?> provider) {
                    Object instance = provider.getProvider();
                    if (instance != null && type.isInstance(instance)) {
                        this.economyClass = type;
                        this.economy = instance;
                        this.isEnabledMethod = type.getMethod("isEnabled");
                        this.getBalanceMethod = type.getMethod("getBalance", OfflinePlayer.class);
                        this.hasMethod = type.getMethod("has", OfflinePlayer.class, double.class);
                        this.withdrawMethod = type.getMethod("withdrawPlayer",
                                OfflinePlayer.class, double.class);
                        this.depositMethod = type.getMethod("depositPlayer",
                                OfflinePlayer.class, double.class);
                        this.formatMethod = optionalMethod(type, "format", double.class);
                        this.currencySingularMethod = optionalMethod(type, "currencyNameSingular");
                        this.currencyPluralMethod = optionalMethod(type, "currencyNamePlural");
                    }
                }
            }
        } catch (Throwable error) {
            logger.log(Level.FINE, "未找到 Vault 经济实现（可忽略）", error);
        }
        return available();
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /** Vault 经济实现是否可用且启用。 */
    public boolean available() {
        Object instance = economy;
        Method method = isEnabledMethod;
        if (instance == null || method == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(instance));
        } catch (Throwable error) {
            return false;
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public double balance(OfflinePlayer player) {
        if (player == null || !available()) {
            return 0.0D;
        }
        try {
            Object value = getBalanceMethod.invoke(economy, player);
            return value instanceof Number number ? number.doubleValue() : 0.0D;
        } catch (Throwable error) {
            logFailure("读取余额", error);
            return 0.0D;
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (player == null || !available() || !validAmount(amount)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(hasMethod.invoke(economy, player, amount));
        } catch (Throwable error) {
            logFailure("检查余额", error);
            return false;
        }
    }

    // ==================================================================
    // 写入
    // ==================================================================

    public Result withdraw(OfflinePlayer player, double amount) {
        return invokeResponse(withdrawMethod, player, amount, "扣款");
    }

    public Result deposit(OfflinePlayer player, double amount) {
        return invokeResponse(depositMethod, player, amount, "存款");
    }

    private Result invokeResponse(Method method, OfflinePlayer player, double amount, String action) {
        if (player == null) {
            return Result.failure(0, amount, "玩家为空");
        }
        if (!validAmount(amount)) {
            return Result.failure(balance(player), amount, "金额必须为正数");
        }
        if (!available() || method == null) {
            return Result.failure(balance(player), amount, "经济服务不可用");
        }
        try {
            Object response = method.invoke(economy, player, amount);
            boolean success = responseSuccess(response);
            double returnedBalance = responseDouble(response, "balance", balance(player));
            String error = responseString(response, "errorMessage");
            return new Result(success, returnedBalance, amount, error);
        } catch (Throwable error) {
            logFailure(action, error);
            return Result.failure(balance(player), amount, rootMessage(error));
        }
    }

    // ==================================================================
    // 展示
    // ==================================================================

    public String format(double amount) {
        if (formatMethod != null && available()) {
            try {
                Object value = formatMethod.invoke(economy, amount);
                if (value != null) {
                    return value.toString();
                }
            } catch (Throwable ignored) {
                // 回退默认格式
            }
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    public String currencyNameSingular() {
        return invokeString(currencySingularMethod, "货币");
    }

    public String currencyNamePlural() {
        return invokeString(currencyPluralMethod, "货币");
    }

    private String invokeString(Method method, String fallback) {
        if (method != null && available()) {
            try {
                Object value = method.invoke(economy);
                if (value != null) {
                    return value.toString();
                }
            } catch (Throwable ignored) {
                // 回退
            }
        }
        return fallback;
    }

    // ==================================================================
    // 反射辅助
    // ==================================================================

    private static boolean validAmount(double amount) {
        return Double.isFinite(amount) && amount > 0;
    }

    private static boolean responseSuccess(Object response) {
        if (response == null) {
            return false;
        }
        try {
            Method method = response.getClass().getMethod("transactionSuccess");
            Object value = method.invoke(response);
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException ignored) {
            // 旧版 Vault 使用 public 字段
        }
        try {
            Field field = response.getClass().getField("transactionSuccess");
            return Boolean.TRUE.equals(field.get(response));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static double responseDouble(Object response, String field, double fallback) {
        if (response == null) {
            return fallback;
        }
        try {
            Method method = response.getClass().getMethod(field);
            Object value = method.invoke(response);
            return value instanceof Number number ? number.doubleValue() : fallback;
        } catch (ReflectiveOperationException ignored) {
            // 尝试字段
        }
        try {
            Field found = response.getClass().getField(field);
            Object value = found.get(response);
            return value instanceof Number number ? number.doubleValue() : fallback;
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static String responseString(Object response, String field) {
        if (response == null) {
            return "";
        }
        try {
            Method method = response.getClass().getMethod(field);
            Object value = method.invoke(response);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException ignored) {
            // 尝试字段
        }
        try {
            Field found = response.getClass().getField(field);
            Object value = found.get(response);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    private void logFailure(String action, Throwable error) {
        logger.warning("Vault 经济操作失败: " + action + " - " + rootMessage(error));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current == null ? "未知错误" : Objects.toString(current.getMessage(), current.toString());
    }
}
