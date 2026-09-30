package com.example.starter.tag.service;

/**
 * 业务冲突：状态不允许该操作（如标签未摘除即发起交接、已确认交接被取消、
 * 并发下持有权已被其他事务抢先改变）。映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
