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
    private final List<ApiDtos.MissingSegmentView> missing;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations) {
        this(status, code, message, violations, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations, List<ApiDtos.MissingSegmentView> missing) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.missing = missing;
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

    /** 缺失段诊断明细，仅缺失段 422 时非空。 */
    public List<ApiDtos.MissingSegmentView> missing() {
        return missing;
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

    /** 422：源段沿回退链全部语种均无已批准译文，返回稳定排序的缺失段与已尝试语种。 */
    public static ApiException missingSegments(String message, List<ApiDtos.MissingSegmentView> missing) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_SEGMENTS", message, null, missing);
    }
}
