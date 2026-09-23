package com.example.starter.domain;

/**
 * 精确根制品不支持锁定目标平台时抛出，服务层映射为 422。
 */
public class PlatformUnsupportedException extends RuntimeException {

    public PlatformUnsupportedException(String message) {
        super(message);
    }
}
