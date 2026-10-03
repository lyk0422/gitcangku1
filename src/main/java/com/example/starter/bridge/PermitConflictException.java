package com.example.starter.bridge;

/**
 * 许可业务冲突异常，映射为 HTTP 409；携带机器可读的原因代码。
 */
public class PermitConflictException extends RuntimeException {

    private final String code;

    public PermitConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 原因代码，如 OVERWEIGHT、WINDOW_OVERLAP、IDEMPOTENCY_CONFLICT。 */
    public String code() {
        return code;
    }
}