package com.example.starter.calibration.service;

/**
 * 幂等并发标记：带 calcKey 的创建类操作在唯一约束（如测量键+版本）上与先提交事务冲突，
 * 当前事务已回滚，应由幂等执行器重放先提交事务固化的首次结果。
 */
class IdempotentConcurrentException extends RuntimeException {

    IdempotentConcurrentException(String message, Throwable cause) {
        super(message, cause);
    }
}
