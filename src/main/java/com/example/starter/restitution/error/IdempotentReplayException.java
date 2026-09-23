package com.example.starter.restitution.error;

/**
 * 幂等重放：不是错误，携带首次成功的状态码与响应体，由控制器建议原样返回。
 */
public class IdempotentReplayException extends RuntimeException {

    private final int httpStatus;
    private final String responseBody;

    public IdempotentReplayException(int httpStatus, String responseBody) {
        super("idempotent replay");
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
