package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码、可机器区分的原因码与明细。
 *
 * @param reason  原因码，例如 ACTIVITY_HOUR_BUDGET_EXHAUSTED、IDEMPOTENCY_PARAMS_MISMATCH；可为 null
 * @param details 明细，明确实际值、要求值或差额等；可为 null
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null, null);
    }

    public ApiException(HttpStatus status, String message, String reason, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
