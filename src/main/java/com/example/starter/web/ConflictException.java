package com.example.starter.web;

/**
 * 请求与当前已提交状态冲突（窗口重叠、幂等参数不一致、重复状态推进等），映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
