package com.example.starter.service;

/**
 * 当前资源状态不允许该操作（如版本号冲突、复核状态不符、并发竞争失败），映射 HTTP 409。
 */
public class ConflictStateException extends RuntimeException {

    /**
     * @param message 错误描述
     */
    public ConflictStateException(String message) {
        super(message);
    }
}
