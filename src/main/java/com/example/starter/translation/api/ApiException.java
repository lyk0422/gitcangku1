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
    private final Integer requiredReviewCount;
    private final Integer missingReviewCount;
    private final List<ApiDtos.AffectedSegmentView> affectedSegments;
    private final List<ApiDtos.RejectedReviewView> rejectedReviews;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations) {
        this(status, code, message, violations, null, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        Integer requiredReviewCount, Integer missingReviewCount,
                        List<ApiDtos.AffectedSegmentView> affectedSegments,
                        List<ApiDtos.RejectedReviewView> rejectedReviews) {
        this(status, code, message, null, requiredReviewCount, missingReviewCount,
                affectedSegments, rejectedReviews);
    }

    private ApiException(HttpStatus status, String code, String message,
                         List<ApiDtos.TermRuleView> violations, Integer requiredReviewCount,
                         Integer missingReviewCount, List<ApiDtos.AffectedSegmentView> affectedSegments,
                         List<ApiDtos.RejectedReviewView> rejectedReviews) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.requiredReviewCount = requiredReviewCount;
        this.missingReviewCount = missingReviewCount;
        this.affectedSegments = affectedSegments;
        this.rejectedReviews = rejectedReviews;
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

    /** 发布要求的有效批准数（每段落每语种）。 */
    public Integer requiredReviewCount() {
        return requiredReviewCount;
    }

    /** 距法定人数的缺失评审数总和。 */
    public Integer missingReviewCount() {
        return missingReviewCount;
    }

    /** 法定人数不足受影响的段落明细。 */
    public List<ApiDtos.AffectedSegmentView> affectedSegments() {
        return affectedSegments;
    }

    /** 阻断发布的有效否决明细。 */
    public List<ApiDtos.RejectedReviewView> rejectedReviews() {
        return rejectedReviews;
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本冲突、段落冲突、冲突范围重叠或 requestId 同键异参。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** 422：业务规则不满足（缺译、审核失效、版本不匹配、冲突资格等）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", message);
    }

    /** 403：角色不允许（解除冲突须由不同于声明人的合规角色确认）。 */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    /** 422：评审人命中有效利益冲突，不得提交批准或否决。 */
    public static ApiException reviewerConflict(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEWER_CONFLICT", message);
    }

    /** 422：译文违反术语规则，返回全部违规术语。 */
    public static ApiException termViolation(String message, List<ApiDtos.TermRuleView> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERM_VIOLATION", message, violations);
    }

    /** 422：法定人数不足，稳定返回要求值、缺失评审数与受影响段落。 */
    public static ApiException quorumFailure(String message, int requiredReviewCount, int missingReviewCount,
                                             List<ApiDtos.AffectedSegmentView> affectedSegments) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUORUM_NOT_MET", message,
                requiredReviewCount, missingReviewCount, affectedSegments, null);
    }

    /** 422：存在有效否决，发布被阻断，返回全部否决明细。 */
    public static ApiException rejectedFailure(String message, List<ApiDtos.RejectedReviewView> rejectedReviews) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_REJECTED", message,
                null, null, null, rejectedReviews);
    }
}
