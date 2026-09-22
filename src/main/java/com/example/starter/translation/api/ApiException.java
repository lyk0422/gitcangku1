package com.example.starter.translation.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 * 术语违规拦截额外携带全部违规术语，随 422 响应返回且不写译文。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiDtos.TermViolation> violations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermViolation> violations) {
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

    public List<ApiDtos.TermViolation> violations() {
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

    /** 422：译文术语违规，返回全部违规术语且不写译文。 */
    public static ApiException termViolations(String message, List<ApiDtos.TermViolation> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERM_VIOLATION", message, violations);
    }
}
