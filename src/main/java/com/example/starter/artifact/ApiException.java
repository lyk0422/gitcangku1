package com.example.starter.artifact;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器转换为错误响应。
 * 抛出后当前事务回滚，失败的写操作不占用幂等键。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
