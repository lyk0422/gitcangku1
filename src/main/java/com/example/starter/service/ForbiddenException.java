package com.example.starter.service;

/**
 * 无权操作（如撤销他人申请），映射 HTTP 403。
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String code, String message) {
        super(code, message);
    }
}
