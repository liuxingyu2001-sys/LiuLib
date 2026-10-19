package com.liuxingyu.liulib.placeholder;

/**
 * PAPI 扩展的生命周期抽象，避免主类直接引用 PlaceholderAPI 类型。
 */
interface ExpansionHandle extends AutoCloseable {

    boolean register();

    @Override
    void close();
}
