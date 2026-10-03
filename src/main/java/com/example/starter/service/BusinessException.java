package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转为统一错误响应。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** HTTP 状态码（409 冲突 / 422 业务规则不满足 / 404 不存在）。 */
    public HttpStatus status() {
        return status;
    }

    /** 稳定错误码，便于调用方程序化判断。 */
    public String code() {
        return code;
    }

    /** 资源不存在（404）。 */
    public static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    /** 状态冲突（409）：重复有效操作、幂等键异参、终态竞争等。 */
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    /** 业务规则不满足（422）：窗口外、已摘除、批号停用、确认超期等。 */
    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}