package com.example.starter.water;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与可区分的错误码。
 * 400 参数非法、404 不存在、409 状态/幂等/并发裁决冲突、422 业务前置不满足（率缺失、窗口外、
 * 额度不足、舍入守恒不成立等）。
 * actual/required/delta 用于数量或时长类错误的实际值、要求值与差额；itemIndex 标识批量失败明细。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String actual;
    private final String required;
    private final String delta;
    private final Integer itemIndex;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message, String actual, String required,
                        String delta, Integer itemIndex) {
        super(message);
        this.status = status;
        this.code = code;
        this.actual = actual;
        this.required = required;
        this.delta = delta;
        this.itemIndex = itemIndex;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String actual() {
        return actual;
    }

    public String required() {
        return required;
    }

    public String delta() {
        return delta;
    }

    public Integer itemIndex() {
        return itemIndex;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422 业务前置不满足，错误码可区分（率缺失、窗口外、额度不足、守恒失败等）。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 422 数量/时长类冲突：携带实际值、要求值或差额，可选批量明细索引。 */
    public static ApiException unprocessable(String code, String message, String actual, String required,
                                             String delta, Integer itemIndex) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, actual, required, delta,
                itemIndex);
    }

    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED", message);
    }
}
