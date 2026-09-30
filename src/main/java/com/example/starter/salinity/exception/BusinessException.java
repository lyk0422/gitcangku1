package com.example.starter.salinity.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带 HTTP 状态码与机器可读错误码。
 */
public abstract class BusinessException extends RuntimeException {

    /**
     * @param message 人类可读错误说明
     */
    protected BusinessException(String message) {
        super(message);
    }

    /**
     * @return 对应的 HTTP 状态码
     */
    public abstract HttpStatus status();

    /**
     * @return 机器可读错误码
     */
    public abstract String code();
}
