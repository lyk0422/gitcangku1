package com.example.starter.salinity.exception;

import org.springframework.http.HttpStatus;

/**
 * 请求参数或业务状态不满足要求，返回 422 UNPROCESSABLE_ENTITY。
 * 覆盖：越界值、跨航次质控、缺深度封存、封存后替换、勘误引用不存在等。
 */
public class UnprocessableException extends BusinessException {

    /**
     * @param message 错误说明
     */
    public UnprocessableException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    @Override
    public String code() {
        return "UNPROCESSABLE";
    }
}
