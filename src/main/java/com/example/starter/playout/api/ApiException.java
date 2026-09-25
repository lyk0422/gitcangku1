package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.RatingViolation;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常，携带明确的 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<RatingViolation> violations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<RatingViolation> violations) {
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

    /** 分级越权明细，仅 422 RATING_EXCEEDED 时非空。 */
    public List<RatingViolation> violations() {
        return violations;
    }

    /** 400：请求参数不合法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** 404：引用的资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本、幂等或状态冲突。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422：业务规则不满足。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 422：分级越权，携带全部越级素材及命中时段明细。 */
    public static ApiException ratingExceeded(String message, List<RatingViolation> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RATING_EXCEEDED",
                message, violations);
    }
}
