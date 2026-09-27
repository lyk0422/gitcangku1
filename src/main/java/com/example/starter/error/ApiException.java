package com.example.starter.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常，携带可区分的 HTTP 状态码：
 * 400 参数非法；404 不存在；409 操作人不匹配、状态或容量冲突（含幂等键改参）；422 封条/借出前置条件失败。
 * 批量检验预约失败时可携带逐条失败明细（actual/required/deficit 给出实际值、要求值与差额）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final transient List<?> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, List<?> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 结构化失败明细；无明细时返回 null（null 语义：单条失败或无批量上下文）。
     */
    public List<?> details() {
        return details;
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

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /**
     * 带逐条明细的批量失败异常（如批量预约部分明细不可行）。
     */
    public static ApiException withDetails(HttpStatus status, String message, List<?> details) {
        return new ApiException(status, message, details);
    }
}
