package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带可区分的 HTTP 状态码：
 * 400 参数非法；404 不存在；409 操作人不匹配或状态冲突（含幂等键改参）；422 前置条件失败。
 * reasonCode 为机器可区分的失败原因码（如披露包截至时刻重放失败原因），无细分原因为 null。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reasonCode;

    public ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public ApiException(HttpStatus status, String reasonCode, String message) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /**
     * 422 前置条件失败并携带机器可区分原因码（如 PENDING_TRANSFER_AT_CUTOFF）。
     */
    public static ApiException unprocessable(String reasonCode, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, reasonCode, message);
    }
}
