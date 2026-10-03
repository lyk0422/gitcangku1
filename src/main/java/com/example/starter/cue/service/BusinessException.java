package com.example.starter.cue.service;

import org.springframework.http.HttpStatus;

/**
 * 业务冲突或校验失败异常，由全局异常处理器映射为对应的 HTTP 状态码。
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

    /** 资源不存在，映射 404。 */
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, message);
    }

    /** 状态或参数冲突（跳号、异参重放、重复成功等），映射 409。 */
    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    /** 请求内容校验失败（无效时长、重复序号等），映射 400。 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}