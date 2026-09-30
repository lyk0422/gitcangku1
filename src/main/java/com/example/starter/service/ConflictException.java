package com.example.starter.service;

/**
 * 状态冲突或资源冲突，映射 HTTP 409。
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
