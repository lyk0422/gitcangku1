package com.example.starter.common;

/**
 * 业务执行结果：HTTP 状态码与响应载荷，供幂等层持久化后在同参重放时原样返回。
 */
public record ApiResult(int status, Object payload) {

    public static ApiResult created(Object payload) {
        return new ApiResult(201, payload);
    }

    public static ApiResult ok(Object payload) {
        return new ApiResult(200, payload);
    }
}