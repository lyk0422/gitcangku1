package com.example.starter.api;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与结构化明细（如拒绝放行的卷号与原因）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final transient Object detail;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Object detail) {
        super(message);
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public Object detail() {
        return detail;
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 409：状态或幂等冲突（异参重放、重复放行、已冻结后修改等）。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** 422：整批不予放行，detail 为卷号到原因列表的映射。 */
    public static ApiException releaseRejected(Object detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "整批不予放行", detail);
    }
}