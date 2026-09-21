package com.example.starter.evidence.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的 HTTP 状态与稳定错误码。
 * 400 参数非法；404 不存在；409 操作人不匹配或状态冲突（含幂等键改参）；422 封条异常前置条件失败。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException sealBroken(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SEAL_BROKEN", message);
    }
}
