package com.example.starter.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务异常，携带 HTTP 状态码与附加响应字段。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final Map<String, Object> attributes;

    public ApiException(int status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(int status, String message, Map<String, Object> attributes) {
        super(message);
        this.status = status;
        this.attributes = new LinkedHashMap<>(attributes);
    }

    public int getStatus() {
        return status;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /** 422：业务校验不通过（采样不足、超范围、时间逆序等）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(422, message);
    }

    /** 422：业务校验不通过，并携带附加字段（如 switchRequestId）。 */
    public static ApiException unprocessable(String message, Map<String, Object> attributes) {
        return new ApiException(422, message, attributes);
    }

    /** 409：状态冲突（幂等异参、运行段重叠、重复审核等）。 */
    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }
}