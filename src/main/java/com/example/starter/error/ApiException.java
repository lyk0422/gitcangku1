package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带可区分的 HTTP 状态码与错误码：
 * 400 参数非法；403 移交令失效或案件权限不足；404 不存在；
 * 409 操作人不匹配或状态冲突（含幂等键改参）；422 证物状态前置条件失败（整批不写入）。
 * 单参数工厂方法使用与状态同名的默认错误码；双参数方法可给出可区分的细分错误码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
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

    public static ApiException badRequest(String message) {
        return badRequest("BAD_REQUEST", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException forbidden(String message) {
        return forbidden("FORBIDDEN", message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException notFound(String message) {
        return notFound("NOT_FOUND", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String message) {
        return conflict("CONFLICT", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String message) {
        return unprocessable("UNPROCESSABLE_ENTITY", message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
