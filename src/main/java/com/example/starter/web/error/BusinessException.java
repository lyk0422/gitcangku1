package com.example.starter.web.error;

/**
 * 业务异常：携带明确的拒绝原因，由全局异常处理器映射为对应 HTTP 状态码与错误体。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
