package com.example.starter.race.service;

/**
 * 写操作的服务返回：响应体与首次成功时的 HTTP 状态码；
 * 幂等重放时由控制器原样返回。
 *
 * @param body   响应体（Jackson 可序列化对象；重放时为解析后的 JsonNode）
 * @param status 首次成功的 HTTP 状态码
 */
public record ServiceResult(Object body, int status) {

    public static ServiceResult ok(Object body) {
        return new ServiceResult(body, 200);
    }

    public static ServiceResult created(Object body) {
        return new ServiceResult(body, 201);
    }
}
