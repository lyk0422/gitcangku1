package com.example.starter.salinity.exception;

import org.springframework.http.HttpStatus;

/**
 * 冲突异常，返回 409 CONFLICT：采样编号相同但内容不同、请求幂等键与其他内容冲突等。
 */
public class ConflictException extends BusinessException {

    /**
     * @param message 错误说明
     */
    public ConflictException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }

    @Override
    public String code() {
        return "CONFLICT";
    }
}
