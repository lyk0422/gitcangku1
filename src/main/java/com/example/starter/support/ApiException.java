package com.example.starter.support;

import com.example.starter.domain.LicenseViolation;

import java.util.List;

/**
 * 业务 API 异常，携带 HTTP 状态码与错误码。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient List<LicenseViolation> violations;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, List<LicenseViolation> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** 许可证策略违规明细；非许可证违规异常为 null。 */
    public List<LicenseViolation> getViolations() {
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

    /** 许可证策略违规 422，携带稳定排序的违规定位明细。 */
    public static ApiException policyViolation(List<LicenseViolation> violations) {
        return new ApiException(422, "POLICY_VIOLATION",
                "解析闭包中存在违反命名空间许可证策略的制品版本", violations);
    }
}
