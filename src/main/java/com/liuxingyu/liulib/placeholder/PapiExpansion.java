package com.liuxingyu.liulib.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Objects;

/**
 * LiuLib 托管的 PAPI 扩展；只在 PlaceholderAPI 已安装时加载。
 */
final class PapiExpansion extends PlaceholderExpansion implements ExpansionHandle {

    private final String identifier;
    private final String author;
    private final String version;
    private final PlaceholderHandler handler;

    PapiExpansion(String identifier, String author, String version, PlaceholderHandler handler) {
        this.identifier = identifier;
        this.author = author == null || author.isBlank() ? "liuxingyu" : author;
        this.version = version == null || version.isBlank() ? "1.0.0" : version;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return author;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        try {
            return handler.resolve(player, params == null ? "" : params);
        } catch (Throwable error) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            unregister();
        } catch (Throwable ignored) {
            // PAPI 已卸载时忽略
        }
    }
}
