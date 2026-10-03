package com.example.starter.calibration.api;

/**
 * 幂等重放：requestId 同参重放时抛出，携带首次结果的 HTTP 状态与响应体，原样返回。
 */
public class IdempotentReplayException extends RuntimeException {

    private final int status;
    private final String body;

    public IdempotentReplayException(int status, String body) {
        super("幂等重放，返回首次结果");
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }
}