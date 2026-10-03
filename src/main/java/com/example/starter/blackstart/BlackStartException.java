package com.example.starter.blackstart;

import org.springframework.http.HttpStatus;

/**
 * 黑启动业务异常，携带对应的 HTTP 状态码。
 */
public class BlackStartException extends RuntimeException {

    private final HttpStatus httpStatus;

    public BlackStartException(HttpStatus httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** 计划不存在（404）。 */
    public static BlackStartException planNotFound(long planId) {
        return new BlackStartException(HttpStatus.NOT_FOUND, "黑启动计划不存在: " + planId);
    }

    /** 幂等键冲突或终态冲突（409）。 */
    public static BlackStartException conflict(String message) {
        return new BlackStartException(HttpStatus.CONFLICT, message);
    }
}