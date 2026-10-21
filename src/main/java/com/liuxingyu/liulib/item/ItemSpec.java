package com.liuxingyu.liulib.item;

/**
 * 物品规格：解析后的“平台 + ID + 数量”。
 *
 * <p>{@link #toString()} 可直接写回配置（Base64 物品除外，它保留原串）。</p>
 *
 * @param source 物品来源
 * @param id     平台内部 ID；Base64 物品为编码串本身
 * @param amount 数量，永远 >= 1
 * @param raw    原始配置串（排障用）
 */
public record ItemSpec(Source source, String id, int amount, String raw) {

    public enum Source {
        /** 原版材质，id 为大写材质名（如 DIAMOND_SWORD）。 */
        VANILLA,
        /** CraftEngine 物品，id 为 namespace:id。 */
        CRAFT_ENGINE,
        /** Paper Base64 / 旧对象流编码的完整物品。 */
        ENCODED,
        /** 自定义前缀（由扩展插件注册的 {@link ItemResolver} 构建，如 liuitemlib 的 {@code lib:}）。 */
        CUSTOM
    }

    public ItemSpec {
        amount = Math.max(1, amount);
    }

    public boolean isVanilla() {
        return source == Source.VANILLA;
    }

    public boolean isCraftEngine() {
        return source == Source.CRAFT_ENGINE;
    }

    public boolean isEncoded() {
        return source == Source.ENCODED;
    }

    public boolean isCustom() {
        return source == Source.CUSTOM;
    }

    /** 规范化 ID：{@code minecraft:DIAMOND} / {@code ce:namespace:id}；编码物品返回 null。 */
    public String canonicalId() {
        return switch (source) {
            case VANILLA -> "minecraft:" + id;
            case CRAFT_ENGINE -> "ce:" + id;
            case ENCODED, CUSTOM -> null;
        };
    }

    /** 数量后缀形式，便于写回配置。 */
    public String configString() {
        if (source == Source.ENCODED || source == Source.CUSTOM) {
            return raw;
        }
        String base = source == Source.CRAFT_ENGINE ? "ce:" + id : id;
        return amount == 1 ? base : base + "*" + amount;
    }

    @Override
    public String toString() {
        return "ItemSpec{" + source + " " + id + " x" + amount + "}";
    }
}
