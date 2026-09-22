package com.example.starter.domain;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器转换为响应。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
