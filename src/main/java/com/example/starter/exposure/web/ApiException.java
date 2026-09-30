package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码、可机读原因码以及差额/实际值等诊断明细。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String message) {
        this(status, null, message, null);
    }

    public ApiException(HttpStatus status, String reason, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 可机读原因码；无细分原因为 null。 */
    public String getReason() {
        return reason;
    }

    /** 诊断明细（实际值、要求值、差额、下一可用小时等）；无明细为 null。 */
    public Map<String, Object> getDetails() {
        return details;
    }
}
