package com.devhub.module.ai.common;

/**
 * 业务异常：由客户端输入或业务规则不满足引起，应映射为 400 而非 500。
 * <p>
 * 与系统故障（RuntimeException/500）区分，调用方可按 code 分流处理。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
