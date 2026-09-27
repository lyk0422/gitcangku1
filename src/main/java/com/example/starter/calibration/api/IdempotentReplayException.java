package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;

/**
 * 幂等重放命中：同 requestId 同参重放，直接返回首次成功响应的完整快照（状态码与响应体）。
 * 读取快照不改变任何状态。
 */
public class IdempotentReplayException extends RuntimeException {

    private final HttpStatus status;
    private final String responseBody;

    public IdempotentReplayException(HttpStatus status, String responseBody) {
        super("idempotent replay");
        this.status = status;
        this.responseBody = responseBody;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
