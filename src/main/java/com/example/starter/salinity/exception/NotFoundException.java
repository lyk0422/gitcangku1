package com.example.starter.salinity.exception;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在，返回 404。
 */
public class NotFoundException extends BusinessException {

    /**
     * @param message 错误说明
     */
    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String code() {
        return "NOT_FOUND";
    }
}
