package com.example.starter.blind;

import org.springframework.http.HttpStatus;

/**
 * 业务 API 异常，携带明确的 HTTP 状态码与安全提示信息。
 * 异常信息不得包含处理代码或席位序号等盲底内容。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 400 请求参数不合法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /** 401 操作者头缺失或角色不合法。 */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    /** 403 权限不足或无权查看揭盲结果。 */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    /** 404 资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 409 状态冲突（含异参重放、未批准揭盲等）。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** 422 业务不可处理：实验满额、容量不足、序列生成失败或存在待处理揭盲申请等。 */
    public static ApiException full(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 422 业务不可处理（与 full 同义），用于容量/序列等非“满额”场景，原因可区分。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
