package com.example.starter.bridge;

/**
 * 许可不存在异常，映射为 HTTP 404。
 */
public class PermitNotFoundException extends RuntimeException {

    public PermitNotFoundException(long permitId) {
        super("许可不存在: " + permitId);
    }
}