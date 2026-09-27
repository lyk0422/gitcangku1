package com.example.starter.batch;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * 业务异常：携带可区分的 HTTP 状态（400 参数非法、404 不存在、409 状态/键冲突、422 前置条件未满足）。
 * 数量、时长或比例类校验失败时可携带实际值、要求值与差额（difference = actual - required）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final BigDecimal actual;
    private final BigDecimal required;
    private final BigDecimal difference;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        BigDecimal actual, BigDecimal required, BigDecimal difference) {
        super(message);
        this.status = status;
        this.code = code;
        this.actual = actual;
        this.required = required;
        this.difference = difference;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public BigDecimal actual() {
        return actual;
    }

    public BigDecimal required() {
        return required;
    }

    public BigDecimal difference() {
        return difference;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECONDITION_FAILED", message);
    }

    /**
     * 422 并携带实际值、要求值与差额（difference = actual - required），
     * 用于效价低于阈值、权重不守恒等数量/比例类前置条件失败。
     */
    public static ApiException unprocessable(String message, BigDecimal actual, BigDecimal required) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECONDITION_FAILED", message,
                actual, required, actual.subtract(required));
    }
}
