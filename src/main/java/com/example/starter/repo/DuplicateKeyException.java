package com.example.starter.repo;

/**
 * 主键冲突：对应实体已存在，由服务层转换为 409。
 */
public class DuplicateKeyException extends RuntimeException {

    public DuplicateKeyException(String message) {
        super(message);
    }
}
