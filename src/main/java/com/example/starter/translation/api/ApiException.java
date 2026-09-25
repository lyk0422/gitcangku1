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
    private final List<ApiDtos.PublishBlocker> blockers;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations) {
        this(status, code, message, violations, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations, List<ApiDtos.PublishBlocker> blockers) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.blockers = blockers;
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

    /** 发布阻断明细，仅发布门禁 422 时非空，按段落、语言稳定排序。 */
    public List<ApiDtos.PublishBlocker> blockers() {
        return blockers;
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

    /** 422：发布门禁未通过（缺译、批准失效、缺少法律审签或被拒绝等），返回稳定排序的全部阻断条目。 */
    public static ApiException publishBlocked(String message, List<ApiDtos.PublishBlocker> blockers) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISH_BLOCKED", message, null, blockers);
    }
}
