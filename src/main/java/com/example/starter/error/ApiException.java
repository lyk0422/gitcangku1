package com.example.starter.error;

/** 业务异常基类，携带 HTTP 状态码。 */
public abstract class ApiException extends RuntimeException {

    private final int status;

    protected ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}