package com.example.starter.plan;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的 HTTP 状态码、稳定错误码与可选冲突明细。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<ConflictDetail> conflicts;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, List<ConflictDetail> conflicts) {
        super(message);
        this.status = status;
        this.code = code;
        this.conflicts = conflicts;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ConflictDetail> conflicts() {
        return conflicts;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException slotConflict(String message, List<ConflictDetail> conflicts) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT", message, conflicts);
    }
}
