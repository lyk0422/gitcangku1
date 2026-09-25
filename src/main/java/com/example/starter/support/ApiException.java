package com.example.starter.support;

import com.example.starter.api.dto.ViolationView;

import java.util.List;

/**
 * 业务 API 异常，携带 HTTP 状态码与错误码。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<ViolationView> violations;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<ViolationView> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ViolationView> getViolations() {
        return violations;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(422, "UNPROCESSABLE_ENTITY", message);
    }

    /** 来源策略违规：422，携带可区分原因与完整路径的全部违规项。 */
    public static ApiException provenanceViolation(String message, List<ViolationView> violations) {
        return new ApiException(422, "PROVENANCE_POLICY_VIOLATION", message, violations);
    }
}
