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
    private final Integer missingReviews;
    private final Integer requiredReviews;
    private final Integer actualReviews;
    private final List<ApiDtos.ScopeView> affectedScopes;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations) {
        this(status, code, message, violations, null, null, null, null);
    }

    private ApiException(HttpStatus status, String code, String message,
                         List<ApiDtos.TermRuleView> violations, Integer missingReviews,
                         Integer requiredReviews, Integer actualReviews,
                         List<ApiDtos.ScopeView> affectedScopes) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.missingReviews = missingReviews;
        this.requiredReviews = requiredReviews;
        this.actualReviews = actualReviews;
        this.affectedScopes = affectedScopes;
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

    /** 缺失评审数，仅法定人数不足 422 时非空。 */
    public Integer missingReviews() {
        return missingReviews;
    }

    /** 要求的评审数，仅法定人数不足 422 时非空。 */
    public Integer requiredReviews() {
        return requiredReviews;
    }

    /** 当前有效评审数，仅法定人数不足 422 时非空。 */
    public Integer actualReviews() {
        return actualReviews;
    }

    /** 受影响段落与语言范围，仅法定人数不足 422 时非空。 */
    public List<ApiDtos.ScopeView> affectedScopes() {
        return affectedScopes;
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本冲突、段落冲突、重叠冲突声明或 requestId 同键异参。 */
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

    /** 422：法定人数不足，返回缺失/要求/实际评审数与受影响范围。 */
    public static ApiException quorumNotMet(String message, int missingReviews, int requiredReviews,
                                            int actualReviews, List<ApiDtos.ScopeView> affectedScopes) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUORUM_NOT_MET", message, null,
                missingReviews, requiredReviews, actualReviews, affectedScopes);
    }
}
