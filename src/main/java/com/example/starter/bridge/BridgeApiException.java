package com.example.starter.bridge;

/**
 * 业务接口异常：携带 HTTP 状态码与稳定错误码。
 */
public class BridgeApiException extends RuntimeException {

    private final int status;
    private final String code;

    public BridgeApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}