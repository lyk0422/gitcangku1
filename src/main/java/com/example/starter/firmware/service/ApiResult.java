package com.example.starter.firmware.service;

/**
 * 写操作结果：HTTP 状态码 + 响应体，用于幂等重放时原样返回。
 *
 * @param status HTTP 状态码
 * @param body   响应体对象
 */
public record ApiResult(int status, Object body) {

    public static ApiResult ok(Object body) {
        return new ApiResult(200, body);
    }

    public static ApiResult created(Object body) {
        return new ApiResult(201, body);
    }
}
