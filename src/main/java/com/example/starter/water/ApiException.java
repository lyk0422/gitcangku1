package com.example.starter.water;

import com.example.starter.water.dto.Dtos.HourViolation;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态与可区分的错误码。
 * 400 参数非法、404 不存在、409 操作人/状态/幂等冲突、422 配额或逐小时下泄上限不足。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<HourViolation> violations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, List<HourViolation> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 逐小时超限明细，仅 422 逐小时校验失败时非 null。 */
    public List<HourViolation> violations() {
        return violations;
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

    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED", message);
    }

    /** 422：逐小时下泄上限/预留校验失败，携带全部超限小时及差额。 */
    public static ApiException hourViolation(String code, String message, List<HourViolation> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, violations);
    }
}
