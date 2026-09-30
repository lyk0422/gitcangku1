package com.example.starter.tag.service;

/**
 * 请求语义不合法：时间倒退、区间交叉、目标站点不匹配、引用不存在等。
 * 映射 HTTP 422。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
