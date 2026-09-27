package com.example.starter.batch;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的 HTTP 状态（400 参数非法、404 不存在、409 状态/键冲突、422 前置条件未满足）。
 * payload 非空时作为响应体返回结构化诊断（如效价实际值/要求值/差额），否则使用 {@link ErrorResponse}。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object payload;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object payload) {
        super(message);
        this.status = status;
        this.code = code;
        this.payload = payload;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object payload() {
        return payload;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECONDITION_FAILED", message);
    }

    /**
     * 422 并携带结构化诊断（效价不合格、权重不守恒等），返回实际值、要求值或差额。
     */
    public static ApiException unprocessable(String message, Object payload) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECONDITION_FAILED", message, payload);
    }
}
