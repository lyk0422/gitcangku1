package com.example.starter.incident;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的 HTTP 状态码与错误码。
 * 400 参数非法，404 资源不存在，409 权限/状态/幂等冲突，422 非法状态流转。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 400：请求参数缺失或非法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** 404：事件不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：权限冲突、状态冲突或幂等键冲突。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** 422：非法状态流转。 */
    public static ApiException illegalTransition(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", message);
    }

    /** 422：单事件累计挂起时长达到上限。 */
    public static ApiException suspensionLimit(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SUSPENSION_LIMIT_EXCEEDED",
                message);
    }
}
