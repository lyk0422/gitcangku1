package com.example.starter.batch;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带可区分的 HTTP 状态（400 参数非法、404 不存在、409 状态/键冲突、422 前置条件未满足）。
 * pendingItems 仅用于条件到期/批次已召回等需要列出未核销子项的 422 场景，其余为 null。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> pendingItems;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, List<String> pendingItems) {
        super(message);
        this.status = status;
        this.code = code;
        this.pendingItems = pendingItems;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> pendingItems() {
        return pendingItems;
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
     * 422 且携带未核销子项列表（条件到期降级、批次已召回等场景）。
     */
    public static ApiException unprocessable(String message, List<String> pendingItems) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECONDITION_FAILED",
                message, pendingItems);
    }
}
