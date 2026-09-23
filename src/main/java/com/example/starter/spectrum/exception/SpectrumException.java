package com.example.starter.spectrum.exception;

import java.util.List;

/**
 * 业务异常基类，携带 HTTP 状态码与错误码。
 */
public class SpectrumException extends RuntimeException {

    private final int status;

    private final String code;

    public SpectrumException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** 参数非法（自环、重复边、重复台站等），HTTP 400。 */
    public static class BadRequest extends SpectrumException {
        public BadRequest(String message) {
            super(400, "BAD_REQUEST", message);
        }
    }

    /** 网络或方案资源不存在，HTTP 404。 */
    public static class NotFound extends SpectrumException {
        public NotFound(String message) {
            super(404, "NOT_FOUND", message);
        }
    }

    /** 版本冲突、planKey 冲突、同 requestId 异参，HTTP 409。 */
    public static class Conflict extends SpectrumException {
        public Conflict(String code, String message) {
            super(409, code, message);
        }
    }

    /**
     * 提交后完整网络存在累计干扰超过预算的台站，HTTP 422。
     * violations 列出全部超标台站、实际累计值与预算，由 Controller 原样输出。
     */
    public static class BudgetExceeded extends SpectrumException {

        private final List<Violation> violations;

        public BudgetExceeded(List<Violation> violations) {
            super(422, "BUDGET_EXCEEDED", "accumulated interference exceeds station budget");
            this.violations = List.copyOf(violations);
        }

        public List<Violation> getViolations() {
            return violations;
        }

        /** 单个超标台站：实际累计干扰与预算。 */
        public record Violation(String stationId, int actual, int budget) {
        }
    }
}
