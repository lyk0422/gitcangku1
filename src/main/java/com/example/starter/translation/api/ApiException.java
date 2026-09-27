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
    private final List<String> affectedSegments;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations,
                        Integer missingReviews, List<String> affectedSegments) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.missingReviews = missingReviews;
        this.affectedSegments = affectedSegments;
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

    /** 法定人数不足时缺失的评审总数，其余场景为 null。 */
    public Integer missingReviews() {
        return missingReviews;
    }

    /** 法定人数不足时受影响的段落（segmentId/language），其余场景为 null。 */
    public List<String> affectedSegments() {
        return affectedSegments;
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本冲突、段落冲突、重叠冲突声明或 requestId 同键异参。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** 403：角色或身份不满足操作要求（如非合规角色解除冲突、声明人自行解除）。 */
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    /** 422：业务规则不满足（缺译、审核失效、版本不匹配、冲突区间非法等）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", message);
    }

    /** 422：译文违反术语规则，返回全部违规术语。 */
    public static ApiException termViolation(String message, List<ApiDtos.TermRuleView> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERM_VIOLATION", message, violations,
                null, null);
    }

    /** 422：排除冲突评审后法定人数不足，稳定返回缺失评审总数与受影响段落。 */
    public static ApiException quorumFailure(String message, int missingReviews, List<String> affectedSegments) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUORUM_INSUFFICIENT", message, null,
                missingReviews, affectedSegments);
    }

    /** 422：存在与当前译文版本一致的有效否决，发布被阻断。 */
    public static ApiException reviewRejected(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_REJECTED", message);
    }
}
