package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带稳定的 HTTP 状态与业务码。
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

    /** 400：请求参数错误。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** 404：授权或记录不存在。 */
    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    /** 409：内容冲突（payload 不一致、幂等参数变化、重复撤回等）。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 410：授权已撤回，旧代数据不可见、写入被拒绝。 */
    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, "CONSENT_REVOKED", message);
    }
}
