package com.example.starter.web;

/**
 * 请求语义不合法（参数、状态或业务规则拒绝），映射 HTTP 422。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
