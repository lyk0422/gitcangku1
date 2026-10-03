package com.example.coral.service;

import org.springframework.http.HttpStatus;

/**
 * 业务冲突异常：携带HTTP状态码，由全局异常处理器转为409/422响应。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 409：容量已满、重复编号、死亡后标活、已封存、requestId异参等状态冲突。 */
    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    /** 422：引用的样方或植株不存在等无法处理的请求。 */
    public static BusinessException unprocessable(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}