package com.example.starter.translation.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiDtos.TermViolationView> violations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermViolationView> violations) {
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

    /** 术语违规明细，仅术语违规 422 时非空，含违规规则及来源。 */
    public List<ApiDtos.TermViolationView> violations() {
        return violations;
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本冲突、段落冲突或 requestId 同键异参。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** 422：业务规则不满足（缺译、审核失效、版本不匹配等）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", message);
    }

    /** 422：译文违反术语规则，返回全部违规术语及来源。 */
    public static ApiException termViolation(String message, List<ApiDtos.TermViolationView> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERM_VIOLATION", message, violations);
    }
}
