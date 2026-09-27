package com.example.starter.calibration.api;

/**
 * 幂等重放：requestId 与指纹均匹配时，返回首次成功响应的完整快照，不重复执行业务写入。
 */
public class IdempotentReplayException extends RuntimeException {

    private final int status;
    private final String body;

    public IdempotentReplayException(int status, String body) {
        super("幂等重放，返回首次响应快照");
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
