package com.liuxingyu.liulib.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件间服务注册/发现门面。
 *
 * <p>Bukkit 的 {@link ServicesManager} 已经能做这件事，但调用方通常要写
 * {@code getRegistration(...)} 判空、手动区分 priority、并自己记录注册过的实例。
 * 本类补上三件事：</p>
 * <ul>
 *   <li>{@link #find(Class)} 返回 {@link Optional}，不再到处判空；</li>
 *   <li>{@link #require(Class)} 在缺失时抛出带接口名的异常，便于快速定位依赖；</li>
 *   <li>记录通过本类注册的实例，{@link #unregisterAll()} 只清理自己注册的，
 *       不会误删同类型下其它插件的实现。</li>
 * </ul>
 *
 * <p>服务实例由 Bukkit 按 {@code owner} 插件生命周期自动注销；LiuLib 停用时也会
 * 调用 {@link #unregisterAll()} 再兜底一次。</p>
 */
public final class ServiceRegistry {

    private final Plugin owner;
    private final ServicesManager manager;
    private final Set<Entry> registered = ConcurrentHashMap.newKeySet();

    public ServiceRegistry(Plugin owner) {
        this(owner, Bukkit.getServicesManager());
    }

    ServiceRegistry(Plugin owner, ServicesManager manager) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    public Plugin owner() {
        return owner;
    }

    // ==================================================================
    // 注册
    // ==================================================================

    /** 以 {@link ServicePriority#Normal} 注册。 */
    public <T> T register(Class<T> type, T service) {
        return register(type, service, ServicePriority.Normal);
    }

    /**
     * 注册服务；同一实例重复注册会直接返回，不会产生重复条目。
     *
     * @param type     服务接口
     * @param service  实现
     * @param priority 冲突时的优先级
     */
    public <T> T register(Class<T> type, T service, ServicePriority priority) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        if (!type.isInstance(service)) {
            throw new IllegalArgumentException("服务实例不是 " + type.getName() + " 的实现: "
                    + service.getClass().getName());
        }
        Entry entry = new Entry(type, service);
        if (!registered.add(entry)) {
            return service;
        }
        try {
            manager.register(type, service, owner,
                    priority == null ? ServicePriority.Normal : priority);
        } catch (RuntimeException error) {
            registered.remove(entry);
            throw error;
        }
        return service;
    }

    /** 注销本类注册的指定服务。 */
    public <T> void unregister(Class<T> type, T service) {
        if (type == null || service == null) {
            return;
        }
        if (registered.remove(new Entry(type, service))) {
            try {
                manager.unregister(type, service);
            } catch (RuntimeException ignored) {
                // 插件停用阶段 Bukkit 可能已经自动注销
            }
        }
    }

    /** 注销通过本类注册的全部服务。 */
    public void unregisterAll() {
        for (Entry entry : new ArrayList<>(registered)) {
            if (registered.remove(entry)) {
                try {
                    manager.unregister(entry.type(), entry.service());
                } catch (RuntimeException ignored) {
                    // 插件停用阶段忽略重复注销
                }
            }
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 取优先级最高的实现；没有返回空。 */
    public <T> Optional<T> find(Class<T> type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(manager.load(type));
    }

    /** 取实现；没有时抛出带接口名的异常。 */
    public <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
                "未找到服务实现: " + (type == null ? "null" : type.getName())
                        + "（请确认提供方插件已启用并声明 depend/softdepend）"));
    }

    /** 所有实现，按 Bukkit 注册顺序（优先级高者在前）。 */
    public <T> List<T> all(Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type == null) {
            return out;
        }
        for (RegisteredServiceProvider<T> provider : manager.getRegistrations(type)) {
            if (provider.getProvider() != null) {
                out.add(provider.getProvider());
            }
        }
        return out;
    }

    public <T> boolean isRegistered(Class<T> type) {
        return find(type).isPresent();
    }

    /** 通过本类注册且尚未注销的实例数。 */
    public int size() {
        return registered.size();
    }

    private record Entry(Class<?> type, Object service) {
    }
}
