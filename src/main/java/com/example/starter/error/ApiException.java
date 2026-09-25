package com.example.starter.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常，携带可区分的 HTTP 状态码：
 * 400 参数非法；404 不存在；409 操作人不匹配或状态冲突（含幂等键改参）；422 封条异常前置条件失败。
 * items 为逐项说明（如迁移申请逐件证物的前置校验结果）；null 表示无逐项明细。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> items;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, List<String> items) {
        super(message);
        this.status = status;
        this.items = items;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 逐项说明；null 表示无逐项明细。
     */
    public List<String> items() {
        return items;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException conflict(String message, List<String> items) {
        return new ApiException(HttpStatus.CONFLICT, message, items);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public static ApiException unprocessable(String message, List<String> items) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, items);
    }
}
