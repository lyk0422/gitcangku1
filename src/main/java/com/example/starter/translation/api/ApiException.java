package com.example.starter.translation.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiDtos.TermRuleView> violations;
    private final List<ApiDtos.FreezeViolationView> freezeViolations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations) {
        this(status, code, message, violations, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations,
                        List<ApiDtos.FreezeViolationView> freezeViolations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.freezeViolations = freezeViolations;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 术语违规明细，仅术语违规 422 时非空。 */
    public List<ApiDtos.TermRuleView> violations() {
        return violations;
    }

    /** 冻结违规明细，仅冻结违规 422 时非空。 */
    public List<ApiDtos.FreezeViolationView> freezeViolations() {
        return freezeViolations;
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

    /** 422：译文违反术语规则，返回全部违规术语。 */
    public static ApiException termViolation(String message, List<ApiDtos.TermRuleView> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERM_VIOLATION", message, violations);
    }

    /** 422：译文未采用冻结允许译法，稳定返回全部违规段落与术语。 */
    public static ApiException freezeViolation(String message, List<ApiDtos.FreezeViolationView> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FREEZE_VIOLATION", message, null, violations);
    }

    /** 409：同一文档版本已存在有效冻结，或冻结状态不允许该操作。 */
    public static ApiException freezeConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "FREEZE_CONFLICT", message);
    }
}
