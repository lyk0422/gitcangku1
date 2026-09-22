package com.example.starter.service;

/**
 * 服务层写操作结果：携带 HTTP 状态码与响应体，
 * 以便幂等重放时原样返回首次成功结果。
 */
public record ServiceResult<T>(int status, T body) {

    public static <T> ServiceResult<T> ok(T body) {
        return new ServiceResult<>(200, body);
    }

    public static <T> ServiceResult<T> created(T body) {
        return new ServiceResult<>(201, body);
    }
}
