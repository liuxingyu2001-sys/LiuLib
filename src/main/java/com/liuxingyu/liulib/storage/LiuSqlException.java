package com.liuxingyu.liulib.storage;

/** SQL 执行失败时的运行时异常，调用方可统一捕获并降级。 */
public class LiuSqlException extends RuntimeException {

    public LiuSqlException(String message, Throwable cause) {
        super(message, cause);
    }

    public LiuSqlException(String message) {
        super(message);
    }
}
