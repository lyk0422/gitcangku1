package com.example.starter.water;

import com.example.starter.water.dto.Dtos.HourViolation;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态与可区分的错误码。
 * 400 参数非法、404 不存在、409 操作人/状态/幂等冲突/曲线已开始小时修改、422 配额或逐小时下泄上限不足。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** 422 逐小时下泄超限时的全部超限小时明细；其余场景为 null。 */
    private final List<HourViolation> violations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, List<HourViolation> violations) {
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

    public List<HourViolation> violations() {
        return violations;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED", message);
    }

    /** 逐小时下泄预留超限：422，错误码 DRAWDOWN_HOURLY_CAP_EXCEEDED，并附全部超限小时及差额。 */
    public static ApiException drawdownExceeded(String message, List<HourViolation> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DRAWDOWN_HOURLY_CAP_EXCEEDED", message,
                violations);
    }
}
