package com.example.starter.consent;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带稳定业务码与对应的 HTTP 状态，由全局异常处理器转换为统一错误响应。
 * 批量操作（如跨区域迁移预检）可携带逐条对象的失败明细，调用方据此区分失败原因。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ErrorResponse.FailureDetail> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<ErrorResponse.FailureDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ErrorResponse.FailureDetail> getDetails() {
        return details;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException gone(String code, String message) {
        return new ApiException(HttpStatus.GONE, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException unprocessable(String code, String message, List<ErrorResponse.FailureDetail> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}
