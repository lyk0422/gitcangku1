package com.example.starter.consent;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.example.starter.consent.dto.ViolationDetail;

/**
 * 业务异常：携带稳定业务码与对应的 HTTP 状态，由全局异常处理器转换为统一错误响应。
 * 批次查询门禁拒绝时可携带逐主体阻断明细。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ViolationDetail> violations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, List<ViolationDetail> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ViolationDetail> getViolations() {
        return violations;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException forbidden(String code, String message, List<ViolationDetail> violations) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message, violations);
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
}
