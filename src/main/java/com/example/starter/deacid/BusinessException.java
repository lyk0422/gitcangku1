package com.example.starter.deacid;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码：404=资源不存在，409=状态冲突或幂等键异参，422=校验不通过。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}