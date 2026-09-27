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
    private final List<ApiDtos.PlaceholderMismatchView> placeholderMismatches;
    private final List<ApiDtos.BatchItemErrorView> items;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations) {
        this(status, code, message, violations, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations,
                        List<ApiDtos.PlaceholderMismatchView> placeholderMismatches,
                        List<ApiDtos.BatchItemErrorView> items) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.placeholderMismatches = placeholderMismatches;
        this.items = items;
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

    /** 占位符计数差异明细，仅占位符不一致 422 时非空。 */
    public List<ApiDtos.PlaceholderMismatchView> placeholderMismatches() {
        return placeholderMismatches;
    }

    /** 批量批准单项失败明细，仅批量批准不完整 422 时非空。 */
    public List<ApiDtos.BatchItemErrorView> items() {
        return items;
    }

    /** 400：请求参数不合法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
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

    /** 422：译文占位符与源段声明不一致，返回全部计数差异（要求值与实际值）。 */
    public static ApiException placeholderMismatch(String message,
                                                   List<ApiDtos.PlaceholderMismatchView> mismatches) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLACEHOLDER_MISMATCH", message,
                null, mismatches, null);
    }

    /** 422：批量批准存在不完整项，整次不改变批准状态，返回全部失败项及原因。 */
    public static ApiException batchIncomplete(String message, List<ApiDtos.BatchItemErrorView> items) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_INCOMPLETE", message,
                null, null, items);
    }
}
