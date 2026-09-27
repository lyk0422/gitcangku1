package com.example.starter.maintenance.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码、稳定错误码与可选明细，由 {@link ApiExceptionHandler} 统一转换为错误响应。
 * 明细用于 422 场景列明缺失或失败的检查项等，便于定位原因。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> details() {
        return details;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static ApiException unprocessable(String code, String message, List<String> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}
